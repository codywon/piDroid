package com.pi.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.WorktreeItem
import com.pi.app.data.model.WorktreesResult
import com.pi.app.ui.theme.LocalAppTheme

/**
 * Git Worktrees 管理抽屉/对话框（方案 A：1:1 对标 pi-web 侧边栏工作树看板）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorktreeManagementDialog(
    worktreesResult: WorktreesResult,
    isOperating: Boolean = false,
    onSwitchWorktree: (WorktreeItem) -> Unit,
    onCreateWorktree: (branch: String) -> Unit,
    onDeleteWorktree: (WorktreeItem, force: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current
    var newBranchName by remember { mutableStateOf("") }
    var dirtyWarningTarget by remember { mutableStateOf<WorktreeItem?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Git Worktrees 检出管理",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                    Text(
                        text = "仓库: ${worktreesResult.projectRoot}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = theme.surfaceContainer,
                    border = BorderStroke(0.5.dp, theme.primary.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "${worktreesResult.worktrees.size} 个检出",
                        fontSize = 11.sp,
                        color = theme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // Worktree 列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(worktreesResult.worktrees, key = { it.path }) { item ->
                    WorktreeCardItem(
                        item = item,
                        isOperating = isOperating,
                        onSwitch = { onSwitchWorktree(item) },
                        onDelete = { onDeleteWorktree(item, false) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            // 新建 Worktree 输入区
            Text(
                text = "新建 Worktree 分支检出：",
                style = MaterialTheme.typography.labelSmall,
                color = theme.textMuted
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    placeholder = { Text("分支名称 (如 feat/xxx)", fontSize = 12.sp) },
                    singleLine = true,
                    enabled = !isOperating,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val b = newBranchName.trim()
                        if (b.isNotBlank()) {
                            onCreateWorktree(b)
                            newBranchName = ""
                        }
                    },
                    enabled = newBranchName.isNotBlank() && !isOperating,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("创建并检出", fontSize = 12.sp)
                }
            }
        }
    }

    // 存在未提交更改时的二次确认弹窗
    dirtyWarningTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { dirtyWarningTarget = null },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = theme.warning) },
            title = { Text("强制移除 Worktree", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    text = "该检出路径存在未提交的更改或子模块。强制移除将永久丢弃这些未提交更改（保留 Git 分支）。确定强制移除吗？",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        dirtyWarningTarget = null
                        onDeleteWorktree(target, true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("强制移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { dirtyWarningTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WorktreeCardItem(
    item: WorktreeItem,
    isOperating: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = LocalAppTheme.current
    val isCur = item.isCurrent

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isCur) theme.surfaceContainer else theme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (isCur) theme.primary.copy(alpha = 0.6f) else theme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isCur) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(theme.success)
                        )
                    }
                    Text(
                        text = item.branch ?: "(detached)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                        color = theme.textPrimary
                    )
                    if (item.isMain) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = theme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "主目录",
                                fontSize = 9.5.sp,
                                color = theme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (isCur) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = theme.success.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "当前检出",
                                fontSize = 9.5.sp,
                                color = theme.success,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.path,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isCur) {
                    FilledTonalButton(
                        onClick = onSwitch,
                        enabled = !isOperating,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("切换", fontSize = 11.sp)
                    }
                }

                if (!item.isMain) {
                    IconButton(
                        onClick = onDelete,
                        enabled = !isOperating,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "移除该检出",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
