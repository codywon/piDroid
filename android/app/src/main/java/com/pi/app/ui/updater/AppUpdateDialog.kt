package com.pi.app.ui.updater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pi.app.ui.theme.LocalAppTheme
import com.pi.app.updater.AppUpdateManager
import com.pi.app.updater.UpdateInfo
import java.io.File
import java.util.Locale

/**
 * 更新 UI 状态密封类
 */
sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class HasUpdate(val info: UpdateInfo) : UpdateUiState()
    data class Downloading(
        val info: UpdateInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedText: String,
        val channelName: String
    ) : UpdateUiState()
    data class ReadyToInstall(val apkFile: File, val info: UpdateInfo) : UpdateUiState()
    data class PermissionRequired(val apkFile: File, val info: UpdateInfo) : UpdateUiState()
    data class Error(val message: String, val info: UpdateInfo?) : UpdateUiState()
}

/**
 * 生产级现代化全自动在线更新对话框 (参考 Termius 架构，无紫色体系)
 */
@Composable
fun AppUpdateDialog(
    state: UpdateUiState,
    onStartDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onIgnore: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (state is UpdateUiState.Idle || state is UpdateUiState.Checking) {
        return
    }

    val context = LocalContext.current
    val currentVersion = remember { AppUpdateManager.getCurrentVersionName(context) }
    val theme = LocalAppTheme.current

    Dialog(
        onDismissRequest = {
            if (state !is UpdateUiState.Downloading) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = state !is UpdateUiState.Downloading,
            dismissOnClickOutside = state !is UpdateUiState.Downloading,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .border(BorderStroke(1.dp, theme.outline.copy(alpha = 0.4f)), RoundedCornerShape(20.dp)),
            color = theme.surfaceContainerLow,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header: 图标与标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(theme.primary.copy(alpha = 0.15f))
                            .border(1.dp, theme.primary.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (state) {
                                is UpdateUiState.Downloading -> Icons.Default.Download
                                is UpdateUiState.ReadyToInstall -> Icons.Default.SystemUpdate
                                is UpdateUiState.PermissionRequired -> Icons.Default.Security
                                is UpdateUiState.Error -> Icons.Default.ErrorOutline
                                else -> Icons.Default.RocketLaunch
                            },
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (state) {
                                is UpdateUiState.Downloading -> "正在高速下载更新"
                                is UpdateUiState.ReadyToInstall -> "新版本已就绪"
                                is UpdateUiState.PermissionRequired -> "需要安装应用授权"
                                is UpdateUiState.Error -> "更新遇到问题"
                                else -> "发现新版本"
                            },
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            fontSize = 17.sp
                        )

                        val updateInfo = when (state) {
                            is UpdateUiState.HasUpdate -> state.info
                            is UpdateUiState.Downloading -> state.info
                            is UpdateUiState.ReadyToInstall -> state.info
                            is UpdateUiState.PermissionRequired -> state.info
                            is UpdateUiState.Error -> state.info
                            else -> null
                        }

                        if (updateInfo != null) {
                            Text(
                                text = "v$currentVersion ➔ v${updateInfo.versionName}",
                                fontSize = 12.sp,
                                color = theme.primary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Body 内容区
                when (state) {
                    is UpdateUiState.HasUpdate -> {
                        // 版本日志卡片
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "发布日期: ${state.info.publishedAt.ifBlank { "最近" }}",
                                        fontSize = 11.sp,
                                        color = theme.textMuted
                                    )
                                    if (state.info.fileSize > 0) {
                                        Text(
                                            text = String.format(Locale.US, "%.1f MB", state.info.fileSize / (1024.0 * 1024.0)),
                                            fontSize = 11.sp,
                                            color = theme.textMuted,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = theme.outline.copy(alpha = 0.25f), thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = state.info.releaseNotes,
                                    fontSize = 13.sp,
                                    color = theme.textPrimary,
                                    lineHeight = 20.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // 操作按钮组
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!state.info.isManual) {
                                OutlinedButton(
                                    onClick = { onIgnore(state.info.tagName) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textSecondary),
                                    border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.4f))
                                ) {
                                    Text("跳过此版", fontSize = 13.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textSecondary),
                                    border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.4f))
                                ) {
                                    Text("稍后提醒", fontSize = 13.sp)
                                }
                            }

                            Button(
                                onClick = { onStartDownload(state.info) },
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = theme.primary,
                                    contentColor = if (theme.isDark) Color.Black else Color.White
                                )
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("立即升级", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    is UpdateUiState.Downloading -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(theme.primary, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = state.channelName,
                                            fontSize = 11.sp,
                                            color = theme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Text(
                                        text = state.speedText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = theme.primary,
                                    trackColor = theme.surfaceContainerHigh
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val downloadedMb = state.downloadedBytes / (1024.0 * 1024.0)
                                    val totalMb = state.totalBytes / (1024.0 * 1024.0)
                                    Text(
                                        text = String.format(Locale.US, "%.1f MB / %.1f MB", downloadedMb, totalMb),
                                        fontSize = 11.sp,
                                        color = theme.textMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.0f%%", state.progress * 100),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "正在通过多路国内镜像智能选路下载，完成后将自动呼起系统无缝覆盖安装。",
                            fontSize = 11.sp,
                            color = theme.textMuted,
                            lineHeight = 16.sp
                        )
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2EA043),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "升级包已安全下载完毕",
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "已配置统一签名 Key，点击下方按钮即可一键覆盖升级，无需卸载原有应用。",
                                    fontSize = 12.sp,
                                    color = theme.textMuted,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { onInstall(state.apkFile) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.primary,
                                contentColor = if (theme.isDark) Color.Black else Color.White
                            )
                        ) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("立即覆盖安装", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    is UpdateUiState.PermissionRequired -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "需要开启【安装未知应用】权限",
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Android 系统安全规范要求：首次升级需要为您开启本应用的安装权限，开启后将自动继续完成升级。",
                                    fontSize = 12.sp,
                                    color = theme.textMuted,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textSecondary),
                                border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.4f))
                            ) {
                                Text("取消", fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    val intent = AppUpdateManager.createInstallPermissionIntent(context)
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = theme.primary,
                                    contentColor = if (theme.isDark) Color.Black else Color.White
                                )
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("前往授权", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }

                    is UpdateUiState.Error -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, Color(0xFFCF222E).copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFCF222E),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "下载中断或校验失败",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFCF222E),
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = state.message,
                                    fontSize = 12.sp,
                                    color = theme.textSecondary,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.textSecondary),
                                border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.4f))
                            ) {
                                Text("关闭", fontSize = 13.sp)
                            }

                            if (state.info != null) {
                                Button(
                                    onClick = { onStartDownload(state.info) },
                                    modifier = Modifier.weight(1.3f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = theme.primary,
                                        contentColor = if (theme.isDark) Color.Black else Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("重试下载", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}
