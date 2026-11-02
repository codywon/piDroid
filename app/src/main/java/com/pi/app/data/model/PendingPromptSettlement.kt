package com.pi.app.data.model

/**
 * 「本机乐观提问」的结算规则（纯 JVM，可单测）。
 *
 * 背景缺陷（v1.8.46 仍在）：[SessionSnapshotMerge] 有一条不变式 ——
 * 「`entryId` 为空的 USER 消息永不丢弃」。它的本意是防止服务端 .jsonl 还没落盘就把用户刚发的
 * 提问抹掉，但**没有出口**：一旦这条提问其实没有被服务端接收（压缩开头的 `abort()` 把它打断、
 * 回执在隧道里丢了、或者它已经落盘又被压缩折进摘要），这个气泡就会永久留在 `messages` 里，
 * 每次对账都被当作 in-flight 追加到列表尾部。
 *
 * 更糟的是快照内容匹配是「从列表头找第一个同文本、`entryId` 为空的气泡」。于是用户下次再发
 * 同样的文本（「继续」这类极常见）时，服务端那条会先匹配到**旧的**气泡，新的气泡没有归属，
 * 被追加到末尾 —— 屏幕上同一条指令出现两遍。
 *
 * 这里给出「什么时候可以判定它没送达」的判据。只有同时满足全部条件才算：
 * 1) 它是本机乐观气泡（`entryId` 为空）且我们记下了它的发送时刻；
 * 2) 已经过了 [graceMs]（给弱网 + 落盘留余量）；
 * 3) 这份服务端快照是**在发送之后才发起**的（所以它一定能看到那条提问，除非服务端根本没有）；
 * 4) 服务端此刻不在跑（在跑就还有戏，不动它）；
 * 5) 它的正文不在服务端队列里（压缩期间被 park 的提问在队列里，压缩结束会自动下发）；
 * 6) 它的提交请求已经返回（还在飞的时候结果未定，不能判死）。
 */
object PendingPromptSettlement {

    /** 默认宽限：pi 正常落盘在 1s 内，15s 是弱网 + 压缩边界的余量。 */
    const val DEFAULT_GRACE_MS = 15_000L

    /** 引导消息在本地气泡上带的前缀（见 MainActivity 的 onSendSteer）。 */
    const val STEER_PREFIX = "➔ [引导] "

    private fun normalize(text: String): String =
        text.trim().removePrefix(STEER_PREFIX).trim()

    /**
     * 找出「确定没送达、应当从列表撤回」的乐观提问。
     *
     * @param items 当前本地消息列表（已做完快照对账）
     * @param sentAtById 乐观气泡 id → 发送时刻（毫秒）
     * @param snapshotStartedAt 这一份服务端快照的**发起**时刻
     * @param serverRunning 服务端是否仍在执行（只有明确读到 `false` 才能判死）
     * @param queuedTexts 服务端队列里的正文（steering + followUp，含压缩期间被 park 的）
     * @param inFlightSendIds 提交请求（POST）还没返回的乐观气泡：结果未知，不能判死
     */
    fun staleUnconfirmed(
        items: List<ChatItem>,
        sentAtById: Map<String, Long>,
        snapshotStartedAt: Long,
        serverRunning: Boolean,
        queuedTexts: Collection<String>,
        now: Long,
        graceMs: Long = DEFAULT_GRACE_MS,
        inFlightSendIds: Collection<String> = emptyList()
    ): List<ChatItem> {
        if (serverRunning) return emptyList()
        if (items.isEmpty() || sentAtById.isEmpty()) return emptyList()
        val queued = queuedTexts.mapTo(HashSet()) { normalize(it) }
        val unsettled = if (inFlightSendIds.isEmpty()) emptySet() else inFlightSendIds.toHashSet()
        return items.filter { item ->
            if (item.role != MessageRole.USER) return@filter false
            if (!item.entryId.isNullOrBlank()) return@filter false
            if (item.id in unsettled) return@filter false
            val sentAt = sentAtById[item.id] ?: return@filter false
            if (snapshotStartedAt <= sentAt) return@filter false
            if (now - sentAt < graceMs) return@filter false
            normalize(item.content) !in queued
        }
    }

    /**
     * 撤回之后要不要把正文还回输入框。
     *
     * 只有当列表里**再没有**同文本的提问时才还：用户往往在没看到反应时又发了一遍，
     * 这时把旧的退回去会让输入框里凭空多出一段他已经发出去的内容。
     */
    fun shouldRestoreToComposer(items: List<ChatItem>, stale: ChatItem): Boolean {
        val target = normalize(stale.content)
        if (target.isBlank()) return false
        return items.none { it.id != stale.id && it.role == MessageRole.USER && normalize(it.content) == target }
    }
}
