package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对树分支回退导航 (navigate_tree / Edit from here) 及快照同步数据流的单元测试，
 * 1:1 对齐 pi-web 的行为逻辑。
 */
class SessionTreeNavigationTest {

    private fun isSameChatContent(a: ChatItem, b: ChatItem): Boolean {
        return a.role == b.role &&
            a.content == b.content &&
            a.thinking == b.thinking &&
            a.isStreaming == b.isStreaming &&
            a.toolCall == b.toolCall &&
            a.usageSummary == b.usageSummary
    }

    private fun mergeItem(localItem: ChatItem, diskItem: ChatItem): ChatItem {
        return if (!isSameChatContent(localItem, diskItem)) {
            val keepId = if (localItem.role == diskItem.role) localItem.id else diskItem.id
            val keepTs = if (localItem.role == diskItem.role) localItem.timestamp else diskItem.timestamp
            diskItem.copy(id = keepId, timestamp = keepTs)
        } else if (localItem.entryId.isNullOrBlank() && !diskItem.entryId.isNullOrBlank()) {
            localItem.copy(entryId = diskItem.entryId)
        } else {
            localItem
        }
    }

    private fun attachEntryIds(msgs: JsonArray?, ids: JsonArray?) {
        if (msgs == null || ids == null) return
        val count = minOf(msgs.size(), ids.size())
        for (i in 0 until count) {
            val m = msgs.get(i)
            val id = ids.get(i)?.takeIf { !it.isJsonNull }?.asString
            if (m != null && m.isJsonObject && !id.isNullOrBlank()) {
                val obj = m.asJsonObject
                if (!obj.has("entryId")) {
                    obj.addProperty("entryId", id)
                }
            }
        }
    }

    @Test
    fun `attachEntryIds correctly pairs parallel context messages with entryIds`() {
        val msgs = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "hello")
            })
            add(JsonObject().apply {
                addProperty("role", "assistant")
                addProperty("content", "hi")
            })
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
    fun `snapshot reconciliation backfills missing entryId for optimistic user message`() {
        val localUserMsg = ChatItem(
            id = "local_1",
            entryId = null, // 本地刚发送，尚未分配 entryId
            role = MessageRole.USER,
            content = "测试提问"
        )
        val diskUserMsg = ChatItem(
            id = "disk_1",
            entryId = "entry_u1", // 服务端快照已落盘条目 ID
            role = MessageRole.USER,
            content = "测试提问"
        )

        val merged = mergeItem(localUserMsg, diskUserMsg)

        assertEquals("local_1", merged.id)
        assertEquals("entry_u1", merged.entryId)
        assertEquals("测试提问", merged.content)
    }

    @Test
    fun `branch navigation truncates subsequent process and tool outputs from chat messages`() {
        // 模拟用户提问 -> 工具执行 -> 助手回复中途停止 -> 从提问处编辑切分支场景
        val localList = mutableListOf(
            ChatItem(id = "1", entryId = "entry_u1", role = MessageRole.USER, content = "第一句提问"),
            ChatItem(id = "2", entryId = "entry_t1", role = MessageRole.TOOL, content = "正在执行 bash 脚本..."),
            ChatItem(id = "3", entryId = "entry_a1", role = MessageRole.AGENT, content = "部分执行输出", isStreaming = false)
        )

        // 服务端执行 navigate_tree 回退分支后，快照只返回该提问之前（此处为根节点，空列表）
        val syncedAfterBranching = emptyList<ChatItem>()

        val effectiveRunning = false
        if (syncedAfterBranching.isNotEmpty() || !effectiveRunning) {
            for (i in syncedAfterBranching.indices) {
                val localItem = localList[i]
                val diskItem = syncedAfterBranching[i]
                localList[i] = mergeItem(localItem, diskItem)
            }
            while (localList.size > syncedAfterBranching.size) {
                localList.removeAt(localList.lastIndex)
            }
        }

        // 截断后，原先残留的工具输出与中断输出全部消失
        assertTrue(localList.isEmpty())
    }

    @Test
    fun `branch navigation on second turn keeps first turn and clears subsequent process outputs`() {
        // 两轮对话场景：第一轮已完成，第二轮提问后产生了过程输出并停止，在第二轮提问处编辑
        val localList = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "3", entryId = "u2", role = MessageRole.USER, content = "第二轮提问"),
            ChatItem(id = "4", entryId = "t2", role = MessageRole.TOOL, content = "第二轮命令执行输出..."),
            ChatItem(id = "5", entryId = "a2", role = MessageRole.AGENT, content = "第二轮半截回答")
        )

        // 服务端把 leaf 回退到 u2 的 parent（即 a1）
        val synced = listOf(
            ChatItem(id = "disk_1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "disk_2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答")
        )

        val effectiveRunning = false
        if (synced.isNotEmpty() || !effectiveRunning) {
            for (i in synced.indices) {
                val localItem = localList[i]
                val diskItem = synced[i]
                localList[i] = mergeItem(localItem, diskItem)
            }
            while (localList.size > synced.size) {
                localList.removeAt(localList.lastIndex)
            }
        }

        // 验证：第一轮对话完整保留，第二轮提问及所有过程输出已被彻底清除
        assertEquals(2, localList.size)
        assertEquals("第一轮提问", localList[0].content)
        assertEquals("第一轮回答", localList[1].content)
    }
}
