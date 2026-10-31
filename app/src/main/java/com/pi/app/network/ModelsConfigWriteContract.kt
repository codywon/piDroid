package com.pi.app.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `PUT /api/models-config` 的请求/错误契约（纯 JVM，可单测）。
 *
 * 权威依据是 pi-web 的 `app/api/models-config/route.ts`：body 就是 models.json 本身，
 * 服务端直接读顶层 `providers` 后校验并写盘 —— **不识别任何包装**。
 *
 * 这条约束值得单独成文，因为历史上这里做过「失败就换 `{config: …}` 再试一次」的兼容重试。
 * 在 0.13.0 里 `{config: …}` 读不到 `providers`，provider 校验与图片上限校验会整体跳过，
 * 本该被拒的配置（例如只有 `api` 没有 `baseUrl` 的空 provider 条目）反而能写进 models.json，
 * 变成 pi 加载不了的坏结构。所以只发一种结构，也不在结果未知时自动重放写请求。
 */
internal object ModelsConfigWriteContract {

    /**
     * 写请求的 body：去掉历史上可能被写进去的 `config` 递归包装，保持顶层 `{providers: …}`。
     * 返回值就是入参本身（原地清理），调用方拿到的仍是要写盘的那份配置。
     */
    fun payloadOf(config: JsonObject): JsonObject {
        config.remove("config")
        return config
    }

    /**
     * 失败时给用户看的文案：优先服务端的人类可读 `error`（如
     * `Provider new-provider needs a Base URL or at least one model`），
     * 否则退回 HTTP 状态码。空 body / 非 JSON（代理返回的 HTML 错误页）都不能抛。
     */
    fun errorMessage(body: String?, httpCode: Int): String {
        val serverError = body?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            runCatching {
                JsonParser.parseString(text).asJsonObject.get("error")
                    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            }.getOrNull()
        }
        return serverError?.takeIf { it.isNotBlank() } ?: "HTTP $httpCode"
    }
}
