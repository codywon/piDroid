package com.pi.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 极简专业标题：文本紧凑简练，点击末尾感叹号弹出补充说明
 */
@Composable
fun InfoTitle(
    title: String,
    infoDescription: String? = null,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    var showDialog by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = title,
            style = style,
            fontWeight = fontWeight,
            color = theme.textPrimary
        )
        if (!infoDescription.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = "详细说明",
                tint = theme.textMuted,
                modifier = Modifier
                    .size(15.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showDialog = true
                    }
            )
        }
    }

    if (showDialog && !infoDescription.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = theme.surfaceContainerLow,
            titleContentColor = theme.textPrimary,
            textContentColor = theme.textSecondary,
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary
                )
            },
            text = {
                Text(
                    text = infoDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("我知道了", color = theme.primary)
                }
            }
        )
    }
}
