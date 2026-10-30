package com.pi.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个 401 是不是网关在拒绝我们手里的 Token」的判定回归测试。
 *
 * 守的是两条容易踩反的边界：
 * - **不能见 401 就判**：pi-web 在模型 API Key 填错时也返回 401（`Invalid API key`），
 *   那跟网关密钥无关，误判会把用户推去重新扫码，而真正该做的是清掉那把坏 Key；
 * - **候选 Token 的 401 不能算**：配对对话框里手填/扫码来的新 Token 试连失败，
 *   说明「这一把填错了」，不能让全局进入失效状态（否则正在用的会话会被停掉）。
 */
class AuthRejectionTest {

    /** 网关 `authMiddleware` 写出的头（server/gateway.js） */
    private val gatewayHeader = "Bearer realm=\"pi-gateway\", error=\"invalid_token\""

    @Test
    fun `gateway 401 with the stored token is a rejection`() {
        assertEquals(
            AuthRejection.Verdict.REJECTED,
            AuthRejection.verdict(401, gatewayHeader, usedStoredToken = true)
        )
        assertTrue(AuthRejection.isGatewayInvalidToken(401, gatewayHeader))
    }

    @Test
    fun `gateway 401 against a candidate token is not a rejection`() {
        // 配对对话框里试连：填错了只影响这一把，不该把整个 App 判成失效
        assertEquals(
            AuthRejection.Verdict.NONE,
            AuthRejection.verdict(401, gatewayHeader, usedStoredToken = false)
        )
    }

    @Test
    fun `pi-web provider key 401 must not be mistaken for a gateway rejection`() {
        // pi-web 自己的 401 不带 WWW-Authenticate（填错的是模型 API Key）
        assertEquals(
            AuthRejection.Verdict.NONE,
            AuthRejection.verdict(401, null, usedStoredToken = true)
        )
        // 即便某些代理塞了一个不相干的 Bearer 质询，没有 invalid_token 也不算
        assertEquals(
            AuthRejection.Verdict.NONE,
            AuthRejection.verdict(401, "Bearer realm=\"upstream\"", usedStoredToken = true)
        )
        assertFalse(AuthRejection.isGatewayInvalidToken(401, "Bearer realm=\"upstream\""))
    }

    @Test
    fun `any success clears the rejection state`() {
        assertEquals(
            AuthRejection.Verdict.CLEARED,
            AuthRejection.verdict(200, null, usedStoredToken = true)
        )
        // 重扫后「试连」成功同样算解除：新 Token 就是能用的那把
        assertEquals(
            AuthRejection.Verdict.CLEARED,
            AuthRejection.verdict(200, null, usedStoredToken = false)
        )
    }

    @Test
    fun `non 2xx non 401 responses change nothing`() {
        for (code in listOf(403, 404, 500, 502, 503)) {
            assertEquals(
                AuthRejection.Verdict.NONE,
                AuthRejection.verdict(code, gatewayHeader, usedStoredToken = true)
            )
        }
    }

    @Test
    fun `header match is case insensitive and format tolerant`() {
        assertTrue(AuthRejection.isGatewayInvalidToken(401, "Bearer realm=\"pi-gateway\", error=\"INVALID_TOKEN\""))
        assertTrue(AuthRejection.isGatewayInvalidToken(401, "Bearer error=\"invalid_token\""))
        assertTrue(AuthRejection.isGatewayInvalidToken(401, AuthRejection.WWW_AUTHENTICATE_INVALID_TOKEN))
        // 状态码不是 401 时，头再像也不算
        assertFalse(AuthRejection.isGatewayInvalidToken(403, gatewayHeader))
    }

    @Test
    fun `signal follows the verdict`() {
        AuthRejectionSignal.clear()
        AuthRejectionSignal.apply(AuthRejection.Verdict.NONE)
        assertFalse(AuthRejectionSignal.rejected.value)

        AuthRejectionSignal.apply(AuthRejection.Verdict.REJECTED)
        assertTrue(AuthRejectionSignal.rejected.value)

        AuthRejectionSignal.apply(AuthRejection.Verdict.CLEARED)
        assertFalse(AuthRejectionSignal.rejected.value)
    }
}
