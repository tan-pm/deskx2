package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AppShortcutItem
import com.example.model.DeckWidgetSpec
import com.example.model.MacroItem
import com.example.model.PomodoroMode
import com.example.model.PomodoroState
import com.example.model.TelemetrySnapshot
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CoralRed
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun resolveAccent(key: String): Color = when (key.lowercase()) {
    "violet" -> SoftViolet
    "emerald" -> EmeraldAccent
    "amber" -> AmberAccent
    "red" -> CoralRed
    else -> ElectricCyan
}

@Composable
fun DeckWidgetCard(
    spec: DeckWidgetSpec,
    telemetry: TelemetrySnapshot,
    pomodoro: PomodoroState,
    onTogglePomodoro: () -> Unit,
    onResetPomodoro: () -> Unit,
    onSelectPomodoroMode: (PomodoroMode) -> Unit,
    onSkipPomodoro: () -> Unit,
    onLaunchApp: (widgetId: String, appCommandOrId: String) -> Unit,
    onTriggerMacro: (widgetId: String, macroId: String) -> Unit,
    onVolumeChange: (widgetId: String, volume: Int) -> Unit,
    onToggleMute: (widgetId: String) -> Unit,
    onToggleMicMute: (widgetId: String) -> Unit,
    onMediaCmd: (widgetId: String, cmd: String) -> Unit,
    onDockerToggle: (widgetId: String, name: String, running: Boolean) -> Unit,
    onAiDecision: (widgetId: String, decision: String) -> Unit,
    onDismissNotification: (widgetId: String, notifId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = resolveAccent(spec.accentColorKey)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (spec.type) {
                "CLOCK_WEATHER" -> ClockWeatherContent(telemetry, accent)
                "POMODORO_TIMER" -> PomodoroTimerContent(
                    title = spec.title,
                    pomodoro = pomodoro,
                    accent = accent,
                    onToggle = onTogglePomodoro,
                    onReset = onResetPomodoro,
                    onSelectMode = onSelectPomodoroMode,
                    onSkip = onSkipPomodoro,
                )
                "CPU_LOAD" -> MetricGaugeContent(
                    title = spec.title,
                    percent = telemetry.cpuPercent.toFloat(),
                    primaryText = "${telemetry.cpuPercent.roundToInt()}%",
                    secondaryText = "${telemetry.cpuTempC.roundToInt()}°C",
                    history = telemetry.cpuHistory,
                    accent = accent,
                    icon = Icons.Rounded.Speed,
                )
                "RAM_USAGE" -> {
                    val ramPct = ((telemetry.ramUsedGb / telemetry.ramTotalGb.coerceAtLeast(1.0)) * 100.0).toFloat()
                    MetricGaugeContent(
                        title = spec.title,
                        percent = ramPct,
                        primaryText = "${telemetry.ramUsedGb} GB",
                        secondaryText = "/ ${telemetry.ramTotalGb} GB",
                        history = telemetry.ramHistory,
                        accent = accent,
                        icon = Icons.Rounded.Memory,
                    )
                }
                "GPU_STATS" -> MetricGaugeContent(
                    title = spec.title,
                    percent = telemetry.gpuPercent.toFloat(),
                    primaryText = "${telemetry.gpuPercent.roundToInt()}% GPU",
                    secondaryText = "${telemetry.gpuTempC.roundToInt()}°C • Agent ${telemetry.agentRssMb} MB",
                    history = telemetry.cpuHistory,
                    accent = accent,
                    icon = Icons.Rounded.GraphicEq,
                )
                "NETWORK_SPEED", "DISK_SPACE" -> NetworkDiskContent(telemetry, accent)
                "MEDIA_PLAYER" -> MediaPlayerContent(
                    telemetry = telemetry,
                    accent = accent,
                    onMediaCmd = { cmd -> onMediaCmd(spec.id, cmd) },
                )
                "VOLUME_MIXER" -> VolumeMixerContent(
                    telemetry = telemetry,
                    accent = accent,
                    onVolumeChange = { v -> onVolumeChange(spec.id, v) },
                    onToggleMute = { onToggleMute(spec.id) },
                    onToggleMicMute = { onToggleMicMute(spec.id) },
                )
                "APP_LAUNCHER" -> AppLauncherContent(
                    title = spec.title,
                    apps = telemetry.apps,
                    onLaunch = { app -> onLaunchApp(spec.id, app.id) },
                )
                "MACRO_BUTTON" -> MacroPadContent(
                    title = spec.title,
                    macros = telemetry.macros,
                    onTrigger = { macro -> onTriggerMacro(spec.id, macro.id) },
                )
                "DEV_DOCKER_GIT" -> DevDockerGitContent(
                    telemetry = telemetry,
                    onDockerToggle = { name, running -> onDockerToggle(spec.id, name, running) },
                )
                "AI_QUOTA_PROMPT" -> AiQuotaPromptContent(
                    telemetry = telemetry,
                    onDecision = { decision -> onAiDecision(spec.id, decision) },
                )
                "PC_NOTIFICATIONS" -> NotificationsContent(
                    telemetry = telemetry,
                    onDismiss = { notifId -> onDismissNotification(spec.id, notifId) },
                )
                else -> Text(spec.title, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PomodoroTimerContent(
    title: String,
    pomodoro: PomodoroState,
    accent: Color,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onSelectMode: (PomodoroMode) -> Unit,
    onSkip: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val modeColor = when (pomodoro.mode) {
        PomodoroMode.FOCUS -> accent
        PomodoroMode.SHORT_BREAK -> EmeraldAccent
        PomodoroMode.LONG_BREAK -> ElectricCyan
    }
    val mins = pomodoro.remainingSeconds / 60
    val secs = pomodoro.remainingSeconds % 60
    val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)
    val progress = if (pomodoro.totalSeconds > 0) {
        1f - (pomodoro.remainingSeconds.toFloat() / pomodoro.totalSeconds.toFloat())
    } else 0f

    WidgetHeader(
        title = title,
        icon = Icons.Rounded.Timer,
        accent = modeColor,
        trailing = "${pomodoro.mode.label} • #${pomodoro.completedCycles}",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = formatted,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = if (pomodoro.isRunning) modeColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("pomodoro_time_text"),
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = modeColor,
                trackColor = SlateSurfaceElevated,
            )
        }

        Spacer(Modifier.width(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onReset()
                },
                modifier = Modifier
                    .size(44.dp)
                    .testTag("pomodoro_reset_button"),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateSurfaceElevated,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_pomodoro_reset),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle()
                    }
                    .testTag("pomodoro_toggle_button"),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = modeColor,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (pomodoro.isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.cd_pomodoro_toggle),
                            tint = Color(0xFF04131E),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkip()
                },
                modifier = Modifier
                    .size(44.dp)
                    .testTag("pomodoro_skip_button"),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateSurfaceElevated,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.cd_pomodoro_skip),
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PomodoroMode.entries.forEach { m ->
            val selected = pomodoro.mode == m
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (selected) modeColor.copy(alpha = 0.2f) else SlateSurfaceElevated,
                border = BorderStroke(1.dp, if (selected) modeColor else SlateBorder),
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clickable { onSelectMode(m) }
                    .testTag("pomodoro_mode_${m.name.lowercase()}"),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(
                        text = m.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) modeColor else TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetHeader(
    title: String,
    icon: ImageVector,
    accent: Color,
    trailing: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextSecondary)
        }
        if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.labelSmall, color = accent)
        }
    }
}

