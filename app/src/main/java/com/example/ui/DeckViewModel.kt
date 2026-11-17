package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DeckRepository
import com.example.data.DeckTransportClient
import com.example.data.HostPreferencesStore
import com.example.data.WifiDiscoveryManager
import com.example.model.ActionAck
import com.example.model.ConnectionUiState
import com.example.model.DeckLayoutConfig
import com.example.model.DiscoveredPc
import com.example.model.PcHostProfile
import com.example.model.PomodoroMode
import com.example.model.PomodoroState
import com.example.model.TelemetrySnapshot
import com.example.model.TransportMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DeckUiState(
    val connectionState: ConnectionUiState = ConnectionUiState.Disconnected(),
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val layout: DeckLayoutConfig = DeckRepository.defaultLayout(),
    val discoveredPcs: List<DiscoveredPc> = emptyList(),
    val savedHosts: List<PcHostProfile> = emptyList(),
    val keepScreenOn: Boolean = true,
    val isScanningQr: Boolean = false,
    val lastFeedbackMessage: String? = null,
    val pomodoro: PomodoroState = PomodoroState(),
)

class DeckViewModel(application: Application) : AndroidViewModel(application) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val repository = DeckRepository(
        prefs = HostPreferencesStore(application, moshi),
        wifiDiscovery = WifiDiscoveryManager(application),
        transportClient = DeckTransportClient(moshi),
    )

    private val _isScanningQr = MutableStateFlow(false)
    private val _feedbackMessage = MutableStateFlow<String?>(null)
    private val _pomodoroState = MutableStateFlow(PomodoroState())
    private val _shareIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 2)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    private var volumeDebounceJob: Job? = null
    private var feedbackClearJob: Job? = null
    private var pomodoroTickerJob: Job? = null

    val uiState: StateFlow<DeckUiState> = combine(
        repository.connectionState,
        repository.telemetry,
        repository.layout,
        repository.discoveredPcs,
        repository.savedHosts,
        repository.keepScreenOn,
        _isScanningQr,
        _feedbackMessage,
        _pomodoroState,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        DeckUiState(
            connectionState = values[0] as ConnectionUiState,
            telemetry = values[1] as TelemetrySnapshot,
            layout = values[2] as DeckLayoutConfig,
            discoveredPcs = values[3] as List<DiscoveredPc>,
            savedHosts = values[4] as List<PcHostProfile>,
            keepScreenOn = values[5] as Boolean,
            isScanningQr = values[6] as Boolean,
            lastFeedbackMessage = values[7] as String?,
            pomodoro = values[8] as PomodoroState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DeckUiState(),
    )

    init {
        repository.startDiscovery(viewModelScope)
        viewModelScope.launch {
            repository.actionAcks.collect { ack: ActionAck ->
                showFeedback(ack.message)
            }
        }
    }

    fun openQrScanner() {
        _isScanningQr.value = true
    }

    fun closeQrScanner() {
        _isScanningQr.value = false
    }

    fun refreshWifiDiscovery() {
        repository.refreshWifiDiscovery(viewModelScope)
        showFeedback("Đang quét lại các PC trên mạng Wi-Fi...")
    }

    fun connectToDiscoveredPc(pc: DiscoveredPc) {
        repository.connect(pc.toProfile(), TransportMode.WIFI, viewModelScope)
    }

    fun connectToSavedHost(profile: PcHostProfile) {
        repository.connect(profile, profile.lastTransportMode, viewModelScope)
    }

    fun connectViaUsbAdb(token: String = "") {
        val lastSavedToken = token.ifBlank {
            uiState.value.savedHosts.firstOrNull()?.token.orEmpty()
        }
        val usbProfile = PcHostProfile(
            id = "usb_adb_local",
            name = "PC qua cáp USB",
            host = "127.0.0.1",
            port = 9876,
            token = lastSavedToken,
            lastTransportMode = TransportMode.USB,
        )
        repository.connect(usbProfile, TransportMode.USB, viewModelScope)
    }

    fun connectManualWifi(host: String, portStr: String, token: String, name: String) {
        val cleanHost = host.trim()
        val port = portStr.trim().toIntOrNull() ?: 9876
        if (cleanHost.isBlank()) {
            showFeedback("Vui lòng nhập địa chỉ IP của máy tính")
            return
        }
        val profile = PcHostProfile(
            id = "$cleanHost:$port",
            name = name.trim().ifBlank { cleanHost },
            host = cleanHost,
            port = port,
            token = token.trim(),
            lastTransportMode = TransportMode.WIFI,
        )
        repository.connect(profile, TransportMode.WIFI, viewModelScope)
    }

    fun handleQrPayload(rawText: String): Boolean {
        val uri = runCatching { Uri.parse(rawText) }.getOrNull() ?: return false
        if (uri.scheme != "deskdeck" || uri.host != "pair") {
            return false
        }
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 9876
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { host }
        if (host.isBlank() || token.isBlank()) {
            return false
        }
        _isScanningQr.value = false
        val profile = PcHostProfile(
            id = "$host:$port",
            name = name,
            host = host,
            port = port,
            token = token,
            lastTransportMode = TransportMode.WIFI,
        )
        repository.connect(profile, TransportMode.WIFI, viewModelScope)
        return true
    }

    fun launchAppShortcut(widgetId: String, appCommandOrId: String) {
        repository.sendAction(widgetId, "LAUNCH_APP", appCommandOrId)
    }

    fun triggerMacro(widgetId: String, macroId: String) {
        repository.sendAction(widgetId, "TRIGGER_MACRO", macroId)
    }

    fun updateVolumeDebounced(widgetId: String, volumePercent: Int) {
        volumeDebounceJob?.cancel()
        volumeDebounceJob = viewModelScope.launch {
            delay(55)
            repository.sendAction(widgetId, "SET_VOLUME", volumePercent.toString())
        }
    }

    fun toggleMasterMute(widgetId: String) {
        repository.sendAction(widgetId, "TOGGLE_MUTE", "toggle")
    }

    fun toggleMicMute(widgetId: String) {
        repository.sendAction(widgetId, "TOGGLE_MIC_MUTE", "toggle")
    }

    fun sendMediaCommand(widgetId: String, command: String) {
        repository.sendAction(widgetId, "MEDIA_CMD", command)
    }

    fun toggleDockerContainer(widgetId: String, name: String, currentlyRunning: Boolean) {
        val action = if (currentlyRunning) "stop" else "start"
        repository.sendAction(widgetId, "DOCKER_TOGGLE", "$name:$action")
    }

    fun sendAiDecision(widgetId: String, decision: String) {
        repository.sendAction(widgetId, "AI_DECISION", decision)
    }

    fun dismissNotification(widgetId: String, notifId: String) {
        repository.sendAction(widgetId, "DISMISS_NOTIF", notifId)
    }

    fun toggleKeepScreenOn() {
        val next = !uiState.value.keepScreenOn
        viewModelScope.launch {
            repository.setKeepScreenOn(next)
        }
        showFeedback(if (next) "Đã bật giữ sáng màn hình" else "Đã tắt giữ sáng màn hình")
    }

    fun updateLayout(newLayout: DeckLayoutConfig) {
        repository.updateLayout(newLayout, viewModelScope)
        showFeedback("Đã lưu bố cục ${newLayout.pages.size} màn hình")
    }

    fun applyLayoutPreset(presetKey: String) {
        val preset = DeckRepository.presetLayout(presetKey)
        repository.updateLayout(preset, viewModelScope)
        showFeedback("Đã áp dụng mẫu: ${preset.presetName}")
    }

    fun togglePomodoro() {
        val cur = _pomodoroState.value
        if (cur.isRunning) {
            pomodoroTickerJob?.cancel()
            pomodoroTickerJob = null
            _pomodoroState.update { it.copy(isRunning = false) }
        } else {
            _pomodoroState.update { it.copy(isRunning = true) }
            pomodoroTickerJob?.cancel()
            pomodoroTickerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    val s = _pomodoroState.value
                    if (!s.isRunning) break
                    if (s.remainingSeconds > 1) {
                        _pomodoroState.update { it.copy(remainingSeconds = s.remainingSeconds - 1) }
                    } else {
                        val finishedFocus = s.mode == PomodoroMode.FOCUS
                        val nextCycles = if (finishedFocus) s.completedCycles + 1 else s.completedCycles
                        val nextMode = if (finishedFocus) {
                            if (nextCycles % 4 == 0) PomodoroMode.LONG_BREAK else PomodoroMode.SHORT_BREAK
                        } else {
                            PomodoroMode.FOCUS
                        }
                        _pomodoroState.value = PomodoroState(
                            mode = nextMode,
                            remainingSeconds = nextMode.durationSeconds,
                            totalSeconds = nextMode.durationSeconds,
                            isRunning = false,
                            completedCycles = nextCycles,
                        )
                        showFeedback(
                            if (finishedFocus) "Hoàn thành Focus #$nextCycles! Đã chuyển sang ${nextMode.label}."
                            else "Hết giờ nghỉ! Sẵn sàng cho phiên Focus tiếp theo."
                        )
                        break
                    }
                }
            }
        }
    }

    fun resetPomodoro() {
        pomodoroTickerJob?.cancel()
        pomodoroTickerJob = null
        val mode = _pomodoroState.value.mode
        _pomodoroState.update {
            it.copy(
                remainingSeconds = mode.durationSeconds,
                totalSeconds = mode.durationSeconds,
                isRunning = false,
            )
        }
    }

    fun selectPomodoroMode(mode: PomodoroMode) {
        pomodoroTickerJob?.cancel()
        pomodoroTickerJob = null
        _pomodoroState.update {
            it.copy(
                mode = mode,
                remainingSeconds = mode.durationSeconds,
                totalSeconds = mode.durationSeconds,
                isRunning = false,
            )
        }
    }

    fun skipPomodoroPhase() {
        pomodoroTickerJob?.cancel()
        pomodoroTickerJob = null
        val cur = _pomodoroState.value
        val nextMode = when (cur.mode) {
            PomodoroMode.FOCUS -> PomodoroMode.SHORT_BREAK
            PomodoroMode.SHORT_BREAK, PomodoroMode.LONG_BREAK -> PomodoroMode.FOCUS
        }
        _pomodoroState.update {
            it.copy(
                mode = nextMode,
                remainingSeconds = nextMode.durationSeconds,
                totalSeconds = nextMode.durationSeconds,
                isRunning = false,
            )
        }
    }

    fun removeSavedHost(hostId: String) {
        viewModelScope.launch {
            repository.removeSavedHost(hostId)
        }
    }

    fun disconnect() {
        pomodoroTickerJob?.cancel()
        repository.disconnect()
    }

    fun shareBundledPcAgent() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) {
                val exportDir = File(ctx.cacheDir, "pc_agent").apply { mkdirs() }
                val outFile = File(exportDir, "deskdeck_agent.py")
                ctx.assets.open("pc_agent/deskdeck_agent.py").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/x-python"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "DeskDeck PC Host Agent (deskdeck_agent.py)")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Chạy trên PC bằng lệnh: python3 deskdeck_agent.py — sau đó mở http://localhost:9876 để tùy chỉnh Dashboard & quét QR.",
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            _shareIntent.emit(Intent.createChooser(intent, "Gửi DeskDeck PC Agent sang máy tính"))
        }
    }

    private fun showFeedback(msg: String) {
        if (msg.isBlank()) return
        _feedbackMessage.value = msg
        feedbackClearJob?.cancel()
        feedbackClearJob = viewModelScope.launch {
            delay(2600)
            _feedbackMessage.value = null
        }
    }
}
