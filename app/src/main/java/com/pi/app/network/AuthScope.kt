package com.pi.app.network

import java.util.Locale

/**
 * 「这条请求该不该带上网关 Bearer Token」的唯一判定点。
 *
 * 为什么必须单独有这一层：网关 Token 不是普通的接口密钥，它是 `server/gateway.js` 里那把
 * **全权凭据**（`AUTH_TOKEN`）—— 拿到它就能连上伴侣 PC 的 pi-web，在用户电脑上执行命令、
 * 读写文件。而 App 里有两条路径会把请求发到用户没配置过的第三方域名：
 *
 * 1. `AuthInterceptor` 挂在主 `OkHttpClient` 上，对所有经过它的请求无条件附加 Token
 *    （技能市场兜底直连 `https://skills.sh/api/search` 就是这么泄出去的）；
 * 2. 聊天里的 Markdown 外链图片（`![](https://图床/x.png)`）会原样进入媒体加载器，
 *    旧实现同样无条件附加 Token，等于让任何一个图床都能接管伴侣电脑。而且这条是**被动**的：
 *    图片进视口就自动发请求，不需要用户点任何东西。
 *
 * 判定规则只有一条：**目标 URL 的 scheme + host + port 与已配置的网关完全一致**才附带。
 *
 * 为什么不用 `java.net.URI` 解析：它对路径里的空格、`|`、`{}`、CJK 一律抛异常，而这些字符
 * OkHttp 会自己百分号编码后照常发出去（工作区路径里带中文和空格的图片正是如此）。用它判定
 * 会把「本来能加载的网关图片」误判成「不是网关」而不带 Token，变成 403 的功能回归。
 * 这里只解析 scheme 与 authority —— 也就是 OkHttp 真正要拨号的那部分 —— 路径一律不参与判定。
 * authority 切不出来（空、缺 host、端口非数字、非 http(s)）才 fail-closed。
 */
object AuthScope {

    /**
     * `scheme://authority` 的切分，authority 到 `/`、`?`、`#` 为止。
     *
     * 反斜杠不写在字符类里：OkHttp 把它当**路径分隔符**，所以先把 `\` 归一化成 `/`
     * 再匹配（见 [authorityOf]），否则 `http://evil.com\@gw/x` 里 `@` 之后的部分
     * 会被当成主机名，正好可以用来伪装成网关。
     */
    private val AUTHORITY_PATTERN = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)""")

    fun shouldAttachToken(gatewayUrl: String?, requestUrl: String?): Boolean {
        val gateway = authorityOf(gatewayUrl) ?: return false
        val target = authorityOf(requestUrl) ?: return false
        return gateway == target
    }

    /** `scheme://host:port` 的规范化形态，只用于等值比较。 */
    private data class Authority(val scheme: String, val host: String, val port: Int)

    private fun authorityOf(rawUrl: String?): Authority? {
        // OkHttp 把反斜杠当路径分隔符（`http://evil.com\@gw/x` 真正连的是 evil.com），
        // 先按同一规则归一化再切 authority；这一步只会让 authority 更早结束，
        // 不可能把别人的主机变成网关。
        val text = rawUrl?.trim().orEmpty().replace('\\', '/')
        if (text.isEmpty()) return null
        val match = AUTHORITY_PATTERN.find(text) ?: return null
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        // 只认 http(s)：file: / data: / javascript: 之类既不是网关，也无从谈凭据。
        val defaultPort = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> return null
        }
        // 去掉 userinfo：`https://gw@evil.com` 真正连的是 evil.com
        val hostPort = match.groupValues[2].substringAfterLast('@')
        val (host, explicitPort) = splitHostPort(hostPort) ?: return null
        val normalizedHost = host.lowercase(Locale.ROOT).trimEnd('.')
        if (normalizedHost.isEmpty()) return null
        // `:80` / `:443` 显式写出与省略必须判成同一台主机，否则用户手打端口就掉线。
        return Authority(scheme, normalizedHost, explicitPort ?: defaultPort)
    }

    /**
     * 把 `host:port` / `[v6]:port` / `host` 切成（主机, 端口）。
     *
     * 端口写成非数字（`:abc`）时返回 null —— 这种地址 OkHttp 自己也会拒绝，
     * 没必要为了“宽容”去猜一个端口出来。
     */
    private fun splitHostPort(hostPort: String): Pair<String, Int?>? {
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end <= 1) return null
            val rest = hostPort.substring(end + 1)
            val port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
            return hostPort.substring(1, end) to port
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort to null
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        return hostPort.substring(0, colon) to port
    }
}
