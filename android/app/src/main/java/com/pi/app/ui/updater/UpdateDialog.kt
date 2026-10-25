package com.pi.app.ui.updater

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.ui.theme.*
import com.pi.app.updater.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    isDownloaded: Boolean,
    onStartDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = TermiusSurface,
        titleContentColor = TermiusTextPrimary,
        textContentColor = TermiusTextSecondary,
        shape = RoundedCornerShape(14.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = TermiusBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("发现新版本 ${updateInfo.latestVersion}", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (updateInfo.apkSize > 0) {
                    Text(
                        text = "安装包: ${String.format("%.1f", updateInfo.apkSize / (1024f * 1024f))} MB",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TermiusTextMuted
                    )
                }

                Surface(
                    color = TermiusBlack,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, TermiusBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = updateInfo.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = TermiusTextSecondary
                        )
                    }
                }

                if (isDownloading) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("下载中...", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary)
                            Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = TermiusBlue, fontFamily = FontFamily.Monospace)
                        }
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = TermiusBlue,
                            trackColor = TermiusSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isDownloaded) {
                        onInstall()
                    } else {
                        onStartDownload()
                    }
                },
                enabled = !isDownloading,
                colors = ButtonDefaults.buttonColors(containerColor = TermiusBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    when {
                        isDownloaded -> "立即覆盖安装"
                        isDownloading -> "下载中..."
                        else -> "立即更新"
                    }
                )
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("稍后", color = TermiusTextSecondary)
                }
            }
        }
    )
}
