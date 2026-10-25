package com.pi.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.ui.theme.*

@Composable
fun ServerConfigDialog(
    initialUrl: String,
    initialToken: String,
    initialName: String,
    onConfirm: (url: String, token: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceContainerLow,
        titleContentColor = theme.textPrimary,
        textContentColor = theme.textSecondary,
        shape = RoundedCornerShape(14.dp),
        title = { Text("PC 连接配置", fontWeight = FontWeight.Bold, color = theme.textPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("主机地址", color = theme.textSecondary) },
                    placeholder = { Text("https://xxx 或 http://192.168.x.x:4100", color = theme.textMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.outline,
                        focusedTextColor = theme.textPrimary,
                        unfocusedTextColor = theme.textPrimary,
                        focusedContainerColor = theme.surfaceContainer,
                        unfocusedContainerColor = theme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("安全 Token", color = theme.textSecondary) },
                    placeholder = { Text("pi_xxxx 密钥", color = theme.textMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.outline,
                        focusedTextColor = theme.textPrimary,
                        unfocusedTextColor = theme.textPrimary,
                        focusedContainerColor = theme.surfaceContainer,
                        unfocusedContainerColor = theme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("主机备注", color = theme.textSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = theme.primary,
                        unfocusedBorderColor = theme.outline,
                        focusedTextColor = theme.textPrimary,
                        unfocusedTextColor = theme.textPrimary,
                        focusedContainerColor = theme.surfaceContainer,
                        unfocusedContainerColor = theme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url.trim(), token.trim(), name.trim()) },
                enabled = url.isNotBlank() && token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("保存连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = theme.textSecondary)
            }
        }
    )
}
