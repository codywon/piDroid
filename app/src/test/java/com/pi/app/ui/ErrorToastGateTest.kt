package com.pi.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorToastGateTest {

    @Test
    fun `first call shows, repeats inside the window are swallowed`() {
        var now = 1_000L
        val gate = ErrorToastGate(windowMs = 60_000L, clock = { now })

        assertTrue(gate.shouldShow("TunnelDown"))
        now += 1_000L
        assertFalse(gate.shouldShow("TunnelDown"))
        now += 58_000L
        assertFalse(gate.shouldShow("TunnelDown"))

        // 窗口一过，允许再提示一次（长故障不能被永久静音）
        now += 2_000L
        assertTrue(gate.shouldShow("TunnelDown"))
    }

    @Test
    fun `different kinds are throttled independently`() {
        var now = 0L
        val gate = ErrorToastGate(windowMs = 60_000L, clock = { now })
        assertTrue(gate.shouldShow("TunnelDown"))
        assertTrue(gate.shouldShow("AuthRejected"))
        assertFalse(gate.shouldShow("TunnelDown"))
    }

    @Test
    fun `reset clears one key or everything`() {
        var now = 0L
        val gate = ErrorToastGate(windowMs = 60_000L, clock = { now })

        assertTrue(gate.shouldShow("Network"))
        gate.reset("Network")
        assertTrue(gate.shouldShow("Network"))

        assertTrue(gate.shouldShow("Timeout"))
        gate.reset()
        assertTrue(gate.shouldShow("Timeout"))
    }
}
