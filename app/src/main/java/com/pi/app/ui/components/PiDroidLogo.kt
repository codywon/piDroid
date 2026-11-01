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
import com.pi.app.ui.theme.ArgbContrast
import com.pi.app.ui.theme.ThemePalette

/**
 * piDroid 极简扁平化品牌图标组件：
 * 纯色圆角底块 + 居中单轮廓几何 π 符号
 *
 * 底块与 π 的颜色是 **theme-invariant**（不随 5 套主题变色）：logo 是品牌标识，
 * 若跟随主题，AMBER 主题下会出现亮黄底块。前景也不能用 `theme.primary` 推导——
 * 那会得到近黑的 π 画在深色底块上（3 套深色主题下约 1.3:1，等于隐形）。
 * 这里统一由实际底块颜色计算：bestOn(#1E293B) = 白，14.6:1。
 */
@Composable
fun PiDroidLogo(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val blockColor = Color(ThemePalette.BrandLogoBackground)
    val glyphColor = Color(ArgbContrast.bestOn(ThemePalette.BrandLogoBackground))
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w / 108f

        // 1. 扁平化纯色圆角底块
        drawRoundRect(
            color = blockColor,
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
        drawPath(path = piPath, color = glyphColor)
    }
}
