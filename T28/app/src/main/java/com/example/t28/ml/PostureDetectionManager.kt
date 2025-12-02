package com.example.t28.ml

import ai.onnxruntime.OnnxMap
import ai.onnxruntime.OnnxSequence
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.getSystemService
import com.example.t28.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "PostureDetector"
private const val CONFIDENCE_THRESHOLD = 0.3f

class PostureDetectionManager(context: Context) : SensorEventListener {

    private val params = ModelParamsLoader.load(context)
    private val specs = SensorSheetRegistry.resolveSpecs(params)
    private val sensorManager: SensorManager = context.getSystemService() ?: throw IllegalStateException("SensorManager unavailable")
    private val aggregator = SensorDataAggregator(specs, params)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val scope = CoroutineScope(Dispatchers.Default)
    private var inferenceJob: Job? = null
    private val _predictions = MutableStateFlow(PosturePrediction("collecting", 0f))
    val predictions: StateFlow<PosturePrediction> = _predictions.asStateFlow()

    private var isRunning = false

    init {
        val modelBytes = context.resources.openRawResource(R.raw.posture_model).use { it.readBytes() }
        session = env.createSession(modelBytes)
        inputName = session.inputNames.first()
    }

    fun start() {
        if (isRunning) return
        aggregator.reset()
        registerSensors()
        inferenceJob = scope.launch {
            while (isActive) {
                delay(1000)
                val features = aggregator.buildFeatureVector()
                if (features != null) {
                    logFeatureVector(features)
                    val prediction = runInference(features)
                    _predictions.value = prediction
                } else {
                    Log.d(TAG, "Waiting for sufficient sensor data to build features")
                }
            }
        }
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        sensorManager.unregisterListener(this)
        inferenceJob?.cancel()
        aggregator.reset()
        _predictions.value = PosturePrediction("collecting", 0f)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> aggregator.record(
                sheetName = "Accelerometer",
                eventTimestampNs = event.timestamp,
                values = mapOf(
                    "Acceleration x (m/s^2)" to event.values[0].toDouble(),
                    "Acceleration y (m/s^2)" to event.values[1].toDouble(),
                    "Acceleration z (m/s^2)" to event.values[2].toDouble()
                )
            )
            Sensor.TYPE_GYROSCOPE -> aggregator.record(
                sheetName = "Gyroscope",
                eventTimestampNs = event.timestamp,
                values = mapOf(
                    "Gyroscope x (rad/s)" to event.values[0].toDouble(),
                    "Gyroscope y (rad/s)" to event.values[1].toDouble(),
                    "Gyroscope z (rad/s)" to event.values[2].toDouble()
                )
            )
            Sensor.TYPE_GRAVITY -> aggregator.record(
                sheetName = "Gravity",
                eventTimestampNs = event.timestamp,
                values = mapOf(
                    "Acceleration x (m/s^2)" to event.values[0].toDouble(),
                    "Acceleration y (m/s^2)" to event.values[1].toDouble(),
                    "Acceleration z (m/s^2)" to event.values[2].toDouble()
                )
            )
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rawX = event.values.getOrNull(0)?.toDouble() ?: 0.0
                val rawY = event.values.getOrNull(1)?.toDouble() ?: 0.0
                val rawZ = event.values.getOrNull(2)?.toDouble() ?: 0.0
                val rawW = event.values.getOrNull(3)?.toDouble() ?: computeQuaternionScalar(rawX, rawY, rawZ)
                val (yawRad, pitchRad, rollRad) = quaternionToYawPitchRoll(rawW, rawX, rawY, rawZ)
                val yawDeg = normalizeYawDegrees(Math.toDegrees(yawRad))
                val pitchDeg = Math.toDegrees(pitchRad)
                val rollDeg = Math.toDegrees(rollRad)
                aggregator.record(
                    sheetName = "Orientation",
                    eventTimestampNs = event.timestamp,
                    values = mapOf(
                        "w" to rawW,
                        "x" to rawX,
                        "y" to rawY,
                        "z" to rawZ,
                        "Direct (°)" to yawDeg,
                        "Yaw (°)" to yawDeg,
                        "Pitch (°)" to pitchDeg,
                        "Roll (°)" to rollDeg
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerSensors() {
        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GRAVITY
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            } ?: Log.w(TAG, "Sensor type $type not available on this device")
        }
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.w(TAG, "Rotation vector sensor not available; orientation features may be inaccurate")
        }
    }

