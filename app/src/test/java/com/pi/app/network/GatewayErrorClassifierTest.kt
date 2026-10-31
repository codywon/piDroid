package com.pi.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 这些用例来自一次真实事故：更换 frps 版本的维护窗口里，frpc 有约 1 秒不在线，
 * frp 用它自己的 404 页面应答了手机的全部请求，App 弹出的却是
 * 「获取会话列表失败: 404」——看起来像地址写错，实际只是隧道还没连回来。
 */
class GatewayErrorClassifierTest {

    // frp 在「没有注册的 http 代理」时返回的页面（真实响应，489 字节）
    private val frpNoRoutePage = """
        <!DOCTYPE html>
        <html>
        <head><title>404 page not found</title></head>
        <body>no route found: agent.example.cn /api/sessions</body>
        </html>
    """.trimIndent()

    // pi-web 对不存在的接口返回 JSON
    private val piWebJson404 = """{"error":"Route not found"}"""

    @Test
    fun `404 with an html page means the tunnel has no client, not a missing endpoint`() {
        val failure = GatewayErrorClassifier.classify(
            GatewayHttpException(404, "/api/sessions", frpNoRoutePage)
        )
        assertEquals(GatewayErrorKind.TunnelDown, failure.kind)
        assertEquals(404, failure.code)
        assertTrue(failure.userMessage.contains("隧道"))
    }

    @Test
    fun `404 with a json body is a genuinely missing endpoint`() {
        val failure = GatewayErrorClassifier.classify(
            GatewayHttpException(404, "/api/sessions", piWebJson404)
        )
        assertEquals(GatewayErrorKind.NotFound, failure.kind)
    }

    @Test
    fun `404 without any body is treated as a missing endpoint`() {
        val failure = GatewayErrorClassifier.classify(GatewayHttpException(404, "/api/x"))
        assertEquals(GatewayErrorKind.NotFound, failure.kind)
    }

    @Test
    fun `nginx own 502 page means the tunnel is offline`() {
        val nginxPage = "<html><head><title>502 Bad Gateway</title></head></html>"
        val failure = GatewayErrorClassifier.classify(GatewayHttpException(502, "/api/ping", nginxPage))
        assertEquals(GatewayErrorKind.TunnelDown, failure.kind)
    }

    @Test
    fun `gateway 502 that names pi-web is not a tunnel problem`() {
        val body = """{"error":"Bad Gateway","message":"无法连接到本地 pi-web (http://127.0.0.1:30141)"}"""
        val failure = GatewayErrorClassifier.classify(GatewayHttpException(502, "/api/sessions", body))
        assertEquals(GatewayErrorKind.LocalPiWebDown, failure.kind)
        assertTrue(failure.userMessage.contains("pi-web"))
    }

    @Test
    fun `503 and 504 are tunnel problems too`() {
        assertEquals(
            GatewayErrorKind.TunnelDown,
            GatewayErrorClassifier.classify(GatewayHttpException(503, "/api/sessions")).kind
        )
        assertEquals(
            GatewayErrorKind.TunnelDown,
            GatewayErrorClassifier.classify(GatewayHttpException(504, "/api/sessions")).kind
        )
    }

    @Test
    fun `auth codes ask for a new pairing`() {
        for (code in listOf(401, 403)) {
            val failure = GatewayErrorClassifier.classify(GatewayHttpException(code, "/api/ping"))
            assertEquals(GatewayErrorKind.AuthRejected, failure.kind)
            assertTrue(failure.userMessage.contains("扫码"))
        }
    }

    @Test
    fun `other 5xx is a server error and keeps the code`() {
        val failure = GatewayErrorClassifier.classify(GatewayHttpException(500, "/api/sessions"))
        assertEquals(GatewayErrorKind.ServerError, failure.kind)
        assertTrue(failure.userMessage.contains("500"))
    }

    @Test
    fun `timeouts and connection failures stay distinguishable`() {
        assertEquals(
            GatewayErrorKind.Timeout,
            GatewayErrorClassifier.classify(SocketTimeoutException("timeout")).kind
        )
        assertEquals(
            GatewayErrorKind.Network,
            GatewayErrorClassifier.classify(UnknownHostException("agent.example.cn")).kind
        )
        assertEquals(
            GatewayErrorKind.Network,
            GatewayErrorClassifier.classify(ConnectException("Failed to connect to /1.2.3.4:443")).kind
        )
        // OkHttp 在隧道抖动时常见的原文案
        assertEquals(
            GatewayErrorKind.Network,
            GatewayErrorClassifier.classify(IOException("unexpected end of stream")).kind
        )
        assertEquals(
            GatewayErrorKind.Network,
            GatewayErrorClassifier.classify(IOException("stream reset")).kind
        )
    }

    @Test
    fun `legacy string payloads still resolve by their status code`() {
        // 旧版本 PiApiClient 抛的是这种字符串，升级过程里仍要能归类
        assertEquals(
            GatewayErrorKind.NotFound,
            GatewayErrorClassifier.classify(IOException("Failed to load sessions: 404")).kind
        )
        assertEquals(
            GatewayErrorKind.TunnelDown,
            GatewayErrorClassifier.classify(IOException("读取运行态失败 (502)")).kind
        )
    }

    @Test
    fun `unknown and empty errors still produce something actionable`() {
        val blank = GatewayErrorClassifier.classify(IOException())
        assertEquals(GatewayErrorKind.Unknown, blank.kind)
        assertTrue(blank.userMessage.isNotBlank())

        val nullError = GatewayErrorClassifier.classify(null)
        assertEquals(GatewayErrorKind.Unknown, nullError.kind)
        assertTrue(nullError.userMessage.isNotBlank())

        val weird = GatewayErrorClassifier.classify(IllegalStateException("boom"))
        assertEquals(GatewayErrorKind.Unknown, weird.kind)
        assertTrue(weird.userMessage.contains("boom"))
    }

    @Test
    fun `toast keys group by kind so repeats collapse`() {
        val a = GatewayErrorClassifier.classify(GatewayHttpException(404, "/api/sessions", frpNoRoutePage))
        val b = GatewayErrorClassifier.classify(GatewayHttpException(404, "/api/models", frpNoRoutePage))
        assertEquals(a.toastKey, b.toastKey)
        assertFalse(a.toastKey == GatewayErrorClassifier.classify(GatewayHttpException(401, "/api/ping")).toastKey)
    }
}
