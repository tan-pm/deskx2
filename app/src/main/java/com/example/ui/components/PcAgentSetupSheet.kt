package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcAgentSetupSheet(
    onDismiss: () -> Unit,
    onShareAgentScript: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copiedText by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "PC Host Agent siêu nhẹ (~6–10 MB RAM)",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Trên Windows có thể tải file deskdeck-agent.exe từ GitHub Release để chạy ngay không cần cài Python, hoặc dùng bản script Python 3 đa nền tảng bên dưới.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            StepBox(
                step = "Bước 1",
                title = "Chạy deskdeck-agent.exe hoặc gửi deskdeck_agent.py",
                body = "Windows: nhấp đúp chạy deskdeck-agent.exe (tải từ Release). Hoặc nhấn nút chia sẻ bên dưới để gửi file Python sang máy tính.",
            )

            StepBox(
                step = "Bước 2",
                title = "Hoặc chạy bằng Terminal / PowerShell",
                code = "deskdeck-agent.exe   # hoặc: python deskdeck_agent.py",
                onCopy = {
                    copyToClipboard(context, "python3 deskdeck_agent.py")
                    copiedText = "Đã sao chép lệnh khởi chạy"
                },
            )

            StepBox(
                step = "Bước 3",
                title = "Mở PC Dashboard & quét QR bằng Wi-Fi",
                code = "http://localhost:9876",
                body = "Trên Dashboard bạn có thể sắp xếp widget, thêm shortcut mở Terminal/VS Code và hiển thị mã QR để điện thoại quét trong 1 chạm.",
                onCopy = {
                    copyToClipboard(context, "http://localhost:9876")
                    copiedText = "Đã sao chép URL Dashboard"
                },
            )

            StepBox(
                step = "Tùy chọn USB",
                title = "Nếu muốn dùng cáp USB qua ADB",
                code = "adb reverse tcp:9876 tcp:9876",
                onCopy = {
                    copyToClipboard(context, "adb reverse tcp:9876 tcp:9876")
                    copiedText = "Đã sao chép lệnh ADB"
                },
            )

            if (copiedText != null) {
                Text(
                    text = copiedText!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldAccent,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onShareAgentScript,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("share_pc_agent_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF04131E),
                    ),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gửi deskdeck_agent.py", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StepBox(
    step: String,
    title: String,
    body: String? = null,
    code: String? = null,
    onCopy: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SlateSurfaceElevated,
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "$step • $title",
                style = MaterialTheme.typography.titleSmall,
                color = SoftViolet,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            if (code != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            modifier = Modifier.weight(1f),
                        )
                        if (onCopy != null) {
                            OutlinedButton(
                                onClick = onCopy,
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, SlateBorder),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Sao chép",
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Text("Copy", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("DeskDeck", text))
}
