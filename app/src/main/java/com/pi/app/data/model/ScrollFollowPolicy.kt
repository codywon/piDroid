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
 * 这里用 Compose 里等价的「首条可见项 (索引, 位移)」，换算见 [scrollDirections]。
 *
 * 另：「跟随时怎么滚」也是约定的一部分 —— pi-web 是**瞬时**贴底
 * （`scrollTo({top: scrollHeight, behavior:"auto"})`），不是缓动动画。缓动在流式期间每帧
 * 都会被新增量打断重开、速度归零，永远收敛不了，表现就是底部那几行在抖。
 */
object ScrollFollowPolicy {

    /** 距底多少像素内算“贴底”。pi-web: `CHAT_SCROLL_TAIL_TOLERANCE = 8`。 */
    const val TAIL_TOLERANCE_PX = 8f

    /** 脱离后再向下滚，进入这个更宽的容差就算重新接上。pi-web: `CHAT_SCROLL_REATTACH_TOLERANCE = 96`。 */
    const val REATTACH_TOLERANCE_PX = 96f

    /**
     * 「离底部还有多远」的哨兵值：最后一条还没渲染进视口时无法精确计算，
     * 用一个远大于任何容差的值表示“离底很远”。它只能用来做「是否贴底 / 是否该显示
     * 回到底部按钮」这类比较，不要拿它参与滚动距离的算术（那样会真的去滚一个天文数字）。
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

    /** 一次滚动事件的方向（Compose 的「首条可见项 索引 / 位移」→ pi-web 的 scrollTop 增减）。 */
    data class ScrollDirections(val scrolledUp: Boolean, val scrolledDown: Boolean) {
        /** 这一帧没有滚动（或首帧还没有可比对象）时调用方应当直接跳过判定。 */
        val changed: Boolean get() = scrolledUp || scrolledDown
    }

    /**
     * 由「首条可见项」的两帧快照推断滚动方向。
     *
     * 输入是 `LazyListItemInfo.index` 与 `LazyListItemInfo.offset`：offset 是该项相对视口起点的
     * 位移（往下滚时内容往上走，位移**越来越负**），而一整行被推出视口之后索引才 +1。
     * 所以「往下」= 索引变大，或索引不变而位移变小；「往上」反之。
     *
     * 跨行与行内两种情况必须**分开且互斥**地判：跨行那一刻位移会从「整行都滚过去了」的负数
     * 跳回 ~0，若把两个条件并列，跨行就会被判成“往上滚”（历史实现正是如此：我们自己的跟随
     * 一跨行就把自己当成“用户上翻”而脱离，下一帧贴底又接上 —— 跟随走一下停一下，底部在抖）。
     */
    fun scrollDirections(
        prevIndex: Int,
        prevOffset: Int,
        index: Int,
        offset: Int
    ): ScrollDirections {
        // 首帧没有上一帧可比，不能凭空猜方向
        if (prevIndex < 0) return ScrollDirections(scrolledUp = false, scrolledDown = false)
        return when {
            index > prevIndex -> ScrollDirections(scrolledUp = false, scrolledDown = true)
            index < prevIndex -> ScrollDirections(scrolledUp = true, scrolledDown = false)
            offset < prevOffset -> ScrollDirections(scrolledUp = false, scrolledDown = true)
            offset > prevOffset -> ScrollDirections(scrolledUp = true, scrolledDown = false)
            else -> ScrollDirections(scrolledUp = false, scrolledDown = false)
        }
    }
}
