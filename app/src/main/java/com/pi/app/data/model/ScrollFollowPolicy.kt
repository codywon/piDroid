package com.pi.app.data.model

/**
 * 聊天列表「是否跟随到底部」的判定策略（纯 JVM，可单测）。
 *
 * 1:1 对齐 pi-web `lib/chat-lazy-load.ts` 的 `getLiveFollowAttached` / `isScrollAtTail` /
 * `shouldShowScrollToLatest`（`components/ChatWindow.tsx` + `hooks/useAgentSession.ts` 是它的调用方）：
 *
 * - 距底 ≤ [TAIL_TOLERANCE_PX] 视为贴着底部 → 跟随；
 * - **用户往上滚 → 立刻脱离**（pi-web 的原话：「Live-follow the streaming output only when the
 *   user is already near the bottom of the message list. If they scrolled up, leave them there.」）；
 * - 原本脱离了，向下滚且进入更宽的 [REATTACH_TOLERANCE_PX] → 重新接上；
 * - 其余情况保持原状态。
 *
 * 为什么必须用**滚动方向**而不是「离底多远」：内容在下方自然增长（新消息、图片解码、卡片变高）
 * 也会让“离底距离”变大，但那不是用户意图 —— 按距离判定会在流式输出时把跟随关掉，
 * 反而表现为“明明在看最新输出，界面却停住了”。pi-web 用的是 `scrollTop` 的增减，
 * 这里用 Compose 里等价的 `firstVisibleItemIndex / firstVisibleItemScrollOffset`。
 */
object ScrollFollowPolicy {

    /** 距底多少像素内算“贴底”。pi-web: `CHAT_SCROLL_TAIL_TOLERANCE = 8`。 */
    const val TAIL_TOLERANCE_PX = 8f

    /** 脱离后再向下滚，进入这个更宽的容差就算重新接上。pi-web: `CHAT_SCROLL_REATTACH_TOLERANCE = 96`。 */
    const val REATTACH_TOLERANCE_PX = 96f

    /** 落差超过几屏就不要再逐屏动画，直接瞬移（否则视觉上就是“自动刷屏往下翻”）。 */
    const val INSTANT_JUMP_VIEWPORTS = 1f

    /**
     * 「离底部还有多远」的哨兵值：最后一条还没渲染进视口时无法精确计算，
     * 用一个远大于任何容差的值表示“离底很远”。调用方做算术前需先与视口高度比较。
     */
    const val FAR_FROM_BOTTOM_PX = Float.MAX_VALUE

    fun isAtTail(distanceToBottom: Float, tailTolerance: Float = TAIL_TOLERANCE_PX): Boolean =
        distanceToBottom <= tailTolerance

    /**
     * 本轮是否应当跟随底部（对应 pi-web `getLiveFollowAttached`）。
     *
     * @param wasAttached 上一轮的跟随状态
     * @param scrolledUp 本帧首条可见项相对上一帧是否朝“更早的内容”移动（用户往上滚）
     * @param scrolledDown 本帧是否朝“更新的内容”移动
     * @param distanceToBottom 距底像素；无法计算时传 [FAR_FROM_BOTTOM_PX]
     */
    fun resolveAttached(
        wasAttached: Boolean,
        scrolledUp: Boolean,
        scrolledDown: Boolean,
        distanceToBottom: Float,
        tailTolerance: Float = TAIL_TOLERANCE_PX,
        reattachTolerance: Float = REATTACH_TOLERANCE_PX
    ): Boolean {
        if (isAtTail(distanceToBottom, tailTolerance)) return true
        if (scrolledUp) return false
        if (!wasAttached && scrolledDown && distanceToBottom <= reattachTolerance) return true
        return wasAttached
    }

    /** 是否显示“回到底部”悬浮按钮（pi-web `shouldShowScrollToLatest`：不在底部就显示）。 */
    fun shouldShowScrollToLatest(distanceToBottom: Float, tailTolerance: Float = TAIL_TOLERANCE_PX): Boolean =
        !isAtTail(distanceToBottom, tailTolerance)

    /**
     * 跟随到底部时该不该用瞬移（而不是逐屏动画）。
     *
     * 落差超过一个视口就直接跳：`animateScrollBy(viewport)` 的循环在长列表里
     * 等于把整屏一屏一屏地自动翻过去 —— 用户看到的就是“自动刷屏滚动到底部”。
     */
    fun needsInstantJump(
        distanceToBottom: Float,
        viewportHeight: Float,
        viewportThreshold: Float = INSTANT_JUMP_VIEWPORTS
    ): Boolean {
        if (viewportHeight <= 0f) return true
        if (distanceToBottom >= FAR_FROM_BOTTOM_PX) return true
        return distanceToBottom > viewportHeight * viewportThreshold
    }
}
