package com.pi.app.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileWatchDebounceTest {

    @Test
    fun `multiple rapid changes trigger reload only once after debounce delay`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var reloadCount = 0

        val debouncer = FileWatchDebouncer(
            scope = scope,
            debounceMs = 200L,
            onReload = { reloadCount++ }
        )

        // 连续触发 3 次变更
        debouncer.onFileChanged()
        testScheduler.advanceTimeBy(50L)
        debouncer.onFileChanged()
        testScheduler.advanceTimeBy(50L)
        debouncer.onFileChanged()

        // 100ms 尚未达到 200ms
        testScheduler.advanceTimeBy(100L)
        assertEquals(0, reloadCount)

        // 超过 200ms，触发一次
        testScheduler.advanceTimeBy(150L)
        assertEquals(1, reloadCount)
    }

    @Test
    fun `cancel stops pending reload`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var reloadCount = 0

        val debouncer = FileWatchDebouncer(
            scope = scope,
            debounceMs = 200L,
            onReload = { reloadCount++ }
        )

        debouncer.onFileChanged()
        testScheduler.advanceTimeBy(100L)
        debouncer.cancel()
        testScheduler.advanceTimeBy(200L)

        assertEquals(0, reloadCount)
    }
}
