package com.pi.app.data.model

/**
 * 图片字节「怎么取、怎么存、怎么解」的三条纯逻辑。
 *
 * 为什么单独抽出来：这三件事分别在 `MediaPreviewComponents`（内存 LruCache）、
 * `resolveMediaBytes`（URL 拼接）和图卡解码里各写了一遍，任一处算错都只在真机上
 * 表现为「图一直转圈 / 内存暴涨」，无法单测。抽成纯 JVM 逻辑后可以逐条钉死。
 *
 * 背景（会话图片多的会话，实测 1263 条消息里 58 条带图、base64 合计 15.5MB）：
 *  - 旧内存池 `LruCache(32)` 按**项数**计容量，单张放宽到 15MB，最坏 32×15MB≈480MB；
 *  - 旧解码 `BitmapFactory.decodeByteArray` 全尺寸出图，而 UI 最大只显示 260dp；
 *  - 图片字节没有任何磁盘缓存，进程一结束就全部重新下载。
 */
object MediaLoadPolicy {

    /** 解码目标长边（px）：屏幕宽度的 2 倍，超过就按 2 的幂降采样。 */
    const val DECODE_TARGET_SCREEN_FACTOR = 2

    /** 兜底解码目标（拿不到屏幕信息时用）：1080×2 对手机足够清晰。 */
    const val FALLBACK_DECODE_TARGET_PX = 2160

    /** 内存池上限：按**字节**计，不再按项数计。 */
    const val MEMORY_CACHE_MAX_BYTES = 24 * 1024 * 1024

    /** 单张图落盘上限：超过不落盘，避免一张几十 MB 的图挤掉整个池。 */
    const val MAX_DISK_ENTRY_BYTES = 15 * 1024 * 1024

    /** 磁盘池上限：超过后按最后访问时间（mtime）淘汰最旧的。 */
    const val DISK_CACHE_MAX_BYTES = 64L * 1024 * 1024

    /**
     * 工具（tool call / tool result）产出的附件 id 前缀。
     *
     * 目的：让这类附件的 id 在**每次解析快照时都一样**，于是图片字节的磁盘缓存能跨进程
     * 复用（随机 UUID 会让每次冷启动都全量重下）；同时工具**又被调用一次**时 toolCallId
     * 会变，缓存自然失效——不必用 TTL 去猜文件是不是被重写过了。
     */
    const val TOOL_ATTACHMENT_ID_PREFIX = "tcid:"

    /** 把工具 id 转成附件 id 前缀；为空时返回 null（调用方退回随机 UUID）。 */
    fun toolIdPrefix(toolCallId: String?): String? =
        toolCallId?.takeIf { it.isNotBlank() }?.let { TOOL_ATTACHMENT_ID_PREFIX + it }

    /**
     * 计算 `BitmapFactory.Options.inSampleSize`：取 2 的幂，使解码后的长边**不超过**
     * [maxLongEdgePx]（原图本来就小于目标时返回 1，不降采样）。
     *
     * 例：4000×3000 的 CAD 图、目标 2160 → 返回 2（解成 2000×1500，内存从≈48MB 降到≈12MB）。
     */
    fun sampleSizeFor(srcWidth: Int, srcHeight: Int, maxLongEdgePx: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxLongEdgePx <= 0) return 1
        val longEdge = maxOf(srcWidth, srcHeight)
        var sample = 1
        while (longEdge / sample > maxLongEdgePx) sample *= 2
        return sample
    }

    /**
     * 磁盘缓存 key：按**内容位置**（文件路径 / 远端 URL）而不是随机 id。
     *
     * 两个约束同时满足：
     *  - `MediaAttachment.id` 默认是随机 UUID，每次重新解析快照都会变，拿它当磁盘 key 等于
     *    每次冷启动都全量重下；
     *  - 但同一路径的文件可能被工具**重写**（典型：CAD 脚本反复生成同名 png），纯按路径
     *    做 key 会把旧图一直当缓存。
     *
     * 所以只把「工具附件 id 的版本部分」（`tcid:<toolCallId>`）当缓存版本：同一次工具调用的
     * 历史无论重开多少次都命中，工具再被调用一次就自动换 key。
     *
     * 只有 Base64 内联的附件（用户当场发的图）没有位置，用「前 256 字符 + 总长度」近似内容寻址。
     *
     * @return 64 位十六进制 key；无法定位时返回空串（调用方跳过磁盘层）。
     */
    fun diskKey(attachmentId: String?, filePath: String?, url: String?, dataBase64: String?): String {
        val revision = attachmentId
            ?.takeIf { it.startsWith(TOOL_ATTACHMENT_ID_PREFIX) }
            ?.substringBefore('#')
        val path = filePath?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
        if (path != null) {
            return sha1Hex(buildString {
                if (revision != null) append(revision).append('|')
                append("loc|").append(path.replace('\\', '/'))
            })
        }
        val remote = url?.trim()?.takeIf { it.isNotEmpty() }
        if (remote != null) {
            return sha1Hex(buildString {
                if (revision != null) append(revision).append('|')
                append("url|").append(remote.replace('\\', '/'))
            })
        }
        val b64 = dataBase64?.trim()?.takeIf { it.isNotEmpty() }
        if (b64 != null) return sha1Hex("b64|" + b64.take(256) + "|" + b64.length)
        return ""
    }

    /**
     * 把「可能是相对的」远端地址拼成绝对 URL。
     *
     * pi-web 的延迟图给的是 `source.url = "/api/sessions/{id}/entries/{eid}/tool-result-image?blockIndex=n"`，
     * 只有拼上网关地址才请求得出去。
     */
    fun absoluteUrl(serverUrl: String, raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        val base = serverUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return if (value.startsWith("/")) base + value else "$base/$value"
    }

    /** SHA-1 十六进制小写（key 用，不涉及任何安全语义）。 */
    fun sha1Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
