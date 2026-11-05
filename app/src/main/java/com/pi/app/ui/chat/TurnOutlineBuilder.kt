package com.pi.app.ui.chat

import com.pi.app.data.model.ChatItem
import com.pi.app.data.model.MessageRole
import com.pi.app.data.model.TurnPartition

/**
 * 把整场会话的消息按用户提问切成回合大纲（1:1 对标 pi-web 的 `TurnInfo`）。
 *
 * **为什么从 `ChatScreen` 里抽出来**：它原来被包在一个 `remember(...)` 里，用作缓存的
 * 六个 key 是 `conversation.size` / 首尾两条的 id / 尾部 content 与 thinking 的长度 /
 * `isAgentBusy`。这套 key 对「非尾部元素的内容变化」完全无感：
 *
 * - 工具结果回来时走的是 `messages[idx] = existing.copy(toolCall = …)`，列表长度不变、
 *   首尾 id 不变，而 TOOL 项的 `content` 恒为 `""`（长度恒 0）—— 六个 key 一个都不动，
 *   大纲于是继续返回**旧快照**：工具跑完了卡片还在转圈；
 * - 模型把正文回填给前面（非尾部）的 AGENT 气泡时同理，正文被缓存吃掉，
 *   直到有全新消息入队才突然闪出来。
 *
 * `messages` 是 `SnapshotStateList`，写入粒度是整个列表：任何一处就地更新都会让
 * `ChatScreen` 重组，所以把缓存键写成「精确的版本号」既无必要也无处可取（那需要把
 * 30+ 个写入口全部改一遍）。取 `remember` 的收益在流式期间本来就是零 ——
 * 那时尾部正文每个 token 都在变，旧 key 每次都会失效重算。因此这里直接每次重算，
 * 用**构造上正确**换掉那套启发式 key。重算成本与流式期间的开销同级，且已被
 * `conversation = olderMessages + messages` 的 O(n) 拼接摊在前面。
 *
 * 纯 JVM（不碰 android.*），因此可以按 `app/src/test/` 的约定直接单测。
 */
fun buildTurnOutlines(
    conversation: List<ChatItem>,
    isAgentBusy: Boolean
): List<TurnOutline> {
    val userIndices = mutableListOf<Int>()
    conversation.forEachIndexed { i, m ->
        if (m.role == MessageRole.USER) {
            userIndices.add(i)
        }
    }

    return userIndices.mapIndexed { turnIdx, uIdx ->
        val turnNum = turnIdx + 1
        val userMsg = conversation[uIdx]
        val nextUIdx = if (turnIdx + 1 < userIndices.size) userIndices[turnIdx + 1] else conversation.size
        val turnSlice = conversation.subList(uIdx + 1, nextUIdx)

        // 如果处于最后一轮且正在运行，按 pi-web 规范：未答复回合里正文只是过程的一部分
        val isCurrentRunning = (turnIdx == userIndices.lastIndex) && isAgentBusy

        // 本回合是否产生了生成式 UI 微组件。若**是**，流式正文要提成独立的最终答复行：
        // 折叠组排在微组件卡片**上方**，正文每多一行就把卡片往下推一行，
        // 真机上看到的就是「每输出一行，卡片跳一下」。
        val turnHasWidget = turnSlice.any { m ->
            m.toolCall?.let { showWidgetPayload(it) } != null
        }

        // 拆分规则见 [TurnPartition]：与 pi-web 一致（未答复不拆），且**绝不丢项** ——
        // 旧实现用 subList(0, finalAnsIdx) 把最终答复之后的过程项整段丢掉，
        // 表现就是「界面停在最后一句正文 + 正在等待模型」，而 PC 上还在继续跑。
        val split = TurnPartition.split(
            items = turnSlice,
            isCurrentRunning = isCurrentRunning,
            turnHasWidget = turnHasWidget
        )
        val finalAns: ChatItem? = split.finalAnswerIndex?.let { turnSlice[it] }
        val finalAnsIdx: Int? = split.finalAnswerIndex?.let { uIdx + 1 + it }
        val processItems: List<ChatItem> = split.processIndices.map { turnSlice[it] }

        var toolCount = 0
        turnSlice.forEach { m ->
            // `visualize_read_me` 是插件内部步骤（本身就不在聊天流里展示），
            // 继续把它算进「M 次工具调用」会让折叠组多报一次，与所见不符。
            if ((m.role == MessageRole.TOOL || m.toolCall != null) &&
                m.toolCall?.let { isGenerativeUiInternalTool(it) } != true
            ) {
                toolCount++
            }
        }

        // 生成式 UI 微组件是**内容**不是**过程**：从过程折叠组里剥出来，独立成行渲染
        // （排在过程组之后、最终答复之前）。不剥离的话，回合一旦答完，
        // ProcessDetailsGroup 自动收起，用户必须先展开那行灰字才看得见图。
        val (widgetItems, processOnly) = processItems.partition { item ->
            item.toolCall?.let { showWidgetPayload(it) } != null
        }

        val endTime = (finalAns ?: processItems.lastOrNull() ?: userMsg).timestamp
        val durationMs = if (endTime > userMsg.timestamp) endTime - userMsg.timestamp else null
        val headings = extractAssistantOutline(
            finalAns?.content ?: processItems.lastOrNull { it.role == MessageRole.AGENT }?.content
        )

        TurnOutline(
            turnNumber = turnNum,
            userIndex = uIdx,
            userMessage = userMsg,
            toolCount = toolCount,
            assistantIndex = finalAnsIdx,
            assistantHeadings = headings,
            processItems = processOnly,
            widgetItems = widgetItems,
            finalAnswerItem = finalAns,
            durationMs = durationMs,
            isRunning = isCurrentRunning
        )
    }
}
