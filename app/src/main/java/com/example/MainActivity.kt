package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ConnectionUiState
import com.example.ui.DeckViewModel
import com.example.ui.components.DeckEditorSheet
import com.example.ui.components.PcAgentSetupSheet
import com.example.ui.screens.ConnectHubScreen
import com.example.ui.screens.DeskDeckScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.theme.DeskDeckTheme
import com.example.ui.theme.ObsidianBg

class MainActivity : ComponentActivity() {
    private val viewModel: DeckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            var showPcSetupSheet by remember { mutableStateOf(false) }
            var showEditorSheet by remember { mutableStateOf(false) }
            var editorInitialPage by remember { mutableIntStateOf(0) }

            // Sync Keep-Screen-On window flag when in DeskDeck mode
            LaunchedEffect(uiState.keepScreenOn, uiState.connectionState) {
                val activeDesk = uiState.connectionState is ConnectionUiState.Connected
                if (activeDesk && uiState.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Collect share intents for exporting deskdeck_agent.py to laptop
            LaunchedEffect(Unit) {
                viewModel.shareIntent.collect { intent ->
                    startActivity(intent)
                }
            }

            DeskDeckTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = ObsidianBg,
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { innerPadding ->
                    val conn = uiState.connectionState
                    when {
                        uiState.isScanningQr -> {
                            QrScannerScreen(
                                onQrDetected = viewModel::handleQrPayload,
                                onBack = viewModel::closeQrScanner,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        conn is ConnectionUiState.Connected -> {
                            DeskDeckScreen(
                                uiState = uiState,
                                connectedInfo = conn,
                                onTogglePomodoro = viewModel::togglePomodoro,
                                onResetPomodoro = viewModel::resetPomodoro,
                                onSelectPomodoroMode = viewModel::selectPomodoroMode,
                                onSkipPomodoro = viewModel::skipPomodoroPhase,
                                onLaunchApp = viewModel::launchAppShortcut,
                                onTriggerMacro = viewModel::triggerMacro,
                                onVolumeChange = viewModel::updateVolumeDebounced,
                                onToggleMute = viewModel::toggleMasterMute,
                                onToggleMicMute = viewModel::toggleMicMute,
                                onMediaCmd = viewModel::sendMediaCommand,
                                onDockerToggle = viewModel::toggleDockerContainer,
                                onAiDecision = viewModel::sendAiDecision,
                                onDismissNotification = viewModel::dismissNotification,
                                onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
                                onOpenDashboardEditor = { pageIdx ->
                                    editorInitialPage = pageIdx
                                    showEditorSheet = true
                                },
                                onOpenPcSetup = { showPcSetupSheet = true },
                                onDisconnect = viewModel::disconnect,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                        else -> {
                            ConnectHubScreen(
                                uiState = uiState,
                                onScanQrClick = viewModel::openQrScanner,
                                onRefreshWifiClick = viewModel::refreshWifiDiscovery,
                                onConnectDiscovered = viewModel::connectToDiscoveredPc,
                                onConnectSaved = viewModel::connectToSavedHost,
                                onRemoveSaved = viewModel::removeSavedHost,
                                onConnectUsbClick = viewModel::connectViaUsbAdb,
                                onConnectManualWifi = viewModel::connectManualWifi,
                                onOpenPcSetupClick = { showPcSetupSheet = true },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                    }

                    if (showPcSetupSheet) {
                        PcAgentSetupSheet(
                            onDismiss = { showPcSetupSheet = false },
                            onShareAgentScript = viewModel::shareBundledPcAgent,
                        )
                    }

                    if (showEditorSheet) {
                        DeckEditorSheet(
                            currentLayout = uiState.layout,
                            initialPageIndex = editorInitialPage,
                            onDismiss = { showEditorSheet = false },
                            onSaveLayout = viewModel::updateLayout,
                            onApplyPreset = viewModel::applyLayoutPreset,
                        )
                    }
                }
            }
        }
    }
}
