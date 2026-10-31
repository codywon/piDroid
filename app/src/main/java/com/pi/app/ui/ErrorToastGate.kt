package com.pi.app.ui

/**
 * 同一类错误在窗口期内只提示一次。
 *
 * 为什么需要：公网隧道重连的那一两秒里，一轮同步会同时失败十几个请求
 * （会话列表、运行态、模型目录、技能、插件、MCP…）。逐个弹 toast 等于给用户
 * 放弹幕，最后只记得最后一条；而去重之后，用户看到的是「隧道暂时断开，正在重连」
 * 这一句可执行的信息，重连完成后界面自己恢复。
 *
 * 纯逻辑、可注入时钟，便于单测；只在主线程使用。
 */
class ErrorToastGate(
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val shownAt = mutableMapOf<String, Long>()

    /** 允许提示则返回 true 并记账；窗口期内的同类错误返回 false。 */
    fun shouldShow(key: String): Boolean {
        val now = clock()
        val last = shownAt[key]
        if (last != null && now - last < windowMs) return false
        shownAt[key] = now
        return true
    }

    fun reset(key: String? = null) {
        if (key == null) shownAt.clear() else shownAt.remove(key)
    }
}
