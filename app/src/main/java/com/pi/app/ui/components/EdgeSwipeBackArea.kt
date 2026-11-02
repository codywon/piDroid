package com.pi.app.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
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
 * 覆盖屏幕左侧狭窄纵向热区，避开顶栏高度（topPadding），避免遮挡左上角导航返回按钮的点击！
 * 当手指从左边缘起手向右滑动超过 [thresholdDp] 时，触发 [onSwipeBack]。
 */
@Composable
fun EdgeSwipeBackArea(
    onSwipeBack: () -> Unit,
    edgeWidth: Dp = 28.dp,
    thresholdDp: Dp = 48.dp,
    topPadding: Dp = 64.dp,
    bottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    var totalDragX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .padding(top = topPadding, bottom = bottomPadding)
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

