package com.pi.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * piDroid 极简扁平化品牌图标组件：
 * 纯色圆角底块 + 居中单轮廓几何 π 符号
 */
@Composable
fun PiDroidLogo(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w / 108f

        // 1. 扁平化纯色圆角底块
        drawRoundRect(
            color = Color(0xFF1E293B),
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = CornerRadius(24f * s, 24f * s)
        )

        // 2. 居中单轮廓几何 π 符号
        val piPath = Path().apply {
            // 顶部横梁
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 26f * s,
                    top = 28f * s,
                    right = 82f * s,
                    bottom = 39f * s,
                    cornerRadius = CornerRadius(4f * s, 4f * s)
                )
            )
            // 左侧竖柱
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 36f * s,
                    top = 34f * s,
                    right = 47f * s,
                    bottom = 80f * s,
                    cornerRadius = CornerRadius(4f * s, 4f * s)
                )
            )
            // 右侧竖柱
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 61f * s,
                    top = 34f * s,
                    right = 72f * s,
                    bottom = 80f * s,
                    cornerRadius = CornerRadius(4f * s, 4f * s)
                )
            )
        }
        drawPath(path = piPath, color = Color.White)
    }
}
