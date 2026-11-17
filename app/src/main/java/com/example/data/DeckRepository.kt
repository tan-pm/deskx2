package com.example.data

import com.example.model.ActionAck
import com.example.model.ConnectionUiState
import com.example.model.DeckActionEvent
import com.example.model.DeckLayoutConfig
import com.example.model.DeckPage
import com.example.model.DeckWidgetSpec
import com.example.model.DiscoveredPc
import com.example.model.PcHostProfile
import com.example.model.TelemetrySnapshot
import com.example.model.TransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class DeckRepository(
    private val prefs: HostPreferencesStore,
    private val wifiDiscovery: WifiDiscoveryManager,
    private val transportClient: DeckTransportClient,
) {
    val savedHosts: Flow<List<PcHostProfile>> = prefs.savedHosts
    val keepScreenOn: Flow<Boolean> = prefs.keepScreenOn
    val discoveredPcs: StateFlow<List<DiscoveredPc>> = wifiDiscovery.discoveredPcs

    private val _connectionState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Disconnected())
    val connectionState: StateFlow<ConnectionUiState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _layout = MutableStateFlow(defaultLayout())
    val layout: StateFlow<DeckLayoutConfig> = _layout.asStateFlow()

    private val _actionAcks = MutableSharedFlow<ActionAck>(extraBufferCapacity = 16)
    val actionAcks: SharedFlow<ActionAck> = _actionAcks.asSharedFlow()

    fun startDiscovery(scope: CoroutineScope) {
        wifiDiscovery.start(scope)
        scope.launch {
            prefs.cachedLayout.collect { cached ->
                if (cached != null && _connectionState.value is ConnectionUiState.Disconnected) {
                    _layout.value = cached
                }
            }
        }
    }

    fun refreshWifiDiscovery(scope: CoroutineScope) {
        wifiDiscovery.triggerProbe(scope)
    }

    fun connect(profile: PcHostProfile, mode: TransportMode, scope: CoroutineScope) {
        _connectionState.value = ConnectionUiState.Connecting(profile.name, mode)
        transportClient.connect(
            profile = profile,
            mode = mode,
            scope = scope,
            listener = object : DeckTransportClient.Listener {
                override fun onConnected(profile: PcHostProfile, mode: TransportMode) {
                    _connectionState.value = ConnectionUiState.Connected(
                        profile = profile,
                        transportMode = mode,
                        latencyMs = 12,
                    )
                    scope.launch {
                        prefs.saveOrUpdateHost(profile.copy(lastTransportMode = mode))
                    }
                }

                override fun onTelemetry(snapshot: TelemetrySnapshot) {
                    _telemetry.value = snapshot
                }

                override fun onLayoutUpdated(layout: DeckLayoutConfig) {
                    _layout.value = layout
                    scope.launch { prefs.saveLayout(layout) }
                }

                override fun onActionAck(ack: ActionAck) {
                    _actionAcks.tryEmit(ack)
                }

                override fun onLatencyMeasured(latencyMs: Int) {
                    val cur = _connectionState.value
                    if (cur is ConnectionUiState.Connected) {
                        _connectionState.value = cur.copy(latencyMs = latencyMs)
                    }
                }

                override fun onDisconnected(reason: String?) {
                    _connectionState.value = ConnectionUiState.Disconnected(errorMessage = reason)
                }
            }
        )
    }

    fun sendAction(widgetId: String, type: String, value: String) {
        val event = DeckActionEvent(
            actionId = UUID.randomUUID().toString().take(8),
            widgetId = widgetId,
            type = type,
            value = value,
        )
        transportClient.sendAction(event)
    }

    fun updateLayout(newLayout: DeckLayoutConfig, scope: CoroutineScope) {
        val sanitized = newLayout.copy(
            pages = newLayout.pages.ifEmpty { defaultLayout().pages }
        )
        _layout.value = sanitized
        scope.launch { prefs.saveLayout(sanitized) }
        val state = _connectionState.value
        if (state is ConnectionUiState.Connected) {
            transportClient.sendLayoutUpdate(sanitized)
        }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        prefs.setKeepScreenOn(enabled)
    }

    suspend fun removeSavedHost(hostId: String) {
        prefs.removeHost(hostId)
    }

    fun disconnect() {
        transportClient.disconnect()
        _connectionState.value = ConnectionUiState.Disconnected()
    }

    companion object {
        fun defaultLayout(): DeckLayoutConfig = DeckLayoutConfig(
            version = 1,
            presetName = "Stream Deck Chuẩn",
            pages = listOf(
                DeckPage(
                    id = "page_overview",
                    title = "Tổng quan & Focus",
                    widgets = listOf(
                        DeckWidgetSpec("w_clock", "CLOCK_WEATHER", "Đồng hồ & Hệ thống", 2, "cyan"),
                        DeckWidgetSpec("w_pomo", "POMODORO_TIMER", "Pomodoro Focus", 2, "red"),
                        DeckWidgetSpec("w_cpu", "CPU_LOAD", "CPU", 1, "cyan"),
                        DeckWidgetSpec("w_ram", "RAM_USAGE", "RAM", 1, "violet"),
                        DeckWidgetSpec("w_media", "MEDIA_PLAYER", "Trình phát Media", 2, "cyan"),
                        DeckWidgetSpec("w_vol", "VOLUME_MIXER", "Âm lượng PC", 2, "amber"),
                        DeckWidgetSpec("w_net", "NETWORK_SPEED", "Mạng & Ổ đĩa", 2, "emerald"),
                    ),
                ),
                DeckPage(
                    id = "page_shortcuts",
                    title = "App & Macro Deck",
                    widgets = listOf(
                        DeckWidgetSpec("w_apps", "APP_LAUNCHER", "Mở nhanh ứng dụng", 2, "cyan"),
                        DeckWidgetSpec("w_macros", "MACRO_BUTTON", "Phím tắt Macro", 2, "violet"),
                        DeckWidgetSpec("w_gpu", "GPU_STATS", "GPU & Nhiệt độ", 2, "amber"),
                    ),
                ),
                DeckPage(
                    id = "page_dev",
                    title = "Dev & Thông báo",
                    widgets = listOf(
                        DeckWidgetSpec("w_ai", "AI_QUOTA_PROMPT", "AI Coding & Duyệt lệnh", 2, "violet"),
                        DeckWidgetSpec("w_dev", "DEV_DOCKER_GIT", "Docker & Git Status", 2, "emerald"),
                        DeckWidgetSpec("w_notif", "PC_NOTIFICATIONS", "Thông báo PC", 4, "amber"),
                    ),
                ),
            ),
        )

        fun presetLayout(presetKey: String): DeckLayoutConfig = when (presetKey) {
            "focus" -> DeckLayoutConfig(
                version = 1,
                presetName = "Focus & Pomodoro",
                pages = listOf(
                    DeckPage(
                        id = "page_focus_1",
                        title = "Focus Station",
                        widgets = listOf(
                            DeckWidgetSpec("w_pomo", "POMODORO_TIMER", "Pomodoro Focus", 2, "red"),
                            DeckWidgetSpec("w_clock", "CLOCK_WEATHER", "Đồng hồ", 2, "cyan"),
                            DeckWidgetSpec("w_media", "MEDIA_PLAYER", "Nhạc Focus", 2, "cyan"),
                            DeckWidgetSpec("w_vol", "VOLUME_MIXER", "Âm lượng PC", 2, "amber"),
                        ),
                    ),
                    DeckPage(
                        id = "page_focus_2",
                        title = "App & Hệ thống",
                        widgets = listOf(
                            DeckWidgetSpec("w_apps", "APP_LAUNCHER", "Mở nhanh ứng dụng", 2, "cyan"),
                            DeckWidgetSpec("w_cpu", "CPU_LOAD", "CPU", 1, "cyan"),
                            DeckWidgetSpec("w_ram", "RAM_USAGE", "RAM", 1, "violet"),
                        ),
                    ),
                ),
            )
            "dev" -> DeckLayoutConfig(
                version = 1,
                presetName = "Developer Cockpit",
                pages = listOf(
                    DeckPage(
                        id = "page_dev_1",
                        title = "Dev & Terminal",
                        widgets = listOf(
                            DeckWidgetSpec("w_apps", "APP_LAUNCHER", "Terminal & Apps", 2, "cyan"),
                            DeckWidgetSpec("w_macros", "MACRO_BUTTON", "Macro Dev", 2, "violet"),
                            DeckWidgetSpec("w_ai", "AI_QUOTA_PROMPT", "AI Approval", 2, "violet"),
                            DeckWidgetSpec("w_dev", "DEV_DOCKER_GIT", "Docker & Git", 2, "emerald"),
                        ),
                    ),
                    DeckPage(
                        id = "page_dev_2",
                        title = "Telemetry & Focus",
                        widgets = listOf(
                            DeckWidgetSpec("w_pomo", "POMODORO_TIMER", "Pomodoro Focus", 2, "red"),
                            DeckWidgetSpec("w_cpu", "CPU_LOAD", "CPU", 1, "cyan"),
                            DeckWidgetSpec("w_ram", "RAM_USAGE", "RAM", 1, "violet"),
                            DeckWidgetSpec("w_notif", "PC_NOTIFICATIONS", "Thông báo PC", 4, "amber"),
                        ),
                    ),
                ),
            )
            "minimal" -> DeckLayoutConfig(
                version = 1,
                presetName = "Tối giản",
                pages = listOf(
                    DeckPage(
                        id = "page_min_1",
                        title = "Desk Minimal",
                        widgets = listOf(
                            DeckWidgetSpec("w_clock", "CLOCK_WEATHER", "Đồng hồ", 2, "cyan"),
                            DeckWidgetSpec("w_pomo", "POMODORO_TIMER", "Pomodoro", 2, "red"),
                            DeckWidgetSpec("w_apps", "APP_LAUNCHER", "Mở App", 2, "cyan"),
                            DeckWidgetSpec("w_vol", "VOLUME_MIXER", "Âm lượng", 2, "amber"),
                        ),
                    ),
                ),
            )
            else -> defaultLayout()
        }
    }
}
