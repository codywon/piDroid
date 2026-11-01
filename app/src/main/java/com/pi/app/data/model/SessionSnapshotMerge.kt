package com.pi.app.data.model

/**
 * 会话快照与本地消息列表的对账规则（纯 JVM，可单测）。
 *
 * 抽出来的唯一目的：把「服务端权威快照 vs 本地内存列表」的合并语义从 Compose/Activity
 * 里剥离成可验证的纯函数，避免树分支回退（navigate_tree / Edit from here）这类
 * 一旦算错就会把「已作废的执行过程输出」重新拼回界面的逻辑只能靠肉眼 review。
 *
 * 与 pi-web 的对应关系：
 * - `isSameChatContent` ≈ pi-web `isSameChatItem`（忽略 id / 时间戳的实质内容比较）
 * - `mergeItem` ≈ pi-web `reconcileMessages` 的单条合并（保留本地稳定 key，补齐 entryId）
 */
object SessionSnapshotMerge {

    private fun isSameBase64(a: String?, b: String?): Boolean {
        if (a == b) return true
        if (a.isNullOrBlank() && b.isNullOrBlank()) return true
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val rawA = a.substringAfter("base64,", a)
        val rawB = b.substringAfter("base64,", b)
        var i = 0
        var j = 0
        val lenA = rawA.length
        val lenB = rawB.length
        while (i < lenA || j < lenB) {
            while (i < lenA && (rawA[i] == '\r' || rawA[i] == '\n' || rawA[i] == ' ')) i++
            while (j < lenB && (rawB[j] == '\r' || rawB[j] == '\n' || rawB[j] == ' ')) j++
            val hasA = i < lenA
            val hasB = j < lenB
            if (hasA != hasB) return false
            if (hasA && rawA[i] != rawB[j]) return false
            i++
            j++
        }
        return true
    }

    private fun isSameMediaAttachment(a: MediaAttachment, b: MediaAttachment): Boolean {
        if (a.kind != b.kind) return false
        if (!a.mimeType.equals(b.mimeType, ignoreCase = true)) return false
        if (a.filePath != b.filePath) return false
        if (a.url != b.url) return false
        if (a.rawContent != b.rawContent) return false
        if (!isSameBase64(a.dataBase64, b.dataBase64)) return false
        return true
    }

