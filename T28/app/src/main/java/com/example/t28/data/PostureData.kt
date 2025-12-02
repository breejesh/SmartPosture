package com.example.t28.data

import androidx.compose.ui.graphics.Color
import com.example.t28.ui.theme.CrossLeggedPurple
import com.example.t28.ui.theme.LeanBackBlue
import com.example.t28.ui.theme.LeanLeftTeal
import com.example.t28.ui.theme.LeanRightIndigo
import com.example.t28.ui.theme.SlouchedOrange
import com.example.t28.ui.theme.UprightGreen
import com.example.t28.ui.theme.UnclassifiedGray

data class PostureBreakdown(
    val posture: String,
    val duration: Int, // in seconds
    val percentage: Float,
    val color: Color
)

data class SessionResult(
    val totalDuration: Int, // in seconds
    val breakdown: List<PostureBreakdown>
)

// Sample data generator
fun generateSampleSessionResult(): SessionResult {
    val upright = PostureBreakdown(
        posture = "Upright",
        duration = 245,
        percentage = 52.5f,
        color = UprightGreen
    )
    val slouched = PostureBreakdown(
        posture = "Slouched",
        duration = 156,
        percentage = 33.3f,
        color = SlouchedOrange
    )
    val crossLegged = PostureBreakdown(
        posture = "Cross-Legged",
        duration = 67,
        percentage = 14.2f,
        color = CrossLeggedPurple
    )

    return SessionResult(
        totalDuration = 468,
        breakdown = listOf(upright, slouched, crossLegged)
    )
}

fun postureColor(label: String): Color = when (label) {
    "straight" -> UprightGreen
    "slouching" -> SlouchedOrange
    "leaning_back" -> LeanBackBlue
    "leaning_left" -> LeanLeftTeal
    "leaning_right" -> LeanRightIndigo
    else -> UnclassifiedGray
}
