package com.pi.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「聊天正文字号」档位计算的回归测试（纯 JVM，不碰 Android / Compose）。
 *
 * 这里守的是三件容易悄悄坏掉的事：
 *  1. **越界不越权**：SharedPreferences 里被写进 3.7 或 NaN 时，界面不能出现「370%」或崩掉，
 *     必须回到合法档位；
 *  2. **档位可复现**：写盘的值永远是干净的 0.1 倍数，不能因浮点累加变成 1.3000001，
 *     否则「前进 3 次再后退 3 次」回不到原位；
 *  3. **到头即止**：到 80% / 160% 后按钮要置灰，zoomIn/zoomOut 不得再变化。
 */
class ChatTextScaleTest {

    @Test
    fun `default matches the pre-existing look`() {
        // 默认必须是 100%：老用户升级后不应发现字号变了
        assertEquals(1.0f, ChatTextScale.DEFAULT, 0f)
        assertEquals(1.0f, ChatTextScale.normalize(ChatTextScale.DEFAULT), 0f)
        assertEquals("100%", ChatTextScale.percentLabel(ChatTextScale.DEFAULT))
    }

    @Test
    fun `out of range values are clamped to the supported band`() {
        assertEquals(ChatTextScale.MAX, ChatTextScale.normalize(99f), 0f)
        assertEquals(ChatTextScale.MIN, ChatTextScale.normalize(0.01f), 0f)
        assertEquals(ChatTextScale.MAX, ChatTextScale.normalize(1.9f), 0f)
        assertEquals(ChatTextScale.MIN, ChatTextScale.normalize(0.5f), 0f)
        assertEquals("160%", ChatTextScale.percentLabel(9f))
    }

    @Test
    fun `non finite values fall back to default`() {
        assertEquals(ChatTextScale.DEFAULT, ChatTextScale.normalize(Float.NaN), 0f)
        assertEquals(ChatTextScale.DEFAULT, ChatTextScale.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(ChatTextScale.DEFAULT, ChatTextScale.normalize(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `values snap onto the 10 percent grid`() {
        // 老版本可能存过任意 float：必须吸附到 0.1 网格，否则“前进/后退”会跳到意外档位
        assertEquals(0.8f, ChatTextScale.normalize(0.82f), 0f)
        assertEquals(0.9f, ChatTextScale.normalize(0.86f), 0f)
        assertEquals(1.3f, ChatTextScale.normalize(1.3000001f), 0f)
        assertEquals(1.2f, ChatTextScale.normalize(1.24f), 0f)
        assertEquals("130%", ChatTextScale.percentLabel(1.3f))
    }

    @Test
    fun `stepping is reversible and stays on clean decimals`() {
        var scale = ChatTextScale.DEFAULT
        repeat(3) { scale = ChatTextScale.zoomIn(scale) }
        assertEquals(1.3f, scale, 0f)
        assertEquals("130%", ChatTextScale.percentLabel(scale))
        repeat(3) { scale = ChatTextScale.zoomOut(scale) }
        assertEquals(1.0f, scale, 0f)
        // 浮点累加不能留下尾巴
        assertEquals(1.0f, (scale * 100f).toInt() / 100f, 0f)
    }

    @Test
    fun `stepping stops at both ends`() {
        assertEquals(ChatTextScale.MAX, ChatTextScale.zoomIn(ChatTextScale.MAX), 0f)
        assertEquals(ChatTextScale.MIN, ChatTextScale.zoomOut(ChatTextScale.MIN), 0f)
        assertTrue(ChatTextScale.isMax(ChatTextScale.MAX))
        assertTrue(ChatTextScale.isMin(ChatTextScale.MIN))
        assertFalse(ChatTextScale.isMax(ChatTextScale.DEFAULT))
        assertFalse(ChatTextScale.isMin(ChatTextScale.DEFAULT))
        // 越界值（比如老版本写坏的数据）也要被当作“到头”
        assertTrue(ChatTextScale.isMax(5f))
        assertTrue(ChatTextScale.isMin(-1f))
    }

    @Test
    fun `body font grows linearly with the scale`() {
        assertEquals(13.5f, ChatTextScale.bodySp(1.0f), 0.001f)
        assertEquals(17.55f, ChatTextScale.bodySp(1.3f), 0.01f)
        // 非法输入不能把正文算成 0sp 或负数
        assertTrue(ChatTextScale.bodySp(Float.NaN) > 0f)
        assertTrue(ChatTextScale.bodySp(0f) > 0f)
    }
}
