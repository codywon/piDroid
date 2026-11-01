package com.pi.app.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.FileEntry
import com.pi.app.data.model.GitFileChange
import com.pi.app.ui.theme.LocalAppTheme
import java.util.Locale

/**
 * 目录树行项组件。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    entry: FileEntry,
    depth: Int,
    fullPath: String,
    isExpanded: Boolean,
    isLoading: Boolean,
    gitChange: GitFileChange?,
    containsGitChanges: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onOpenExternal: () -> Unit,
    onCopyPath: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    var menuExpanded by remember { mutableStateOf(false) }

    val isHidden = entry.hidden != null
    val isOutsideLink = entry.isOutsideLink

    val rowAlpha = if (isHidden || isOutsideLink) 0.55f else 1.0f
    val indentDp = (depth * 16).dp

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .combinedClickable(
                    onClick = {
                        if (!isOutsideLink) onClick()
                    },
                    onLongClick = {
                        menuExpanded = true
                    }
                )
                .padding(start = 12.dp + indentDp, end = 12.dp)
                .alpha(rowAlpha)
        ) {
            // 目录折角箭头
            if (entry.isDir) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 1.5.dp,
                            color = theme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (isExpanded) {
                                Icons.Default.KeyboardArrowDown
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = if (isExpanded) "折叠" else "展开",
                            tint = theme.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(20.dp))
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 图标
            val icon = resolveFileIcon(entry)
            val iconTint = resolveIconTint(entry, isExpanded, theme.primary, theme.textMuted)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 名称
            Text(
                text = entry.name,
                color = theme.textPrimary,
                fontSize = 13.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 软链标签或 Git 状态
            if (isOutsideLink) {
                Text(
                    text = "指向根外",
                    color = theme.textMuted,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else if (gitChange != null) {
                GitBadge(status = gitChange.status)
            } else if (containsGitChanges) {
                // 目录包含子变更：显示黄色小圆点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color(0xFFD6A84B), CircleShape)
                )
            }
        }

        // 长按菜单
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.background(theme.surfaceContainer)
        ) {
            if (!entry.isDir) {
                DropdownMenuItem(
                    text = { Text("查看文件", color = theme.textPrimary, fontSize = 13.sp) },
                    onClick = {
                        menuExpanded = false
                        onPreview()
                    }
                )
                DropdownMenuItem(
                    text = { Text("保存到手机", color = theme.textPrimary, fontSize = 13.sp) },
                    onClick = {
                        menuExpanded = false
                        onDownload()
                    }
                )
                DropdownMenuItem(
                    text = { Text("用其他应用打开", color = theme.textPrimary, fontSize = 13.sp) },
                    onClick = {
                        menuExpanded = false
                        onOpenExternal()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("复制绝对路径", color = theme.textPrimary, fontSize = 13.sp) },
                onClick = {
                    menuExpanded = false
                    onCopyPath()
                }
            )
        }
    }
}

@Composable
internal fun EmptyDirectoryRow(
    depth: Int,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val indentDp = (depth * 16).dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(start = 36.dp + indentDp, end = 12.dp)
    ) {
        Text(
            text = "empty",
            color = theme.textMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
internal fun ErrorDirectoryRow(
    depth: Int,
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val indentDp = (depth * 16).dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(start = 36.dp + indentDp, end = 12.dp)
    ) {
        Text(
            text = "加载失败: $errorMessage",
            color = Color(0xFFF87171),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "重试",
            color = Color(0xFF60A5FA),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
internal fun TruncatedNoticeRow(
    depth: Int,
    modifier: Modifier = Modifier
) {
    val indentDp = (depth * 16).dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp + indentDp, end = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            text = "已达单级显示上限（最多展示前 500 项）",
            color = Color(0xFFF59E0B),
            fontSize = 11.5.sp
        )
    }
}

@Composable
private fun GitBadge(status: String) {
    val (label, color) = when (status.lowercase(Locale.ROOT)) {
        "modified" -> "M" to Color(0xFFD6A84B)
        "added" -> "A" to Color(0xFF4ADE80)
        "deleted" -> "D" to Color(0xFFF87171)
        "renamed" -> "R" to Color(0xFF60A5FA)
        "untracked" -> "U" to Color(0xFF4ADE80)
        "conflict" -> "C" to Color(0xFFF87171)
        else -> status.take(1).uppercase(Locale.ROOT) to Color(0xFFD6A84B)
    }

    Text(
        text = label,
        color = color,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

private fun resolveFileIcon(entry: FileEntry): ImageVector {
    if (entry.outsideLinkTarget != null) return Icons.Default.Link
    if (entry.isDir) return Icons.Default.Folder

    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> Icons.Default.Image
        "pdf", "docx", "doc" -> Icons.Default.Description
        "mp3", "wav", "m4a", "flac", "ogg" -> Icons.Default.Audiotrack
        "mp4", "mov", "webm", "mkv" -> Icons.Default.Videocam
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun resolveIconTint(
    entry: FileEntry,
    isExpanded: Boolean,
    primaryColor: Color,
    mutedColor: Color
): Color {
    if (entry.isDir) {
        return if (isExpanded) primaryColor else primaryColor.copy(alpha = 0.85f)
    }
    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "kt", "java", "ts", "tsx", "js", "jsx", "py", "rs", "go" -> Color(0xFF60A5FA)
        "json", "yaml", "yml", "toml", "xml" -> Color(0xFFFBBF24)
        "md", "markdown" -> Color(0xFF34D399)
        "png", "jpg", "jpeg", "svg" -> Color(0xFFA78BFA)
        "pdf", "docx" -> Color(0xFFF87171)
        else -> mutedColor
    }
}
