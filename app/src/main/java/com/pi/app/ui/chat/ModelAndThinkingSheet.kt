package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ModelInfo
import com.pi.app.data.model.ThinkingLevel
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

/**
 * 1:1 对标 pi-web 官方交互的模型与思考级别切换面板：
 * - 顶部增加“筛选模型...”即时搜索栏
 * - 右侧五角星星标系统：实心金星 ★ (全局默认模型) / 空心灰星 ☆ (点击一键设为默认模型，调用 PUT /api/models/default)
 *   （实心星本身即默认标识，不再额外显示「默认」文字徽标，保持列表清爽）
 * - 左侧勾选 ✓：当前会话正在使用的模型
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelAndThinkingSheet(
    availableModels: List<ModelInfo>,
    selectedModelId: String,
    currentThinkingLevel: ThinkingLevel,
    defaultModelId: String? = null,
    defaultProvider: String? = null,
    onModelSelected: (ModelInfo) -> Unit,
    onThinkingLevelChanged: (ThinkingLevel) -> Unit,
    onSetDefaultModel: ((ModelInfo) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val providers = remember(availableModels) {
        availableModels.map { it.provider }.distinct()
    }

    val filteredModels = remember(availableModels, selectedProvider, searchQuery) {
        var list = if (selectedProvider == null) availableModels else availableModels.filter { it.provider == selectedProvider }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            list = list.filter {
                it.displayName.lowercase().contains(q) ||
                it.id.lowercase().contains(q) ||
                it.provider.lowercase().contains(q)
            }
        }
        list
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceContainerLow,
        contentColor = theme.textPrimary,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = theme.outline
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoTitle(
                title = "模型与思考级别切换",
                infoDescription = "仅作用于当前会话；全局默认在设置页",
                style = MaterialTheme.typography.titleMedium
            )

            // 1. 对标 pi-web 的“筛选模型...”即时搜索栏
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = theme.surfaceContainer,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.size(16.dp),
                        tint = theme.textMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = theme.textPrimary,
                            fontSize = 12.5.sp
                        ),
                        cursorBrush = SolidColor(theme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "筛选模型...",
                                    color = theme.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除",
                                modifier = Modifier.size(14.dp),
                                tint = theme.textSecondary
                            )
                        }
                    }
                }
            }

            // 2. 思考深度选择 (7 档横向平滑滑动)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "思考深度 (Thinking Level)",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.textSecondary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ThinkingLevel.entries.forEach { level ->
                        val isSelected = currentThinkingLevel == level
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) theme.primary.copy(alpha = 0.14f) else theme.background,
                            border = BorderStroke(1.dp, if (isSelected) theme.primary else theme.outline),
                            modifier = Modifier.clickable { onThinkingLevelChanged(level) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = level.value,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSelected) theme.primary else theme.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // 3. Provider 分组选择
            if (providers.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedProvider == null) theme.primary.copy(alpha = 0.14f) else theme.background,
                        border = BorderStroke(1.dp, if (selectedProvider == null) theme.primary else theme.outline),
                        modifier = Modifier.clickable { selectedProvider = null }
                    ) {
                        Text(
                            text = "全部 (${availableModels.size})",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = if (selectedProvider == null) theme.primary else theme.textSecondary
                        )
                    }

                    providers.forEach { p ->
                        val isSel = selectedProvider == p
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSel) theme.primary.copy(alpha = 0.14f) else theme.background,
                            border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                            modifier = Modifier.clickable { selectedProvider = p }
                        ) {
                            Text(
                                text = p,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (isSel) theme.primary else theme.textSecondary
                            )
                        }
                    }
                }
            }

            // 4. 模型列表 (对标 pi-web：左侧勾选当前会话 + 右侧五角星默认模型标记)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredModels, key = { "${it.provider}:${it.id}" }) { model ->
                    val isSelected = model.id == selectedModelId
                    val isDefault = (model.id == defaultModelId) && (defaultProvider.isNullOrBlank() || defaultProvider == model.provider)

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) theme.primary.copy(alpha = 0.12f) else theme.background,
                        border = BorderStroke(1.dp, if (isSelected) theme.primary else theme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onModelSelected(model)
                                    onDismiss()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧：当前会话选中勾选 ✓ (对标 pi-web)
                            Box(
                                modifier = Modifier.width(22.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "当前选中",
                                        tint = theme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // 中间：模型名称、Provider 徽标与能力提示
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSelected) theme.primary else theme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = theme.surfaceContainerLow,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = model.provider,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            color = theme.textMuted
                                        )
                                    }

                                    if (model.supportsThinking) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = "支持思考推理",
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSelected) theme.primary else Color(0xFF8B5CF6)
                                        )
                                    }
                                    if (model.supportsVision) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Icon(
                                            imageVector = Icons.Default.Image,
                                            contentDescription = "支持多模态视觉",
                                            modifier = Modifier.size(12.dp),
                                            tint = if (isSelected) theme.primary else theme.textMuted
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = model.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        color = theme.textSecondary
                                    )
                                    Text(
                                        text = "·",
                                        fontSize = 10.sp,
                                        color = theme.outline
                                    )
                                    Text(
                                        text = "${model.contextWindow / 1000}k ctx",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.5.sp,
                                        color = theme.textMuted
                                    )
                                }
                            }

                            // 右侧：星标收藏/设为全局默认模型按钮 (实心金星 ★ / 柔和空心星 ☆)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onSetDefaultModel?.invoke(model) }
                                    .padding(start = 6.dp)
                            ) {
                                if (isDefault) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "当前默认模型",
                                        tint = Color(0xFFF59E0B),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "点击设为全局默认模型",
                                        tint = theme.textMuted.copy(alpha = 0.22f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
