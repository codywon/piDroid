package com.pi.app.network

import com.pi.app.data.storage.AppPreferences
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val prefs: AppPreferences) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 请求已经自带 Authorization 时必须原样放行。
        // 扫码配对 / 手动配置的「试连」用的是刚扫到的新 Token，而本地 prefs 里可能还是
        // 上一轮配对的旧 Token。这里若无条件覆盖，轮换 Token 后握手永远用旧值，
        // 结果就是 401 且永远无法重新配对（旧版 bug）。
        if (originalRequest.header("Authorization") != null) {
            // 候选 Token 的 401 只说明「这一把填错了」，不参与全局失效判定
            return classify(chain.proceed(originalRequest), usedStoredToken = false)
        }

        val token = prefs.authToken.trim()
        if (token.isEmpty()) {
            return chain.proceed(originalRequest)
        }

        val requestBuilder = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")

        val response = chain.proceed(requestBuilder.build())
        // 请求飞行期间用户可能点了「断开连接」清空 prefs：那种情况下这个 401 与手里的值无关
        return classify(response, usedStoredToken = prefs.authToken.trim() == token)
    }

    /**
     * 只做一件事：把「网关拒绝了我们存的 Token」这个事实交给 [AuthRejectionSignal]，
     * 让轮询能停下、界面能提示重新扫码（此前这类 401 被各条轮询静默吞掉，
     * 网关日志却满屏都是）。
     */
    private fun classify(response: Response, usedStoredToken: Boolean): Response {
        AuthRejectionSignal.apply(
            AuthRejection.verdict(
                statusCode = response.code,
                wwwAuthenticate = response.headers["WWW-Authenticate"],
                usedStoredToken = usedStoredToken
            )
        )
        return response
    }
}
