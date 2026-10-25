package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
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
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    val providers = remember(availableModels) {
        availableModels.map { it.provider }.distinct()
    }
    val filteredModels = remember(availableModels, selectedProvider) {
        if (selectedProvider == null) availableModels else availableModels.filter { it.provider == selectedProvider }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TermiusSurface,
        contentColor = TermiusTextPrimary,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = TermiusBorder
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoTitle(
                title = "模型与思考级别切换",
                infoDescription = "在此无缝切换正在服务的前端模型与推理思考档位，即时作用于当前会话。",
                style = MaterialTheme.typography.titleMedium
            )

            // 1. 思考深度选择 (7 档深度横向滑动)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "思考深度 (Thinking Level)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TermiusTextSecondary
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
                            color = if (isSelected) Color(0xFF1B3D5A) else TermiusSurfaceVariant,
                            border = BorderStroke(1.dp, if (isSelected) TermiusBlue else TermiusBorder),
                            modifier = Modifier.clickable { onThinkingLevelChanged(level) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = level.value,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSelected) TermiusBlue else TermiusTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = TermiusBorder)

            // 2. Provider 分组选择
            if (providers.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedProvider == null) TermiusBlue.copy(alpha = 0.2f) else TermiusSurfaceVariant,
                        border = BorderStroke(1.dp, if (selectedProvider == null) TermiusBlue else TermiusBorder),
                        modifier = Modifier.clickable { selectedProvider = null }
                    ) {
                        Text(
                            text = "全部 (${availableModels.size})",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = if (selectedProvider == null) TermiusBlue else TermiusTextSecondary
                        )
                    }

                    providers.forEach { p ->
                        val isSel = selectedProvider == p
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSel) TermiusBlue.copy(alpha = 0.2f) else TermiusSurfaceVariant,
                            border = BorderStroke(1.dp, if (isSel) TermiusBlue else TermiusBorder),
                            modifier = Modifier.clickable { selectedProvider = p }
                        ) {
                            Text(
                                text = p,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (isSel) TermiusBlue else TermiusTextSecondary
                            )
                        }
                    }
                }
            }

            // 3. 模型列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredModels, key = { "${it.provider}:${it.id}" }) { model ->
                    val isSelected = model.id == selectedModelId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onModelSelected(model)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF162335) else TermiusSurfaceVariant,
                        border = BorderStroke(1.dp, if (isSelected) TermiusBlue else TermiusBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) TermiusBlue else TermiusTextSecondary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
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
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "ID: ${model.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TermiusTextSecondary
                                    )
                                    if (model.hasVision) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(11.dp), tint = TermiusGreen)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("Vision", style = MaterialTheme.typography.labelSmall, color = TermiusGreen)
                                        }
                                    }
                                    if (model.supportsThinking) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(11.dp), tint = TermiusBlue)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("Thinking", style = MaterialTheme.typography.labelSmall, color = TermiusBlue)
                                        }
                                    }
                                }
                            }

                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = "已选择", tint = TermiusBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
