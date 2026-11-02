package com.pi.app.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕左侧边缘右滑手势触发区（Edge Swipe Back）。
 *
 * 仅覆盖屏幕最左侧（默认 32dp）的狭窄纵向热区，因此屏幕中间的任何水平滚动
 * （如源码等宽代码、PDF 缩放画布、Markdown 表格等）完全不受干扰。
 * 当手指从左边缘起手向右滑动超过 [thresholdDp] 时，触发 [onSwipeBack]。
 */
@Composable
fun EdgeSwipeBackArea(
    onSwipeBack: () -> Unit,
    edgeWidth: Dp = 32.dp,
    thresholdDp: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    var totalDragX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(edgeWidth)
            .pointerInput(onSwipeBack) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onDragEnd = {
                        val thresholdPx = thresholdDp.toPx()
                        if (totalDragX >= thresholdPx) {
                            onSwipeBack()
                        }
                        totalDragX = 0f
                    },
                    onDragCancel = { totalDragX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount
                    }
                )
            }
    )
}
