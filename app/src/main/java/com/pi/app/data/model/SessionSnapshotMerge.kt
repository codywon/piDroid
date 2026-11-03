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
            while (i < lenA && (rawA[i] == '\r' || rawA[i] == '\n' || rawA[i] == ' ' || rawA[i] == '\t')) i++
            while (j < lenB && (rawB[j] == '\r' || rawB[j] == '\n' || rawB[j] == ' ' || rawB[j] == '\t')) j++
            val hasA = i < lenA
            val hasB = j < lenB
            if (hasA != hasB) return false
            if (hasA && rawA[i] != rawB[j]) return false
            if (hasA) i++
            if (hasB) j++
        }
        return true
    }

    private fun isSameMediaAttachment(a: MediaAttachment, b: MediaAttachment): Boolean {
        if (a.kind != b.kind) return false
        if (!a.mimeType.equals(b.mimeType, ignoreCase = true)) return false
        if (a.filePath?.replace('\\', '/') != b.filePath?.replace('\\', '/')) return false
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
            a.thinkingDurationSec == b.thinkingDurationSec &&
            a.modelName == b.modelName &&
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
            val keepDuration = diskItem.thinkingDurationSec ?: localItem.thinkingDurationSec
            val keepModelName = diskItem.modelName ?: localItem.modelName
            diskItem.copy(
                id = keepId,
                timestamp = keepTs,
                streamMessageId = keepStreamId,
                mediaList = resolvedMedia,
                thinkingDurationSec = keepDuration,
                modelName = keepModelName
            )
        } else if (localItem.entryId.isNullOrBlank() && !diskItem.entryId.isNullOrBlank()) {
            localItem.copy(entryId = diskItem.entryId)
        } else {
            localItem
        }
    }

    /**
     * 服务端快照是否**落后于本地尾部**（快照的尾巴只到本地已经看过的中间位置）。
     *
     * 为什么需要这条判定：pi-web 的 live wrapper 在外部进程写同一个 jsonl 时，内存 leaf 会落后于磁盘，
     * 同一个响应里 `stats` 是新的、`context` 是旧的（分支滞后）；磁盘快照回放 + 后续网络快照
     * 也可能出现同样的“新本地 + 旧快照”组合。
     *
     * 判定方式：取快照里最新的 `entryId`，它**出现在本地、却不是本地的尾巴**就说明本地跑到了快照前面。
     * 三种看起来更直接、实际都会误判的写法：
     * - 时间戳（历史条目时间戳缺失时解析器会兜底成 `now`，而 `mergeItem` 又会保留本地时间戳）；
     * - 「本地有快照没有的 entryId」（窗口向前滑动时，最老的几条天然掉出快照窗口，
     *   那样每滑一次都会被当成滞后，界面长期卡在只追加模式）；
     * - 「响应 leafId ≠ context 末条」（分支最后一条是不产出消息的 `model_change` 时恒不相等）。
     */
    fun isSnapshotOlderThanLocal(local: List<ChatItem>, synced: List<ChatItem>): Boolean {
        val syncedNewestId = synced.asReversed()
            .firstNotNullOfOrNull { it.entryId?.takeIf { id -> id.isNotBlank() } }
            ?: return false
        val localNewestId = local.asReversed()
            .firstNotNullOfOrNull { it.entryId?.takeIf { id -> id.isNotBlank() } }
            ?: return false
        // 尾巴相同 = 快照追上了；本地多出来的只是尚未落盘的在途项，由 in-flight 规则保护。
        if (localNewestId == syncedNewestId) return false
        // 快照的尾巴落在本地中间 → 快照就是本地的前缀，比本地旧。
        return local.any { it.entryId == syncedNewestId }
    }

    /**
     * 连续多少轮「快照缺本地条目」之后，允许服务端权威覆盖一次（只在会话已空闲时）。
     *
     * 为什么要出口：只追加模式是对付滞后快照的止血手段，但如果服务端**真的**丢了一些条目
     * （会话裁剪 / 分支重写而客户端没走 replaceHistory），界面会永久显示已不存在的内容。
     * 会话空闲时本机的在途内容已经落盘，此时以服务端为准是安全的；在跑时绝不动（否则会扼断在途输出）。
     */
    const val STALE_STREAK_BEFORE_SERVER_WINS = 3

    /** 本轮对账是否该放弃只追加模式、改以服务端为准。详见 [STALE_STREAK_BEFORE_SERVER_WINS]。 */
    fun shouldTrustServerOverLocal(staleStreak: Int, effectiveRunning: Boolean): Boolean {
        if (effectiveRunning) return false
        return staleStreak >= STALE_STREAK_BEFORE_SERVER_WINS
    }

    /**
     * 将本地消息列表 [local] 与服务端权威快照 [synced] 进行对账合并（纯 JVM，可单测）。
     *
     * 核心契约与不变式：
     * 1) 以服务端提交历史为权威基准，匹配到的本地项保留本地稳定的 UI 标识与时间戳（避免 Compose 重组闪烁）；
     * 2) 本地正在在途的乐观用户消息（尚未包含在服务端快照中）、正在流式接收的助手消息、或正在执行的工具卡片，
     *    **绝不可被截断抹除**，无论此时网络轮询判定运行态与否；
     * 3) 仅在会话真正空闲且已无未提交内容时，才剔除已被远端抛弃的过时分支历史；
     * 4) 未落盘候选的内容匹配从**最新一条往前**找，避免陈旧的同文本气泡抢走服务端刚落盘的提问；
     *    这些气泡的最终去留由 [PendingPromptSettlement] 判定（超时未确认即撤回）；
     * 5) [snapshotStale] 为真（快照落后于本地尾部）时，本地**已落盘但快照里没有**的条目一律保留，
     *    且已匹配条目优先保留本地（更新）的正文 —— 本回合的真相只能往前长，不会因一次滞后快照回滚。
     */
    fun reconcileList(
        local: List<ChatItem>,
        synced: List<ChatItem>,
        effectiveRunning: Boolean,
        snapshotStale: Boolean = false
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

        // 快照里已有的 entryId 集合：用于判断哪些本地条目是“快照还没追上”而不是“已过期”。
        val syncedEntryIds = synced.mapNotNullTo(HashSet()) { it.entryId?.takeIf { id -> id.isNotBlank() } }

        // 1. 服务端权威历史对账
        //
        // 「未落盘候选」的内容匹配一律**从列表尾部往前找**（最新的优先）。
        // 从头部找会把服务端刚落盘的那条提问，配给更早遗留的同文本气泡（例如压缩开头 abort 掉的
        // 一次提问、或回执丢过一次的提问）：旧气泡被「认领」，新气泡反而没有归属，于是被当作
        // in-flight 追加到末尾 —— 界面上就是同一条指令显示两遍（「继续」这类高频短句最容易踩中）。
        val matchedLocalIndices = mutableSetOf<Int>()
        val reconciledCommitted = ArrayList<ChatItem>(synced.size)
        val newestFirst = local.indices.reversed()

        for (diskItem in synced) {
            // 优先按 entryId 精确匹配
            var localMatchIdx = -1
            if (!diskItem.entryId.isNullOrBlank()) {
                for (i in local.indices) {
                    if (!matchedLocalIndices.contains(i) && local[i].entryId == diskItem.entryId) {
                        localMatchIdx = i
                        break
                    }
                }
            }

            // 若 entryId 未匹配，针对乐观用户消息尝试按角色与正文内容匹配
            //
            // 正文比较**必须**走 [UserPromptText.samePrompt] 而不是简单的 trim+equals：
            // 带图片的提问落盘时，pi 会往正文末尾追加一行给模型看的尺寸提示
            // （`[Image: original 1220x2656, displayed at 919x2000. …]`），手机截图几乎必然被缩放，
            // 于是「本机原话」与「服务端原话 + 提示行」永远对不上 —— 乐观气泡认领不了服务端那条，
            // 被当作 in-flight 追加到列表末尾，界面上就是「模型已经答完，下面又冒出一条我发的提问」。
            if (localMatchIdx < 0 && diskItem.role == MessageRole.USER) {
                for (i in newestFirst) {
                    if (matchedLocalIndices.contains(i)) continue
                    val item = local[i]
                    if (item.role != MessageRole.USER) continue
                    if (!item.entryId.isNullOrBlank()) continue
                    if (UserPromptText.samePrompt(item.content, diskItem.content)) {
                        localMatchIdx = i
                        break
                    }
                }
            }

            // 若 entryId 未匹配，针对本地在途工具卡片按 toolCallId 或工具名/命令精确匹配
            if (localMatchIdx < 0 && diskItem.role == MessageRole.TOOL) {
                val diskTcId = diskItem.toolCall?.toolCallId
                for (i in newestFirst) {
                    if (matchedLocalIndices.contains(i)) continue
                    val item = local[i]
                    if (item.role != MessageRole.TOOL) continue
                    if (!item.entryId.isNullOrBlank()) continue
                    if (!diskTcId.isNullOrBlank() && item.toolCall?.toolCallId == diskTcId) {
                        localMatchIdx = i
                        break
                    }
                }
                if (localMatchIdx < 0) {
                    val diskToolName = diskItem.toolCall?.toolName
                    val diskCmd = diskItem.toolCall?.command
                    if (!diskToolName.isNullOrBlank()) {
                        for (i in newestFirst) {
                            if (matchedLocalIndices.contains(i)) continue
                            val item = local[i]
                            if (item.role != MessageRole.TOOL) continue
                            if (!item.entryId.isNullOrBlank()) continue
                            if (item.toolCall?.toolName == diskToolName &&
                                (diskCmd.isNullOrBlank() || item.toolCall?.command == diskCmd)
                            ) {
                                localMatchIdx = i
                                break
                            }
                        }
                    }
                }
            }

            // 若 entryId 未匹配，针对在途或流式生成的助手消息按角色顺序匹配
            if (localMatchIdx < 0 && diskItem.role == MessageRole.AGENT) {
                for (i in newestFirst) {
                    if (matchedLocalIndices.contains(i)) continue
                    val item = local[i]
                    if (item.role != MessageRole.AGENT) continue
                    if (!item.entryId.isNullOrBlank()) continue
                    localMatchIdx = i
                    break
                }
            }

            if (localMatchIdx >= 0) {
                matchedLocalIndices.add(localMatchIdx)
                val localItem = local[localMatchIdx]
                // 如果最后一条正在流式接收，且本地累积的正文比快照更新，保留本地流式正文
                if (localItem.isStreaming && localItem.role == MessageRole.AGENT && diskItem.role == MessageRole.AGENT) {
                    val mergedContent = if (localItem.content.length >= diskItem.content.length) localItem.content else diskItem.content
                    val mergedThinking = if ((localItem.thinking?.length ?: 0) >= (diskItem.thinking?.length ?: 0)) localItem.thinking else diskItem.thinking
                    val keepStreamId = localItem.streamMessageId ?: diskItem.streamMessageId
                    val resolvedMedia = if (diskItem.mediaList.isNotEmpty()) diskItem.mediaList else localItem.mediaList
                    val keepDuration = diskItem.thinkingDurationSec ?: localItem.thinkingDurationSec
                    val keepModelName = diskItem.modelName ?: localItem.modelName
                    reconciledCommitted.add(
                        diskItem.copy(
                            id = localItem.id,
                            timestamp = localItem.timestamp,
                            content = mergedContent,
                            thinking = mergedThinking,
                            isStreaming = effectiveRunning,
                            streamMessageId = keepStreamId,
                            mediaList = resolvedMedia,
                            thinkingDurationSec = keepDuration,
                            modelName = keepModelName
                        )
                    )
                } else if (snapshotStale) {
                    // 快照落后于本地：同一条消息保留本地（更新）的正文/思考/工具输出，
                    // 只把快照里才有、本地缺的元数据补上。否则一次滞后快照就会把已经流下来的
                    // 内容回滚成几百字前的样子。快照追上后（snapshotStale=false）恢复正常以服务端为准。
                    //
                    // 工具卡片例外：服务端已经有结果的，必须单向补进来。否则 SSE 一旦断在
                    // `tool_execution_end` 之前，那张卡会永远转圈（本机再也等不到结束事件）。
                    val localTool = localItem.toolCall
                    val diskTool = diskItem.toolCall
                    val resolvedTool = if (localTool != null && diskTool != null &&
                        localTool.isRunning && localTool.output.isNullOrBlank() && !diskTool.output.isNullOrBlank()
                    ) {
                        localTool.copy(
                            output = diskTool.output,
                            durationMs = diskTool.durationMs,
                            isError = diskTool.isError,
                            isRunning = false,
                            fullOutputPath = diskTool.fullOutputPath ?: localTool.fullOutputPath,
                            mediaList = if (diskTool.mediaList.isNotEmpty()) diskTool.mediaList else localTool.mediaList
                        )
                    } else {
                        localTool
                    }
                    reconciledCommitted.add(
                        localItem.copy(
                            entryId = localItem.entryId ?: diskItem.entryId,
                            usageSummary = localItem.usageSummary ?: diskItem.usageSummary,
                            modelName = localItem.modelName ?: diskItem.modelName,
                            thinkingDurationSec = localItem.thinkingDurationSec ?: diskItem.thinkingDurationSec,
                            toolCall = resolvedTool
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
        // b) 运行中时，任何尚未落盘的本地消息（提问、助手流式/分段、工具卡片）都是当前在途内容，绝不丢弃
        // c) 正在通过 SSE 实时流式接收的助手消息 (isStreaming == true)
        // d) 正在执行的工具卡片 (toolCall?.isRunning == true)
        val inFlightItems = local.filterIndexed { idx, item ->
            if (matchedLocalIndices.contains(idx)) return@filterIndexed false
            when {
                // 尚未落盘的乐观用户消息：无论是否 running 都绝不能丢
                item.role == MessageRole.USER && item.entryId.isNullOrBlank() -> true
                // 运行中时，尚未落盘的本地消息（提问、助手、工具）都是当前活跃回合在途产物，绝不丢弃
                effectiveRunning && item.entryId.isNullOrBlank() -> true
                // 正在实时生成的助手消息：保留
                item.role == MessageRole.AGENT && item.isStreaming -> true
                // 正在执行的工具：保留
                item.role == MessageRole.TOOL && item.toolCall?.isRunning == true -> true
                // 快照落后于本地尾部（分支 leaf 滞后）：本机已经看到的这些条目绝不能当“过期分支”删掉，
                // 否则界面会永久停在快照的旧尾巴上（“PC 一直在跑，手机不再往后长”的根因）。
                snapshotStale && !item.entryId.isNullOrBlank() && item.entryId !in syncedEntryIds -> true
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
