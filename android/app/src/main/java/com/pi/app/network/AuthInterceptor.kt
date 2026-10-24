package com.pi.app.network

import com.pi.app.data.storage.AppPreferences
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val prefs: AppPreferences) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = prefs.authToken

        val requestBuilder = originalRequest.newBuilder()
        if (token.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return chain.proceed(requestBuilder.build())
    }
}
