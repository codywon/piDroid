package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文 token 统计「不造假」的回归测试。
 *
 * 背景：`PiApiClient` 旧实现有一条为了「看起来不爆表」而编造数值的分支 ——
 * 当会话累计输入超过模型窗口时返回 `inTokens % 80000`（下限 24000）：
 *
 * ```kotlin
 * } else if (inTokens > effectiveMaxContext) {
 *     (inTokens % 80000L).coerceAtLeast(24000L)   // 真实 340K 会被显示成 20K
 * }
 * ```
 *
 * 这既让用户看不到自己已经超窗（并发继续堆上下文），也让排查「上下文怎么爆的」
 * 失去依据。现在只允许两个真实来源：
 *  - 最后一条 assistant 消息的 usage（`input + cacheRead`）= 此刻上下文大小；
 *  - 会话累计输入 `stats.tokens.input`（前者缺失时的退路）。
 *
 * 超窗则如实显示「已超窗」，不再把百分比说成 100%。
 */
class TokenStatsContextTest {

    @Test
    fun `authoritative context wins over cumulative input`() {
        assertEquals(
            128_000L,
            TokenStats.resolveContextTokens(authoritativeContextTokens = 128_000L, fallbackInputTokens = 9_999_999L)
        )
    }

    @Test
    fun `cumulative input is used only when the authoritative value is missing`() {
        assertEquals(
            300_000L,
            TokenStats.resolveContextTokens(authoritativeContextTokens = 0L, fallbackInputTokens = 300_000L)
        )
        // 负数（脏数据）夹到 0，不能变成“负的上下文”
        assertEquals(
            0L,
            TokenStats.resolveContextTokens(authoritativeContextTokens = -1L, fallbackInputTokens = -5L)
        )
    }

    @Test
    fun `over window values are reported as is instead of modulo faked`() {
        // 旧实现的 340_000 % 80_000 = 20_000，会被显示成 20K/200K(10%)——完全失真
        val resolved = TokenStats.resolveContextTokens(0L, 340_000L)
        assertEquals(340_000L, resolved)
        assertTrue(TokenStats.isContextOverflow(resolved, 200_000L))
    }

    @Test
    fun `overflow boundary is strict`() {
        assertFalse(TokenStats.isContextOverflow(200_000L, 200_000L))
        assertTrue(TokenStats.isContextOverflow(200_001L, 200_000L))
        // 窗口未知（0）时不算超窗，避免除零与无意义的告警
        assertFalse(TokenStats.isContextOverflow(999_999L, 0L))
    }

    @Test
    fun `usage text shows a real percentage within the window`() {
        // formatTokenStandard 的千位形态是 "128 K"（"%.1f K" 去掉了 .0，但保留空格）
        assertEquals("128 K / 200 K (64.0%)", TokenStats.formatContextUsage(128_000L, 200_000L))
        assertEquals("0 / 200 K (0.0%)", TokenStats.formatContextUsage(0L, 200_000L))
    }

    @Test
    fun `usage text says overflow instead of a fake 100 percent`() {
        val text = TokenStats.formatContextUsage(340_000L, 200_000L)
        assertEquals("340 K / 200 K (已超窗)", text)
        assertTrue(text.contains("已超窗"))
    }

    @Test
    fun `stats expose the overflow flag for the hud`() {
        val over = TokenStats(contextTokens = 340_000L, maxContextWindow = 200_000L)
        assertTrue(over.isContextOverflow)
        // 进度条本身仍夹在 0~1，否则会画出界
        assertEquals(1f, over.contextUsagePercent, 0.0001f)

        val normal = TokenStats(contextTokens = 100_000L, maxContextWindow = 200_000L)
        assertFalse(normal.isContextOverflow)
        assertEquals(0.5f, normal.contextUsagePercent, 0.0001f)
    }
}
