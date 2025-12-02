package com.example.t28

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.t28.data.SessionResult
import com.example.t28.ui.screens.HomeScreen
import com.example.t28.ui.screens.RecordingScreen
import com.example.t28.ui.screens.ResultsScreen
import com.example.t28.ui.theme.T28Theme
import com.example.t28.viewmodel.SessionViewModel

enum class AppScreen {
    HOME, RECORDING, RESULTS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val sessionViewModel: SessionViewModel = viewModel()
            T28Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    AppContent(sessionViewModel)
                }
            }
        }
    }
}

@Composable
fun AppContent(viewModel: SessionViewModel) {
    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    val uiState by viewModel.uiState.collectAsState()
    val sessionResultState by viewModel.sessionResult.collectAsState()
    var latestResult by remember { mutableStateOf<SessionResult?>(null) }

    when (currentScreen) {
        AppScreen.HOME -> {
            HomeScreen(
                onStartSession = {
                    viewModel.startSession()
                    currentScreen = AppScreen.RECORDING
                },
                isSessionActive = uiState.isRunning
            )
        }
        AppScreen.RECORDING -> {
            RecordingScreen(
                uiState = uiState,
                onStopSession = {
                    latestResult = viewModel.stopSession()
                    currentScreen = AppScreen.RESULTS
                },
                onCancel = {
                    viewModel.stopSession()
                    latestResult = null
                    currentScreen = AppScreen.HOME
                }
            )
        }
        AppScreen.RESULTS -> {
            ResultsScreen(
                sessionResult = latestResult ?: sessionResultState,
                onBackToHome = {
                    viewModel.clearResult()
                    latestResult = null
                    currentScreen = AppScreen.HOME
                }
            )
        }
    }
}