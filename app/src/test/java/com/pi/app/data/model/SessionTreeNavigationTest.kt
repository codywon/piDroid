package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话树分支回退（navigate_tree / 「从此处编辑」）数据流回归测试。
 *
 * 守护三件事：
 * 1) 服务端 `context.entryIds` 必须正确绑定到平行数组 `context.messages` 上（否则「从此处编辑」拿不到 entryId）；
 * 2) 快照对账必须为本地乐观消息补齐 entryId，同时保持 LazyColumn key 稳定；
 * 3) 分支回退 / 权威替换后，被放弃分支上的工具输出与中断过程必须从列表里彻底消失。
 */
class SessionTreeNavigationTest {

    /** 与 PiApiClient.attachEntryIds 同语义的纯函数（生产代码在 PiApiClient 私有方法内，此处复刻契约做守护）。 */
    private fun attachEntryIds(msgs: JsonArray?, ids: JsonArray?) {
        if (msgs == null || ids == null) return
        val count = minOf(msgs.size(), ids.size())
        for (i in 0 until count) {
            val m = msgs.get(i)
            val id = ids.get(i)?.takeIf { !it.isJsonNull }?.asString
            if (m != null && m.isJsonObject && !id.isNullOrBlank()) {
                val obj = m.asJsonObject
                if (!obj.has("entryId")) obj.addProperty("entryId", id)
            }
        }
    }

