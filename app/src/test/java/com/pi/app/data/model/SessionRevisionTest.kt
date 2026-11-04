package com.pi.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话快照「新鲜度」判定的回归测试，对齐 pi-web `useAgentSession.ts:813-818`。
 *
 * 守护的线上现象：长会话里每来一批新内容就把已翻页的历史整段缩回首屏窗口
 * （界面上表现为“往上翻的历史忽然没了 / 正文回退”）。
 */
class SessionRevisionTest {

    @Test
    fun `revision 相同且本地窗口更大时保留已加载历史`() {
        val keep = SessionRevision.keepLoadedWindow(
            cachedRevision = "rev-a",
            incomingRevision = "rev-a",
            cachedEntryCount = 240,
            incomingEntryCount = 117
        )
        assertTrue(keep)
    }

    @Test
    fun `revision 相同但本地窗口更小时仍用服务端窗口`() {
        assertFalse(
            SessionRevision.keepLoadedWindow("rev-a", "rev-a", cachedEntryCount = 117, incomingEntryCount = 240)
        )
        // 相等也算「不比服务端少」，可以保留（与服务端窗口一致）
        assertTrue(
            SessionRevision.keepLoadedWindow("rev-a", "rev-a", cachedEntryCount = 117, incomingEntryCount = 117)
        )
    }

    @Test
    fun `revision 变化或不稳定一律重取`() {
        assertFalse(SessionRevision.keepLoadedWindow("rev-a", "rev-b", 240, 117))
        // 服务端把 revision 判成不稳定（null）时不能沿用旧窗口
        assertFalse(SessionRevision.keepLoadedWindow("rev-a", null, 240, 117))
        assertFalse(SessionRevision.keepLoadedWindow(null, "rev-a", 240, 117))
        assertFalse(SessionRevision.keepLoadedWindow("", "rev-a", 240, 117))
        assertFalse(SessionRevision.keepLoadedWindow(null, null, 0, 0))
    }
}
