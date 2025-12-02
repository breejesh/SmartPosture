package com.example.t28.ml

import java.util.Locale

data class PosturePrediction(
    val predictedLabel: String,
    val confidence: Float
) {
    val displayLabel: String
        get() = if (predictedLabel == "unclassified") {
            "Unclassified"
        } else {
            predictedLabel.replace('_', ' ').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        }
}
