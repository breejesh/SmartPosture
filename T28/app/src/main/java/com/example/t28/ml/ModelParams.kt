package com.example.t28.ml

import android.content.Context
import com.example.t28.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration exported from the training notebook.
 */
data class FeatureRange(val min: Double, val max: Double)

data class MotionPcaParams(
    val columns: List<String>,
    val components: List<List<Double>>,
    val mean: List<Double>,
    val prefix: String,
    val componentCount: Int
)

data class PcaBundle(val motionPca: MotionPcaParams?)

data class ModelParams(
    val perFeatureMinMax: Map<String, FeatureRange>,
    val windowSize: Int,
    val interval: Double,
    val trimSeconds: Double,
    val sheetsToLoad: List<String>,
    val classLabels: List<String>,
    val featureColumns: List<String>,
    val orientationFeatures: List<String>,
    val pca: PcaBundle?
)

object ModelParamsLoader {
    fun load(context: Context): ModelParams {
        val rawJson = context.resources.openRawResource(R.raw.model_params).use { input ->
            input.bufferedReader().readText()
        }
        val json = JSONObject(rawJson)
        val perFeatureMinMax = json.getJSONObject("per_feature_min_max").toFeatureRangeMap()
        val featureColumns = json.getJSONArray("feature_columns").toList()
        val orientationFeatures = json.optJSONArray("orientation_features")?.toList().orEmpty()
        val pca = json.optJSONObject("pca")?.let { obj ->
            PcaBundle(motionPca = obj.optJSONObject("motion_pca")?.toMotionPca())
        }
        return ModelParams(
            perFeatureMinMax = perFeatureMinMax,
            windowSize = json.getInt("window_size"),
            interval = json.getDouble("interval"),
            trimSeconds = json.getDouble("trim_seconds"),
            sheetsToLoad = json.getJSONArray("sheets_to_load").toList(),
            classLabels = json.getJSONArray("class_labels").toList(),
            featureColumns = featureColumns,
            orientationFeatures = orientationFeatures,
            pca = pca
        )
    }
}

private fun org.json.JSONArray.toList(): List<String> {
    val items = mutableListOf<String>()
    for (i in 0 until length()) {
        items += getString(i)
    }
    return items
}

private fun JSONObject.toFeatureRangeMap(): Map<String, FeatureRange> {
    val ranges = mutableMapOf<String, FeatureRange>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val rangeObject = getJSONObject(key)
        ranges[key] = FeatureRange(
            min = rangeObject.getDouble("min"),
            max = rangeObject.getDouble("max")
        )
    }
    return ranges
}

private fun JSONObject.toMotionPca(): MotionPcaParams {
    return MotionPcaParams(
        columns = getJSONArray("columns").toList(),
        components = getJSONArray("components").toDoubleMatrix(),
        mean = getJSONArray("mean").toDoubleList(),
        prefix = getString("prefix"),
        componentCount = getInt("n_components")
    )
}

private fun JSONArray.toDoubleList(): List<Double> {
    val items = mutableListOf<Double>()
    for (i in 0 until length()) {
        items += getDouble(i)
    }
    return items
}

private fun JSONArray.toDoubleMatrix(): List<List<Double>> {
    val matrix = mutableListOf<List<Double>>()
    for (i in 0 until length()) {
        val row = getJSONArray(i).toDoubleList()
        matrix += row
    }
    return matrix
}
