package com.pi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KaTeX 公式页面模板的回归测试。
 *
 * 两类事故必须被拦住：
 *  1. **脚本逃逸**：公式里出现 `</script>` 会把内联脚本提前截断 → 整张卡片白板；
 *  2. **CSS 注入**：颜色来自主题，但仍要保证只有 `#RRGGBB(A)` 能进模板。
 *
 * 模板刻意不 import Android / Compose，才能在无 SDK 的本机跑单元测试。
 */
class KaTeXPageTemplateTest {

    @Test
    fun `latex cannot break out of the inline script tag`() {
        val html = KaTeXPageTemplate.build(
            latex = "a</script><script>alert(1)</script>\\times 2",
            displayMode = true,
            fontSizePx = 15f,
            colorHex = "#123456"
        )
        assertFalse(html.contains("</script><script>alert(1)"))
        assertTrue(html.contains("\\u003c/script\\u003e"))
    }

    @Test
    fun `backslashes and quotes survive as js escapes`() {
        val html = KaTeXPageTemplate.build(
            latex = "\\frac{\"a\"}{b}",
            displayMode = false,
            fontSizePx = 14f,
            colorHex = "#000000"
        )
        // 原样保留会被 JS 当成字符串结束；必须是双反斜杠 + 转义引号
        assertTrue(html.contains("\\\\frac{\\\"a\\\"}{b}"))
    }

    @Test
    fun `css color is whitelisted`() {
        assertEquals("#000000", KaTeXPageTemplate.sanitizeCssColor("red;}body{color:red"))
        assertEquals("#000000", KaTeXPageTemplate.sanitizeCssColor(""))
        assertEquals("#000000", KaTeXPageTemplate.sanitizeCssColor("#12345g"))
        assertEquals("#000000", KaTeXPageTemplate.sanitizeCssColor("#123456789"))
        assertEquals("#1F2328", KaTeXPageTemplate.sanitizeCssColor("#1F2328"))
        assertEquals("#ffffff", KaTeXPageTemplate.sanitizeCssColor(" #ffffff "))
    }

    @Test
    fun `page wires offline katex assets and reports dimensions`() {
        val html = KaTeXPageTemplate.build("x^2", true, 15f, "#1F2328")
        assertTrue(html.contains(KaTeXPageTemplate.CSS_URL))
        assertTrue(html.contains(KaTeXPageTemplate.JS_URL))
        assertTrue(html.contains(KaTeXPageTemplate.ASSET_HOST))
        assertTrue(html.contains("katex.render"))
        assertTrue(html.contains("var DISPLAY = true;"))
        // 宿主靠 __PI_MATH_DIMS 取高度，缺了它卡片会永远停在兜底高度
        assertTrue(html.contains("__PI_MATH_DIMS"))
        assertTrue(html.contains("__PI_MATH_FIT"))
        // 字体是异步加载的，必须二次校准
        assertTrue(html.contains("document.fonts.ready"))
    }

    @Test
    fun `inline mode renders inline`() {
        val html = KaTeXPageTemplate.build("x^2", false, 13.5f, "#1F2328")
        assertTrue(html.contains("var DISPLAY = false;"))
        assertTrue(html.contains("font-size: 13.5px"))
    }

    @Test
    fun `invalid font size falls back to a sane default`() {
        assertTrue(KaTeXPageTemplate.build("x", true, 0f, "#000000").contains("font-size: 15.0px"))
        assertTrue(KaTeXPageTemplate.build("x", true, Float.NaN, "#000000").contains("font-size: 15.0px"))
    }
}
