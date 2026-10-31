package com.pi.app.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 网关请求失败的语义归类。
 *
 * 为什么需要它：同一件事在不同链路下会给出**完全不同的 HTTP 码**。典型例子是公网隧道
 * 掉线——Cloudflare 回 502/1033，frp 作为 vhost 路由器回它自己的 **404 "no route found"**。
 * 直接把码砸给用户，看到的就是「获取会话列表失败: 404」，看起来像地址写错或接口不存在，
 * 实际含义却是「隧道客户端没连上，几秒后自己会好」。
 *
 * 归类之后，每种情况都能给出**不同的下一步动作**：等几秒 / 重启 pi-web / 重新扫码 / 升级版本。
 */
enum class GatewayErrorKind {
    /** 公网隧道暂时没有客户端注册（frp 的 404 "no route found"、nginx 的 502/503/504）。 */
    TunnelDown,

    /** 隧道是通的，但网关转发不到本机 pi-web（网关自己的 502，正文里点名 pi-web）。 */
    LocalPiWebDown,

    /** 密钥被拒（401/403）：唯一出路是重新扫码配对。 */
    AuthRejected,

    /** 网关正常应答但这个接口不存在（JSON 404）：多半是 App 与 pi-web 版本不匹配。 */
    NotFound,

    /** 网关内部错误（其它 5xx）。 */
    ServerError,

    /** 读超时 / callTimeout：链路慢或半死，已由调用方重试过一次。 */
    Timeout,

    /** 连不上：DNS、握手、连接被重置（切基站、NAT 回收旧映射）。 */
    Network,

    /** 其余情况，保留原文案便于排查。 */
    Unknown
}

/**
 * 服务端返回了非 2xx。带上路径与正文片段，才能区分「隧道掉线的 HTML 404」
 * 与「接口真不存在的 JSON 404」——两者的处理方式完全不同。
 */
class GatewayHttpException(
    val code: Int,
    val path: String,
    val bodyPreview: String? = null
) : IOException("HTTP $code $path")

/** 一次失败的结构化结论。 */
data class GatewayFailure(
    val kind: GatewayErrorKind,
    val code: Int?,
    val detail: String
) {
    /** 给用户看的一句话：说清「发生了什么」和「该做什么」，不暴露 HTTP 术语。 */
    val userMessage: String
        get() = when (kind) {
            GatewayErrorKind.TunnelDown -> "公网隧道暂时断开，正在自动重连（稍等几秒）"
            GatewayErrorKind.LocalPiWebDown -> "网关连不上本机 pi-web，请确认它已启动"
            GatewayErrorKind.AuthRejected -> "密钥无效或访问被拒绝（401/403），请重新扫码配对"
            GatewayErrorKind.NotFound -> "网关没有这个接口（HTTP 404），请确认 pi-web 与 App 版本"
            GatewayErrorKind.ServerError -> "网关内部错误（HTTP ${code ?: 500}），请查看 PC 端日志"
            GatewayErrorKind.Timeout -> "网关响应超时（已自动重试一次），请检查网络"
            GatewayErrorKind.Network -> "连不上网关：请检查手机网络与服务器地址"
            GatewayErrorKind.Unknown -> detail.ifBlank { "连接失败，请重试" }
        }

    /** toast 去重用的键：同类错误在一个窗口内只提示一次。 */
    val toastKey: String get() = kind.name
}

object GatewayErrorClassifier {

    /** frp / nginx 这类基础设施层返回的裸 HTML 页面特征。 */
    private val EDGE_PAGE_MARKERS = listOf(
        "<!doctype",
        "<html",
        "404 page not found",
        "no route found",
        "502 bad gateway",
        "503 service temporarily unavailable"
    )

    /** 网关自己 502 时正文里会点名 pi-web（见 gateway.js 的 Bad Gateway 分支）。 */
    private val PI_WEB_DOWN_MARKERS = listOf("pi-web", "本地 pi-web")

    private val HTTP_CODE = Regex("\\b([45]\\d{2})\\b")

    fun classify(error: Throwable?): GatewayFailure {
        if (error == null) return GatewayFailure(GatewayErrorKind.Unknown, null, "")
        val detail = error.message?.trim().orEmpty()

        if (error is GatewayHttpException) {
            return classifyCode(error.code, error.bodyPreview, detail)
        }

        val name = error.javaClass.simpleName
        val lower = detail.lowercase()
        // 兼容历史形态的 "xxx: 404" 字符串（真正的 HTTP 失败已走 GatewayHttpException）
        val legacyCode = HTTP_CODE.find(detail)?.groupValues?.get(1)?.toIntOrNull()

        return when {
            error is SocketTimeoutException || error is InterruptedIOException ||
                name.contains("Timeout") || lower.contains("timeout") ->
                GatewayFailure(GatewayErrorKind.Timeout, null, detail)

            error is UnknownHostException || error is ConnectException || error is SSLException ->
                GatewayFailure(GatewayErrorKind.Network, null, detail)

            lower.contains("unable to resolve host") || lower.contains("failed to connect") ||
                lower.contains("stream reset") || lower.contains("unexpected end of stream") ||
                lower.contains("connection reset") || lower.contains("connection closed") ->
                GatewayFailure(GatewayErrorKind.Network, null, detail)

            // 历史形态：把状态码写在文案里
            legacyCode != null -> classifyCode(legacyCode, null, detail)

            detail.isBlank() -> GatewayFailure(GatewayErrorKind.Unknown, null, name)
            else -> GatewayFailure(GatewayErrorKind.Unknown, null, detail)
        }
    }

    private fun classifyCode(code: Int, bodyPreview: String?, detail: String): GatewayFailure {
        val body = bodyPreview.orEmpty().lowercase()
        val looksLikeEdgePage = EDGE_PAGE_MARKERS.any { body.contains(it) }
        return when {
            code == 401 || code == 403 ->
                GatewayFailure(GatewayErrorKind.AuthRejected, code, detail)

            // 隧道没起来时，frp 用它自己的 404 页面应答每一个路径；接口真不存在时 pi-web 回 JSON
            code == 404 && looksLikeEdgePage ->
                GatewayFailure(GatewayErrorKind.TunnelDown, code, detail)

            code == 404 -> GatewayFailure(GatewayErrorKind.NotFound, code, detail)

            code == 502 || code == 503 || code == 504 ->
                if (PI_WEB_DOWN_MARKERS.any { body.contains(it) }) {
                    GatewayFailure(GatewayErrorKind.LocalPiWebDown, code, detail)
                } else {
                    GatewayFailure(GatewayErrorKind.TunnelDown, code, detail)
                }

            code in 500..599 -> GatewayFailure(GatewayErrorKind.ServerError, code, detail)

            else -> GatewayFailure(GatewayErrorKind.Unknown, code, detail)
        }
    }
}
