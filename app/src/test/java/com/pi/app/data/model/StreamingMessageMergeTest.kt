package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归用例：重连补发的整条 assistant message 快照**不得**在工具卡后面再追加一份正文。
 *
 * 现场（piDroid 1.7.36）：过程输出里同一段正文出现两次，形状固定为
 * `正文 → 工具卡 → 同一段正文`。根因是 pi 每次 SSE 连上都会补发
 * `message_start{message: streamingMessage}`（整条快照），而旧实现只在
 * 「列表最后一条是 AGENT」时才覆盖，尾部是工具卡时走新开气泡分支。
 */
class StreamingMessageMergeTest {

    private fun user(text: String = "开始干活") =
        ChatItem(role = MessageRole.USER, content = text)

    private fun agent(
        text: String,
        messageId: String? = null,
        streaming: Boolean = false
    ) = ChatItem(
        role = MessageRole.AGENT,
        content = text,
        isStreaming = streaming,
        streamMessageId = messageId
    )

    private fun tool(callId: String = "tc1") = ChatItem(
        role = MessageRole.TOOL,
        content = "",
        toolCall = ToolCallInfo(toolCallId = callId, toolName = "bash", isRunning = true)
    )

    @Test
    fun `重连快照落在工具卡之前的那条正文上，且判定为幂等`() {
        val body = "快照完成 (env sha256 ffd20cd8…)。现在步骤 2：分两小步注入开关。"
        val messages = listOf(user(), agent(body, messageId = "ms1"), tool())

        val update = StreamingMessageMerge.resolveTextUpdate(messages, "ms1", body)

        assertTrue("同样的快照必须判定为空操作，否则就会在工具卡后面多出一条", update.noop)
    }

    @Test
    fun `没有任何消息身份时，尾部工具卡也不会新开重复气泡`() {
        val body = "开关已注入（唯一一行）。先重建 worker 容器并验证。"
        // 老协议：气泡没被标记身份（streamMessageId = null）
        val messages = listOf(user(), agent(body), tool("tc2"))

        val update = StreamingMessageMerge.resolveTextUpdate(messages, null, body)

        assertTrue(update.noop)
    }

    @Test
    fun `断流丢增量后，快照补齐全量正文仍写回同一条气泡`() {
        val partial = "开关已注入（唯"
        val full = "开关已注入（唯一一行）。先重建 worker 容器并验证。"
        val messages = listOf(user(), agent(partial, messageId = "ms1"), tool())

        val update = StreamingMessageMerge.resolveTextUpdate(messages, "ms1", full)

        assertFalse(update.noop)
        assertEquals(1, update.index)
        assertEquals(full, update.content)
    }

    @Test
    fun `本地正文比快照更新时绝不回退`() {
        val local = "正在执行 docker compose build ..."
        val snapshot = "正在执行 docker"
        val messages = listOf(user(), agent(local, messageId = "ms1"))

        val update = StreamingMessageMerge.resolveTextUpdate(messages, "ms1", snapshot)

        assertTrue(update.noop)
    }

    @Test
    fun `本轮还没有这条消息的气泡时新建一条`() {
        val messages = listOf(user(), agent("上一条消息", messageId = "ms1"))

        val update = StreamingMessageMerge.resolveTextUpdate(messages, "ms2", "新消息的第一段正文")

        assertFalse(update.noop)
        assertNull(update.index)
        assertEquals("新消息的第一段正文", update.content)
    }

    @Test
    fun `一条消息被拆成两块正文时，整条快照不会把第一块重复写进第二块`() {
        val block0 = "先看容器状态。"
        val block1 = "worker 已重启完成。"
        val messages = listOf(
            user(),
            agent(block0, messageId = "ms1"),
            tool(),
            agent(block1, messageId = "ms1")
        )

        val update = StreamingMessageMerge.resolveTextUpdate(messages, "ms1", "$block0\n$block1")

        assertTrue("两块正文合起来已覆盖快照，重复补发必须空操作", update.noop)
    }

    @Test
    fun `多块正文收到追加的新快照时只补最后一块的增量`() {
        val block0 = "先看容器状态。"
        val block1 = "worker 已重启"
        val messages = listOf(
            user(),
            agent(block0, messageId = "ms1"),
            tool(),
            agent(block1, messageId = "ms1")
        )

        val update = StreamingMessageMerge.resolveTextUpdate(
            messages,
            "ms1",
            "$block0\n$block1，容器状态 healthy。"
        )

        assertFalse(update.noop)
        assertEquals(3, update.index)
        assertEquals("$block1，容器状态 healthy。", update.content)
    }

    @Test
    fun `正文增量写回在途消息的气泡，即使工具卡在尾部`() {
        val messages = listOf(user(), agent("快照完成", messageId = "ms1"), tool())

        val index = StreamingMessageMerge.resolveDeltaIndex(messages, "ms1")

        assertEquals(1, index)
    }

    @Test
    fun `正文增量不会串写到别的消息的气泡上`() {
        val messages = listOf(
            user(),
            agent("上一条消息的正文", messageId = "ms1"),
            tool(),
            agent("当前消息的正文", messageId = "ms2")
        )

        val index = StreamingMessageMerge.resolveDeltaIndex(messages, "ms2")

        assertEquals(3, index)
    }

    @Test
    fun `没有消息身份时增量沿用尾部语义，尾部是工具卡则新开气泡`() {
        val messages = listOf(user(), agent("正文"), tool())

        assertEquals(-1, StreamingMessageMerge.resolveDeltaIndex(messages, null))
        // 尾部就是 AGENT 气泡 → 直接续写它（下标 = 最后一个元素，即 size - 1）
        assertEquals(1, StreamingMessageMerge.resolveDeltaIndex(listOf(user(), agent("正文")), null))
    }

    @Test
    fun `上一轮的消息不会被本轮快照认领`() {
        // 上一条消息的正文恰好是本轮快照的前缀：本轮作用域必须拦住跨轮命名碰撞
        val messages = listOf(
            user("第一轮"),
            agent("部署完成", messageId = "ms1"),
            user("第二轮"),
            agent("部署完成后我又", messageId = "ms2")
        )

        val index = StreamingMessageMerge.resolveDeltaIndex(messages, "ms3")

        assertEquals(-1, index)
    }
}
