package com.pi.app.data.model

import com.google.gson.JsonObject

/**
 * `POST /api/sessions/[id]/fork` 的请求体。
 *
 * pi-web 0.18.0 起这个路由多认一个可选 `cwd`：源会话的目录已被删除时，把整份历史复制到该目录
 * 下的一个新会话（上游 `forkSessionIntoCwd()`，pi 的 Fork）。不带 `cwd` 时仍是「在当前目录分叉
 * 出一个分支」，也就是 App 一直以来的行为。
 *
 * 服务端是用 `Object.hasOwn(body, "cwd")` 判定的，所以**不能**塞 `null` 或空串：那会被当成
 * fork-into-cwd，然后以 `cwd-invalid` 400 拒掉。
 */
fun forkRequestBody(targetCwd: String?): String {
    val cwd = targetCwd?.trim()
    if (cwd.isNullOrEmpty()) return "{}"
    return JsonObject().apply { addProperty("cwd", cwd) }.toString()
}