    fun isSameMediaList(a: List<MediaAttachment>, b: List<MediaAttachment>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!isSameMediaAttachment(a[i], b[i])) return false
        }
        return true
    }

    /** 两条消息的实质内容是否一致（忽略 id 与 timestamp，用于消除轮询闪屏）。 */
    fun isSameChatContent(a: ChatItem, b: ChatItem): Boolean {
        return a.role == b.role &&
            a.content == b.content &&
            a.thinking == b.thinking &&
            a.isStreaming == b.isStreaming &&
            a.toolCall == b.toolCall &&
            a.usageSummary == b.usageSummary &&
            isSameMediaList(a.mediaList, b.mediaList)
    }

    /**
     * 合并单条消息：
     * 1) 内容不一致 → 以服务端为准，但角色相同时保留本地 id / timestamp（LazyColumn key 稳定）；
     * 2) 内容一致但本地缺 entryId → 静默补齐（本地乐观气泡落盘后「从此处编辑」才能用）；
     * 3) 其余情况原样返回本地对象，避免无意义重建。
     */
    fun mergeItem(localItem: ChatItem, diskItem: ChatItem): ChatItem {
        return if (!isSameChatContent(localItem, diskItem)) {
            val keepId = if (localItem.role == diskItem.role) localItem.id else diskItem.id
            val keepTs = if (localItem.role == diskItem.role) localItem.timestamp else diskItem.timestamp
            // streamMessageId 是**本地在途身份**（服务端快照里没有），内容被服务端覆盖时必须带过去，
            // 否则流式过程中一次对账就会把身份抹掉，下一次重连补发又会新开一条重复气泡。
            val keepStreamId = if (localItem.role == diskItem.role && diskItem.streamMessageId == null) {
                localItem.streamMessageId
            } else {
                diskItem.streamMessageId
            }
            // 媒体附件合并策略：
            // - 若服务端快照包含媒体附件，以服务端为准，但对匹配的附件保留本地 id 与文件名，杜绝闪烁重解
            // - 若服务端快照暂无媒体附件（例如刚写入但字段尚未序列化完），但本地角色匹配且有媒体，保留本地媒体
            val resolvedMedia = when {
                diskItem.mediaList.isNotEmpty() -> {
                    if (localItem.role == diskItem.role && localItem.mediaList.size == diskItem.mediaList.size) {
                        diskItem.mediaList.mapIndexed { idx, diskMedia ->
                            val localMedia = localItem.mediaList.getOrNull(idx)
                            if (localMedia != null && isSameMediaAttachment(localMedia, diskMedia)) {
                                diskMedia.copy(
                                    id = localMedia.id,
                                    title = if (diskMedia.title.isBlank() || diskMedia.title == "图片附件") {
                                        localMedia.title.ifBlank { diskMedia.title }
                                    } else {
                                        diskMedia.title
                                    }
                                )
                            } else {
                                diskMedia
                            }
                        }
                    } else {
                        diskItem.mediaList
                    }
                }
                localItem.role == diskItem.role && localItem.mediaList.isNotEmpty() -> localItem.mediaList
                else -> emptyList()
            }
            diskItem.copy(
                id = keepId,
                timestamp = keepTs,
                streamMessageId = keepStreamId,
                mediaList = resolvedMedia
            )
        } else if (localItem.entryId.isNullOrBlank() && !diskItem.entryId.isNullOrBlank()) {
            localItem.copy(entryId = diskItem.entryId)
        } else {
            localItem
        }
    }

    /**
     * 将本地消息列表 [local] 与服务端权威快照 [synced] 进行对账合并（纯 JVM，可单测）。
     *
     * 核心契约与不变式：
     * 1) 以服务端提交历史为权威基准，匹配到的本地项保留本地稳定的 UI 标识与时间戳（避免 Compose 重组闪烁）；
     * 2) 本地正在在途的乐观用户消息（尚未包含在服务端快照中）、正在流式接收的助手消息、或正在执行的工具卡片，
     *    **绝不可被截断抹除**，无论此时网络轮询判定运行态与否；
     * 3) 仅在会话真正空闲且已无未提交内容时，才剔除已被远端抛弃的过时分支历史。
     */
    fun reconcileList(
        local: List<ChatItem>,
        synced: List<ChatItem>,
        effectiveRunning: Boolean
    ): List<ChatItem> {
        if (local.isEmpty()) return synced
        if (synced.isEmpty()) {
            // 如果服务端快照暂时为空（例如网络瞬断或首屏尚未就绪），但本地有乐观消息，必须保留
            return if (effectiveRunning || local.any { it.role == MessageRole.USER && it.entryId.isNullOrBlank() }) {
                local
            } else {
                emptyList()
            }
        }

        // 1. 服务端权威历史对账
        val matchedLocalIndices = mutableSetOf<Int>()
        val reconciledCommitted = ArrayList<ChatItem>(synced.size)

        for (diskItem in synced) {
            // 优先按 entryId 精确匹配
            var localMatchIdx = -1
            if (!diskItem.entryId.isNullOrBlank()) {
                localMatchIdx = local.indexOfFirst { idx ->
                    !matchedLocalIndices.contains(idx) && local[idx].entryId == diskItem.entryId
                }
            }

            // 若 entryId 未匹配，针对乐观用户消息尝试按角色与正文内容匹配
            if (localMatchIdx < 0 && diskItem.role == MessageRole.USER) {
                val diskContent = diskItem.content.trim()
                localMatchIdx = local.indexOfFirst { idx ->
                    if (matchedLocalIndices.contains(idx)) return@indexOfFirst false
                    val item = local[idx]
                    if (item.role != MessageRole.USER) return@indexOfFirst false
                    val localContent = item.content.trim()
                    localContent == diskContent ||
                        localContent.removePrefix("➔ [引导] ").trim() == diskContent
                }
            }

            if (localMatchIdx >= 0) {
                matchedLocalIndices.add(localMatchIdx)
                val localItem = local[localMatchIdx]
                // 如果最后一条正在流式接收，且本地累积的正文比快照更新，保留本地流式正文
                if (localItem.isStreaming && localItem.role == MessageRole.AGENT && diskItem.role == MessageRole.AGENT) {
                    val mergedContent = if (localItem.content.length >= diskItem.content.length) localItem.content else diskItem.content
                    val mergedThinking = if ((localItem.thinking?.length ?: 0) >= (diskItem.thinking?.length ?: 0)) localItem.thinking else diskItem.thinking
                    reconciledCommitted.add(
                        diskItem.copy(
                            id = localItem.id,
                            timestamp = localItem.timestamp,
                            content = mergedContent,
                            thinking = mergedThinking,
                            isStreaming = effectiveRunning
                        )
                    )
                } else {
                    reconciledCommitted.add(mergeItem(localItem, diskItem))
                }
            } else {
                reconciledCommitted.add(diskItem)
            }
        }

        // 2. 提取本地尚未包含在服务端快照中的有效在途消息 (In-Flight Items)
        // 包含：
        // a) 用户刚发送但服务端尚未记录的乐观消息 (role == USER, entryId == null)
        // b) 正在通过 SSE 实时流式接收的助手消息 (isStreaming == true)
        // c) 正在执行的工具卡片 (toolCall?.isRunning == true)
        val inFlightItems = local.filterIndexed { idx, item ->
            if (matchedLocalIndices.contains(idx)) return@filterIndexed false
            when {
                // 尚未落盘的乐观用户消息：无论是否 running 都绝不能丢
                item.role == MessageRole.USER && item.entryId.isNullOrBlank() -> true
                // 正在实时生成的助手消息：保留
                item.role == MessageRole.AGENT && item.isStreaming -> true
                // 正在执行的工具：保留
                item.role == MessageRole.TOOL && item.toolCall?.isRunning == true -> true
                else -> false
            }
        }

        return if (inFlightItems.isNotEmpty()) {
            reconciledCommitted + inFlightItems
        } else {
            reconciledCommitted
        }
    }
}
