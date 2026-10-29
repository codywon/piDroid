package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话页快照对账节奏的回归测试。
 *
 * 守护的 bug（v1.7.51 修复）：巡检把「在跑」直接当成「每 1.5s 重下一次 200–400KB 全量快照」，
 * 手机（尤其经 Cloudflare 隧道）会被这份流量占满，表现为「进会话一直同步不下来、右上角一直转圈」。
 * 修复后：事件流健康时以 SSE 为准，快照只做 10s 安全网；流不健康时才收紧到 3s；
 * 运行态翻转 / 列表版本变化仍必须立刻对账（否则会漏掉「任务结束」）。
 */
class SessionSyncCadenceTest {

    private val now = 1_000_000L

    @Test
    fun `healthy stream while running backs off to the ten second safety net`() {
        assertEquals(SessionSyncCadence.RUNNING_INTERVAL_MS, SessionSyncCadence.minIntervalMs(serverSaysRunning = true, streamHealthy = true))
        // 1.6s 一次（旧行为）必须被否掉
        assertFalse(
            SessionSyncCadence.shouldSyncDetail(
                now = now,
                lastSyncAt = now - 1_600L,
                listVersionChanged = false,
                runStateTransition = false,
                serverSaysRunning = true,
                streamHealthy = true
            )
        )
        assertTrue(
            SessionSyncCadence.shouldSyncDetail(
                now = now,
                lastSyncAt = now - 10_000L,
                listVersionChanged = false,
                runStateTransition = false,
                serverSaysRunning = true,
                streamHealthy = true
            )
        )
    }

    @Test
    fun `broken stream falls back to a tight snapshot cadence`() {
        assertEquals(SessionSyncCadence.UNHEALTHY_INTERVAL_MS, SessionSyncCadence.minIntervalMs(serverSaysRunning = true, streamHealthy = false))
        assertTrue(
            SessionSyncCadence.shouldSyncDetail(
                now = now,
                lastSyncAt = now - 3_000L,
                listVersionChanged = false,
                runStateTransition = false,
                serverSaysRunning = true,
                streamHealthy = false
            )
        )
    }

    @Test
    fun `idle session is not polled faster than fifteen seconds`() {
        assertFalse(
            SessionSyncCadence.shouldSyncDetail(
                now = now,
                lastSyncAt = now - 9_000L,
                listVersionChanged = false,
                runStateTransition = false,
                serverSaysRunning = false,
                streamHealthy = true
            )
        )
        // 空闲 + 心跳间隙（流暂时“不健康”）也不得收紧成 3s：正文不会变，拉也是白拉
        assertEquals(
            SessionSyncCadence.IDLE_INTERVAL_MS,
            SessionSyncCadence.minIntervalMs(serverSaysRunning = false, streamHealthy = false)
        )
    }

    /** 任务结束/开始必须立刻对账：漏掉这一步会让界面一直停在「运行中」。 */
    @Test
    fun `run state transition always syncs immediately`() {
        assertTrue(
            SessionSyncCadence.shouldSyncDetail(
                now = now,
                lastSyncAt = now,
                listVersionChanged = false,
                runStateTransition = true,
                serverSaysRunning = false,
                streamHealthy = true
            )
        )
        assertTrue(
            SessionSyncCadence.shouldSyncDetail(
                now = now,
                lastSyncAt = now,
                listVersionChanged = true,
                runStateTransition = false,
                serverSaysRunning = true,
                streamHealthy = true
            )
        )
    }

    @Test
    fun `stream health requires a recent event and an open connection`() {
        assertTrue(SessionSyncCadence.isStreamHealthy(connected = true, lastActivityAt = now - 5_000L, now = now))
        // 心跳周期是 30s：34s 内有事件仍算健康，36s 才算静默
        assertTrue(SessionSyncCadence.isStreamHealthy(connected = true, lastActivityAt = now - 34_000L, now = now))
        assertFalse(SessionSyncCadence.isStreamHealthy(connected = true, lastActivityAt = now - 36_000L, now = now))
        assertFalse(SessionSyncCadence.isStreamHealthy(connected = false, lastActivityAt = now - 1_000L, now = now))
        // 还没收到过任何事件（刚开流）-> 不算健康，先让快照把首屏补上
        assertFalse(SessionSyncCadence.isStreamHealthy(connected = true, lastActivityAt = 0L, now = now))
    }
}
