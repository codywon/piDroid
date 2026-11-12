package com.pi.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台常驻通知栏状态与文案对账仲裁逻辑的纯 JVM 单元测试。
 *
 * 核心保障：
 * 1. 运行态优先级：无论前台还是后台有任务在跑，通知栏必须处于「运行中态」，绝不能被历史完成任务覆盖；
 * 2. 隔离已完成任务名：新任务或后台任务运行时，标题严格提取当前运行任务，绝不能借用历史 lastFinishedTaskTitle；
 * 3. 刚完成态边界：仅在全局任务数为 0 且有刚完成任务时，才显示「✓ 任务已完成」；
 * 4. 统计与跳转目标精准对应。
 */
class PiNotificationPolicyTest {

    @Test
    fun `foreground busy task resolves to running state and uses current task title`() {
        val result = PiNotificationPolicy.resolveForegroundNotification(
            isBusy = true,
            activeSessionsCount = 1,
            totalSessions = 5,
            sessionTitle = "代码重构任务",
            liveSessionTitle = null,
            lastFinishedTaskTitle = "旧的已完成任务", // 模拟历史残留
            phase = "正在执行 grep 工具...",
            project = "my-project",
            devName = "MacBook-Pro",
            sessionId = "sess-100"
        )

        assertTrue("Must be in running state", result.isRunning)
        assertEquals("⚡ 智能任务执行中 · 代码重构任务", result.title)
        assertEquals("正在执行 grep 工具...", result.content)
        assertEquals("1 项运行 / 4 项完成", result.subText)
        assertEquals("sess-100", result.targetSessionId)
        assertTrue(result.bigText.contains("● 当前任务: 代码重构任务"))
        assertTrue(result.bigText.contains("1 个运行中 · 4 个已就绪"))
        assertFalse("Must NOT contain old finished task name", result.bigText.contains("旧的已完成任务"))
    }

    @Test
    fun `background running task resolves to running state even when isBusy is false`() {
        // 用户退回到列表页或在 PC 端启动任务：isBusy = false，但 activeSessionsCount = 1
        val result = PiNotificationPolicy.resolveForegroundNotification(
            isBusy = false,
            activeSessionsCount = 1,
            totalSessions = 10,
            sessionTitle = null,
            liveSessionTitle = "后台自动化测试",
            lastFinishedTaskTitle = "昨天完成的任务", // 历史残留
            phase = null,
            project = "pi-workspace",
            devName = "Ubuntu-Server",
            sessionId = null,
            liveSessionId = "sess-200"
        )

        assertTrue("Must be in running state when background tasks exist", result.isRunning)
        assertEquals("⚡ 智能任务执行中 · 后台自动化测试", result.title)
        assertEquals("1 项运行 / 9 项完成", result.subText)
        assertEquals("sess-200", result.targetSessionId)
        assertFalse("Must NOT show completed state", result.title.startsWith("✓"))
        assertFalse("Must NOT show old finished task", result.title.contains("昨天完成的任务"))
    }

    @Test
    fun `running state with null titles falls back to smart task name instead of old finished task`() {
        // 即使没有当前标题与 liveSessionTitle，也不能回退到历史完成任务
        val result = PiNotificationPolicy.resolveForegroundNotification(
            isBusy = true,
            activeSessionsCount = 0, // 乐观开始阶段
            totalSessions = 3,
            sessionTitle = null,
            liveSessionTitle = null,
            lastFinishedTaskTitle = "历史完成任务",
            phase = "正在等待模型..."
        )

        assertTrue(result.isRunning)
        assertEquals("⚡ 智能任务执行中 · 智能任务", result.title)
        assertEquals("1 项运行 / 2 项完成", result.subText)
        assertFalse("Must never fallback to lastFinishedTaskTitle", result.title.contains("历史完成任务"))
    }

    @Test
    fun `just finished task resolves to completed state when no tasks are running`() {
        val result = PiNotificationPolicy.resolveForegroundNotification(
            isBusy = false,
            activeSessionsCount = 0,
            totalSessions = 8,
            sessionTitle = null,
            liveSessionTitle = null,
            lastFinishedTaskTitle = "编写单元测试",
            phase = null,
            project = "pi-app",
            devName = "Workstation",
            sessionId = "sess-finished"
        )

        assertFalse("Should not be running", result.isRunning)
        assertEquals("✓ 任务已完成 · 编写单元测试", result.title)
        assertEquals("主机: Workstation | 所有步骤执行完毕，长连接就绪", result.content)
        assertEquals("0 运行 / 8 完成", result.subText)
        assertEquals("sess-finished", result.targetSessionId)
        assertTrue(result.bigText.contains("✓ 最近完成: 编写单元测试"))
        assertTrue(result.bigText.contains("0 个运行中 · 8 个已就绪"))
    }

    @Test
    fun `idle state resolves to guardian notification when no tasks and no finished task`() {
        val result = PiNotificationPolicy.resolveForegroundNotification(
            isBusy = false,
            activeSessionsCount = 0,
            totalSessions = 6,
            sessionTitle = null,
            liveSessionTitle = null,
            lastFinishedTaskTitle = null,
            phase = null,
            project = "pi-app",
            devName = "Mac-Mini"
        )

        assertFalse("Should not be running", result.isRunning)
        assertEquals("πDroid 常驻守护中 · 6 项任务就绪", result.title)
        assertEquals("主机: Mac-Mini | 保持长连接与后台实时对账", result.content)
        assertEquals("6 项就绪", result.subText)
        assertNull(result.targetSessionId)
        assertTrue(result.bigText.contains("网关状态: 实时长连接正常"))
    }

    @Test
    fun `idle state with 0 total sessions shows simple guardian text`() {
        val result = PiNotificationPolicy.resolveForegroundNotification(
            isBusy = false,
            activeSessionsCount = 0,
            totalSessions = 0,
            sessionTitle = null,
            liveSessionTitle = null,
            lastFinishedTaskTitle = null
        )

        assertFalse(result.isRunning)
        assertEquals("πDroid · 网关常驻守护中", result.title)
        assertEquals("守护中", result.subText)
    }
}
