package com.pi.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pi.app.R
import com.pi.app.ui.theme.ArgbContrast
import com.pi.app.ui.theme.ThemePalette

/**
 * πDroid 品牌标识：纯色圆角底块 + 居中衬线字母 π（横梁两端下折、竖腿带衬脚）。
 *
 * 字形来源是 `res/drawable/ic_pi_glyph.xml`（108 视口，字形 58.3×53.1，占画布 54%）。
 * 桌面图标不能用这一份：adaptive icon 只显示中心 72 区域、等于放大 1.5 倍，
 * 所以 launcher 走单独的 `ic_launcher_foreground.xml`（字形 36×32.8）。
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
    Box(
        modifier = modifier
            .size(size)
            // 圆角比例与 drawable 内 108 视口的 24/108 保持一致
            .clip(RoundedCornerShape(size * 0.222f))
            .background(blockColor),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_pi_glyph),
            contentDescription = null,
            colorFilter = ColorFilter.tint(glyphColor),
            modifier = Modifier.fillMaxSize()
        )
    }
}
