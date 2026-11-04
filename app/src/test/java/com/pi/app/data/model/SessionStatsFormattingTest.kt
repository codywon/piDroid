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
    fun `buildSessionMetaParts puts duration first and time last`() {
        val parts = SessionStatsFormatting.buildSessionMetaParts(
            dateStr = "10-31 16:40",
            totalActiveMs = 18 * 60 * 1000L + 42_000L,
            totalTokens = 24_800L,
            cost = 0.0214
        )
        assertEquals(4, parts.size)
        // 耗时必须在最左：它带时钟图标，是这一行的视觉起点
        assertEquals("18m 42s", parts[0].text)
        assertEquals(SessionStatsFormatting.SessionMetaKind.DURATION, parts[0].kind)

        assertEquals("24.8k tok", parts[1].text)
        assertEquals(SessionStatsFormatting.SessionMetaKind.TOKENS, parts[1].kind)

        assertEquals("$0.0214", parts[2].text)
        assertEquals(SessionStatsFormatting.SessionMetaKind.COST, parts[2].kind)

        assertEquals("10-31 16:40", parts[3].text)
        assertEquals(SessionStatsFormatting.SessionMetaKind.TIME, parts[3].kind)
    }

    @Test
    fun `buildSessionMetaParts drops empty values`() {
        val parts = SessionStatsFormatting.buildSessionMetaParts(
            dateStr = "",
            totalActiveMs = 0L,
            totalTokens = 0L,
            cost = 0.0
        )
        assertTrue(parts.isEmpty())
    }
}
