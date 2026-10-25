package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.data.model.ModelInfo
import com.pi.app.data.model.ThinkingLevel
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelAndThinkingSheet(
    availableModels: List<ModelInfo>,
    selectedModelId: String,
    currentThinkingLevel: ThinkingLevel,
    onModelSelected: (ModelInfo) -> Unit,
    onThinkingLevelChanged: (ThinkingLevel) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TermiusSurface,
        contentColor = TermiusTextPrimary,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp).width(36.dp).height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = TermiusBorder
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InfoTitle(
                title = "模型与思考推理",
                infoDescription = "为 Agent 指定大模型底座与内部推理 Token 预算。",
                style = MaterialTheme.typography.titleMedium
            )

            // 1. 思考深度选择
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTitle(
                    title = "思考级别",
                    infoDescription = "Off: 立即输出；Low: 基础校验；Medium: 架构分析 (推荐)；High: 复杂代码推演。",
                    style = MaterialTheme.typography.labelMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ThinkingLevel.values().forEach { level ->
                        val isSelected = currentThinkingLevel == level
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF1F3556) else TermiusSurfaceVariant,
                            border = BorderStroke(1.dp, if (isSelected) TermiusBlue else TermiusBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onThinkingLevelChanged(level) }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = level.label.take(4),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) TermiusBlue else TermiusTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = TermiusBorder)

            // 2. 模型列表
            Text("智能体模型", style = MaterialTheme.typography.labelMedium, color = TermiusTextSecondary)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableModels, key = { it.id }) { model ->
                    val isSelected = model.id == selectedModelId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onModelSelected(model)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) Color(0xFF1B2A3D) else TermiusSurfaceVariant,
                        border = BorderStroke(1.dp, if (isSelected) TermiusBlue else TermiusBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) TermiusBlue else TermiusTextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = TermiusSurface,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = model.provider,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            color = TermiusTextMuted
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${model.contextWindow / 1000}k 容量" +
                                            if (model.supportsThinking) " · 深度推理" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = TermiusTextSecondary
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = TermiusBlue
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
