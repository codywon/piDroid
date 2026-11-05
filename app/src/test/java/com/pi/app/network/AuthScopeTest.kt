package com.pi.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条请求能不能带上网关 Bearer Token」的回归测试。
 *
 * 守的是两个已经真实发生过（或差点发生）的泄密面：
 *
 * 1. **技能市场兜底直连**：`PiApiClient.searchSkills` 在网关搜索失败时会用**同一个**
 *    带 `AuthInterceptor` 的 client 去请求 `https://skills.sh/api/search`，旧实现无条件
 *    附加 `Authorization`，等于把伴侣 PC 的全权凭据交给第三方；对方的 401 还会被
 *    `AuthRejection` 当成「网关收回了密钥」，反过来弹窗逼用户重新扫码。
 * 2. **Markdown 外链图片**：`![](https://图床/x.png)` 会原样进入媒体加载器并**自动**发请求
 *    （图片进视口即触发，不需要用户点击），旧实现同样无条件附加 Token。
 *
 * 因此这里断言的是一条「白名单」语义：除了网关自己，谁都不带。
 * 判定必须 fail-closed —— 解析不出来就当作不认识，宁可有内网图 401。
 */
class AuthScopeTest {

    private val gateway = "https://pale-cloud.trycloudflare.com"

    @Test
    fun `gateway itself is in scope`() {
        assertTrue(AuthScope.shouldAttachToken(gateway, "$gateway/api/ping"))
        assertTrue(AuthScope.shouldAttachToken(gateway, "$gateway/api/files/raw?path=D%3A%2Fa.png&cwd="))
    }

    @Test
    fun `third party hosts are never in scope`() {
        // 技能市场兜底直连：这是仓库里唯一一条走主 client 的外部 URL
        assertFalse(AuthScope.shouldAttachToken(gateway, "https://skills.sh/api/search?q=react&limit=50"))
        // Markdown 外链图片：进视口就自动请求，属于被动泄密面
        assertFalse(AuthScope.shouldAttachToken(gateway, "https://cdn.example.net/evil/leak.png"))
        // 同域名的子域也不算同一台网关
        assertFalse(AuthScope.shouldAttachToken(gateway, "https://api.trycloudflare.com/x"))
    }

    @Test
    fun `scheme and port must match`() {
        assertFalse(AuthScope.shouldAttachToken("http://192.168.1.5:4100", "https://192.168.1.5:4100/x"))
        assertFalse(AuthScope.shouldAttachToken("http://192.168.1.5:4100", "http://192.168.1.5:4101/x"))
        assertTrue(AuthScope.shouldAttachToken("http://192.168.1.5:4100", "http://192.168.1.5:4100/x"))
    }

    @Test
    fun `explicit default port equals omitted port`() {
        assertTrue(AuthScope.shouldAttachToken("https://gw.example.com", "https://gw.example.com:443/x"))
        assertTrue(AuthScope.shouldAttachToken("https://gw.example.com:443", "https://gw.example.com/x"))
        assertTrue(AuthScope.shouldAttachToken("http://gw.example.com", "http://gw.example.com:80/x"))
    }

    @Test
    fun `path query trailing slash and case do not change identity`() {
        assertTrue(AuthScope.shouldAttachToken("$gateway/", "$gateway/api/ping"))
        assertTrue(AuthScope.shouldAttachToken("$gateway", "$gateway?x=1"))
        assertTrue(AuthScope.shouldAttachToken("https://GW.Example.com", "https://gw.example.com/x"))
    }

    @Test
    fun `surrounding whitespace in the stored gateway is tolerated`() {
        // 扫码 / 粘贴带进来的前后空格：旧实现只 trimEnd('/')，OkHttp 会直接抛 Unexpected char
        assertTrue(AuthScope.shouldAttachToken("  $gateway  ", "$gateway/api/ping"))
    }

    @Test
    fun `userinfo cannot disguise the real host`() {
        // 视觉上像网关，真正连的是 evil.com
        assertFalse(AuthScope.shouldAttachToken(gateway, "https://pale-cloud.trycloudflare.com@evil.com/x"))
    }

    @Test
    fun `backslash is a path separator not part of the host`() {
        // OkHttp 把 `\` 当路径分隔符：这条真正连的是 evil.com，`@` 之后的主机名不能参与判定
        assertFalse(AuthScope.shouldAttachToken(gateway, "https://evil.com\\@pale-cloud.trycloudflare.com/x"))
    }

    @Test
    fun `gateway paths with spaces or cjk keep the token`() {
        // 工作区真实路径：Windows 中文目录 + 空格。OkHttp 会百分号编码后照常请求，
        // 判定不能因为路径“不好解析”就把它当成外站——那会让内网图片变成 403。
        assertTrue(
            AuthScope.shouldAttachToken(
                "http://192.168.1.5:4100",
                "http://192.168.1.5:4100/D:/我的项目/图 1.png"
            )
        )
        assertTrue(
            AuthScope.shouldAttachToken(
                "http://192.168.1.5:4100",
                "http://192.168.1.5:4100/api/files/raw?path=D%3A%2F%E6%88%91%2Fx&cwd=E%3A%2Fa+b"
            )
        )
    }

    @Test
    fun `non http schemes are out of scope`() {
        assertFalse(AuthScope.shouldAttachToken(gateway, "file:///storage/emulated/0/x.png"))
        assertFalse(AuthScope.shouldAttachToken(gateway, "javascript:alert(1)"))
        assertFalse(AuthScope.shouldAttachToken(gateway, "data:image/svg+xml;base64,AAAA"))
    }

    @Test
    fun `blank or unparseable input fails closed`() {
        // 还没配置网关（首次启动）时不能凭“反正只有一个地址”就附带 Token
        assertFalse(AuthScope.shouldAttachToken("", "$gateway/api/ping"))
        assertFalse(AuthScope.shouldAttachToken(null, "$gateway/api/ping"))
        assertFalse(AuthScope.shouldAttachToken(gateway, ""))
        assertFalse(AuthScope.shouldAttachToken(gateway, null))
        assertFalse(AuthScope.shouldAttachToken(gateway, "not a url"))
        assertFalse(AuthScope.shouldAttachToken(gateway, "https://"))
        assertFalse(AuthScope.shouldAttachToken(gateway, "http://[::1"))
        assertFalse(AuthScope.shouldAttachToken(null, null))
    }

    @Test
    fun `lan hostname with underscore still matches itself`() {
        // 真实场景：Windows 机器名带下划线，JDK 的 `URI.getHost()` 对它返回 null、
        // `getPort()` 返回 -1，所以主机与端口必须从 authority 字面量自己切，
        // 否则不同端口会被归一成同一个默认端口（fail-open）。
        assertTrue(AuthScope.shouldAttachToken("http://my_pc:4100", "http://my_pc:4100/api/ping"))
        assertFalse(AuthScope.shouldAttachToken("http://my_pc:4100", "http://other_pc:4100/api/ping"))
        assertFalse(AuthScope.shouldAttachToken("http://my_pc:4100", "http://my_pc:9999/api/ping"))
    }

    @Test
    fun `ipv6 literal hosts are compared with their port`() {
        assertTrue(AuthScope.shouldAttachToken("http://[::1]:4100", "http://[::1]:4100/api/ping"))
        assertFalse(AuthScope.shouldAttachToken("http://[::1]:4100", "http://[::1]:9999/api/ping"))
        assertFalse(AuthScope.shouldAttachToken("http://[::1]:4100", "http://[::2]:4100/api/ping"))
    }
}
