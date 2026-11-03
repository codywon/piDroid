package com.pi.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 文件变更防抖过滤策略（纯 JVM，不依赖任何外部测试依赖）。
 */
class FileWatchDebouncePolicy(
    val debounceMs: Long = 200L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var lastReloadAt: Long = 0L

    fun shouldReload(now: Long = clock()): Boolean {
        if (lastReloadAt > 0L && now - lastReloadAt < debounceMs) {
            return false
        }
        lastReloadAt = now
        return true
    }

    fun reset() {
        lastReloadAt = 0L
    }
}

/**
 * 文件修改事件协程防抖包装器。
 */
class FileWatchDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 200L,
    private val onReload: suspend () -> Unit
) {
    private var job: Job? = null

    fun onFileChanged() {
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            onReload()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
