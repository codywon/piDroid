package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.BranchTreeNode
import com.pi.app.data.model.BranchTreeRow
import com.pi.app.data.model.BranchTreeTopology
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 会话多叉分支拓扑树选择器（方案 A：1:1 对标 pi-web vn/vt 组件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchTreeBottomSheet(
    treeNodes: List<BranchTreeNode>,
    activeLeafId: String?,
    isAgentBusy: Boolean,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current
    val rows = remember(treeNodes, activeLeafId) {
        BranchTreeTopology.flattenTree(treeNodes, activeLeafId)
    }

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AltRoute,
                        contentDescription = null,
                        tint = theme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "会话分支拓扑 (${rows.size} 个节点)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary
                    )
                }
                Text(
                    text = if (isAgentBusy) "任务执行中锁定" else "点击节点快速切换",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAgentBusy) theme.warning else theme.textMuted
                )
            }

            HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前会话暂无分支拓扑",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(rows, key = { it.node.id }) { row ->
                        BranchTreeRowItem(
                            row = row,
                            isAgentBusy = isAgentBusy,
                            onClick = {
                                if (!row.isCurrentLeaf && !isAgentBusy) {
                                    onSelectEntry(row.node.id)
                                    onDismiss()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchTreeRowItem(
    row: BranchTreeRow,
    isAgentBusy: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    val node = row.node
    val isActive = row.isActivePath
    val isLeaf = row.isCurrentLeaf

    val bg = when {
        isLeaf -> theme.primary.copy(alpha = if (theme.isDark) 0.16f else 0.08f)
        isActive -> theme.surfaceContainer.copy(alpha = 0.5f)
        else -> theme.surfaceContainerLow
    }

    val border = if (isLeaf) {
        BorderStroke(1.dp, theme.primary.copy(alpha = 0.5f))
    } else null

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLeaf && !isAgentBusy, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 前置缩进与连线字符
            if (row.depth > 0) {
                val indentPrefix = buildString {
                    for (hasLine in row.parentLines) {
                        append(if (hasLine) "  │ " else "    ")
                    }
                    append(if (row.isLastChild) "  └─ " else "  ├─ ")
                }
                Text(
                    text = indentPrefix,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (isActive) theme.primary else theme.textMuted.copy(alpha = 0.6f)
                )
            }

            // 状态圆点
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isLeaf -> theme.success
                            isActive -> theme.primary
                            else -> theme.textMuted.copy(alpha = 0.5f)
                        }
                    )
            )

            Spacer(modifier = Modifier.width(6.dp))

            // 角色徽标 [U] / [A]
            val role = node.role
            if (role != null) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = if (role == "user") theme.primary.copy(alpha = 0.18f) else theme.surfaceContainer,
                    border = BorderStroke(0.5.dp, if (role == "user") theme.primary.copy(alpha = 0.4f) else theme.outline)
                ) {
                    Text(
                        text = if (role == "user") "U" else "A",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (role == "user") theme.primary else theme.textMuted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // 单链压缩折叠标记
            if (node.compressedCount > 0) {
                Text(
                    text = "+${node.compressedCount} 轮",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            // 摘要预览文本
            val preview = node.previewText.ifBlank { "会话节点 #${node.id.take(6)}" }
            Text(
                text = preview,
                fontSize = 11.5.sp,
                fontWeight = if (isLeaf) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isLeaf -> theme.textPrimary
                    isActive -> theme.textSecondary
                    else -> theme.textMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isLeaf) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = theme.success.copy(alpha = 0.18f),
                    border = BorderStroke(0.5.dp, theme.success.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "当前活跃",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.success,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
