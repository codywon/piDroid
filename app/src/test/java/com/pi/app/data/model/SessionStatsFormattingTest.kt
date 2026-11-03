package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsFormattingTest {

    @Test
    fun `formatActiveDuration formats seconds minutes and hours correctly`() {
        assertEquals("", SessionStatsFormatting.formatActiveDuration(0L))
        assertEquals("", SessionStatsFormatting.formatActiveDuration(-1000L))
        assertEquals("45s", SessionStatsFormatting.formatActiveDuration(45_000L))
        assertEquals("18m 42s", SessionStatsFormatting.formatActiveDuration((18 * 60 + 42) * 1000L))
        assertEquals("15m", SessionStatsFormatting.formatActiveDuration(15 * 60 * 1000L))
        assertEquals("2h 15m", SessionStatsFormatting.formatActiveDuration((2 * 3600 + 15 * 60) * 1000L))
        assertEquals("3h", SessionStatsFormatting.formatActiveDuration(3 * 3600 * 1000L))
    }

    @Test
    fun `formatTokensCompact formats tokens correctly`() {
        assertEquals("", SessionStatsFormatting.formatTokensCompact(0L))
        assertEquals("850 tok", SessionStatsFormatting.formatTokensCompact(850L))
        assertEquals("10k tok", SessionStatsFormatting.formatTokensCompact(10_000L))
        assertEquals("24.8k tok", SessionStatsFormatting.formatTokensCompact(24_800L))
        assertEquals("1.2M tok", SessionStatsFormatting.formatTokensCompact(1_200_000L))
    }

    @Test
    fun `formatCostCompact respects threshold and precision`() {
        assertEquals("", SessionStatsFormatting.formatCostCompact(0.0))
        assertEquals("", SessionStatsFormatting.formatCostCompact(0.00005))
        assertEquals("$0.0001", SessionStatsFormatting.formatCostCompact(0.0001))
        assertEquals("$0.0214", SessionStatsFormatting.formatCostCompact(0.02143))
    }

    @Test
    fun `buildCompactMetricsLine constructs non-empty items`() {
        val stats = SessionStatsSummary(
            totalTokens = 24_800L,
            cost = 0.0214,
            toolCalls = 4,
            totalActiveMs = 18 * 60 * 1000L + 42_000L
        )
        val metrics = SessionStatsFormatting.buildCompactMetricsLine("10-31 16:40", stats)
        assertEquals(4, metrics.size)
        assertEquals("10-31 16:40", metrics[0].first)
        assertEquals(false, metrics[0].second)

        assertEquals("18m 42s", metrics[1].first)
        assertEquals(true, metrics[1].second) // with clock icon

        assertEquals("24.8k tok", metrics[2].first)
        assertEquals(false, metrics[2].second)

        assertEquals("$0.0214", metrics[3].first)
        assertEquals(false, metrics[3].second)
    }
}
