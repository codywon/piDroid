package com.pi.app.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词增强的纯规则测试（JVM，无 Android 依赖）。
 *
 * 覆盖三件最容易静默出错的事：
 * 1. 哪些输入不允许增强；
 * 2. 一个返回结果是否还对应「当前草稿/分支/模型」；
 * 3. 上下文怎么裁剪。
 */
class PromptEnhancementRulesTest {

    // ── blockedReason ──────────────────────────────────────────────

    @Test
    fun `blank draft is blocked`() {
        assertEquals("先输入内容", PromptEnhancementRules.blockedReason("   "))
    }

    @Test
    fun `slash commands and shell prefixes are blocked`() {
        assertEquals("命令不参与增强", PromptEnhancementRules.blockedReason("/plan 继续"))
        assertEquals("shell 指令不参与增强", PromptEnhancementRules.blockedReason("! ls -la"))
    }

    @Test
    fun `unclosed code fence is blocked but a closed one is allowed`() {
        assertEquals("代码围栏未闭合", PromptEnhancementRules.blockedReason("```kotlin\nval a = 1"))
        assertNull(PromptEnhancementRules.blockedReason("```kotlin\nval a = 1\n```"))
    }

    @Test
    fun `normal draft is allowed and oversize draft is blocked`() {
        assertNull(PromptEnhancementRules.blockedReason("帮我看看这个报错，先给建议别改代码"))
        assertEquals("草稿超过 20 字上限", PromptEnhancementRules.blockedReason("x".repeat(21), maxDraftChars = 20))
    }

    // ── canApply ───────────────────────────────────────────────────

    private val target = PromptEnhancementTarget("conn-1", "provider-a", "model-a")
    private val ticket = PromptEnhancementTicket(
        requestId = "req-1",
        connectionKey = "conn-1",
        sessionId = "session-1",
        draftRevision = 3,
        branchRevision = 2,
        modelKey = target.modelKey
    )
    private val result = PromptEnhancementResult(
        requestId = "req-1",
        draftRevision = 3,
        branchRevision = 2,
        enhancedText = "增强后的指令",
        changed = true,
        provider = "provider-a",
        modelId = "model-a",
        contextUsed = true
    )

    private fun env(
        connectionKey: String = "conn-1",
        sessionId: String = "session-1",
        draftRevision: Long = 3,
        branchRevision: Long = 2,
        modelKey: String = target.modelKey
    ) = PromptEnhancementEnvironment(connectionKey, sessionId, draftRevision, branchRevision, modelKey)

    @Test
    fun `applies when every identity still matches`() {
        assertTrue(PromptEnhancementRules.canApply(ticket, result, env()))
    }

    @Test
    fun `stale draft revision is discarded`() {
        // 用户继续输入（包括编辑后又改回原文）都会推进修订号
        assertFalse(PromptEnhancementRules.canApply(ticket, result, env(draftRevision = 4)))
    }

    @Test
    fun `stale branch revision is discarded`() {
        assertFalse(PromptEnhancementRules.canApply(ticket, result, env(branchRevision = 3)))
    }

    @Test
    fun `other connection session or model is discarded`() {
        assertFalse(PromptEnhancementRules.canApply(ticket, result, env(connectionKey = "conn-2")))
        assertFalse(PromptEnhancementRules.canApply(ticket, result, env(sessionId = "session-2")))
        assertFalse(PromptEnhancementRules.canApply(ticket, result, env(modelKey = "provider-a:model-b")))
    }

    @Test
    fun `mismatched request id or blank result is discarded`() {
        assertFalse(PromptEnhancementRules.canApply(ticket, result.copy(requestId = "req-2"), env()))
        assertFalse(PromptEnhancementRules.canApply(ticket, result.copy(enhancedText = "   "), env()))
    }

    // ── canUndo ────────────────────────────────────────────────────

    @Test
    fun `undo only while the enhanced text is still in the composer`() {
        val undo = PromptEnhancementUndoState("conn-1", "原文", "增强后")
        assertTrue(PromptEnhancementRules.canUndo(undo, "增强后", "conn-1"))
        assertFalse(PromptEnhancementRules.canUndo(undo, "增强后 + 我又写了一行", "conn-1"))
        assertFalse(PromptEnhancementRules.canUndo(undo, "增强后", "conn-2"))
        assertFalse(PromptEnhancementRules.canUndo(null, "增强后", "conn-1"))
        assertFalse(
            PromptEnhancementRules.canUndo(
                PromptEnhancementUndoState("conn-1", "一样", "一样"),
                "一样",
                "conn-1"
            )
        )
    }

    // ── selectContext ──────────────────────────────────────────────

    private fun context() = listOf(
        PromptEnhancementContextMessage("user", "第一轮的需求"),
        PromptEnhancementContextMessage("assistant", "第一轮的回答"),
        PromptEnhancementContextMessage("user", "   "),
        PromptEnhancementContextMessage("assistant", "y".repeat(900)),
        PromptEnhancementContextMessage("user", "最后一轮的需求")
    )

    @Test
    fun `keeps the newest messages, drops blanks and clips long ones`() {
        val picked = PromptEnhancementRules.selectContext(context())
        assertEquals("最后一轮的需求", picked.last().text)
        assertTrue(picked.none { it.text.isBlank() })
        assertTrue(picked.all { it.role == "user" || it.role == "assistant" })
        assertTrue(picked.any { it.text.length in 1..801 && it.text.endsWith("…") })
    }

    @Test
    fun `respects message count and total character budgets`() {
        assertEquals(2, PromptEnhancementRules.selectContext(context(), maxMessages = 2).size)
        assertTrue(
            PromptEnhancementRules.selectContext(context(), maxTotalChars = 10)
                .all { it.text.length <= 10 }
        )
        assertTrue(PromptEnhancementRules.selectContext(context(), maxMessages = 0).isEmpty())
        assertTrue(PromptEnhancementRules.selectContext(context(), maxTotalChars = 0).isEmpty())
    }

    // ── capability parsing ─────────────────────────────────────────

    @Test
    fun `parses capability payload from the gateway`() {
        val cap = PromptEnhancementCapabilityParser.parse(
            """{"status":"ok","capabilities":{"promptEnhancement":{"version":1,"available":true,"maxDraftChars":6000,"maxContextChars":4000}}}"""
        )
        assertEquals(1, cap?.version)
        assertTrue(cap?.available == true)
        assertEquals(6000, cap?.maxDraftChars)
        assertEquals(4000, cap?.maxContextChars)
    }

    @Test
    fun `missing or malformed capability payload yields null`() {
        assertNull(PromptEnhancementCapabilityParser.parse(null))
        assertNull(PromptEnhancementCapabilityParser.parse(""))
        assertNull(PromptEnhancementCapabilityParser.parse("OK"))
        assertNull(PromptEnhancementCapabilityParser.parse("""{"status":"ok"}"""))
        // available 缺失时按不可用处理，预算回落到本地默认值
        val partial = PromptEnhancementCapabilityParser.parse(
            """{"capabilities":{"promptEnhancement":{"version":1}}}"""
        )
        assertEquals(false, partial?.available)
        assertEquals(PromptEnhancementRules.MAX_DRAFT_CHARS, partial?.maxDraftChars)
    }
}
