package com.pi.app.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pi.app.data.model.Session
import com.pi.app.data.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class PiApiClient(private val prefs: AppPreferences) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(prefs))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 验证服务器与 Token 是否有效
     */
    suspend fun ping(targetUrl: String? = null, token: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = targetUrl ?: prefs.serverUrl
        val authToken = token ?: prefs.authToken
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("Server URL is empty"))

        try {
            val req = Request.Builder()
                .url("$baseUrl/api/ping")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(response.body?.string() ?: "OK")
                } else {
                    Result.failure(IOException("Server error: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取所有会话列表
     */
    suspend fun getSessions(): Result<List<Session>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("Server URL is empty"))

        try {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val type = object : TypeToken<List<Session>>() {}.type
                    val list: List<Session> = gson.fromJson(body, type) ?: emptyList()
                    Result.success(list)
                } else {
                    Result.failure(IOException("Failed to load sessions: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 向指定会话下发用户指令
     */
    suspend fun sendCommand(sessionId: String, prompt: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val payload = mapOf("prompt" to prompt)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)

            val req = Request.Builder()
                .url("$baseUrl/api/agent/$sessionId")
                .post(body)
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(IOException("Send command failed: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 提交工具审批（批准或拒绝高风险命令）
     */
    suspend fun sendApproval(sessionId: String, approved: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val payload = mapOf("approved" to approved)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)

            val req = Request.Builder()
                .url("$baseUrl/api/agent/$sessionId/approval")
                .post(body)
                .build()

            client.newCall(req).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
