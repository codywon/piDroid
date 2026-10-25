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
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TermiusSurface,
        titleContentColor = TermiusTextPrimary,
        textContentColor = TermiusTextSecondary,
        shape = RoundedCornerShape(14.dp),
        title = { Text("PC 连接配置", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("主机地址", color = TermiusTextSecondary) },
                    placeholder = { Text("https://xxx 或 http://192.168.x.x:4100", color = TermiusTextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermiusBlue,
                        unfocusedBorderColor = TermiusBorder,
                        focusedTextColor = TermiusTextPrimary,
                        unfocusedTextColor = TermiusTextPrimary,
                        focusedContainerColor = TermiusBlack,
                        unfocusedContainerColor = TermiusBlack
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("安全 Token", color = TermiusTextSecondary) },
                    placeholder = { Text("pi_xxxx 密钥", color = TermiusTextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermiusBlue,
                        unfocusedBorderColor = TermiusBorder,
                        focusedTextColor = TermiusTextPrimary,
                        unfocusedTextColor = TermiusTextPrimary,
                        focusedContainerColor = TermiusBlack,
                        unfocusedContainerColor = TermiusBlack
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("主机备注", color = TermiusTextSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermiusBlue,
                        unfocusedBorderColor = TermiusBorder,
                        focusedTextColor = TermiusTextPrimary,
                        unfocusedTextColor = TermiusTextPrimary,
                        focusedContainerColor = TermiusBlack,
                        unfocusedContainerColor = TermiusBlack
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url.trim(), token.trim(), name.trim()) },
                enabled = url.isNotBlank() && token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = TermiusBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("保存连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TermiusTextSecondary)
            }
        }
    )
}
