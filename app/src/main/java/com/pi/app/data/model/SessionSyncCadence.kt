package com.pi.app.data.model

/**
 * 会话页「快照对账」的节奏策略（纯 JVM，可单测）。
 *
 * 病根（本次修复）：`GET /api/sessions/{id}?tail=60&deferMedia=1&tree=summary` 实测
 * 230–435KB（会话越长越大，`tree=summary` 仍然带整棵摘要树），而旧巡检是
 * 「只要在跑就 1.5s 拉一次全量快照」——等于持续 ~150KB/s 占满手机带宽。手机大多还经
 * Cloudflare 隧道，一条 300KB 请求要 1–3s，于是：正文（SSE）、列表刷新、首屏快照全部在
 * 排队，用户看到的就是「进会话一直同步不下来 / 右上角一直转圈」。
 *
 * pi-web 自己的 `useAgentSession` 并不在运行中轮询正文：它只在**回合边界**
 * （agent_end / agent_settled / prompt_done / 事件流重连）重读快照。这里对齐同一原则：
 * - 空闲 → 15s（没有新内容可同步）；
 * - 在跑 + 事件流健康 → 10s 安全网，正文交给 SSE 实时推；
 * - 在跑 + 流断了 / 长时间无事件 → 收紧到 3s，用快照兜底把正文补回来；
 * - 运行态翻转（开始/结束）或列表版本变化 → 立刻对账，不看最小间隔。
 */
object SessionSyncCadence {

    /**
     * 事件流「健康」窗口：必须**大于** pi 的心跳周期（30s，events 路由里的 NN），
     * 否则两个心跳之间的空白会被当成断流，让调速白做。也明显小于 45s 的重挂阈值。
     */
    const val STREAM_HEALTHY_WINDOW_MS = 35_000L

    /** 在跑 + 事件流健康：只做低频安全网对账。 */
    const val RUNNING_INTERVAL_MS = 10_000L

    /** 空闲：静态会话没有必要勤拉。 */
    const val IDLE_INTERVAL_MS = 15_000L

    /** 事件流不健康（未连上 / 静默）：快照就是唯一的信息来源，必须收紧。 */
    const val UNHEALTHY_INTERVAL_MS = 3_000L

    /** 事件流此刻是否可信（连上了且最近确实有事件，心跳也算）。 */
    fun isStreamHealthy(connected: Boolean, lastActivityAt: Long, now: Long): Boolean {
        if (!connected) return false
        if (lastActivityAt <= 0L) return false
        return now - lastActivityAt <= STREAM_HEALTHY_WINDOW_MS
    }

    /** 两次全量快照之间的最小间隔。 */
    fun minIntervalMs(serverSaysRunning: Boolean, streamHealthy: Boolean): Long = when {
        // 空闲：即使事件流静默（心跳之间）也不必勤拉，正文不会变
        !serverSaysRunning -> IDLE_INTERVAL_MS
        !streamHealthy -> UNHEALTHY_INTERVAL_MS
        else -> RUNNING_INTERVAL_MS
    }

    /**
     * 这一轮是否值得再下一次全量快照。
     *
     * @param listVersionChanged `/api/agent/running` 的 sessionListVersion 变了（有新内容落盘）
     * @param runStateTransition 服务端运行态与上一轮不一致（开始 / 结束）
     */
    fun shouldSyncDetail(
        now: Long,
        lastSyncAt: Long,
        listVersionChanged: Boolean,
        runStateTransition: Boolean,
        serverSaysRunning: Boolean,
        streamHealthy: Boolean
    ): Boolean {
        if (runStateTransition || listVersionChanged) return true
        return now - lastSyncAt >= minIntervalMs(serverSaysRunning, streamHealthy)
    }
}
