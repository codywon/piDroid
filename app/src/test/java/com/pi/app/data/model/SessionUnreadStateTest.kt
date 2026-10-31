package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUnreadStateTest {

    @Test
    fun `background task completion marks session as unread`() {
        val currentUnread = emptySet<String>()
        val previousRunning = setOf("session-a", "session-b")
        val currentRunning = setOf("session-b") // session-a finished
        val activeSessionId = null // User is on session list

        val next = SessionUnreadState.reconcileRunningSnapshot(
            currentUnread = currentUnread,
            previousRunning = previousRunning,
            currentRunning = currentRunning,
            activeSessionId = activeSessionId
        )

        assertTrue("session-a should be unread", next.contains("session-a"))
        assertFalse("session-b is still running, not unread", next.contains("session-b"))
        assertEquals(setOf("session-a"), next)
    }

    @Test
    fun `active session completion does not mark as unread`() {
        val currentUnread = emptySet<String>()
        val previousRunning = setOf("session-active")
        val currentRunning = emptySet<String>() // finished
        val activeSessionId = "session-active" // User is currently viewing it

        val next = SessionUnreadState.reconcileRunningSnapshot(
            currentUnread = currentUnread,
            previousRunning = previousRunning,
            currentRunning = currentRunning,
            activeSessionId = activeSessionId
        )

        assertFalse("active session must not be marked unread", next.contains("session-active"))
        assertTrue(next.isEmpty())
    }

    @Test
    fun `newly running session is removed from unread`() {
        val currentUnread = setOf("session-a")
        val previousRunning = emptySet<String>()
        val currentRunning = setOf("session-a") // session-a restarted

        val next = SessionUnreadState.reconcileRunningSnapshot(
            currentUnread = currentUnread,
            previousRunning = previousRunning,
            currentRunning = currentRunning,
            activeSessionId = null
        )

        assertFalse("restarted session must not remain unread", next.contains("session-a"))
        assertTrue(next.isEmpty())
    }

    @Test
    fun `manual toggle marks read and unread correctly`() {
        var unread = emptySet<String>()

        unread = SessionUnreadState.toggle(unread, "session-1")
        assertTrue(unread.contains("session-1"))

        unread = SessionUnreadState.toggle(unread, "session-1")
        assertFalse(unread.contains("session-1"))
    }

    @Test
    fun `prune removes deleted sessions from unread set`() {
        val unread = setOf("session-1", "session-deleted")
        val validIds = setOf("session-1", "session-2")

        val pruned = SessionUnreadState.prune(unread, validIds)
        assertEquals(setOf("session-1"), pruned)
    }
}
