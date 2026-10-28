package com.pi.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.SkillInfo
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 技能详情（预览 SKILL.md / 在线改源码 / 开关「在模型提示词中可见」）。
 *
 * 从原来的 AlertDialog 换成接近全屏的 ModalBottomSheet，原因是那个弹窗在手机上：
 * 1) 宽度被 AlertDialog 的默认 maxWidth 卡死（约 320~360dp），Markdown 正文几乎一行一句；
 * 2) 标题行塞了「名字 + 全局徽标 + 效果预览 + 源码编辑」四件东西 SpaceBetween，
 *    结果分段按钮被挤成逐字换行，徽标与分段控件看起来像同一组（语义完全不同）；
 * 3) 正文区固定 heightIn(max = 340.dp)，大半屏幕空着浪费。
 *
 * 现在：高度撑到 94%，标题行只放「图标 + 名字 + 作用域徽标 + 关闭」，
 * 视图切换单独一行做成真正的两段分段控件，正文吃满剩余高度，底部固定操作区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailSheet(
    skill: SkillInfo,
    markdownContent: String?,
    isLoading: Boolean,
    isEditing: Boolean,
    editBuffer: String,
    onEditBufferChange: (String) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    onToggleVisible: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isProject = skill.isProjectScope
    val isVisible = skill.isVisibleToModel
    val accent = if (isVisible) Color(0xFF2EA043) else theme.textMuted

    val displayPath = remember(skill) {
        skill.filePath?.takeIf { it.isNotBlank() }
            ?: skill.baseDir?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/', '\\')}/SKILL.md" }
            ?: if (isProject) "./.pi/skills/${skill.name}/SKILL.md" else "~/.pi/agent/skills/${skill.name}/SKILL.md"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.surfaceContainerLow,
        contentColor = theme.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = CircleShape,
                color = theme.outline
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = 16.dp)
        ) {
            // ── 标题行：图标 + 名字 + 作用域徽标 + 关闭 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.displayTitle,
                        color = theme.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScopeBadge(isProject = isProject)
                        Text(
                            text = if (isVisible) "对模型可见" else "对模型隐藏",
                            fontSize = 10.5.sp,
                            color = if (isVisible) Color(0xFF3FB950) else theme.textMuted
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = theme.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 视图切换：真正的两段分段控件（与作用域徽标彻底分开）──
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = theme.surfaceContainer,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(3.dp)) {
                    SegmentItem(
                        text = "效果预览",
                        selected = !isEditing,
                        modifier = Modifier.weight(1f),
                        onClick = { onEditingChange(false) }
                    )
                    SegmentItem(
                        text = "源码编辑",
                        selected = isEditing,
                        modifier = Modifier.weight(1f),
                        onClick = { onEditingChange(true) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 可见性开关（一句话说清后果）──
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (isVisible) Color(0xFF2EA043).copy(alpha = 0.08f) else theme.surfaceContainer,
                border = BorderStroke(1.dp, if (isVisible) Color(0xFF2EA043).copy(alpha = 0.35f) else theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isVisible) "在模型提示词中可见" else "对模型隐藏",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isVisible) Color(0xFF3FB950) else theme.textPrimary
                        )
                        Text(
                            text = if (isVisible) "模型可在系统提示词中发现并主动调用"
                            else "仍可在输入框手动 /${skill.name} 调用",
                            fontSize = 10.5.sp,
                            color = if (isVisible) theme.textSecondary else theme.textMuted
                        )
                    }
                    Switch(
                        checked = isVisible,
                        onCheckedChange = onToggleVisible,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF2EA043),
                            checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f),
                            uncheckedThumbColor = theme.textMuted,
                            uncheckedTrackColor = theme.surfaceContainerHigh
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 路径 + 版本（路径两行显示、点一下复制，不再一段截断成 "nod…"）──
            PathAndVersionRow(
                displayPath = displayPath,
                version = skill.versionShort,
                link = skill.skillsShUrl,
                onCopy = {
                    clipboard.setText(AnnotatedString(displayPath))
                    android.widget.Toast.makeText(context, "已复制技能路径", android.widget.Toast.LENGTH_SHORT).show()
                },
                onOpenLink = { url ->
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            )
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 正文：吃满剩余高度 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    isLoading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 14.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = theme.primary
                        )
                        Text(
                            text = "正在从网关加载 SKILL.md 源码...",
                            fontSize = 12.sp,
                            color = theme.textSecondary
                        )
                    }

                    isEditing -> OutlinedTextField(
                        value = editBuffer,
                        onValueChange = onEditBufferChange,
                        label = { Text("SKILL.md 正文与指令源码", fontSize = 11.sp, color = theme.textSecondary) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = theme.textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = theme.primary,
                            unfocusedBorderColor = theme.outline,
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary
                        )
                    )

                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownRenderer(
                            content = markdownContent?.ifBlank { editBuffer }
                                ?: skill.description
                                ?: "暂无描述"
                        )
                    }
                }
            }

            // ── 底部操作区（固定，不随正文滚走）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isEditing) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = theme.textMuted)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { onSave(editBuffer) },
                        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("保存修改", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                } else {
                    Text(
                        text = "长按正文可选中复制",
                        fontSize = 10.5.sp,
                        color = theme.textMuted
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.surfaceContainerHigh),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("关闭", color = theme.textPrimary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeBadge(isProject: Boolean) {
    val color = if (isProject) Color(0xFF8250DF) else Color(0xFF1F6FEB)
    val textColor = if (isProject) Color(0xFFD2A8FF) else Color(0xFF58A6FF)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.18f),
        border = BorderStroke(0.5.dp, color)
    ) {
        Text(
            text = if (isProject) "项目" else "全局",
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun SegmentItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) theme.primary.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) theme.primary else Color.Transparent),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) theme.primary else theme.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        )
    }
}

@Composable
private fun PathAndVersionRow(
    displayPath: String,
    version: String?,
    link: String?,
    onCopy: () -> Unit,
    onOpenLink: (String) -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceContainer,
        border = BorderStroke(0.5.dp, theme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "路径",
                    fontSize = 10.sp,
                    color = theme.textMuted,
                    modifier = Modifier.width(30.dp)
                )
                Text(
                    text = displayPath,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制路径",
                    tint = theme.textMuted,
                    modifier = Modifier
                        .size(13.dp)
                        .clickable(onClick = onCopy)
                )
            }
            if (version != null || link != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "版本",
                        fontSize = 10.sp,
                        color = theme.textMuted,
                        modifier = Modifier.width(30.dp)
                    )
                    Text(
                        text = version ?: "本地",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                    if (link != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onOpenLink(link) }
                        ) {
                            Text(
                                text = "skills.sh",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF58A6FF)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color(0xFF58A6FF),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
