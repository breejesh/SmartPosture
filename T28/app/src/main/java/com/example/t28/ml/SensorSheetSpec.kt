package com.example.t28.ml

import kotlin.math.sqrt

/**
 * Mirrors the preprocessing structure from the training notebook so that
 * feature generation on-device matches training exactly.
 */
data class SensorSheetSpec(
    val sheetName: String,
    val key: String,
    val baseColumns: List<String>,
    val magnitudeSpec: MagnitudeSpec? = null
)

data class MagnitudeSpec(
    val outputColumn: String,
    val axisColumns: List<String>
)

object SensorSheetRegistry {
    private val defaultSpecs = mapOf(
        "Accelerometer" to SensorSheetSpec(
            sheetName = "Accelerometer",
            key = "df_accelerometer_bucketed",
            baseColumns = listOf(
                "Acceleration x (m/s^2)",
                "Acceleration y (m/s^2)",
                "Acceleration z (m/s^2)"
            ),
            magnitudeSpec = MagnitudeSpec(
                outputColumn = "accelerometer_magnitude",
                axisColumns = listOf(
                    "Acceleration x (m/s^2)",
                    "Acceleration y (m/s^2)",
                    "Acceleration z (m/s^2)"
                )
            )
        ),
        "Gyroscope" to SensorSheetSpec(
            sheetName = "Gyroscope",
            key = "df_gyroscope_bucketed",
            baseColumns = listOf(
                "Gyroscope x (rad/s)",
                "Gyroscope y (rad/s)",
                "Gyroscope z (rad/s)"
            ),
            magnitudeSpec = MagnitudeSpec(
                outputColumn = "gyroscope_magnitude",
                axisColumns = listOf(
                    "Gyroscope x (rad/s)",
                    "Gyroscope y (rad/s)",
                    "Gyroscope z (rad/s)"
                )
            )
        ),
        "Orientation" to SensorSheetSpec(
            sheetName = "Orientation",
            key = "df_orientation_bucketed",
            baseColumns = listOf(
                "w",
                "x",
                "y",
                "z",
                "Direct (°)",
                "Yaw (°)",
                "Pitch (°)",
                "Roll (°)"
            )
        ),
        "Gravity" to SensorSheetSpec(
            sheetName = "Gravity",
            key = "df_gravity_bucketed",
            baseColumns = listOf(
                "Acceleration x (m/s^2)",
                "Acceleration y (m/s^2)",
                "Acceleration z (m/s^2)"
            ),
            magnitudeSpec = MagnitudeSpec(
                outputColumn = "gravity_magnitude",
                axisColumns = listOf(
                    "Acceleration x (m/s^2)",
                    "Acceleration y (m/s^2)",
                    "Acceleration z (m/s^2)"
                )
            )
        )
    )

    fun resolveSpecs(params: ModelParams): List<SensorSheetSpec> =
        params.sheetsToLoad.mapNotNull { defaultSpecs[it] }
}

internal fun calculateMagnitude(values: List<Double>): Double =
    sqrt(values.sumOf { it * it })
