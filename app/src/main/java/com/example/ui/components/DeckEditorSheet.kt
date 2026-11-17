package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DeckLayoutConfig
import com.example.model.DeckPage
import com.example.model.DeckWidgetCatalog
import com.example.model.DeckWidgetSpec
import com.example.ui.theme.CoralRed
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeckEditorSheet(
    currentLayout: DeckLayoutConfig,
    initialPageIndex: Int,
    onDismiss: () -> Unit,
    onSaveLayout: (DeckLayoutConfig) -> Unit,
    onApplyPreset: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pages by remember(currentLayout) { mutableStateOf(currentLayout.pages) }
    var selectedPageIdx by remember(currentLayout) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, (currentLayout.pages.size - 1).coerceAtLeast(0)))
    }
    var showAddWidgetPicker by remember { mutableStateOf(false) }

    val safeIdx = selectedPageIdx.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    val activePage = pages.getOrNull(safeIdx)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 880.dp,
        containerColor = SlateSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header + Save button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tùy chỉnh Màn hình & Widget",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Chỉnh từng màn hình, kích thước cột (1–4) và màu cho Stream Deck ngang",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        onSaveLayout(currentLayout.copy(pages = pages))
                        onDismiss()
                    },
                    modifier = Modifier.height(48.dp).testTag("editor_save_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF04131E),
                    ),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Lưu & Đồng bộ", fontWeight = FontWeight.Bold)
                }
            }

            // Quick Presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mẫu nhanh:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                listOf(
                    "stream_deck" to "Stream Deck Chuẩn",
                    "focus" to "Focus Pomodoro",
                    "dev" to "Developer Cockpit",
                    "minimal" to "Tối giản",
                ).forEach { (key, label) ->
                    OutlinedButton(
                        onClick = {
                            onApplyPreset(key)
                            onDismiss()
                        },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, SlateBorder),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Screen / Page Tabs + Add Page
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.forEachIndexed { idx, p ->
                    val selected = idx == safeIdx
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) ElectricCyan else SlateSurfaceElevated,
                        border = BorderStroke(1.dp, if (selected) ElectricCyan else SlateBorder),
                        modifier = Modifier
                            .height(42.dp)
                            .clickable { selectedPageIdx = idx }
                            .testTag("editor_page_tab_$idx"),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                            Text(
                                text = "${idx + 1}. ${p.title}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color(0xFF04131E) else TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val newPage = DeckPage(
                            id = "page_${System.currentTimeMillis()}",
                            title = "Màn hình ${pages.size + 1}",
                            widgets = emptyList(),
                        )
                        pages = pages + newPage
                        selectedPageIdx = pages.lastIndex
                    },
                    modifier = Modifier.height(42.dp).testTag("editor_add_page_button"),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricCyan)
                    Spacer(Modifier.width(4.dp))
                    Text("Thêm màn hình", color = ElectricCyan, style = MaterialTheme.typography.labelMedium)
                }
            }

            // Active Page Settings
            if (activePage != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SlateSurfaceElevated,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = activePage.title,
                                onValueChange = { nextTitle ->
                                    pages = pages.mapIndexed { idx, p ->
                                        if (idx == safeIdx) p.copy(title = nextTitle) else p
                                    }
                                },
                                label = { Text("Tên màn hình ${safeIdx + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("editor_page_title_input"),
                            )
                            IconButton(
                                onClick = {
                                    if (safeIdx > 0) {
                                        val mutable = pages.toMutableList()
                                        val item = mutable.removeAt(safeIdx)
                                        mutable.add(safeIdx - 1, item)
                                        pages = mutable
                                        selectedPageIdx = safeIdx - 1
                                    }
                                },
                                enabled = safeIdx > 0,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = "Chuyển màn hình sang trái")
                            }
                            IconButton(
                                onClick = {
                                    if (safeIdx < pages.lastIndex) {
                                        val mutable = pages.toMutableList()
                                        val item = mutable.removeAt(safeIdx)
                                        mutable.add(safeIdx + 1, item)
                                        pages = mutable
                                        selectedPageIdx = safeIdx + 1
                                    }
                                },
                                enabled = safeIdx < pages.lastIndex,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Rounded.ArrowForward, contentDescription = "Chuyển màn hình sang phải")
                            }
                            IconButton(
                                onClick = {
                                    if (pages.size > 1) {
                                        pages = pages.filterIndexed { idx, _ -> idx != safeIdx }
                                        selectedPageIdx = (safeIdx - 1).coerceAtLeast(0)
                                    }
                                },
                                enabled = pages.size > 1,
                                modifier = Modifier.size(48.dp).testTag("editor_delete_page_button"),
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Xóa màn hình",
                                    tint = if (pages.size > 1) CoralRed else TextMuted,
                                )
                            }
                        }

                        // Widgets inside this screen
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Các Widget trên màn hình này (${activePage.widgets.size})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Button(
                                onClick = { showAddWidgetPicker = !showAddWidgetPicker },
                                modifier = Modifier.height(40.dp).testTag("editor_toggle_add_widget"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan.copy(alpha = 0.16f),
                                    contentColor = ElectricCyan,
                                ),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Thêm Widget")
                            }
                        }

                        if (showAddWidgetPicker) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = ObsidianBg,
                                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "Chọn Widget để thêm vào \"${activePage.title}\":",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = ElectricCyan,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        DeckWidgetCatalog.items.forEach { item ->
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = SlateSurfaceElevated,
                                                border = BorderStroke(1.dp, SlateBorder),
                                                modifier = Modifier
                                                    .clickable {
                                                        val newWidget = DeckWidgetSpec(
                                                            id = "w_${item.type.lowercase()}_${System.currentTimeMillis() % 100000}",
                                                            type = item.type,
                                                            title = item.defaultTitle,
                                                            colSpan = item.defaultColSpan,
                                                            accentColorKey = item.defaultAccent,
                                                        )
                                                        pages = pages.mapIndexed { idx, p ->
                                                            if (idx == safeIdx) p.copy(widgets = p.widgets + newWidget) else p
                                                        }
                                                        showAddWidgetPicker = false
                                                    }
                                                    .testTag("add_widget_${item.type}"),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricCyan)
                                                    Text(item.defaultTitle, style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (activePage.widgets.isEmpty()) {
                            Text(
                                text = "Màn hình này chưa có widget nào. Nhấn \"+ Thêm Widget\" để tạo bố cục riêng.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                            )
                        } else {
                            activePage.widgets.forEachIndexed { wIdx, widget ->
                                WidgetEditorRow(
                                    widget = widget,
                                    canMoveUp = wIdx > 0,
                                    canMoveDown = wIdx < activePage.widgets.lastIndex,
                                    onUpdate = { updated ->
                                        pages = pages.mapIndexed { pIdx, p ->
                                            if (pIdx == safeIdx) {
                                                p.copy(widgets = p.widgets.mapIndexed { idx, cur ->
                                                    if (idx == wIdx) updated else cur
                                                })
                                            } else p
                                        }
                                    },
                                    onMoveUp = {
                                        if (wIdx > 0) {
                                            pages = pages.mapIndexed { pIdx, p ->
                                                if (pIdx == safeIdx) {
                                                    val m = p.widgets.toMutableList()
                                                    val it = m.removeAt(wIdx)
                                                    m.add(wIdx - 1, it)
                                                    p.copy(widgets = m)
                                                } else p
                                            }
                                        }
                                    },
                                    onMoveDown = {
                                        if (wIdx < activePage.widgets.lastIndex) {
                                            pages = pages.mapIndexed { pIdx, p ->
                                                if (pIdx == safeIdx) {
                                                    val m = p.widgets.toMutableList()
                                                    val it = m.removeAt(wIdx)
                                                    m.add(wIdx + 1, it)
                                                    p.copy(widgets = m)
                                                } else p
                                            }
                                        }
                                    },
                                    onDelete = {
                                        pages = pages.mapIndexed { pIdx, p ->
                                            if (pIdx == safeIdx) {
                                                p.copy(widgets = p.widgets.filterIndexed { idx, _ -> idx != wIdx })
                                            } else p
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun WidgetEditorRow(
    widget: DeckWidgetSpec,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (DeckWidgetSpec) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianBg,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = widget.title,
                    onValueChange = { onUpdate(widget.copy(title = it)) },
                    label = { Text(widget.type) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Đẩy widget lên")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Đẩy widget xuống")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Xóa widget", tint = CoralRed)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Column Span selector (1..4)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Độ rộng:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    (1..4).forEach { span ->
                        val active = widget.colSpan == span
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) ElectricCyan else SlateSurfaceElevated,
                            border = BorderStroke(1.dp, if (active) ElectricCyan else SlateBorder),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable { onUpdate(widget.copy(colSpan = span)) },
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(
                                    text = "${span}x",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) Color(0xFF04131E) else TextSecondary,
                                )
                            }
                        }
                    }
                }

                // Accent color picker
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("cyan", "violet", "emerald", "amber", "red").forEach { colorKey ->
                        val c = resolveAccent(colorKey)
                        val selected = widget.accentColorKey.equals(colorKey, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) Color.White else SlateBorder,
                                    shape = CircleShape,
                                )
                                .clickable { onUpdate(widget.copy(accentColorKey = colorKey)) },
                        )
                    }
                }
            }
        }
    }
}
