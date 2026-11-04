package com.pi.app

import android.app.Application
import android.util.Log
import java.io.File

/**
 * 进程级崩溃兜底：把未捕获异常的堆栈写到 `filesDir/last-crash.txt`。
 *
 * 为什么需要：本机没有接入任何崩溃平台，而「盯着一个任务跑一会就闪退」这种问题
 * 没有堆栈根本无法定位（logcat 需要 USB，用户手里没有）。这里只做一件事：
 * **落盘**；下一次启动时 [MainActivity] 会把它写进 logcat 并上传给已配对的伴侣网关
 * （`POST /api/mobile/crash` → PC 侧 `server/logs/crash-*.log`）。
 *
 * 不吞异常：写完仍然交回系统默认处理器，崩溃照旧（避免掩盖问题）。
 */
class PiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            previous?.uncaughtException(thread, error)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val header = buildString {
            append("time=").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("version=").append(packageManager.getPackageInfo(packageName, 0).versionName).append('\n')
            append('\n')
        }
        val text = header + Log.getStackTraceString(error)
        File(filesDir, CRASH_FILE).writeText(text)
    }

    companion object {
        const val CRASH_FILE = "last-crash.txt"
    }
}
