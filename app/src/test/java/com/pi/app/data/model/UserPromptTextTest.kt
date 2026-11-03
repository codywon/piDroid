package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户提问正文的归一化：剥掉 pi 追加的图片尺寸提示行后再比较。
 *
 * 回归背景（v1.8.67 现场）：手机截图为 1220x2656，pi 会把它缩到 919x2000 并在**落盘的提问正文
 * 末尾**追加 `[Image: original 1220x2656, displayed at 919x2000. Multiply coordinates by 1.33 …]`。
 * 本机乐观气泡里只有用户原话，于是快照对账认不出这是同一条提问，把它当作在-flight 追加到列表
 * 末尾 —— 用户看到的就是「模型已经答完，下面又冒出一条我发的指令」。
 */
class UserPromptTextTest {

    private val sent = "如图，你现在用 FO-TR-S 标识，这样不清晰，请依然从汇聚总机柜画线连接"
    private val stored = sent +
        "\n\n[Image: original 1220x2656, displayed at 919x2000. " +
        "Multiply coordinates by 1.33 to map to original image.]"

    @Test
    fun `dimension note is stripped from the stored prompt`() {
        assertEquals(sent, UserPromptText.stripImageHints(stored))
    }

    @Test
    fun `multiple image hints are all stripped`() {
        val twoImages = sent +
            "\n[Image: original 1220x2656, displayed at 919x2000. Multiply coordinates by 1.33 to map to original image.]" +
            "\n[Image: original 2656x1220, displayed at 2000x919. Multiply coordinates by 1.33 to map to original image.]"

        assertEquals(sent, UserPromptText.stripImageHints(twoImages))
    }

    @Test
    fun `converted and omitted hints are stripped too`() {
        assertEquals("看图", UserPromptText.stripImageHints("看图 [Image converted from bmp to png.]"))
        assertEquals(
            "看图",
            UserPromptText.stripImageHints("看图\n[Image omitted: could not be resized below the inline image size limit.]")
        )
    }

    @Test
    fun `the sent prompt and the stored prompt are the same prompt`() {
        assertTrue(UserPromptText.samePrompt(sent, stored))
    }

    @Test
    fun `steer prefix does not break the comparison`() {
        assertTrue(UserPromptText.samePrompt("➔ [引导] 先跑测试", "先跑测试"))
    }

    @Test
    fun `different prompts are still different`() {
        assertFalse(UserPromptText.samePrompt(sent, "$sent 另外把图例也改一下"))
    }

    @Test
    fun `plain text without hints is untouched`() {
        val plain = "把 CAB-S-B22 的三根出线连到各排首柜"
        assertEquals(plain, UserPromptText.stripImageHints(plain))
        assertEquals(plain, UserPromptText.normalize(plain))
    }

    @Test
    fun `square brackets in normal prose are not treated as hints`() {
        val prose = "参考 [3.2 节] 的标注规则，[Image] 这个词只是我在说图片"
        assertEquals(prose, UserPromptText.stripImageHints(prose))
    }

    @Test
    fun `single blank lines inside the prompt survive`() {
        val multi = "第一段\n\n第二段"
        assertEquals(multi, UserPromptText.stripImageHints("$multi\n\n" +
            "[Image: original 800x600, displayed at 800x600. Multiply coordinates by 1.00 to map to original image.]"))
    }

    /**
     * 端到端回归：快照对账必须认领这条带图片的乐观提问，而不是把它追加到助手答复之后。
     */
    @Test
    fun `reconcile matches an optimistic prompt carrying an image note at the tail`() {
        val optimistic = ChatItem(id = "opt", role = MessageRole.USER, content = sent, timestamp = 10L)
        val answer = ChatItem(
            id = "ans",
            entryId = "e2",
            role = MessageRole.AGENT,
            content = "明白，不能只用 FO 编号和断开箭头表示来缆。",
            timestamp = 20L
        )
        val serverUser = ChatItem(
            id = "disk_u",
            entryId = "e1",
            role = MessageRole.USER,
            content = stored,
            timestamp = 11L
        )

        val merged = SessionSnapshotMerge.reconcileList(
            local = listOf(optimistic, answer),
            synced = listOf(serverUser, answer.copy(isStreaming = true)),
            effectiveRunning = true
        )

        // 顺序必须是「提问 → 答复」，且提问只出现一次（乐观气泡被服务端那条认领）。
        assertEquals(listOf(MessageRole.USER, MessageRole.AGENT), merged.map { it.role })
        assertEquals("e1", merged.first().entryId)
        assertEquals(2, merged.size)
    }
}
