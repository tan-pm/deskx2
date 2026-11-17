package com.example.model

import com.squareup.moshi.JsonClass

enum class TransportMode {
    WIFI,
    USB,
}

@JsonClass(generateAdapter = true)
data class PcHostProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 9876,
    val token: String = "",
    val lastTransportMode: TransportMode = TransportMode.WIFI,
    val lastConnectedAt: Long = System.currentTimeMillis(),
)

data class DiscoveredPc(
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val osName: String,
    val discoveredAt: Long = System.currentTimeMillis(),
) {
    fun toProfile(): PcHostProfile = PcHostProfile(
        id = "${host}:${port}",
        name = name,
        host = host,
        port = port,
        token = token,
        lastTransportMode = TransportMode.WIFI,
    )
}

@JsonClass(generateAdapter = true)
data class DeckWidgetSpec(
    val id: String,
    val type: String,
    val title: String,
    val colSpan: Int = 2,
    val accentColorKey: String = "cyan",
)

@JsonClass(generateAdapter = true)
data class DeckPage(
    val id: String,
    val title: String,
    val widgets: List<DeckWidgetSpec>,
)

@JsonClass(generateAdapter = true)
data class DeckLayoutConfig(
    val version: Int = 1,
    val presetName: String = "Full PhoneBox",
    val pages: List<DeckPage> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AppShortcutItem(
    val id: String,
    val label: String,
    val command: String,
    val icon: String = "terminal",
    val color: String = "cyan",
)

@JsonClass(generateAdapter = true)
data class MacroItem(
    val id: String,
    val label: String,
    val hotkey: String = "",
    val command: String = "",
)

@JsonClass(generateAdapter = true)
data class DockerContainerItem(
    val name: String,
    val state: String,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class AiPendingPrompt(
    val id: String,
    val tool: String,
    val command: String,
    val reason: String,
)

@JsonClass(generateAdapter = true)
data class PcNotificationItem(
    val id: String,
    val app: String,
    val title: String,
    val body: String,
    val time: String,
)

@JsonClass(generateAdapter = true)
data class TelemetrySnapshot(
    val type: String = "telemetry",
    val timestamp: Long = System.currentTimeMillis(),
    val cpuPercent: Double = 0.0,
    val cpuTempC: Double = 0.0,
    val ramUsedGb: Double = 0.0,
    val ramTotalGb: Double = 16.0,
    val gpuPercent: Double = 0.0,
    val gpuTempC: Double = 0.0,
    val netDownKbps: Double = 0.0,
    val netUpKbps: Double = 0.0,
    val diskPercent: Double = 0.0,
    val uptimeFormatted: String = "0h 00m",
    val agentRssMb: Double = 18.4,
    val mediaTitle: String = "Chưa có media đang phát",
    val mediaArtist: String = "Desktop Audio",
    val mediaPlaying: Boolean = false,
    val masterVolume: Int = 65,
    val isMuted: Boolean = false,
    val micMuted: Boolean = false,
    val cpuHistory: List<Double> = emptyList(),
    val ramHistory: List<Double> = emptyList(),
    val dockerContainers: List<DockerContainerItem> = emptyList(),
    val gitRepoName: String = "workspace",
    val gitBranch: String = "main",
    val gitChangedFiles: Int = 0,
    val gitOpenPrs: Int = 0,
    val aiProvider: String = "Local Dev Session",
    val aiQuotaRemainingPct: Int = 82,
    val aiBurnRateText: String = "Ổn định",
    val aiPendingPrompt: AiPendingPrompt? = null,
    val notifications: List<PcNotificationItem> = emptyList(),
    val apps: List<AppShortcutItem> = emptyList(),
    val macros: List<MacroItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DeckActionEvent(
    val actionId: String,
    val widgetId: String,
    val type: String,
    val value: String,
)

@JsonClass(generateAdapter = true)
data class ActionAck(
    val type: String = "ack",
    val actionId: String = "",
    val widgetId: String = "",
    val ok: Boolean = true,
    val message: String = "",
)

sealed interface ConnectionUiState {
    data class Disconnected(
        val errorMessage: String? = null,
    ) : ConnectionUiState

    data class Connecting(
        val hostName: String,
        val transportMode: TransportMode,
    ) : ConnectionUiState

    data class Connected(
        val profile: PcHostProfile,
        val transportMode: TransportMode,
        val latencyMs: Int,
    ) : ConnectionUiState
}

enum class PomodoroMode(val label: String, val durationSeconds: Int) {
    FOCUS("Focus", 25 * 60),
    SHORT_BREAK("Nghỉ ngắn", 5 * 60),
    LONG_BREAK("Nghỉ dài", 15 * 60),
}

data class PomodoroState(
    val mode: PomodoroMode = PomodoroMode.FOCUS,
    val remainingSeconds: Int = PomodoroMode.FOCUS.durationSeconds,
    val totalSeconds: Int = PomodoroMode.FOCUS.durationSeconds,
    val isRunning: Boolean = false,
    val completedCycles: Int = 0,
)

data class WidgetCatalogItem(
    val type: String,
    val defaultTitle: String,
    val defaultColSpan: Int,
    val defaultAccent: String,
    val description: String,
)

object DeckWidgetCatalog {
    val items: List<WidgetCatalogItem> = listOf(
        WidgetCatalogItem("CLOCK_WEATHER", "Đồng hồ & Hệ thống", 2, "cyan", "Giờ hệ thống, uptime và RAM agent"),
        WidgetCatalogItem("POMODORO_TIMER", "Pomodoro Focus", 2, "red", "Đếm ngược tập trung 25p / nghỉ 5p / 15p"),
        WidgetCatalogItem("CPU_LOAD", "CPU", 1, "cyan", "Tải CPU, nhiệt độ và biểu đồ"),
        WidgetCatalogItem("RAM_USAGE", "RAM", 1, "violet", "Dung lượng RAM đang sử dụng"),
        WidgetCatalogItem("GPU_STATS", "GPU & Nhiệt độ", 2, "amber", "Hiệu suất & nhiệt độ GPU"),
        WidgetCatalogItem("NETWORK_SPEED", "Mạng & Ổ đĩa", 2, "emerald", "Tốc độ tải xuống/lên và dung lượng ổ đĩa"),
        WidgetCatalogItem("MEDIA_PLAYER", "Trình phát Media", 2, "cyan", "Play/Pause, chuyển bài trên PC"),
        WidgetCatalogItem("VOLUME_MIXER", "Âm lượng PC", 2, "amber", "Thanh trượt âm lượng, tắt tiếng & mic"),
        WidgetCatalogItem("APP_LAUNCHER", "Mở nhanh ứng dụng", 2, "cyan", "Các nút mở Terminal, VS Code, Browser..."),
        WidgetCatalogItem("MACRO_BUTTON", "Phím tắt Macro", 2, "violet", "Bảng nút chạy lệnh & phím tắt nhanh"),
        WidgetCatalogItem("DEV_DOCKER_GIT", "Docker & Git Status", 2, "emerald", "Theo dõi Git branch & bật/tắt container"),
        WidgetCatalogItem("AI_QUOTA_PROMPT", "AI Coding & Duyệt lệnh", 2, "violet", "Duyệt hoặc từ chối lệnh AI từ xa"),
        WidgetCatalogItem("PC_NOTIFICATIONS", "Thông báo PC", 2, "amber", "Hiển thị thông báo đẩy từ máy tính"),
    )
}