    /** 复刻 MainActivity 中 synced 的合并/截断编排（replaceHistory = false 的增量路径）。 */
    private fun incrementalSync(
        local: MutableList<ChatItem>,
        synced: List<ChatItem>,
        effectiveRunning: Boolean
    ) {
        val reconciled = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning)
        for (i in reconciled.indices) {
            if (i < local.size) {
                if (local[i] != reconciled[i]) {
                    local[i] = reconciled[i]
                }
            } else {
                local.add(reconciled[i])
            }
        }
        while (local.size > reconciled.size) {
            local.removeAt(local.lastIndex)
        }
    }

    /** 复刻 replaceHistory = true 的权威替换路径（pi-web 的 loadSession）。 */
    private fun authoritativeSync(local: MutableList<ChatItem>, synced: List<ChatItem>) {
        local.clear()
        local.addAll(synced)
    }

    @Test
    fun `attachEntryIds pairs parallel context messages with entryIds`() {
        val msgs = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", "hello") })
            add(JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "hi") })
        }
        val ids = JsonArray().apply {
            add("entry_user_1")
            add("entry_assistant_1")
        }

        attachEntryIds(msgs, ids)

        assertEquals("entry_user_1", msgs.get(0).asJsonObject.get("entryId").asString)
        assertEquals("entry_assistant_1", msgs.get(1).asJsonObject.get("entryId").asString)
    }

    @Test
    fun `attachEntryIds tolerates mismatched lengths without crashing`() {
        val msgs = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "user") })
            add(JsonObject().apply { addProperty("role", "assistant") })
        }
        val ids = JsonArray().apply { add("only_one") }

        attachEntryIds(msgs, ids)

        assertEquals("only_one", msgs.get(0).asJsonObject.get("entryId").asString)
        assertNull(msgs.get(1).asJsonObject.get("entryId"))
    }

    @Test
    fun `merge backfills entryId for optimistic user message and keeps local id`() {
        val local = ChatItem(id = "local_1", entryId = null, role = MessageRole.USER, content = "测试提问")
        val disk = ChatItem(id = "disk_1", entryId = "u1", role = MessageRole.USER, content = "测试提问")

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals("local_1", merged.id)
        assertEquals("u1", merged.entryId)
    }

    @Test
    fun `merge preserves local media attachments when optimistic user message is backfilled with entryId`() {
        val localMedia = listOf(
            MediaAttachment(id = "loc_img_1", kind = MediaKind.IMAGE, mimeType = "image/png", dataBase64 = "base64payload", title = "screenshot.png")
        )
        val diskMedia = listOf(
            MediaAttachment(id = "srv_img_1", kind = MediaKind.IMAGE, mimeType = "image/png", dataBase64 = "base64payload", title = "图片附件")
        )
        val local = ChatItem(id = "local_1", entryId = null, role = MessageRole.USER, content = "测试附图", mediaList = localMedia)
        val disk = ChatItem(id = "disk_1", entryId = "u1", role = MessageRole.USER, content = "测试附图", mediaList = diskMedia)

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals("local_1", merged.id)
        assertEquals("u1", merged.entryId)
        assertEquals(1, merged.mediaList.size)
        // 保持本地稳定的附件 ID 和原始文件名，避免 Compose 闪烁重组
        assertEquals("loc_img_1", merged.mediaList[0].id)
        assertEquals("screenshot.png", merged.mediaList[0].title)
    }

    @Test
    fun `merge takes server media attachments when local message had none`() {
        val diskMedia = listOf(
            MediaAttachment(id = "srv_img_1", kind = MediaKind.IMAGE, mimeType = "image/png", dataBase64 = "remote_img_data", title = "图片附件")
        )
        val local = ChatItem(id = "local_1", entryId = "u1", role = MessageRole.USER, content = "测试附图", mediaList = emptyList())
        val disk = ChatItem(id = "disk_1", entryId = "u1", role = MessageRole.USER, content = "测试附图", mediaList = diskMedia)

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals(1, merged.mediaList.size)
        assertEquals("remote_img_data", merged.mediaList[0].dataBase64)
    }

    @Test
    fun `merge retains local media if server snapshot media is temporarily empty`() {
        val localMedia = listOf(
            MediaAttachment(id = "loc_img_1", kind = MediaKind.IMAGE, mimeType = "image/png", dataBase64 = "local_payload", title = "photo.jpg")
        )
        val local = ChatItem(id = "local_1", entryId = null, role = MessageRole.USER, content = "带图发送", mediaList = localMedia)
        val disk = ChatItem(id = "disk_1", entryId = "u1", role = MessageRole.USER, content = "带图发送", mediaList = emptyList())

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals(1, merged.mediaList.size)
        assertEquals("local_payload", merged.mediaList[0].dataBase64)
    }

    @Test
    fun `merge recognizes line-wrapped and prefixed base64 media as identical content`() {
        // 模拟一个大图 Base64：unwrapped (本地 NO_WRAP) vs wrapped (服务端带 \n 换行且带 data:image/png;base64, 前缀)
        val rawChunk = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val unwrappedB64 = rawChunk.repeat(20) // 约 2KB
        val wrappedB64 = "data:image/png;base64," + unwrappedB64.chunked(64).joinToString("\r\n")

        val localMedia = listOf(
            MediaAttachment(id = "loc_1", kind = MediaKind.IMAGE, mimeType = "image/png", dataBase64 = unwrappedB64, title = "test.png")
        )
        val diskMedia = listOf(
            MediaAttachment(id = "srv_1", kind = MediaKind.IMAGE, mimeType = "image/png", dataBase64 = wrappedB64, title = "图片附件")
        )

        val local = ChatItem(id = "l1", entryId = "u1", role = MessageRole.USER, content = "看图", mediaList = localMedia)
        val disk = ChatItem(id = "d1", entryId = "u1", role = MessageRole.USER, content = "看图", mediaList = diskMedia)

        // 验证两份 Base64 虽有前缀和大量回车换行，实质内容判定为相同
        assertTrue(SessionSnapshotMerge.isSameChatContent(local, disk))

        val merged = SessionSnapshotMerge.mergeItem(local, disk)
        // 保持本地实例，避免轮询重复触发 Compose 重绘
        assertSame(local, merged)
    }

    @Test
    fun `merge without entryId keeps the very same instance (no needless recompose)`() {
        val local = ChatItem(id = "l", entryId = "u1", role = MessageRole.USER, content = "x")
        val disk = ChatItem(id = "d", entryId = "u1", role = MessageRole.USER, content = "x")
        assertSame(local, SessionSnapshotMerge.mergeItem(local, disk))
    }

    @Test
    fun `mergeContentChange takes server text but keeps local key when role matches`() {
        val local = ChatItem(id = "l", role = MessageRole.AGENT, content = "半截", isStreaming = true, timestamp = 100L)
        val disk = ChatItem(id = "d", role = MessageRole.AGENT, content = "完整回复", isStreaming = false, timestamp = 200L)

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals("完整回复", merged.content)
        assertEquals("l", merged.id)
        assertEquals(100L, merged.timestamp)
    }

    @Test
    fun `editing the first turn truncates everything when branch goes back to root`() {
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一句提问"),
            ChatItem(id = "2", entryId = "t1", role = MessageRole.TOOL, content = "bash 输出..."),
            ChatItem(id = "3", entryId = "a1", role = MessageRole.AGENT, content = "半截回答")
        )

        // leaf 回到根：服务端活跃分支为空
        authoritativeSync(local, emptyList())

        assertTrue(local.isEmpty())
    }

    @Test
    fun `editing the second turn drops that turn's process output but keeps turn one`() {
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "3", entryId = "u2", role = MessageRole.USER, content = "第二轮提问"),
            ChatItem(id = "4", entryId = "t2", role = MessageRole.TOOL, content = "第二轮命令输出..."),
            ChatItem(id = "5", entryId = "a2", role = MessageRole.AGENT, content = "第二轮半截回答")
        )
        // leaf 回退到 u2 的父节点 a1
        val synced = listOf(
            ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "h2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答")
        )

        authoritativeSync(local, synced)

        assertEquals(2, local.size)
        assertEquals("第一轮提问", local[0].content)
        assertEquals("第一轮回答", local[1].content)
    }

    @Test
    fun `authoritative sync also discards stale items even when locally marked running`() {
        // 回归：若沿用增量路径，isStreaming 尾部的 inFlightTail 会把旧过程重新拼回来
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "中断输出", isStreaming = true)
        )

        authoritativeSync(local, listOf(ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "提问")))

        assertEquals(1, local.size)
        assertEquals("提问", local[0].content)
    }

    @Test
    fun `incremental sync keeps streaming tail while the run is still going`() {
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "流式中", isStreaming = true)
        )
        val synced = listOf(ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "提问"))

        incrementalSync(local, synced, effectiveRunning = true)

        assertEquals(2, local.size)
    }

    @Test
    fun `incremental sync preserves optimistic user message when server snapshot is behind even if idle`() {
        // 用户刚提交提问，服务端快照（synced）尚未落盘记录该提问，无论当前判定是否 running，绝不可抹杀提问
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "local_opt_u2", entryId = null, role = MessageRole.USER, content = "第二轮提问(乐观上屏)")
        )
        val synced = listOf(
            ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "h2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答")
        )

        incrementalSync(local, synced, effectiveRunning = false)

        assertEquals(3, local.size)
        assertEquals("第二轮提问(乐观上屏)", local[2].content)
        assertEquals("local_opt_u2", local[2].id)
    }

    @Test
    fun `incremental sync preserves both optimistic user message and streaming tail when server snapshot is behind`() {
        // 用户提交提问后模型立即开始 SSE 流式输出，此时 local 有 2 条未落盘项（提问 + 流式回答），快照仍落后 2 条
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "local_opt_u2", entryId = null, role = MessageRole.USER, content = "第二轮提问"),
            ChatItem(id = "stream_a2", entryId = null, role = MessageRole.AGENT, content = "正在流式思考并回答...", isStreaming = true)
        )
        val synced = listOf(
            ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "h2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答")
        )

        incrementalSync(local, synced, effectiveRunning = true)

        assertEquals(4, local.size)
        assertEquals("第二轮提问", local[2].content)
        assertEquals("正在流式思考并回答...", local[3].content)
        assertTrue(local[3].isStreaming)
    }

    @Test
    fun `incremental sync merges optimistic user message into server user message once server catches up`() {
        // 服务端 snapshot 随后追赶上来，本地乐观消息无缝匹配，回填 entryId 并保留本地 id 与流式状态
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "local_opt_u2", entryId = null, role = MessageRole.USER, content = "第二轮提问"),
            ChatItem(id = "stream_a2", entryId = null, role = MessageRole.AGENT, content = "流式文字", isStreaming = true)
        )
        val synced = listOf(
            ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "h2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "srv_u2", entryId = "entry_u2", role = MessageRole.USER, content = "第二轮提问")
        )

        incrementalSync(local, synced, effectiveRunning = true)

        assertEquals(4, local.size)
        assertEquals("local_opt_u2", local[2].id)
        assertEquals("entry_u2", local[2].entryId)
        assertEquals("第二轮提问", local[2].content)
        assertEquals("流式文字", local[3].content)
    }

    @Test
    fun `incremental sync gives the server copy to the newest identical prompt, not a stale one`() {
        // 回归：压缩开头会 `await this.abort()`，被丢掉的那次提问会留下一条 entryId 永远为空的
        // 乐观气泡（#PendingPromptSettlement 负责按超时撤回）。用户后来又发了同样的文本
        // （「继续」这类高频短句最常见），服务端只落盘了一条 —— 必须由**新**气泡认领，
        // 旧气泡留在原地等待撤回；否则新气泡没有归属、被追加到列表尾部，界面就是“同一条指令两遍”。
        val local = mutableListOf(
            ChatItem(id = "stale", entryId = null, role = MessageRole.USER, content = "继续"),
            ChatItem(id = "a_prev", entryId = "a_prev", role = MessageRole.AGENT, content = "上一轮回答"),
            ChatItem(id = "fresh", entryId = null, role = MessageRole.USER, content = "继续")
        )
        val synced = listOf(
            ChatItem(id = "h_prev_q", entryId = "e_prev", role = MessageRole.USER, content = "上一轮提问"),
            ChatItem(id = "h_prev_a", entryId = "a_prev", role = MessageRole.AGENT, content = "上一轮回答"),
            ChatItem(id = "h_new", entryId = "e_new", role = MessageRole.USER, content = "继续")
        )

        incrementalSync(local, synced, effectiveRunning = false)

        assertEquals(4, local.size)
        // 服务端那条归最新的气泡，entryId 被正确回填
        assertEquals("e_new", local.first { it.id == "fresh" }.entryId)
        // 陈旧气泡仍未被认领（下一步由 PendingPromptSettlement 撤回）
        assertNull(local.first { it.id == "stale" }.entryId)
    }

    @Test
    fun `incremental sync matches uncommitted tool and agent without duplicate bubbles`() {
        // 本地包含正在执行的工具和流式助手消息，服务端快照追赶上后，正确匹配并回填 entryId，不产生重复条目
        val toolCall = ToolCallInfo(toolCallId = "call_abc", toolName = "bash", command = "ls -la", isRunning = false)
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "执行命令"),
            ChatItem(id = "local_tool", entryId = null, role = MessageRole.TOOL, content = "输出", toolCall = toolCall),
            ChatItem(id = "local_a1", entryId = null, role = MessageRole.AGENT, content = "执行完毕", isStreaming = true)
        )
        val serverToolCall = ToolCallInfo(toolCallId = "call_abc", toolName = "bash", command = "ls -la", isRunning = false)
        val synced = listOf(
            ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "执行命令"),
            ChatItem(id = "srv_t1", entryId = "entry_t1", role = MessageRole.TOOL, content = "输出", toolCall = serverToolCall),
            ChatItem(id = "srv_a1", entryId = "entry_a1", role = MessageRole.AGENT, content = "执行完毕", isStreaming = false)
        )

        incrementalSync(local, synced, effectiveRunning = false)

        assertEquals(3, local.size)
        assertEquals("local_tool", local[1].id)
        assertEquals("entry_t1", local[1].entryId)
        assertEquals("local_a1", local[2].id)
        assertEquals("entry_a1", local[2].entryId)
    }

    @Test
    fun `isSameBase64 handles tab-indented and multi-space base64 strings`() {
        val b64A = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAE=\n\t\r "
        val b64B = "  iVBORw0KGgoAAAANSUhEUgAAAAE=  "

        val localMedia = listOf(MediaAttachment(id = "1", dataBase64 = b64A))
        val diskMedia = listOf(MediaAttachment(id = "2", dataBase64 = b64B))

        val local = ChatItem(id = "l1", role = MessageRole.USER, content = "看图", mediaList = localMedia)
        val disk = ChatItem(id = "d1", role = MessageRole.USER, content = "看图", mediaList = diskMedia)

        assertTrue(SessionSnapshotMerge.isSameChatContent(local, disk))
    }

    @Test
    fun `isSameMediaAttachment normalizes Windows backslash and forward slash path separators`() {
        val mediaA = listOf(MediaAttachment(id = "1", filePath = "D:\\workspace\\project\\image.png", mimeType = "image/png"))
        val mediaB = listOf(MediaAttachment(id = "2", filePath = "D:/workspace/project/image.png", mimeType = "image/png"))

        val local = ChatItem(id = "l1", role = MessageRole.USER, content = "文件", mediaList = mediaA)
        val disk = ChatItem(id = "d1", role = MessageRole.USER, content = "文件", mediaList = mediaB)

        assertTrue(SessionSnapshotMerge.isSameChatContent(local, disk))
    }

    @Test
    fun `incremental sync preserves streamMessageId mediaList and duration when merging streaming agent`() {
        val localMedia = listOf(MediaAttachment(id = "loc_img", mimeType = "image/png", dataBase64 = "abc"))
        val local = mutableListOf(
            ChatItem(
                id = "agent_stream_1",
                role = MessageRole.AGENT,
                content = "流式长文本",
                thinking = "深度思考中...",
                thinkingDurationSec = 10,
                modelName = "claude-3-7-sonnet",
                isStreaming = true,
                streamMessageId = "msg_stream_42",
                mediaList = localMedia
            )
        )
        val synced = listOf(
            ChatItem(
                id = "disk_agent_1",
                entryId = "entry_agent_42",
                role = MessageRole.AGENT,
                content = "流式短文本",
                thinking = "深度思考",
                thinkingDurationSec = 12,
                modelName = "claude-3-7-sonnet",
                isStreaming = false,
                mediaList = emptyList()
            )
        )

        incrementalSync(local, synced, effectiveRunning = true)

        assertEquals(1, local.size)
        val result = local[0]
        assertEquals("agent_stream_1", result.id)
        assertEquals("entry_agent_42", result.entryId)
        assertEquals("msg_stream_42", result.streamMessageId) // 保护本地在途身份
        assertEquals("流式长文本", result.content) // 保留本地更新的内容
        assertEquals("深度思考中...", result.thinking)
        assertEquals(12, result.thinkingDurationSec) // 采纳服务端更新的耗时
        assertEquals("claude-3-7-sonnet", result.modelName)
        assertEquals(1, result.mediaList.size) // 保护本地媒体附件
        assertTrue(result.isStreaming)
    }

    @Test
    fun `mergeItem updates thinkingDurationSec and modelName when populated from server`() {
        val local = ChatItem(id = "l1", role = MessageRole.AGENT, content = "回答完毕", thinking = "思考", thinkingDurationSec = null, modelName = null)
        val disk = ChatItem(id = "d1", role = MessageRole.AGENT, content = "回答完毕", thinking = "思考", thinkingDurationSec = 15, modelName = "gemini-flash")

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals("l1", merged.id)
        assertEquals(15, merged.thinkingDurationSec)
        assertEquals("gemini-flash", merged.modelName)
    }
}
