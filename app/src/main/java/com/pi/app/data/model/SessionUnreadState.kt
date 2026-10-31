package com.pi.app.data.model

/**
 * 会话未读状态管理（1:1 对标 pi-web SessionSidebar 中的 unreadSessionIds 驱动逻辑）：
 *
 * 1. 后台任务完成时（之前在 runningIds 中、当前已不再运行，且不是用户正停留的会话），自动标记为未读。
 * 2. 重新启动运行的会话，从未读中移除（避免与运行态 spinner 冲突）。
 * 3. 用户打开进入会话时，自动标记为已读。
 * 4. 支持在列表菜单中手动切换「标为未读 / 标为已读」。
 * 5. 远端会话被删除或列表更新时，自动清理不存在会话的未读残留。
 */
object SessionUnreadState {

    /**
     * 根据轻量 runningIds 快照的变化计算下一轮未读集合。
     *
     * @param currentUnread 当前已有的未读会话 id 集合
     * @param previousRunning 上一轮记录的运行中会话 id 集合
     * @param currentRunning 当前快照中的运行中会话 id 集合
     * @param activeSessionId 当前用户正在前台查看的会话 id（若在会话详情页），在首页时为 null
     */
    fun reconcileRunningSnapshot(
        currentUnread: Set<String>,
        previousRunning: Set<String>,
        currentRunning: Set<String>,
        activeSessionId: String?
    ): Set<String> {
        val completedInBackground = previousRunning.filter { id ->
            !currentRunning.contains(id) && id != activeSessionId
        }
        val newlyRunning = currentRunning.filter { id ->
            !previousRunning.contains(id)
        }

        var next = currentUnread
        if (completedInBackground.isNotEmpty()) {
            next = next + completedInBackground
        }
        if (newlyRunning.isNotEmpty()) {
            next = next - newlyRunning.toSet()
        }
        if (activeSessionId != null && next.contains(activeSessionId)) {
            next = next - activeSessionId
        }
        return next
    }

    /**
     * 手动切换单条会话的未读状态。
     */
    fun toggle(currentUnread: Set<String>, sessionId: String): Set<String> {
        return if (currentUnread.contains(sessionId)) {
            currentUnread - sessionId
        } else {
            currentUnread + sessionId
        }
    }

    /**
     * 标记单条会话为已读。
     */
    fun markRead(currentUnread: Set<String>, sessionId: String): Set<String> {
        return currentUnread - sessionId
    }

    /**
     * 标记单条会话为未读。
     */
    fun markUnread(currentUnread: Set<String>, sessionId: String): Set<String> {
        return currentUnread + sessionId
    }

    /**
     * 过滤清理已经从服务端删除的无效未读 ID。
     */
    fun prune(currentUnread: Set<String>, validSessionIds: Set<String>): Set<String> {
        return currentUnread.filter { validSessionIds.contains(it) }.toSet()
    }
}
