package com.pi.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SVG 预览与缩放画布 HTML 模板构建的回归测试。
 *
 * 守护的线上现象：SVG 全屏查看时捏合放大不失真，但无法拖拽平移漫游查看其他区域。
 * 根因是父级 Compose 手势拦截、以及 WebView 内部缺少各向同性的视区平移投影与边界漫游。
 */
class SvgCanvasHtmlTest {

    private val sampleSvg = "<svg viewBox=\"0 0 100 100\"><circle cx=\"50\" cy=\"50\" r=\"40\"/></svg>"
    private val testNonce = "test_nonce_12345"

    @Test
    fun `静态卡片预览模式不注入缩放脚本与浮动徽标`() {
        val html = buildSvgHtml(
            cleanSvg = sampleSvg,
            isDark = true,
            enableZoom = false,
            scriptNonce = testNonce
        )

        assertTrue("必须包含 CSP nonce", html.contains("nonce-$testNonce"))
        assertTrue("必须包含 SVG 内容", html.contains(sampleSvg))
        assertFalse("静态卡片不应包含缩放控制徽标", html.contains("id=\"zoom-badge\""))
        assertFalse("静态卡片不应包含手势脚本", html.contains("window.resetZoom"))
        assertFalse("静态卡片不应包含平移函数", html.contains("panBy"))
    }

    @Test
    fun `全屏手势缩放模式注入完整手势引擎与双向桥接方法`() {
        val html = buildSvgHtml(
            cleanSvg = sampleSvg,
            isDark = false,
            enableZoom = true,
            scriptNonce = testNonce
        )

        // 1. 安全防护
        assertTrue("CSP nonce 必须在 CSP meta 中声明", html.contains("script-src 'nonce-$testNonce'"))
        assertTrue("Script 标签必须携带对应 nonce", html.contains("<script nonce=\"$testNonce\">"))

        // 2. 交互徽标与视口样式
        assertTrue("必须包含缩放比例徽标", html.contains("<div id=\"zoom-badge\">100%</div>"))
        assertTrue("必须禁用浏览器默认触控手势", html.contains("touch-action: none"))

        // 3. 矢量平移与边界漫游函数
        assertTrue("必须包含各向同性物理像素平移函数 panBy", html.contains("function panBy(dx, dy)"))
        assertTrue("必须包含视区边界漫游函数 clampViewBox", html.contains("function clampViewBox()"))
        assertTrue("必须包含原子缩放与双指平移函数 zoomAndPan", html.contains("function zoomAndPan("))

        // 4. 对外导出的控制接口（供顶栏按钮调用）
        assertTrue("必须导出 window.zoomIn", html.contains("window.zoomIn = zoomIn"))
        assertTrue("必须导出 window.zoomOut", html.contains("window.zoomOut = zoomOut"))
        assertTrue("必须导出 window.resetZoom", html.contains("window.resetZoom = resetZoom"))

        // 5. 与 Android 原生层的缩放比例同步桥接
        assertTrue("必须向原生汇报比例变动", html.contains("window.Android.onZoomChange(scale)"))

        // 6. 完整的手势生命周期监听（含 touchcancel 防丢点）
        assertTrue("必须监听 touchstart", html.contains("container.addEventListener('touchstart'"))
        assertTrue("必须监听 touchmove", html.contains("container.addEventListener('touchmove'"))
        assertTrue("必须监听 touchend", html.contains("container.addEventListener('touchend'"))
        assertTrue("必须监听 touchcancel", html.contains("container.addEventListener('touchcancel'"))

        // 7. 消除 letterbox 宽屏黑边的动态物理尺寸缩放与自适应
        assertTrue("必须保持原始 viewBox 恒定防止相对畸变", html.contains("svg.setAttribute('viewBox', initX"))
        assertTrue("必须包含动态物理容器尺寸获取函数", html.contains("function getContainerSize()"))
        assertTrue("必须包含基准缩放比函数", html.contains("function getBaseScale("))
        assertTrue("必须包含动态更新计算函数", html.contains("function updateViewBox("))
        assertTrue("必须监听窗口尺寸变动自适应", html.contains("window.addEventListener('resize'"))
    }
}