@Composable
private fun ClockWeatherContent(telemetry: TelemetrySnapshot, accent: Color) {
    val timeStr = remember(telemetry.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(telemetry.timestamp))
    }
    val dateStr = remember(telemetry.timestamp) {
        SimpleDateFormat("EEE, dd MMM", Locale("vi")).format(Date(telemetry.timestamp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = timeStr,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$dateStr • Uptime ${telemetry.uptimeFormatted}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = SlateSurfaceElevated,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = "PC Agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Text(
                    text = "${telemetry.agentRssMb} MB RAM",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun MetricGaugeContent(
    title: String,
    percent: Float,
    primaryText: String,
    secondaryText: String,
    history: List<Double>,
    accent: Color,
    icon: ImageVector,
) {
    val animatedPct by animateFloatAsState(
        targetValue = percent.coerceIn(0f, 100f),
        animationSpec = spring(),
        label = "metricPct",
    )
    val dynamicAccent = when {
        animatedPct > 85f -> CoralRed
        animatedPct > 70f -> AmberAccent
        else -> accent
    }

    WidgetHeader(title = title, icon = icon, accent = dynamicAccent, trailing = secondaryText)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(50.dp)) {
                val stroke = 5.5.dp.toPx()
                drawArc(
                    color = SlateSurfaceElevated,
                    startAngle = 140f,
                    sweepAngle = 260f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = dynamicAccent,
                    startAngle = 140f,
                    sweepAngle = 260f * (animatedPct / 100f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text(
                text = "${animatedPct.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            )
            SparklineCanvas(
                values = history,
                color = dynamicAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
            )
        }
    }
}

@Composable
private fun SparklineCanvas(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxVal = (values.maxOrNull() ?: 100.0).coerceAtLeast(10.0)
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { idx, v ->
            val x = idx * stepX
            val y = size.height - ((v / maxVal).toFloat().coerceIn(0f, 1f) * size.height)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun NetworkDiskContent(telemetry: TelemetrySnapshot, accent: Color) {
    WidgetHeader(
        title = "Mạng & Ổ đĩa",
        icon = Icons.Rounded.Storage,
        accent = accent,
        trailing = "Disk ${telemetry.diskPercent.roundToInt()}%",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatPill(
            label = "Tải xuống",
            value = formatSpeed(telemetry.netDownKbps),
            color = ElectricCyan,
            modifier = Modifier.weight(1f),
        )
        StatPill(
            label = "Tải lên",
            value = formatSpeed(telemetry.netUpKbps),
            color = SoftViolet,
            modifier = Modifier.weight(1f),
        )
    }
    LinearProgressIndicator(
        progress = { (telemetry.diskPercent / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp)),
        color = accent,
        trackColor = SlateSurfaceElevated,
    )
}

@Composable
private fun StatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = SlateSurfaceElevated,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = color,
            )
        }
    }
}

@Composable
private fun MediaPlayerContent(
    telemetry: TelemetrySnapshot,
    accent: Color,
    onMediaCmd: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    WidgetHeader(title = "Trình phát Media", icon = Icons.Rounded.GraphicEq, accent = accent)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = telemetry.mediaTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = telemetry.mediaArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMediaCmd("prev")
                },
                modifier = Modifier.size(44.dp).testTag("media_prev_button"),
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.cd_media_prev), modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMediaCmd("play_pause")
                    }
                    .testTag("media_play_pause_button"),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = accent,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (telemetry.mediaPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.cd_media_play_pause),
                            tint = Color(0xFF04131E),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMediaCmd("next")
                },
                modifier = Modifier.size(44.dp).testTag("media_next_button"),
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.cd_media_next), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun VolumeMixerContent(
    telemetry: TelemetrySnapshot,
    accent: Color,
    onVolumeChange: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onToggleMicMute: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var sliderVal by remember { mutableFloatStateOf(telemetry.masterVolume.toFloat()) }
    LaunchedEffect(telemetry.masterVolume) {
        sliderVal = telemetry.masterVolume.toFloat()
    }

    WidgetHeader(
        title = "Âm lượng PC",
        icon = if (telemetry.isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
        accent = if (telemetry.isMuted) CoralRed else accent,
        trailing = if (telemetry.isMuted) "Đã tắt tiếng" else "${sliderVal.roundToInt()}%",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleMute()
            },
            modifier = Modifier
                .size(44.dp)
                .testTag("master_mute_button"),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (telemetry.isMuted) CoralRed.copy(alpha = 0.2f) else SlateSurfaceElevated,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (telemetry.isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        contentDescription = stringResource(R.string.cd_mute_master),
                        tint = if (telemetry.isMuted) CoralRed else accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Slider(
            value = sliderVal,
            onValueChange = { next ->
                sliderVal = next
                onVolumeChange(next.roundToInt())
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).testTag("master_volume_slider"),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = SlateSurfaceElevated,
            ),
        )

        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleMicMute()
            },
            modifier = Modifier
                .size(44.dp)
                .testTag("mic_mute_button"),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (telemetry.micMuted) CoralRed.copy(alpha = 0.2f) else SlateSurfaceElevated,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (telemetry.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.cd_mute_mic),
                        tint = if (telemetry.micMuted) CoralRed else EmeraldAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLauncherContent(
    title: String,
    apps: List<AppShortcutItem>,
    onLaunch: (AppShortcutItem) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    WidgetHeader(
        title = title,
        icon = Icons.Rounded.Terminal,
        accent = ElectricCyan,
        trailing = "1 chạm mở trên PC",
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        apps.forEach { app ->
            val appColor = resolveAccent(app.color)
            val icon = when (app.icon) {
                "code" -> Icons.Rounded.Code
                "public" -> Icons.Rounded.Public
                "folder" -> Icons.Rounded.Folder
                "dns" -> Icons.Rounded.Dns
                else -> Icons.Rounded.Terminal
            }
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, appColor.copy(alpha = 0.4f)),
                modifier = Modifier
                    .height(42.dp)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLaunch(app)
                    }
                    .testTag("app_shortcut_${app.id}"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = appColor, modifier = Modifier.size(17.dp))
                    Text(text = app.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacroPadContent(
    title: String,
    macros: List<MacroItem>,
    onTrigger: (MacroItem) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    WidgetHeader(title = title, icon = Icons.Rounded.BoltIconFallback, accent = SoftViolet)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        macros.forEach { macro ->
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, SoftViolet.copy(alpha = 0.35f)),
                modifier = Modifier
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTrigger(macro)
                    }
                    .testTag("macro_button_${macro.id}"),
            ) {
                Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text(text = macro.label, style = MaterialTheme.typography.titleSmall)
                    if (macro.hotkey.isNotBlank()) {
                        Text(
                            text = macro.hotkey,
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftViolet,
                        )
                    }
                }
            }
        }
    }
}

