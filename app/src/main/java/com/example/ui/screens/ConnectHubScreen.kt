package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ConnectionUiState
import com.example.model.DiscoveredPc
import com.example.model.PcHostProfile
import com.example.model.TransportMode
import com.example.ui.DeckUiState
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

@Composable
fun ConnectHubScreen(
    uiState: DeckUiState,
    onScanQrClick: () -> Unit,
    onRefreshWifiClick: () -> Unit,
    onConnectDiscovered: (DiscoveredPc) -> Unit,
    onConnectSaved: (PcHostProfile) -> Unit,
    onRemoveSaved: (String) -> Unit,
    onConnectUsbClick: (String) -> Unit,
    onConnectManualWifi: (host: String, port: String, token: String, name: String) -> Unit,
    onOpenPcSetupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showManualDialog by remember { mutableStateOf(false) }
    var showUsbTokenDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0E1420), ObsidianBg),
                )
            ),
    ) {
        val isWideLandscape = maxWidth >= 660.dp

        if (isWideLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Left column: Brand + Connection status + Hero 1-Tap Connect
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 10.dp),
                ) {
                    item { ConnectHeader() }
                    item { ConnectionStatusBanners(uiState.connectionState) }
                    item {
                        HeroConnectCard(
                            uiState = uiState,
                            onScanQrClick = onScanQrClick,
                            onConnectUsbClick = {
                                if (uiState.savedHosts.isNotEmpty()) {
                                    onConnectUsbClick(uiState.savedHosts.first().token)
                                } else {
                                    showUsbTokenDialog = true
                                }
                            },
                            onOpenManualDialog = { showManualDialog = true },
                        )
                    }
                }

                // Right column: Discovered Wi-Fi PCs + Saved Hosts + PC Agent Setup
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 10.dp),
                ) {
                    item {
                        WifiDiscoveryHeader(onRefreshWifiClick = onRefreshWifiClick)
                    }
                    if (uiState.discoveredPcs.isEmpty()) {
                        item { EmptyWifiCard() }
                    } else {
                        items(uiState.discoveredPcs, key = { "${it.host}:${it.port}" }) { pc ->
                            DiscoveredPcCard(pc = pc, onClick = { onConnectDiscovered(pc) })
                        }
                    }

                    item {
                        Text(
                            text = stringResource(R.string.section_saved_hosts),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (uiState.savedHosts.isEmpty()) {
                        item { EmptySavedHostsCard() }
                    } else {
                        items(uiState.savedHosts, key = { it.id }) { profile ->
                            SavedHostCard(
                                profile = profile,
                                onConnect = { onConnectSaved(profile) },
                                onRemove = { onRemoveSaved(profile.id) },
                            )
                        }
                    }

                    item { PcAgentSetupCard(onOpenPcSetupClick = onOpenPcSetupClick) }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ConnectHeader() }
                item { ConnectionStatusBanners(uiState.connectionState) }
                item {
                    HeroConnectCard(
                        uiState = uiState,
                        onScanQrClick = onScanQrClick,
                        onConnectUsbClick = {
                            if (uiState.savedHosts.isNotEmpty()) {
                                onConnectUsbClick(uiState.savedHosts.first().token)
                            } else {
                                showUsbTokenDialog = true
                            }
                        },
                        onOpenManualDialog = { showManualDialog = true },
                    )
                }
                item { WifiDiscoveryHeader(onRefreshWifiClick = onRefreshWifiClick) }
                if (uiState.discoveredPcs.isEmpty()) {
                    item { EmptyWifiCard() }
                } else {
                    items(uiState.discoveredPcs, key = { "${it.host}:${it.port}" }) { pc ->
                        DiscoveredPcCard(pc = pc, onClick = { onConnectDiscovered(pc) })
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.section_saved_hosts),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (uiState.savedHosts.isEmpty()) {
                    item { EmptySavedHostsCard() }
                } else {
                    items(uiState.savedHosts, key = { it.id }) { profile ->
                        SavedHostCard(
                            profile = profile,
                            onConnect = { onConnectSaved(profile) },
                            onRemove = { onRemoveSaved(profile.id) },
                        )
                    }
                }
                item { PcAgentSetupCard(onOpenPcSetupClick = onOpenPcSetupClick) }
            }
        }
    }
    if (showManualDialog) {
        ManualConnectDialog(
            onDismiss = { showManualDialog = false },
            onConnect = { host, port, token, name ->
                showManualDialog = false
                onConnectManualWifi(host, port, token, name)
            },
        )
    }

    if (showUsbTokenDialog) {
        UsbTokenDialog(
            onDismiss = { showUsbTokenDialog = false },
            onConnect = { token ->
                showUsbTokenDialog = false
                onConnectUsbClick(token)
            },
        )
    }
}

