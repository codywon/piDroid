package com.pi.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些断言钉住的是 pi-web 的两条过滤规则（见 [SessionMessageVisibility] 的文档注释）：
 * 漏掉任何一条，界面上都会出现用户没说过的话——"Goal" 卡片与
 * "Continue the current task from the co…" 卡片就是它们的产物。
 */
class SessionMessageVisibilityTest {

    @Test
    fun `system role is never rendered`() {
        // pi-web 服务端构造 context.messages 时 `case "message": if role==="system" return null`
        assertFalse(SessionMessageVisibility.isVisible("system", null))
        assertFalse(SessionMessageVisibility.isVisible("system", true))
    }

    @Test
    fun `custom message without display flag is hidden`() {
        // MessageView 的 rz(e){return "custom"===e.role && !e.display}：缺字段在 JS 里是 falsy
        assertFalse(SessionMessageVisibility.isVisible("custom", null))
    }

    @Test
    fun `custom message with display false is hidden`() {
        // auto-continue-after-compaction 的续跑指令就是 display:false
        assertFalse(SessionMessageVisibility.isVisible("custom", false))
    }

    @Test
    fun `custom message is rendered only when display is explicitly true`() {
        assertTrue(SessionMessageVisibility.isVisible("custom", true))
    }

    @Test
    fun `ordinary roles are unaffected by the display flag`() {
        assertTrue(SessionMessageVisibility.isVisible("user", null))
        assertTrue(SessionMessageVisibility.isVisible("assistant", null))
        assertTrue(SessionMessageVisibility.isVisible("toolResult", null))
        assertTrue(SessionMessageVisibility.isVisible("toolResult", false))
    }

    @Test
    fun `compaction view model is detected for custom compaction messages`() {
        // pi-web 服务端：case "compaction" → {role:"custom", customType:"compaction", content:<summary>, display:true}
        assertTrue(SessionMessageVisibility.isCompactionView("custom", "compaction"))
    }

    @Test
    fun `other custom messages are not treated as compaction`() {
        assertFalse(SessionMessageVisibility.isCompactionView("custom", "auto-continue-after-compaction"))
        assertFalse(SessionMessageVisibility.isCompactionView("custom", null))
        assertFalse(SessionMessageVisibility.isCompactionView("user", "compaction"))
    }

    @Test
    fun `compaction view survives the visibility gate`() {
        // 压缩摘要是 display:true 的 custom 消息：既要被认出是压缩卡片，也不能被当作隐藏消息丢掉
        assertTrue(SessionMessageVisibility.isVisible("custom", true))
        assertTrue(SessionMessageVisibility.isCompactionView("custom", "compaction"))
    }
}
