package com.pi.app.data.model

/**
 * 在途 assistant message 的正文落点规则（纯 JVM，可单测）。
 *
 * 对齐 pi-web 的 streaming 语义：`message_start` 携带的是**当前 streamingMessage 的整条快照**
 * （pi 的 agent 状态机里 `message_start/message_update` 设置 streamingMessage、`message_end` 才清空），
 * pi-web 客户端收到它只做一件事 —— `dispatch({type:"snapshot", message})`，即**整条覆盖替换**，
 * 因此重连补发天然幂等，永远不会多出一条消息。
 *
 * 而本 App 的消息列表是 append-only 的 `ChatItem` 列表，且工具调用卡是**独立条目**，
 * 于是「最后一条是不是 AGENT 气泡」不能作为落点依据：工具卡在 `toolcall_start` 就插到正文后面了，
 * 此后重连补发的整条快照会被当成「尾部不是 AGENT」而新开一条气泡，
 * 表现为过程输出里同一段正文在工具卡后面又完整重复一遍（且会被 inFlightTail 保活，越攒越多）。
 *
 * 这里用「在途消息身份 [ChatItem.streamMessageId] + 本轮作用域」定位落点：
 * 同一条消息的正文永远写回**同一条气泡**，重复补发会被判定为 [TextUpdate.noop]。
 */
object StreamingMessageMerge {

    /** 整条正文快照的落点结论。 */
    data class TextUpdate(
        /** 目标气泡下标；null 表示需要新建一条气泡。 */
        val index: Int?,
        /** 应当写入目标气泡的正文（[noop] 为 true 时无意义）。 */
        val content: String,
        /** 本地已经有这段内容，什么都不用做（重连补发幂等的关键）。 */
        val noop: Boolean
    ) {
        companion object {
            val NOOP = TextUpdate(index = null, content = "", noop = true)
        }
    }

    /** 本轮起点：最后一条 USER 消息之后的下标（没有 USER 时从 0 开始）。 */
    fun currentTurnStart(messages: List<ChatItem>): Int {
        val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
        return if (lastUser < 0) 0 else lastUser + 1
    }

    /** 本轮之内属于指定在途消息的全部 AGENT 气泡下标（一条消息可能被拆成多个正文块）。 */
    fun indicesOfMessage(messages: List<ChatItem>, messageId: String?): List<Int> {
        if (messageId == null) return emptyList()
        val start = currentTurnStart(messages)
        return (start until messages.size).filter { i ->
            val m = messages[i]
            m.role == MessageRole.AGENT && m.streamMessageId == messageId
        }
    }

    /** 本轮之内最后一条 AGENT 气泡下标，没有则 -1。 */
    private fun lastAgentIndexOfTurn(messages: List<ChatItem>): Int {
        val start = currentTurnStart(messages)
        for (i in messages.lastIndex downTo start) {
            if (messages[i].role == MessageRole.AGENT) return i
        }
        return -1
    }

    /**
     * 正文增量（`text_delta`）的落点：命中返回下标，-1 表示需要新开气泡。
     *
     * - 已知在途消息身份：只落到**属于该消息**的气泡上，绝不跨消息串写；
     * - 身份未知（老版本服务端没带 message）：沿用「尾部是 AGENT 才续写」的保守规则。
     */
    fun resolveDeltaIndex(messages: List<ChatItem>, messageId: String?): Int {
        if (messageId == null) {
            return if (messages.lastOrNull()?.role == MessageRole.AGENT) messages.lastIndex else -1
        }
        return indicesOfMessage(messages, messageId).lastOrNull() ?: -1
    }

    /**
     * 整条正文快照（`message_start` 补发 / `text_end` 定稿）的落点。
     *
     * 判定顺序（与 pi-web 的 snapshot 覆盖语义一致，但保留本地可能更靠前的流式进度）：
     * 1. 本地已含这段内容 → [TextUpdate.noop]；
     * 2. 快照是本地内容的超集 → 覆盖（单块）或只补增量（多块）；
     * 3. 内容对不上（服务端回滚/重试）→ 单块直接覆盖，多块只覆盖最后一块，绝不新开气泡造成重复。
     */
    fun resolveTextUpdate(messages: List<ChatItem>, messageId: String?, snapshot: String): TextUpdate {
        if (snapshot.isBlank()) return TextUpdate.NOOP

        val owned = indicesOfMessage(messages, messageId)
        if (owned.isEmpty()) return resolveTextUpdateWithoutOwner(messages, messageId, snapshot)

        val localJoined = owned.joinToString("\n") { messages[it].content }
        if (localJoined == snapshot) return TextUpdate.NOOP
        // 快照落后于本地已渲染的正文（正在逐 token 追加中）→ 绝不回退
        if (localJoined.isNotEmpty() && localJoined.startsWith(snapshot)) return TextUpdate.NOOP

        if (snapshot.startsWith(localJoined)) {
            val target = owned.last()
            if (owned.size == 1) return TextUpdate(target, snapshot, noop = false)
            // 多块正文：只把新增部分补到最后一块，前面的块不动
            val appended = snapshot.substring(localJoined.length).trimStart('\n')
            return TextUpdate(target, messages[target].content + appended, noop = false)
        }

        // 内容对不上：单块直接以快照为准
        if (owned.size == 1) return TextUpdate(owned.last(), snapshot, noop = false)
        // 多块：去掉前几块已渲染的部分，只覆盖最后一块，避免整段重复
        val head = owned.dropLast(1).joinToString("\n") { messages[it].content }
        val remainder = if (head.isNotEmpty() && snapshot.startsWith(head)) {
            snapshot.substring(head.length).trimStart('\n')
        } else {
            snapshot
        }
        val target = owned.last()
        return if (remainder == messages[target].content) {
            TextUpdate.NOOP
        } else {
            TextUpdate(target, remainder, noop = false)
        }
    }

    /** 本轮里还没有这条消息的气泡：先尝试认领本轮最后一条 AGENT 气泡，否则新开。 */
    private fun resolveTextUpdateWithoutOwner(
        messages: List<ChatItem>,
        messageId: String?,
        snapshot: String
    ): TextUpdate {
        val lastAgent = lastAgentIndexOfTurn(messages)
        if (lastAgent >= 0) {
            val local = messages[lastAgent].content
            if (local == snapshot) return TextUpdate.NOOP
            // 本地更新（快照落后）
            if (local.isNotEmpty() && local.startsWith(snapshot)) return TextUpdate.NOOP
            // 老协议（服务端不提供消息身份）：尾部就是这条在途正文 → 覆盖
            if (messageId == null && messages.lastIndex == lastAgent) {
                return TextUpdate(lastAgent, snapshot, noop = false)
            }
            // 有身份但本地漏标记：快照包含这条气泡已渲染的正文 → 认领并补齐，不新开重复气泡
            if (local.isNotEmpty() && snapshot.startsWith(local)) {
                return TextUpdate(lastAgent, snapshot, noop = false)
            }
        }
        return TextUpdate(index = null, content = snapshot, noop = false)
    }
}
