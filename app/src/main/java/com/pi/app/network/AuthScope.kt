package com.pi.app.network

import java.net.IDN
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
        val (host, explicitPort, isBracketedIpv6) = splitHostPort(hostPort) ?: return null
        val normalizedHost = if (isBracketedIpv6) {
            val canonicalIpv6 = normalizeIpv6(host) ?: return null
            "[$canonicalIpv6]"
        } else {
            normalizeDomainOrIpv4(host) ?: return null
        }
        // `:80` / `:443` 显式写出与省略必须判成同一台主机，否则用户手打端口就掉线。
        return Authority(scheme, normalizedHost, explicitPort ?: defaultPort)
    }

    private data class HostPort(val host: String, val port: Int?, val isBracketedIpv6: Boolean)

    /**
     * 把 `host:port` / `[v6]:port` / `host` 切成（主机, 端口, 是否方括号 IPv6）。
     *
     * 端口写成非数字（`:abc`）或超出 1..65535 时返回 null —— 这种地址 OkHttp 自己也会拒绝，
     * 没必要为了“宽容”去猜一个端口出来。
     */
    private fun splitHostPort(hostPort: String): HostPort? {
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end <= 1) return null
            val rest = hostPort.substring(end + 1)
            val port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> {
                    val p = rest.substring(1).toIntOrNull() ?: return null
                    if (p !in 1..65535) return null
                    p
                }
                else -> return null
            }
            return HostPort(hostPort.substring(1, end), port, isBracketedIpv6 = true)
        }
        if (hostPort.contains('[') || hostPort.contains(']')) return null
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return HostPort(hostPort, null, isBracketedIpv6 = false)
        val hostPart = hostPort.substring(0, colon)
        if (hostPart.contains(':')) return null
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return HostPort(hostPart, port, isBracketedIpv6 = false)
    }

    /** 使用纯 JVM [IDN.toASCII] 规范化国际化域名，同时拒绝空标签与空白字符。 */
    private fun normalizeDomainOrIpv4(rawHost: String): String? {
        if (rawHost.isEmpty() || rawHost.any { it.isWhitespace() || it == '[' || it == ']' || it == ':' }) {
            return null
        }
        val trimmed = rawHost.trimEnd('.')
        if (trimmed.isEmpty()) return null
        val ascii = runCatching { IDN.toASCII(trimmed) }.getOrNull() ?: return null
        val normalized = ascii.lowercase(Locale.ROOT).trimEnd('.')
        if (normalized.isEmpty()) return null
        return if (normalized.split('.').any { it.isEmpty() }) null else normalized
    }

    /**
     * 纯 Kotlin 实现的 IPv6 地址 16-byte 解析与 RFC 5952 规范化。
     * 不依赖 Android API 或 InetAddress。
     */
    private fun normalizeIpv6(rawIpv6: String): String? {
        if (rawIpv6.isEmpty() || rawIpv6.any { it.isWhitespace() }) return null
        if (rawIpv6.contains(":::")) return null

        val doubleColonIdx = rawIpv6.indexOf("::")
        val bytes = ByteArray(16)

        if (doubleColonIdx < 0) {
            if (rawIpv6.startsWith(':') || rawIpv6.endsWith(':')) return null
            val filled = parseIpv6Segment(rawIpv6, allowIpv4Suffix = true, dst = bytes, dstOffset = 0, maxBytes = 16)
                ?: return null
            if (filled != 16) return null
        } else {
            if (rawIpv6.indexOf("::", doubleColonIdx + 2) >= 0) return null
            val left = rawIpv6.substring(0, doubleColonIdx)
            val right = rawIpv6.substring(doubleColonIdx + 2)

            val leftBytes = ByteArray(16)
            val leftLen = if (left.isEmpty()) {
                0
            } else {
                if (left.startsWith(':') || left.endsWith(':')) return null
                parseIpv6Segment(left, allowIpv4Suffix = false, dst = leftBytes, dstOffset = 0, maxBytes = 14)
                    ?: return null
            }

            val rightBytes = ByteArray(16)
            val rightLen = if (right.isEmpty()) {
                0
            } else {
                if (right.startsWith(':') || right.endsWith(':')) return null
                parseIpv6Segment(right, allowIpv4Suffix = true, dst = rightBytes, dstOffset = 0, maxBytes = 14)
                    ?: return null
            }

            if (leftLen + rightLen > 14) return null

            leftBytes.copyInto(bytes, destinationOffset = 0, startIndex = 0, endIndex = leftLen)
            rightBytes.copyInto(bytes, destinationOffset = 16 - rightLen, startIndex = 0, endIndex = rightLen)
        }

        return formatIpv6Rfc5952(bytes)
    }

    private fun parseIpv6Segment(
        segment: String,
        allowIpv4Suffix: Boolean,
        dst: ByteArray,
        dstOffset: Int,
        maxBytes: Int
    ): Int? {
        val parts = segment.split(':')
        var pos = dstOffset
        val limit = dstOffset + maxBytes
        for (idx in parts.indices) {
            val part = parts[idx]
            if (part.isEmpty()) return null
            if (part.contains('.')) {
                if (!allowIpv4Suffix || idx != parts.lastIndex) return null
                if (pos + 4 > limit) return null
                val v4 = parseIpv4Bytes(part) ?: return null
                for (b in v4) {
                    dst[pos++] = b
                }
            } else {
                val value = parseHexWord(part) ?: return null
                if (pos + 2 > limit) return null
                dst[pos++] = (value ushr 8).toByte()
                dst[pos++] = (value and 0xFF).toByte()
            }
        }
        return pos - dstOffset
    }

    private fun parseHexWord(hex: String): Int? {
        if (hex.isEmpty() || hex.length > 4) return null
        var acc = 0
        for (ch in hex) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> return null
            }
            acc = (acc shl 4) or digit
        }
        return acc
    }

    private fun parseIpv4Bytes(ipv4: String): ByteArray? {
        val octets = ipv4.split('.')
        if (octets.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val s = octets[i]
            if (s.isEmpty() || s.length > 3) return null
            if (s.length > 1 && s[0] == '0') return null
            var v = 0
            for (ch in s) {
                if (ch !in '0'..'9') return null
                v = v * 10 + (ch - '0')
            }
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun formatIpv6Rfc5952(bytes: ByteArray): String {
        val hextets = IntArray(8) { i ->
            ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
        }

        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (hextets[i] == 0) {
                val start = i
                while (i < 8 && hextets[i] == 0) i++
                val len = i - start
                if (len >= 2 && len > bestLen) {
                    bestStart = start
                    bestLen = len
                }
            } else {
                i++
            }
        }

        val sb = StringBuilder()
        var idx = 0
        while (idx < 8) {
            if (idx == bestStart) {
                sb.append("::")
                idx += bestLen
            } else {
                if (idx > 0 && idx != bestStart + bestLen) {
                    sb.append(':')
                }
                sb.append(hextets[idx].toString(16))
                idx++
            }
        }
        return sb.toString()
    }
}
