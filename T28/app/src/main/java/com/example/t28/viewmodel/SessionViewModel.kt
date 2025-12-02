package com.example.t28.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.t28.data.PostureBreakdown
import com.example.t28.data.SessionResult
import com.example.t28.data.postureColor
import com.example.t28.ml.PostureDetectionManager
import com.example.t28.ml.PosturePrediction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SessionViewModel(application: Application) : AndroidViewModel(application) {

    private val detectionManager = PostureDetectionManager(application)
    private val postureHistory = mutableListOf<PosturePrediction>()

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private val _sessionResult = MutableStateFlow<SessionResult?>(null)
    val sessionResult: StateFlow<SessionResult?> = _sessionResult.asStateFlow()

    private var timerJob: Job? = null
    private var collectorJob: Job? = null

    fun startSession() {
        if (_uiState.value.isRunning) return
        postureHistory.clear()
        _sessionResult.value = null
        detectionManager.start()
        _uiState.value = SessionUiState(isRunning = true)
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.update { current ->
                    current.copy(elapsedSeconds = current.elapsedSeconds + 1)
                }
            }
        }
        collectorJob = viewModelScope.launch {
            detectionManager.predictions.collect { prediction ->
                if (!_uiState.value.isRunning || prediction.predictedLabel == "collecting") return@collect
                postureHistory.add(prediction)
                _uiState.update { current ->
                    current.copy(
                        currentPosture = prediction.displayLabel,
                        confidence = prediction.confidence,
                        lastLabel = prediction.predictedLabel
                    )
                }
            }
        }
    }

    fun stopSession(): SessionResult? {
        if (!_uiState.value.isRunning) return _sessionResult.value
        detectionManager.stop()
        timerJob?.cancel()
        collectorJob?.cancel()
        val totalElapsed = _uiState.value.elapsedSeconds
        val result = if (totalElapsed > 0) buildResult(totalElapsed) else null
        _sessionResult.value = result
        _uiState.value = SessionUiState()
        return result
    }

    fun clearResult() {
        _sessionResult.value = null
    }

    private fun buildResult(totalElapsedSeconds: Int): SessionResult {
        val sampleCount = postureHistory.size
        if (sampleCount == 0) {
            return SessionResult(totalDuration = totalElapsedSeconds, breakdown = emptyList())
        }

        val counts = postureHistory.groupingBy { it.predictedLabel }.eachCount()

        data class DurationAllocation(
            val label: String,
            val percentage: Float,
            val baseSeconds: Int,
            val remainder: Float
        )

        val allocations = counts.entries.map { (label, occurrences) ->
            val proportion = occurrences.toFloat() / sampleCount
            val rawSeconds = proportion * totalElapsedSeconds
            DurationAllocation(
                label = label,
                percentage = proportion * 100f,
                baseSeconds = rawSeconds.toInt(),
                remainder = rawSeconds - rawSeconds.toInt()
            )
        }

        val baseSum = allocations.sumOf { it.baseSeconds }
        var remaining = totalElapsedSeconds - baseSum
        val sortedAllocations = allocations.sortedByDescending { it.remainder }
        val adjustedDurations = mutableMapOf<String, Int>()
        sortedAllocations.forEach { allocation ->
            val addOne = if (remaining > 0) {
                remaining--
                1
            } else {
                0
            }
            adjustedDurations[allocation.label] = allocation.baseSeconds + addOne
        }

        val breakdown = allocations.map { allocation ->
            PostureBreakdown(
                posture = allocation.label.toReadable(),
                duration = adjustedDurations.getValue(allocation.label),
                percentage = allocation.percentage,
                color = postureColor(allocation.label)
            )
        }.sortedByDescending { it.duration }

        return SessionResult(
            totalDuration = totalElapsedSeconds,
            breakdown = breakdown
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}

data class SessionUiState(
    val isRunning: Boolean = false,
    val elapsedSeconds: Int = 0,
    val currentPosture: String = "Collecting...",
    val confidence: Float = 0f,
    val lastLabel: String = "unclassified"
)

private fun String.toReadable(): String =
    if (this == "unclassified") {
        "Unclassified"
    } else {
        replace('_', ' ').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }
