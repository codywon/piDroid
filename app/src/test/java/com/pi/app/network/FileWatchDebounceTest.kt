package com.pi.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileWatchDebounceTest {

    @Test
    fun `rapid consecutive file changes within debounce threshold are suppressed`() {
        var currentTime = 1000L
        val policy = FileWatchDebouncePolicy(
            debounceMs = 200L,
            clock = { currentTime }
        )

        // 第一次触发：允许刷新
        assertTrue(policy.shouldReload())

        // 50ms 之后再次触发：防抖抑制
        currentTime += 50L
        assertFalse(policy.shouldReload())

        // 100ms 之后再次触发：防抖抑制
        currentTime += 50L
        assertFalse(policy.shouldReload())

        // 超过 200ms 后再次触发：允许刷新
        currentTime += 150L
        assertTrue(policy.shouldReload())
    }

    @Test
    fun `reset clears previous timestamp`() {
        var currentTime = 1000L
        val policy = FileWatchDebouncePolicy(
            debounceMs = 200L,
            clock = { currentTime }
        )

        assertTrue(policy.shouldReload())
        assertFalse(policy.shouldReload())

        policy.reset()
        assertTrue(policy.shouldReload())
    }
}
