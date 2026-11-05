package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Prompt 缓存命中率的回归测试：口径必须与上游 pi-web 一致。
 *
 * 上游 `components/AppShell.tsx` 的式子（标签「平均缓存命中率」）：
 *
 * ```ts
 * cacheRead / (cacheRead + cacheWrite + input) * 100
 * ```
 *
 * 三个数全部取自 `sessionStats.tokens`（会话累计）。本项目曾有两处偏离：
 *  - 分母漏掉 `cacheWrite`；
 *  - `input` 取最后一条 assistant 消息的 usage（单轮），而 `cacheRead` 取会话累计 ——
 *    混合口径的结果是「累计命中 ÷ 单轮输入」，任何有前缀缓存的会话都被钉在 99.99%，
 *    显示成顶格 100.0%，这个数字既不真实也不携带信息。
 */
class TokenStatsCacheRateTest {

    @Test
    fun `cache write belongs to the denominator like pi web`() {
        val stats = TokenStats(
            inputTokens = 1_000L,
            cacheReadTokens = 8_000L,
            cacheWriteTokens = 1_000L,
        )
        // 8000 / (8000 + 1000 + 1000) = 80.0%；漏掉 cacheWrite 会算成 88.9%
        assertEquals(80.0f, stats.cacheHitRate, 0.0001f)
    }

    @Test
    fun `cumulative inputs give a rate that is not pinned to 100 percent`() {
        // 截图里那场会话的真实量级：累计命中 27.5 M，累计输入约 46 K
        val stats = TokenStats(
            inputTokens = 46_000L,
            cacheReadTokens = 27_500_000L,
        )
        assertEquals(99.8329f, stats.cacheHitRate, 0.001f)
        assertTrue("累计口径下不应顶格 100%", stats.cacheHitRate < 99.99f)
        assertEquals("99.8%", format(stats.cacheHitRate))
    }

    @Test
    fun `mixing a per-turn input with a cumulative cache read always reads as 100 percent`() {
        // 旧实现喂进来的就是这两个数：单轮输入 169 + 累计 27.5 M。
        // 这里不断言旧写法（它已被替换），只把「为什么它必然显示 100.0%」钉成可执行的证据。
        val mixed = 27_500_000f / (27_500_000f + 169f) * 100f
        assertEquals(99.9994f, mixed, 0.001f)
        assertEquals("100.0%", format(mixed))
    }

    @Test
    fun `zero usage reports zero instead of dividing by zero`() {
        assertEquals(0f, TokenStats().cacheHitRate, 0.0001f)
        assertEquals(0f, TokenStats(cacheWriteTokens = 0L, inputTokens = 0L, cacheReadTokens = 0L).cacheHitRate, 0.0001f)
    }

    // ---- 汇总口径的回退：只能整体退，不能逐字段退 ----

    @Test
    fun `cumulative tokens win even when input is legitimately zero`() {
        // 整段 prompt 全命中缓存：stats.tokens.input 合法地为 0，cacheRead 为正。
        // 逐字段回退会把这个 0 换成单轮 input(169)，分母就成了「累计 + 单轮」的混合物。
        val (input, output) = TokenStats.resolveSummaryTokens(
            hasCumulativeTokens = true,
            cumulativeInput = 0L,
            cumulativeOutput = 0L,
            lastAssistantInput = 169L,
            lastAssistantOutput = 42L
        )
        assertEquals(0L, input)
        assertEquals(0L, output)
    }

    @Test
    fun `missing cumulative block falls back to the last assistant usage`() {
        val (input, output) = TokenStats.resolveSummaryTokens(
            hasCumulativeTokens = false,
            cumulativeInput = 0L,
            cumulativeOutput = 0L,
            lastAssistantInput = 1_400L,
            lastAssistantOutput = 260L
        )
        assertEquals(1_400L, input)
        assertEquals(260L, output)
    }

    @Test
    fun `cumulative values are what the ratio is computed from`() {
        val (input, output) = TokenStats.resolveSummaryTokens(
            hasCumulativeTokens = true,
            cumulativeInput = 46_000L,
            cumulativeOutput = 3_100L,
            lastAssistantInput = 169L,
            lastAssistantOutput = 42L
        )
        val stats = TokenStats(
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = 27_500_000L
        )
        assertEquals(99.8329f, stats.cacheHitRate, 0.001f)
    }

    @Test
    fun `negative inputs never leak into the summary`() {
        val (input, output) = TokenStats.resolveSummaryTokens(
            hasCumulativeTokens = true,
            cumulativeInput = -5L,
            cumulativeOutput = -1L,
            lastAssistantInput = 10L,
            lastAssistantOutput = 20L
        )
        assertEquals(0L, input)
        assertEquals(0L, output)
    }

    /** 与 HUD 里的 `String.format(Locale.US, "%.1f%%", stats.cacheHitRate)` 逐字一致 */
    private fun format(rate: Float): String = String.format(Locale.US, "%.1f%%", rate)
}
