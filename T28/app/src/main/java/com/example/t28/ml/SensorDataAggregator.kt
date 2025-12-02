package com.example.t28.ml

import android.util.Log
import java.util.LinkedHashMap
import kotlin.math.round
import kotlin.math.sqrt

private const val BUFFER_DURATION_SECONDS = 60.0
private const val MOTION_PCA_KEY = "df_motion_pca"
private const val TAG = "SensorAggregator"

private data class MotionPcaSource(val key: String, val columns: List<String>)
private data class ColumnLookup(val sourceKey: String, val columnName: String)

data class SensorSample(val timestampSec: Double, val values: Map<String, Double>)

data class BucketRow(val timeBucket: Double, val values: LinkedHashMap<String, Double>)

/**
 * Collects raw sensor readings and recreates the preprocessing pipeline used during training.
 */
class SensorDataAggregator(
    private val specs: List<SensorSheetSpec>,
    private val params: ModelParams
) {
    private val lock = Any()
    private val readings: MutableMap<String, MutableList<SensorSample>> =
        specs.associate { it.sheetName to mutableListOf<SensorSample>() }.toMutableMap()
    private var baseTimestampNs: Long = 0L
    private val intervalSeconds = params.interval
    private val trimSeconds = params.trimSeconds

    private val motionSources = listOf(
        MotionPcaSource(
            key = "df_accelerometer_bucketed",
            columns = listOf(
                "Acceleration x (m/s^2)",
                "Acceleration y (m/s^2)",
                "Acceleration z (m/s^2)"
            )
        ),
        MotionPcaSource(
            key = "df_gyroscope_bucketed",
            columns = listOf(
                "Gyroscope x (rad/s)",
                "Gyroscope y (rad/s)",
                "Gyroscope z (rad/s)"
            )
        ),
        MotionPcaSource(
            key = "df_gravity_bucketed",
            columns = listOf(
                "Acceleration x (m/s^2)",
                "Acceleration y (m/s^2)",
                "Acceleration z (m/s^2)"
            )
        )
    )

    private val motionColumnLookup: Map<String, ColumnLookup> = motionSources
        .flatMap { source ->
            source.columns.map { column ->
                val sanitized = "${source.key}_${sanitizeColumnName(column)}"
                sanitized to ColumnLookup(source.key, column)
            }
        }
        .toMap()

    private val motionOutputColumns: List<String> = params.pca?.motionPca?.let { motion ->
        (1..motion.componentCount).map { index -> "${motion.prefix}_pc$index" }
    }.orEmpty()

    fun reset() {
        synchronized(lock) {
            readings.values.forEach { it.clear() }
            baseTimestampNs = 0L
            Log.d(TAG, "Resetting aggregator buffers")
        }
    }

    fun record(sheetName: String, eventTimestampNs: Long, values: Map<String, Double>) {
        val bucket = readings[sheetName] ?: return
        synchronized(lock) {
            if (baseTimestampNs == 0L) {
                baseTimestampNs = eventTimestampNs
            }
            val seconds = (eventTimestampNs - baseTimestampNs) / 1_000_000_000.0
            if (seconds < 0) return
            bucket.add(SensorSample(seconds, values))
            pruneOldSamples(bucket)
        }
    }

    fun buildFeatureVector(): FloatArray? = synchronized(lock) {
        if (params.featureColumns.isEmpty()) {
            Log.w(TAG, "No feature columns configured; cannot build vector")
            return@synchronized null
        }

        val bucketedData = LinkedHashMap<String, List<BucketRow>>()
        for (spec in specs) {
            val samples = readings[spec.sheetName]?.toList().orEmpty()
            val rows = buildBucketRows(samples, spec)
            if (rows.isEmpty()) {
                Log.d(TAG, "No bucket rows for ${spec.key}; waiting for more samples")
                return@synchronized null
            }
            bucketedData[spec.key] = rows
        }

        bucketedData["df_orientation_bucketed"]?.lastOrNull()?.values?.let { latestRawOrientation ->
            val rawYaw = latestRawOrientation["Yaw (°)"]
            val rawPitch = latestRawOrientation["Pitch (°)"]
            val rawRoll = latestRawOrientation["Roll (°)"]
            Log.d(
                TAG,
                "Raw orientation before scaling: yaw=${rawYaw?.let { String.format("%.3f", it) }}, pitch=${rawPitch?.let { String.format("%.3f", it) }}, roll=${rawRoll?.let { String.format("%.3f", it) }}"
            )
        }

        val scaledData = bucketedData.mapValues { (key, rows) ->
            rows.map { row -> scaleBucketRow(key, row) }
        }

        val transformedData = applyTransforms(scaledData)
        if (transformedData.isEmpty()) {
            Log.d(TAG, "Transforms yielded no data (missing PCA/orientation overlap)")
            return@synchronized null
        }

        val rollingFeatures = transformedData.mapValues { (key, rows) ->
            val columns = when (key) {
                MOTION_PCA_KEY -> motionOutputColumns
                "df_orientation_bucketed" -> params.orientationFeatures
                else -> emptyList()
            }
            computeRollingFeatures(key, rows, columns, params.windowSize)
        }

        if (rollingFeatures.isEmpty()) {
            Log.d(TAG, "Rolling window produced no features; need at least ${params.windowSize} buckets")
            return@synchronized null
        }

        val bucketIntersection = rollingFeatures.values
            .filter { it.isNotEmpty() }
            .map { it.keys.toSet() }
            .reduceOrNull { acc, set -> acc intersect set }
            ?: return@synchronized null
        if (bucketIntersection.isEmpty()) {
            Log.d(TAG, "No overlapping buckets across feature groups; waiting for synchronized samples")
            return@synchronized null
        }

        val latestBucket = bucketIntersection.maxOrNull() ?: return@synchronized null
        val featureLookup = LinkedHashMap<String, Double>()
        rollingFeatures.values.forEach { bucketMap ->
            bucketMap[latestBucket]?.let { values -> featureLookup.putAll(values) }
        }
        if (featureLookup.isEmpty()) {
            Log.d(TAG, "Feature lookup empty for bucket $latestBucket")
            return@synchronized null
        }

        val orderedFeatures = params.featureColumns.map { featureName ->
            val value = featureLookup[featureName]
            if (value == null) {
                Log.d(TAG, "Missing feature $featureName for bucket $latestBucket")
                return@synchronized null
            }
            value
        }

        Log.d(
            TAG,
            "Bucket=$latestBucket features=${orderedFeatures.joinToString(prefix = "[", postfix = "]") { String.format("%.4f", it) }}"
        )

        return@synchronized orderedFeatures.map { it.toFloat() }.toFloatArray()
    }

    private fun buildBucketRows(
        samples: List<SensorSample>,
        spec: SensorSheetSpec
    ): List<BucketRow> {
        if (samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.timestampSec }
        val startThreshold = sorted.first().timestampSec + trimSeconds
        val trimmed = sorted.filter { it.timestampSec >= startThreshold }
        if (trimmed.isEmpty()) return emptyList()

        val bucketMap = linkedMapOf<Double, MutableList<Map<String, Double>>>()
        trimmed.forEach { sample ->
            val bucketKey = round(sample.timestampSec / intervalSeconds) * intervalSeconds
            bucketMap.getOrPut(bucketKey) { mutableListOf() }.add(sample.values)
        }

        val rows = mutableListOf<BucketRow>()
        bucketMap.forEach { (bucket, bucketValues) ->
            val values = LinkedHashMap<String, Double>()
            var validRow = true
            for (column in spec.baseColumns) {
                val columnValues = bucketValues.mapNotNull { it[column] }
                if (columnValues.isEmpty()) {
                    validRow = false
                    break
                }
                values[column] = columnValues.average()
            }
            if (!validRow) return@forEach
            spec.magnitudeSpec?.let { magnitudeSpec ->
                val axisValues = magnitudeSpec.axisColumns.mapNotNull { values[it] }
                if (axisValues.size == magnitudeSpec.axisColumns.size) {
                    values[magnitudeSpec.outputColumn] = calculateMagnitude(axisValues)
                }
            }
            rows.add(BucketRow(bucket, values))
        }
        return rows.sortedBy { it.timeBucket }
    }

    private fun applyTransforms(
        scaledData: Map<String, List<BucketRow>>
    ): Map<String, List<BucketRow>> {
        val result = LinkedHashMap<String, List<BucketRow>>()
        val motionRows = applyMotionPca(scaledData) ?: return emptyMap()
        if (motionRows.isEmpty()) {
            Log.d(TAG, "Motion PCA rows empty; check motion sensor overlap")
            return emptyMap()
        }
        result[MOTION_PCA_KEY] = motionRows

        if (params.orientationFeatures.isNotEmpty()) {
            val orientationRows = scaledData["df_orientation_bucketed"].orEmpty().map { row ->
                val filteredValues = LinkedHashMap<String, Double>()
                params.orientationFeatures.forEach { feature ->
                    row.values[feature]?.let { filteredValues[feature] = it }
                }
                BucketRow(row.timeBucket, filteredValues)
            }.filter { it.values.size == params.orientationFeatures.size }

            if (orientationRows.isEmpty()) {
                Log.d(TAG, "Orientation rows empty; waiting for pitch/roll coverage")
            } else {
                result["df_orientation_bucketed"] = orientationRows
                val latestOrientation = orientationRows.lastOrNull()?.values
                if (!latestOrientation.isNullOrEmpty()) {
                    Log.d(
                        TAG,
                        "Latest orientation snapshot: ${latestOrientation.mapValues { String.format("%.3f", it.value) }}"
                    )
                }
            }
        }

        return result
    }

    private fun applyMotionPca(
        scaledData: Map<String, List<BucketRow>>
    ): List<BucketRow>? {
        val motionParams = params.pca?.motionPca ?: return null
        if (motionParams.columns.isEmpty()) return null

        val rowsBySource = mutableMapOf<String, Map<Double, BucketRow>>()
        for (source in motionSources) {
            val rows = scaledData[source.key] ?: return null
            if (rows.isEmpty()) return null
            rowsBySource[source.key] = rows.associateBy { it.timeBucket }
        }

        val bucketIntersection = rowsBySource.values
            .map { it.keys.toSet() }
            .reduceOrNull { acc, set -> acc intersect set }
            ?: return emptyList()
        if (bucketIntersection.isEmpty()) {
            Log.d(TAG, "No common buckets across motion sources; skipping PCA projection")
            return emptyList()
        }

        val meanVector = motionParams.mean.toDoubleArray()
        val componentMatrix = motionParams.components.map { it.toDoubleArray() }
        val rows = mutableListOf<BucketRow>()
        val sortedBuckets = bucketIntersection.sorted()
        for (bucket in sortedBuckets) {
            val rawVector = DoubleArray(motionParams.columns.size)
            var valid = true
            motionParams.columns.forEachIndexed { index, columnName ->
                val lookup = motionColumnLookup[columnName]
                if (lookup == null) {
                    valid = false
                    return@forEachIndexed
                }
                val row = rowsBySource[lookup.sourceKey]?.get(bucket)
                val value = row?.values?.get(lookup.columnName)
                if (value == null) {
                    valid = false
                    return@forEachIndexed
                }
                rawVector[index] = value
            }
            if (!valid) continue
            val projected = projectPca(rawVector, meanVector, componentMatrix)
            val valueMap = LinkedHashMap<String, Double>()
            projected.forEachIndexed { idx, componentValue ->
                val columnName = "${motionParams.prefix}_pc${idx + 1}"
                valueMap[columnName] = componentValue
            }
            rows.add(BucketRow(bucket, valueMap))
        }
        return rows
    }

    private fun projectPca(
        rawVector: DoubleArray,
        meanVector: DoubleArray,
        components: List<DoubleArray>
    ): DoubleArray {
        val centered = DoubleArray(rawVector.size) { index -> rawVector[index] - meanVector[index] }
        val projected = DoubleArray(components.size)
        components.forEachIndexed { componentIndex, component ->
            var sum = 0.0
            centered.forEachIndexed { featureIndex, value ->
                sum += component[featureIndex] * value
            }
            projected[componentIndex] = sum
        }
        return projected
    }

    private fun computeRollingFeatures(
        sheetKey: String,
        rows: List<BucketRow>,
        columns: List<String>,
        windowSize: Int
    ): Map<Double, Map<String, Double>> {
        if (rows.size < windowSize || columns.isEmpty()) return emptyMap()
        val sorted = rows.sortedBy { it.timeBucket }
        val columnLookup = columns.associateWith { sanitizeColumnName(it) }
        val result = linkedMapOf<Double, Map<String, Double>>()
        for (index in (windowSize - 1) until sorted.size) {
            val featureMap = LinkedHashMap<String, Double>()
            var valid = true
            for (column in columns) {
                val windowValues = mutableListOf<Double>()
                for (cursor in index - windowSize + 1..index) {
                    val value = sorted[cursor].values[column]
                    if (value == null || value.isNaN()) {
                        valid = false
                        break
                    }
                    windowValues.add(value)
                }
                if (!valid || windowValues.isEmpty()) {
                    valid = false
                    break
                }
                val mean = windowValues.average()
                val variance = windowValues.map { (it - mean) * (it - mean) }.average()
                val std = sqrt(variance)
                val sanitized = columnLookup.getValue(column)
                featureMap["${sheetKey}_${sanitized}_mean"] = mean
                featureMap["${sheetKey}_${sanitized}_std"] = std
            }
            if (valid) {
                result[sorted[index].timeBucket] = featureMap
            }
        }
        return result
    }

    private fun scaleBucketRow(dfKey: String, row: BucketRow): BucketRow {
        val scaledValues = LinkedHashMap<String, Double>()
        row.values.forEach { (column, value) ->
            val range = params.perFeatureMinMax["$dfKey.$column"]
            val scaled = range?.let { scaleValue(value, it) } ?: value
            scaledValues[column] = scaled
        }
        return BucketRow(row.timeBucket, scaledValues)
    }

    private fun pruneOldSamples(samples: MutableList<SensorSample>) {
        val cutoff = (samples.lastOrNull()?.timestampSec ?: 0.0) - BUFFER_DURATION_SECONDS
        while (samples.isNotEmpty() && samples.first().timestampSec < cutoff) {
            samples.removeAt(0)
        }
    }
}

private fun scaleValue(value: Double, range: FeatureRange): Double {
    val span = range.max - range.min
    if (span == 0.0) return 0.0
    val clamped = when {
        value < range.min -> range.min
        value > range.max -> range.max
        else -> value
    }
    return (clamped - range.min) / span
}

private fun sanitizeColumnName(source: String): String = source
    .replace(" ", "_")
    .replace("(", "")
    .replace(")", "")
    .replace("°", "deg")
    .replace("/", "_per_")
    .replace("^", "pow")
    .replace("µ", "micro")
