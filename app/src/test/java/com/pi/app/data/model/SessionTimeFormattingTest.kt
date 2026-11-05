package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话相对时间的回归测试。
 *
 * 对照基准是 pi-web `lib/format.ts` 的 `Rg()`（zh-CN 单位表）：
 * <1 分钟「刚刚」→「N分」→「N小时」→「N天」→「N周」→「N个月」→「N年」。
 * 这条规则决定侧栏每张会话卡右侧显示什么，口径飘一格用户就会觉得「同步慢了」。
 */
class SessionTimeFormattingTest {

    private val now = 1_790_000_000_000L // 固定参照时刻，避免测试依赖真实时间

    private fun ago(millis: Long): String =
        SessionTimeFormatting.formatRelative((now - millis).toString(), now)

    @Test
    fun `under one minute reads just now`() {
        assertEquals("刚刚", ago(0L))
        assertEquals("刚刚", ago(59_999L))
    }

    @Test
    fun `minutes hours and days follow pi-web thresholds`() {
        assertEquals("1分", ago(60_000L))
        assertEquals("59分", ago(59 * 60_000L))
        assertEquals("1小时", ago(60 * 60_000L))
        assertEquals("23小时", ago(23 * 3_600_000L))
        assertEquals("1天", ago(24 * 3_600_000L))
        assertEquals("6天", ago(6 * 86_400_000L))
    }

    @Test
    fun `weeks months and years follow pi-web thresholds`() {
        assertEquals("1周", ago(7 * 86_400_000L))
        assertEquals("4周", ago(29 * 86_400_000L))
        assertEquals("1个月", ago(30 * 86_400_000L))
        assertEquals("12个月", ago(364 * 86_400_000L))
        assertEquals("1年", ago(365 * 86_400_000L))
        assertEquals("2年", ago(800 * 86_400_000L))
    }

    @Test
    fun `future timestamps collapse to just now instead of negative units`() {
        val future = (now + 5 * 60_000L).toString()
        assertEquals(SessionTimeFormatting.JUST_NOW, SessionTimeFormatting.formatRelative(future, now))
    }

    @Test
    fun `unparseable or empty input yields empty string`() {
        assertEquals("", SessionTimeFormatting.formatRelative("", now))
        assertEquals("", SessionTimeFormatting.formatRelative(null, now))
        assertEquals("", SessionTimeFormatting.formatRelative("not-a-time", now))
    }

    @Test
    fun `utc iso strings are parsed without timezone drift`() {
        // 2026-10-27T08:31:27.266Z —— pi-web 下发的 modified 就是这个形状
        val parsed = SessionTimeFormatting.parseMillis("2026-10-27T08:31:27.266Z")
        val expected = java.time.Instant.parse("2026-10-27T08:31:27.266Z").toEpochMilli()
        assertEquals(expected, parsed)

        val relative = SessionTimeFormatting.formatRelative("2026-10-27T08:31:27.266Z", expected + 90_000L)
        assertEquals("1分", relative)
    }

    @Test
    fun `epoch millis strings are accepted as-is`() {
        assertEquals(now, SessionTimeFormatting.parseMillis(now.toString()))
        assertEquals("2天", SessionTimeFormatting.formatRelative(now.toString(), now + 2 * 86_400_000L))
    }
}
