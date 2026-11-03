package com.pi.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 文件修改事件防抖器（纯 JVM，可单测）。
 *
 * 避免编译器或编辑器保存时产生连续的临时写入触发多次无意义重新加载。
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
