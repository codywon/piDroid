package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ToolDefinition
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 工具定义面板（对标 pi-web 的 `get_tools` 视图）。
 *
 * 只读：把本会话当前装了什么工具、哪些是启用的、各自的参数摊开给用户看。
 * 权限胶囊只说明「哪一档预设」，这里回答的是「这一档到底装进了什么」。
 *
 * 说明性文字一律不额外占行（见 docs/DESIGN-labels.md）：状态用徽章，参数用一段元数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDefinitionsSheet(
    load: suspend () -> Result<List<ToolDefinition>>,
    onDismissRequest: () -> Unit
) {
    val theme = LocalAppTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tools by remember { mutableStateOf<List<ToolDefinition>?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(reloadToken) {
        isLoading = true
        error = null
        load()
            .onSuccess { tools = it }
            .onFailure {
                error = it.message ?: "读取工具定义失败"
                tools = null
            }
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = theme.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(theme.outline.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = theme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tools?.let { "工具定义 · ${it.size}" } ?: "工具定义",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (error != null) {
                        IconButton(onClick = { reloadToken++ }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重试",
                                modifier = Modifier.size(17.dp),
                                tint = theme.primary
                            )
                        }
                    }
                    IconButton(onClick = onDismissRequest, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                            tint = theme.textSecondary
                        )
                    }
                }
            }

            when {
                isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = theme.primary
                    )
                }

                error != null -> Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                tools.isNullOrEmpty() -> Text(
                    text = "本会话没有启用的工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textMuted,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tools.orEmpty(), key = { it.name }) { tool ->
                        ToolDefinitionRow(tool)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolDefinitionRow(tool: ToolDefinition) {
    val theme = LocalAppTheme.current
    val stateLabel = when {
        !tool.active -> "未启用"
        tool.declarationHidden -> "脚本内"
        else -> "已启用"
    }
    val stateColor = when {
        !tool.active -> theme.textMuted
        tool.declarationHidden -> theme.warning
        else -> theme.success
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.surfaceContainer,
        border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tool.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stateLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = stateColor
                )
            }
            if (tool.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = theme.textSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(tool.parameterSummary)
                    val required = tool.requiredParameters
                    if (required.isNotEmpty()) {
                        append(" · 必填 ")
                        append(required.joinToString(", ") { it.name })
                    }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = theme.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
