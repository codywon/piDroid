package com.pi.app.ui.components

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕左侧边缘右滑手势返回（Edge Swipe Back）。
 *
 * **为什么是 Modifier 而不是叠加的 Box**：
 * 早期实现是一个 `Box(width = 28.dp, fillMaxHeight)` 盖在页面之上。Compose 的命中测试
 * 只会把指针事件交给**最上层命中的兄弟节点**，所以这样一个透明热区会把下面所有点击
 * 全部吃掉 —— 用户看到的现象就是「左上角返回箭头点一下没反应，但左滑能用」。
 * 改成挂在根容器上的 Modifier 后，事件从父节点流过、子节点照常收到点击，
 * 只有真正从左边缘起手、横向位移超过阈值的手势才会被拦截（[PointerEventPass.Initial]）。
 *
 * 判定规则：
 * - 起手点必须在左边缘 [edgeWidth] 以内，否则完全不介入；
 * - 横向位移先超过 [edgeWidth] 才算「边缘右滑」（排除列表轻微抖动）；
 * - 位移达到 [threshold] 时触发一次 [onSwipeBack]，随后吞掉本次手势剩余事件，
 *   避免一次滑动连续触发；纯点击（无位移）不会消费任何事件。
 *
 * **Android 13+ 直接停用**：清单里开启了 `enableOnBackInvokedCallback`，系统已经在
 * 同一块左边缘接管了返回手势（带预测动效）。继续用 `PointerEventPass.Initial` 抢它，
 * 手感上是「滑到一半卡住」或者一次手势触发两次返回。三处调用方
 * （FilesScreen / ArtifactViewerFullScreen / GitChangesFullScreen）都注册了 `BackHandler`，
 * 系统回退走的就是同一个处理函数，所以这里停用不丢任何功能，只是不再和系统抢手势。
 *
 * SDK 判断放在 Modifier 工厂里而不是组合里：它是运行期常量，不需要状态跟踪。
 */
fun Modifier.edgeSwipeBack(
    onSwipeBack: () -> Unit,
    edgeWidth: Dp = 28.dp,
    threshold: Dp = 48.dp
): Modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    this
} else this.pointerInput(onSwipeBack, edgeWidth, threshold) {
    val edgePx = edgeWidth.toPx()
    val thresholdPx = threshold.toPx()
    awaitEachGesture {
        // Initial：父节点先于子节点看到按下，仅读取坐标、不消费
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edgePx) return@awaitEachGesture
        val pointerId: PointerId = down.id

        var totalX = 0f
        var armed = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: return@awaitEachGesture
            if (change.changedToUpIgnoreConsumed()) return@awaitEachGesture
            val dx = change.positionChange().x
            if (dx != 0f) {
                totalX += dx
                if (!armed && totalX > edgePx) armed = true
            }
            if (!armed) continue
            change.consume()
            if (totalX >= thresholdPx) {
                onSwipeBack()
                // 一次性触发：把这次手势剩余部分吞掉，直到手指抬起
                while (true) {
                    val rest = awaitPointerEvent(PointerEventPass.Initial)
                    val c = rest.changes.firstOrNull { it.id == pointerId } ?: break
                    c.consume()
                    if (c.changedToUpIgnoreConsumed()) break
                }
                return@awaitEachGesture
            }
        }
    }
}
