package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactionRecordParsingTest {

    @Test
    fun `compaction record calculates tokensSaved and savingsPercent correctly`() {
        val record = CompactionRecord(
            id = "entry-compact-1",
            timestamp = 1730000000000L,
            summary = "Prior conversation summary",
            tokensBefore = 100_000L,
            tokensAfter = 25_000L,
            cost = 0.005
        )
        assertEquals(75_000L, record.tokensSaved)
        assertEquals(75, record.savingsPercent)
    }

    @Test
    fun `savingsPercent handles zero tokensBefore without division by zero`() {
        val record = CompactionRecord(
            id = "entry-compact-2",
            timestamp = 1730000000000L,
            summary = "",
            tokensBefore = 0L,
            tokensAfter = 0L,
            cost = 0.0
        )
        assertEquals(0L, record.tokensSaved)
        assertEquals(0, record.savingsPercent)
    }
}
