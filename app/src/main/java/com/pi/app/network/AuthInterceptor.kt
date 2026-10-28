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
            return chain.proceed(originalRequest)
        }

        val token = prefs.authToken.trim()
        if (token.isEmpty()) {
            return chain.proceed(originalRequest)
        }

        val requestBuilder = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")

        return chain.proceed(requestBuilder.build())
    }
}