    private fun runInference(features: FloatArray): PosturePrediction {
        val inputShape = longArrayOf(1, features.size.toLong())
        val prediction = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), inputShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val probabilities = extractProbabilities(result)
                logProbabilities(probabilities)
                val (label, confidence) = rankPredictions(probabilities)
                val finalLabel = if (confidence >= CONFIDENCE_THRESHOLD) label else "unclassified"
                Log.d(
                    TAG,
                    "Prediction label=$finalLabel confidence=${"%.3f".format(confidence)} raw=${"%.3f".format(probabilities.maxOrNull() ?: 0f)}"
                )
                PosturePrediction(finalLabel, confidence)
            }
        }
        return prediction
    }

    private fun extractProbabilities(result: OrtSession.Result): FloatArray {
        val iterator = result.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val probabilities = parseProbabilities(entry.value)
            if (probabilities != null) {
                return probabilities
            }
        }
        throw IllegalStateException("Unable to parse ONNX output")
    }

    private fun parseProbabilities(value: OnnxValue): FloatArray? = when (value) {
        is OnnxTensor -> parseProbabilityPayload(value.value)
        is OnnxMap -> parseProbabilityMap(value.value)
        is OnnxSequence -> {
            value.value.asSequence().mapNotNull { element ->
                when (element) {
                    is OnnxValue -> parseProbabilities(element)
                    else -> parseProbabilityPayload(element)
                }
            }.firstOrNull()
        }
        else -> parseProbabilityPayload(value.value)
    }

    private fun parseProbabilityPayload(payload: Any?): FloatArray? = when (payload) {
        null -> null
        is FloatArray -> payload
        is DoubleArray -> payload.map { it.toFloat() }.toFloatArray()
        is Array<*> -> parseProbabilityPayload(payload.firstOrNull())
        is Map<*, *> -> parseProbabilityMap(payload)
        is List<*> -> payload.asSequence().mapNotNull { parseProbabilityPayload(it) }.firstOrNull()
        else -> null
    }

    private fun parseProbabilityMap(raw: Map<*, *>?): FloatArray? {
        raw ?: return null
        val probs = FloatArray(params.classLabels.size)
        params.classLabels.forEachIndexed { index, label ->
            val key = when {
                raw.containsKey(label) -> label
                raw.containsKey(index.toLong()) -> index.toLong()
                raw.containsKey(index) -> index
                else -> null
            }
            val valueFloat = (key?.let { raw[it] } as? Number)?.toFloat() ?: 0f
            probs[index] = valueFloat
        }
        return probs
    }

    private fun rankPredictions(probabilities: FloatArray): Pair<String, Float> {
        if (probabilities.isEmpty()) return "unclassified" to 0f
        var maxIndex = 0
        var maxValue = Float.NEGATIVE_INFINITY
        probabilities.forEachIndexed { index, value ->
            if (value > maxValue) {
                maxValue = value
                maxIndex = index
            }
        }
        val label = params.classLabels.getOrElse(maxIndex) { "unclassified" }
        return label to max(0f, maxValue)
    }

    private fun computeQuaternionScalar(x: Double, y: Double, z: Double): Double {
        val sum = x * x + y * y + z * z
        if (sum >= 1.0) return 0.0
        return sqrt(1.0 - sum)
    }

    // Mirrors Phyphox' Z-Y-X Euler extraction so yaw/pitch/roll match the training set.
    private fun quaternionToYawPitchRoll(w: Double, x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val magnitude = sqrt(w * w + x * x + y * y + z * z)
        if (magnitude == 0.0) return Triple(0.0, 0.0, 0.0)
        val qw = w / magnitude
        val qx = x / magnitude
        val qy = y / magnitude
        val qz = z / magnitude

        val roll = atan2(2.0 * (qw * qx + qy * qz), 1.0 - 2.0 * (qx * qx + qy * qy))
        val sinPitch = 2.0 * (qw * qy - qz * qx)
        val pitch = if (abs(sinPitch) >= 1.0) {
            val sign = if (sinPitch >= 0.0) 1.0 else -1.0
            sign * (Math.PI / 2.0)
        } else {
            asin(sinPitch)
        }
        val yaw = atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz))
        return Triple(yaw, pitch, roll)
    }

    private fun normalizeYawDegrees(rawYaw: Double): Double {
        var normalized = rawYaw % 360.0
        if (normalized < 0) {
            normalized += 360.0
        }
        return normalized
    }

    private fun logFeatureVector(features: FloatArray) {
        val formatted = features.joinToString(prefix = "[", postfix = "]") { value ->
            String.format("%.4f", value)
        }
        Log.d(TAG, "Feature vector sent to ONNX: $formatted")
    }

    private fun logProbabilities(probabilities: FloatArray) {
        val entries = params.classLabels.mapIndexed { index, label ->
            val value = probabilities.getOrNull(index) ?: 0f
            "$label=${String.format("%.3f", value)}"
        }
        Log.d(TAG, "ONNX probabilities: ${entries.joinToString(", ")}")
    }
}