private val Icons.Rounded.BoltIconFallback: ImageVector
    get() = Icons.Rounded.AutoAwesome

@Composable
private fun DevDockerGitContent(
    telemetry: TelemetrySnapshot,
    onDockerToggle: (name: String, running: Boolean) -> Unit,
) {
    WidgetHeader(
        title = "Docker & Git Status",
        icon = Icons.Rounded.Code,
        accent = EmeraldAccent,
        trailing = "${telemetry.gitRepoName} (${telemetry.gitBranch})",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatPill("Thay đổi chưa commit", "${telemetry.gitChangedFiles} files", AmberAccent, Modifier.weight(1f))
        StatPill("PR chờ review", "${telemetry.gitOpenPrs} PRs", SoftViolet, Modifier.weight(1f))
    }

    if (telemetry.dockerContainers.isEmpty()) {
        Text(
            text = "Không phát hiện Docker container đang chạy trên PC.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            telemetry.dockerContainers.forEach { c ->
                val running = c.state.equals("running", ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = c.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = c.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (running) EmeraldAccent else TextMuted,
                            )
                        }
                        IconButton(
                            onClick = { onDockerToggle(c.name, running) },
                            modifier = Modifier.size(44.dp).testTag("docker_toggle_${c.name}"),
                        ) {
                            Icon(
                                imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = if (running) "Dừng container" else "Chạy container",
                                tint = if (running) CoralRed else EmeraldAccent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiQuotaPromptContent(
    telemetry: TelemetrySnapshot,
    onDecision: (String) -> Unit,
) {
    WidgetHeader(
        title = telemetry.aiProvider,
        icon = Icons.Rounded.AutoAwesome,
        accent = SoftViolet,
        trailing = "Quota ${telemetry.aiQuotaRemainingPct}% • ${telemetry.aiBurnRateText}",
    )

    LinearProgressIndicator(
        progress = { (telemetry.aiQuotaRemainingPct / 100f).coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp)),
        color = SoftViolet,
        trackColor = SlateSurfaceElevated,
    )

    val prompt = telemetry.aiPendingPrompt
    AnimatedVisibility(visible = prompt != null) {
        if (prompt != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AmberAccent.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${prompt.tool} xin phép chạy lệnh:",
                        style = MaterialTheme.typography.titleSmall,
                        color = AmberAccent,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ObsidianBg,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = prompt.command,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                        )
                    }
                    Text(
                        text = prompt.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onDecision("deny") },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("ai_deny_button"),
                            border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRed),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Từ chối")
                        }
                        Button(
                            onClick = { onDecision("allow") },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("ai_allow_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldAccent,
                                contentColor = Color(0xFF052E16),
                            ),
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cho phép", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsContent(
    telemetry: TelemetrySnapshot,
    onDismiss: (String) -> Unit,
) {
    WidgetHeader(
        title = "Thông báo từ PC",
        icon = Icons.Rounded.Notifications,
        accent = AmberAccent,
        trailing = "${telemetry.notifications.size} mới",
    )

    if (telemetry.notifications.isEmpty()) {
        Text(
            text = "Không có thông báo nào từ máy tính.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            telemetry.notifications.forEach { n ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${n.app} • ${n.time}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberAccent,
                            )
                            Text(text = n.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = n.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                        }
                        IconButton(
                            onClick = { onDismiss(n.id) },
                            modifier = Modifier.size(44.dp).testTag("dismiss_notif_${n.id}"),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Xóa thông báo", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun formatSpeed(kbps: Double): String {
    return if (kbps >= 1024.0) {
        String.format(Locale.US, "%.1f MB/s", kbps / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB/s", kbps)
    }
}