@Composable
private fun ConnectHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElectricCyan.copy(alpha = 0.14f))
                    .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Wireless & USB Stream Deck",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = SlateSurfaceElevated,
            border = BorderStroke(1.dp, SlateBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(EmeraldAccent)
                )
                Text(
                    text = "Wi-Fi Ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldAccent,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusBanners(conn: ConnectionUiState) {
    AnimatedVisibility(visible = conn is ConnectionUiState.Connecting) {
        if (conn is ConnectionUiState.Connecting) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ElectricCyan.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = ElectricCyan,
                    )
                    Text(
                        text = "Đang kết nối tới ${conn.hostName} (${conn.transportMode.name})...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricCyan,
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = conn is ConnectionUiState.Disconnected && conn.errorMessage != null) {
        val err = (conn as? ConnectionUiState.Disconnected)?.errorMessage.orEmpty()
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CoralRed.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = err,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = CoralRed,
            )
        }
    }
}

@Composable
private fun HeroConnectCard(
    uiState: DeckUiState,
    onScanQrClick: () -> Unit,
    onConnectUsbClick: () -> Unit,
    onOpenManualDialog: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            ElectricCyan.copy(alpha = 0.12f),
                            SoftViolet.copy(alpha = 0.08f),
                            Color.Transparent,
                        )
                    )
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.connect_hero_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.connect_hero_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Button(
                onClick = onScanQrClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("scan_qr_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = Color(0xFF04131E),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_scan_qr),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onConnectUsbClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("usb_connect_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SoftViolet.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftViolet),
                ) {
                    Icon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_usb_connect),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = onOpenManualDialog,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("manual_connect_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SlateBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Icon(Icons.Rounded.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_manual_connect),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiDiscoveryHeader(onRefreshWifiClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.section_wifi_discovered),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(
            onClick = onRefreshWifiClick,
            modifier = Modifier.testTag("refresh_wifi_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Quét lại Wi-Fi",
                tint = TextSecondary,
            )
        }
    }
}

@Composable
private fun EmptyWifiCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SlateSurface,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.empty_wifi_discovery),
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

@Composable
private fun EmptySavedHostsCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SlateSurface,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.empty_saved_hosts),
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Composable
private fun PcAgentSetupCard(onOpenPcSetupClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPcSetupClick)
            .testTag("pc_setup_button"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AmberAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_pc_agent_setup),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Agent Python siêu nhẹ (~20MB RAM) kèm Web Dashboard chỉnh widget & QR trên PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun DiscoveredPcCard(
    pc: DiscoveredPc,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("discovered_pc_card"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ElectricCyan.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Computer, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = pc.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${pc.host}:${pc.port} • ${pc.osName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ElectricCyan,
            ) {
                Text(
                    text = "Kết nối",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF04131E),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SavedHostCard(
    profile: PcHostProfile,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent = if (profile.lastTransportMode == TransportMode.USB) SoftViolet else ElectricCyan
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
            .testTag("saved_host_card"),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (profile.lastTransportMode == TransportMode.USB) Icons.Rounded.Usb else Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = accent,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${profile.host}:${profile.port} • Chạm để nối lại",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Xóa máy tính đã lưu",
                    tint = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ManualConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: String, token: String, name: String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9876") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        title = { Text("Kết nối Wi-Fi thủ công", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Địa chỉ IP máy tính (vd: 192.168.1.50)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("manual_host_input"),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Cổng (mặc định 9876)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token trên PC Dashboard") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("manual_token_input"),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên gợi nhớ (tùy chọn)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(host, port, token, name) },
                modifier = Modifier.testTag("manual_submit_button"),
            ) {
                Text("Kết nối")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        },
    )
}

@Composable
private fun UsbTokenDialog(
    onDismiss: () -> Unit,
    onConnect: (token: String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        title = { Text("Kết nối USB ADB", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Cắm cáp USB và nhấn 'Kích hoạt USB (ADB Reverse)' trên PC Dashboard, sau đó nhập Token hiển thị trên PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token xác thực") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(token) }) { Text("Kết nối USB") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        },
    )
}
