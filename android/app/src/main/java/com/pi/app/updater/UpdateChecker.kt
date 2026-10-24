package com.pi.app.updater

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val latestVersion: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long = 0L,
    val hasNewVersion: Boolean = false
)

class UpdateChecker(private val currentVersion: String = "1.1.1") {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /**
     * 自动从 GitHub 官方 Releases 检查最新发布版本
     */
    suspend fun checkForUpdates(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/codywon/piDroid/releases/latest"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "piDroid-Mobile-Client")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = gson.fromJson(body, JsonObject::class.java)

                    val tagName = json.get("tag_name")?.asString ?: ""
                    val cleanLatestVersion = tagName.trimStart('v', 'V')
                    val bodyNotes = json.get("body")?.asString ?: "无更新描述"

                    var downloadUrl = ""
                    var apkSize = 0L

                    // 寻找 releases assets 中的 apk 附件
                    val assets = json.getAsJsonArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.size()) {
                            val assetObj = assets[i].asJsonObject
                            val name = assetObj.get("name")?.asString ?: ""
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = assetObj.get("browser_download_url")?.asString ?: ""
                                apkSize = assetObj.get("size")?.asLong ?: 0L
                                break
                            }
                        }
                    }

                    val hasNewVersion = isVersionGreater(cleanLatestVersion, currentVersion.trimStart('v', 'V'))

                    Result.success(
                        UpdateInfo(
                            latestVersion = tagName,
                            changelog = bodyNotes,
                            downloadUrl = downloadUrl,
                            apkSize = apkSize,
                            hasNewVersion = hasNewVersion
                        )
                    )
                } else {
                    Result.failure(Exception("GitHub API HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isVersionGreater(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val length = maxOf(latestParts.size, currentParts.size)

            for (i in 0 until length) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }
}
