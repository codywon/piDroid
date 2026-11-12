package com.pi.app.notification

/**
 * 前台保活服务常驻通知卡片展示的数据载荷。
 */
data class ForegroundNotificationData(
    val isRunning: Boolean,
    val title: String,
    val content: String,
    val subText: String,
    val bigText: String,
    val targetSessionId: String?
)

/**
 * 常驻通知栏状态与文案对账仲裁器（纯 JVM 逻辑，无 Android 运行时依赖）。
 *
 * 核心对账规则：
 * 1. 真实运行态优先：只要当前会话处于 busy 执行态，或者会话列表中存在活跃任务（isLive），
 *    即判定为运行中状态（effectiveRunning == true），展示「⚡ 智能任务执行中 · [当前运行任务名称]」；
 * 2. 杜绝任务名串扰：运行态标题严格遵循 [sessionTitle] -> [currentSessionTitle] -> [liveSessionTitle] -> "智能任务"；
 *    **严禁** 回退借用历史已完成任务名称 [lastFinishedTaskTitle]，杜绝新任务挂旧任务名的严重串扰；
 * 3. 刚完成态边界：仅在全局没有任何任务正在运行（effectiveRunning == false）且存在最近完成任务标记时展示，
 *    清晰呈现「✓ 任务已完成 · [任务名称]」与统计就绪数；
 * 4. 空闲常驻态：当无任何任务在运行且无刚完成标记时，展示网关长连接常驻守护与就绪大盘。
 */
object PiNotificationPolicy {

    fun resolveForegroundNotification(
        isBusy: Boolean,
        activeSessionsCount: Int,
        totalSessions: Int,
        sessionTitle: String? = null,
        liveSessionTitle: String? = null,
        lastFinishedTaskTitle: String? = null,
        phase: String? = null,
        project: String = "默认工作区",
        devName: String = "Remote PC",
        sessionId: String? = null,
        liveSessionId: String? = null
    ): ForegroundNotificationData {
        val runningCount = if (isBusy) maxOf(1, activeSessionsCount) else activeSessionsCount
        val effectiveRunning = isBusy || runningCount > 0
        val completedCount = maxOf(0, totalSessions - runningCount)

        return if (effectiveRunning) {
            val resolvedTitle = sessionTitle?.trim()?.ifBlank { null }
                ?: liveSessionTitle?.trim()?.ifBlank { null }
                ?: "智能任务"
            val targetId = sessionId?.trim()?.ifBlank { null } ?: liveSessionId?.trim()?.ifBlank { null }

            val title = "⚡ 智能任务执行中 · $resolvedTitle"
            val content = phase ?: "正在流式执行推理与工具调用..."
            val subText = "$runningCount 项运行 / $completedCount 项完成"
            val bigText = buildString {
                appendLine("● 当前任务: $resolvedTitle")
                appendLine("● 工作空间: $project")
                appendLine("● 实时进度: ${phase ?: "正在执行流式推理与工具调用..."}")
                appendLine("● 任务统计: $runningCount 个运行中 · $completedCount 个已就绪 (共 $totalSessions 个)")
                append("● 远程主机: $devName (网关长连接正常)")
            }
            ForegroundNotificationData(
                isRunning = true,
                title = title,
                content = content,
                subText = subText,
                bigText = bigText,
                targetSessionId = targetId
            )
        } else if (!lastFinishedTaskTitle.isNullOrBlank()) {
            val title = "✓ 任务已完成 · $lastFinishedTaskTitle"
            val content = "主机: $devName | 所有步骤执行完毕，长连接就绪"
            val subText = "0 运行 / $completedCount 完成"
            val bigText = buildString {
                appendLine("✓ 最近完成: $lastFinishedTaskTitle")
                appendLine("● 工作空间: $project")
                appendLine("● 当前状态: 空闲待命中，随时响应远程指令")
                appendLine("● 任务统计: 0 个运行中 · $completedCount 个已就绪 (共 $totalSessions 个)")
                append("● 远程主机: $devName")
            }
            ForegroundNotificationData(
                isRunning = false,
                title = title,
                content = content,
                subText = subText,
                bigText = bigText,
                targetSessionId = sessionId?.trim()?.ifBlank { null }
            )
        } else {
            val title = if (totalSessions > 0) "πDroid 常驻守护中 · $completedCount 项任务就绪" else "πDroid · 网关常驻守护中"
            val content = "主机: $devName | 保持长连接与后台实时对账"
            val subText = if (totalSessions > 0) "$completedCount 项就绪" else "守护中"
            val bigText = buildString {
                appendLine("● 网关状态: 实时长连接正常，数据增量同步中")
                appendLine("● 当前工作空间: $project")
                appendLine("● 任务统计: 0 个运行中 · $completedCount 个已就绪 (共 $totalSessions 个)")
                append("● 远程主机: $devName")
            }
            ForegroundNotificationData(
                isRunning = false,
                title = title,
                content = content,
                subText = subText,
                bigText = bigText,
                targetSessionId = null
            )
        }
    }
}
