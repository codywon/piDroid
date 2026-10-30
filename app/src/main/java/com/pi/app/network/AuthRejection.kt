package com.pi.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 判定「这个 401 是不是网关在拒绝我们手里的 Bearer Token」。
 *
 * 为什么不能「见 401 就当密钥失效」：pi-web 自己也会 401 —— 模型供应商的 API Key 填错时
 * 它会返回 `Invalid API key`（见 `GlobalModelsConfigTab` 的清理入口）。那跟网关密钥毫无关系，
 * 误判会把用户往「重新扫码」的死路上推，而真正该做的是去清掉那把坏 Key。
 *
 * 网关的 401 带独有标记（`server/gateway.js` 的 `authMiddleware`）：
 * `WWW-Authenticate: Bearer realm="pi-gateway", error="invalid_token"`。
 * 只有它才意味着「本地存的是上一轮轮换掉的旧 Token」，唯一出路是重新扫码。
 */
object AuthRejection {
    /** 必须与 `server/gateway.js` 写出的头逐字一致（只比对 `invalid_token`，对前导格式宽容）。 */
    const val WWW_AUTHENTICATE_INVALID_TOKEN = "Bearer realm=\"pi-gateway\", error=\"invalid_token\""

    /** 一次请求对「密钥是否失效」这件事给出的结论。 */
    enum class Verdict {
        /** 与本机密钥无关（网络错误、pi-web 自己的 401、5xx…），不改状态。 */
        NONE,

        /** 网关拒绝了我们手里的 Token：本地存的是旧值。 */
        REJECTED,

        /** 请求成功：手里的 Token 是有效的，之前置位的失效状态可以清了。 */
        CLEARED
    }

    fun isGatewayInvalidToken(statusCode: Int, wwwAuthenticate: String?): Boolean {
        if (statusCode != 401) return false
        val header = wwwAuthenticate ?: return false
        return header.contains("invalid_token", ignoreCase = true)
    }

    /**
     * @param usedStoredToken 这次请求带的是 `prefs.authToken`，而不是扫码 / 手填时的候选 Token。
     *   候选 Token 的 401 只说明「这一把填错了」（配对对话框正在让用户改），
     *   不能让整个 App 进入「密钥失效」状态，更不能把正常在用的会话停掉。
     */
    fun verdict(statusCode: Int, wwwAuthenticate: String?, usedStoredToken: Boolean): Verdict = when {
        // 任何一次成功都证明手里的 Token 有效：重扫后第一条 200 就能自动解除失效状态，
        // 不需要谁记得去「复位」这个信号。
        statusCode in 200..299 -> Verdict.CLEARED
        isGatewayInvalidToken(statusCode, wwwAuthenticate) && usedStoredToken -> Verdict.REJECTED
        else -> Verdict.NONE
    }
}

/**
 * 全局「网关密钥已失效」信号。
 *
 * 起因：轮换 Token 后 App 手里还是旧值，而各条轮询（`/api/agent/running` 每 2.5s、
 * 会话页另外几条）都是静默 `getOrNull()` 吞掉错误 —— 于是网关日志里刷出一片
 * `[auth] 401 ... token=present(len=35)`，用户界面却只表现为「怎么都不更新」，没有任何出路提示。
 *
 * 现在的约定：
 * - 置位后列表页 / 会话页的轮询循环立即退出（不再用无效 Token 打穿网关日志）；
 * - 界面弹一次「密钥已失效，请重新扫码配对」；
 * - 重新配对成功后，第一条成功请求就会把它清零，轮询自动恢复。
 */
object AuthRejectionSignal {
    private val _rejected = MutableStateFlow(false)
    val rejected: StateFlow<Boolean> = _rejected.asStateFlow()

    fun reportRejected() {
        _rejected.value = true
    }

    fun clear() {
        _rejected.value = false
    }

    /** 把一次请求的判定落到信号上。 */
    fun apply(verdict: AuthRejection.Verdict) {
        when (verdict) {
            AuthRejection.Verdict.REJECTED -> reportRejected()
            AuthRejection.Verdict.CLEARED -> clear()
            AuthRejection.Verdict.NONE -> Unit
        }
    }
}
