package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ConnectionUiState
import com.example.model.PomodoroMode
import com.example.model.TransportMode
import com.example.ui.DeckUiState
import com.example.ui.components.DeckWidgetCard
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
import kotlinx.coroutines.launch

@Composable
fun DeskDeckScreen(
    uiState: DeckUiState,
    connectedInfo: ConnectionUiState.Connected,
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
    onToggleKeepScreenOn: () -> Unit,
    onOpenDashboardEditor: (currentPageIndex: Int) -> Unit,
    onOpenPcSetup: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDisconnect)

    val pages = uiState.layout.pages
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    val scope = rememberCoroutineScope()

    val modeColor = if (connectedInfo.transportMode == TransportMode.USB) SoftViolet else ElectricCyan
    val modeLabel = if (connectedInfo.transportMode == TransportMode.USB) {
        stringResource(R.string.status_connected_usb)
    } else {
        stringResource(R.string.status_connected_wifi)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Ultra-slim Stream Deck Landscape Top Bar (48dp height)
            Surface(
                color = SlateSurface,
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Single-line compact host status pill
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = SlateSurfaceElevated,
                        border = BorderStroke(1.dp, SlateBorder),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldAccent),
                            )
                            Icon(
                                imageVector = if (connectedInfo.transportMode == TransportMode.USB) Icons.Rounded.Usb else Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = modeColor,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = connectedInfo.profile.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "$modeLabel • ${connectedInfo.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = modeColor,
                                maxLines = 1,
                            )
                        }
                    }

                    // Inline swipeable Stream Deck page tabs (30dp visual pill inside 48dp touch height)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pages.forEachIndexed { idx, page ->
                            val selected = pagerState.currentPage == idx
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable {
                                        scope.launch { pagerState.animateScrollToPage(idx) }
                                    }
                                    .testTag("deck_page_tab_$idx"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (selected) ElectricCyan else SlateSurfaceElevated,
                                    border = BorderStroke(1.dp, if (selected) ElectricCyan else SlateBorder),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 11.dp),
                                    ) {
                                        Text(
                                            text = page.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) Color(0xFF04131E) else TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Slim quick action icons (48dp touch height, 18dp crisp icons)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onOpenDashboardEditor(pagerState.currentPage) },
                            modifier = Modifier.size(44.dp, 48.dp).testTag("edit_dashboard_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.cd_edit_dashboard),
                                tint = ElectricCyan,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onToggleKeepScreenOn,
                            modifier = Modifier.size(44.dp, 48.dp).testTag("keep_screen_on_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LightMode,
                                contentDescription = stringResource(R.string.cd_keep_screen_on),
                                tint = if (uiState.keepScreenOn) AmberAccent else TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onOpenPcSetup,
                            modifier = Modifier.size(44.dp, 48.dp).testTag("deck_pc_setup_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = stringResource(R.string.cd_pc_setup),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onDisconnect,
                            modifier = Modifier.size(44.dp, 48.dp).testTag("disconnect_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = stringResource(R.string.cd_disconnect),
                                tint = CoralRed,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Landscape Stream Deck Swipeable Horizontal Pager
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val columns = if (maxWidth >= 640.dp) 4 else 2

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIdx ->
                    val page = pages.getOrNull(pageIdx)
                    if (page == null || page.widgets.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = SlateSurface,
                                border = BorderStroke(1.dp, SlateBorder),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = "Màn hình \"${page?.title ?: "Mới"}\" chưa có widget",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextSecondary,
                                    )
                                    Button(
                                        onClick = { onOpenDashboardEditor(pageIdx) },
                                        modifier = Modifier.height(48.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ElectricCyan,
                                            contentColor = Color(0xFF04131E),
                                        ),
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Thêm Widget vào màn hình này", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 10.dp,
                                end = 10.dp,
                                top = 8.dp,
                                bottom = 28.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = page.widgets,
                                key = { "${page.id}_${it.id}" },
                                span = { spec ->
                                    val span = spec.colSpan.coerceIn(1, columns)
                                    GridItemSpan(span)
                                },
                            ) { spec ->
                                DeckWidgetCard(
                                    spec = spec,
                                    telemetry = uiState.telemetry,
                                    pomodoro = uiState.pomodoro,
                                    onTogglePomodoro = onTogglePomodoro,
                                    onResetPomodoro = onResetPomodoro,
                                    onSelectPomodoroMode = onSelectPomodoroMode,
                                    onSkipPomodoro = onSkipPomodoro,
                                    onLaunchApp = onLaunchApp,
                                    onTriggerMacro = onTriggerMacro,
                                    onVolumeChange = onVolumeChange,
                                    onToggleMute = onToggleMute,
                                    onToggleMicMute = onToggleMicMute,
                                    onMediaCmd = onMediaCmd,
                                    onDockerToggle = onDockerToggle,
                                    onAiDecision = onAiDecision,
                                    onDismissNotification = onDismissNotification,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stream Deck Page Indicator Dots at bottom
        if (pages.size > 1) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = SlateSurface.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, SlateBorder),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pages.forEachIndexed { idx, _ ->
                        val active = pagerState.currentPage == idx
                        Box(
                            modifier = Modifier
                                .height(5.dp)
                                .width(if (active) 18.dp else 5.dp)
                                .clip(CircleShape)
                                .background(if (active) ElectricCyan else TextMuted.copy(alpha = 0.5f))
                                .clickable { scope.launch { pagerState.animateScrollToPage(idx) } },
                        )
                    }
                }
            }
        }

        // Floating Action Feedback Pill
        AnimatedVisibility(
            visible = uiState.lastFeedbackMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp, start = 20.dp, end = 20.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = SlateSurfaceElevated,
                border = BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.6f)),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(EmeraldAccent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = uiState.lastFeedbackMessage.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
