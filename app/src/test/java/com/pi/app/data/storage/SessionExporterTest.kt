package com.pi.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExporterTest {

    @Test
    fun `exportFileName uses title when valid and sanitizes special characters`() {
        val fileName = SessionExporter.exportFileName(
            sessionId = "7d8a9f1234567890",
            title = "Fix: models/cache & refactor?"
        )
        assertEquals("pi-session-Fix_-models_cache-refactor_.html", fileName)
    }

    @Test
    fun `exportFileName falls back to truncated sessionId when title is blank`() {
        val fileName = SessionExporter.exportFileName(
            sessionId = "7d8a9f1234567890",
            title = "   "
        )
        assertEquals("pi-session-7d8a9f12.html", fileName)
    }

    @Test
    fun `exportFileName clamps long titles to 50 chars`() {
        val longTitle = "a".repeat(100)
        val fileName = SessionExporter.exportFileName(
            sessionId = "7d8a9f1234567890",
            title = longTitle
        )
        assertTrue(fileName.startsWith("pi-session-"))
        assertTrue(fileName.endsWith(".html"))
        // "pi-session-" (11) + 50 + ".html" (5) = 66
        assertEquals(66, fileName.length)
    }
}
