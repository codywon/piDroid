package com.pi.app.network

import com.google.gson.JsonParser
import com.pi.app.data.model.FileDirectoryListing
import com.pi.app.data.model.FileEntry
import com.pi.app.data.model.FileIndexMatch

/**
 * 文件目录与索引响应的纯解析工具（纯 JVM，不依赖 Android 运行时）。
 *
 * 对应上游 @agegr/pi-web:
 * - `GET /api/files/<path>?type=list` (app/api/files/[...path]/route.ts)
 * - `GET /api/file-index?cwd=&q=` (app/api/file-index/route.ts)
 */
object FileListParsing {

    /**
     * 解析目录列表 JSON 响应。
     * 服务端格式：
     * {
     *   "entries": [
     *     {
     *       "name": "src",
     *       "isDir": true,
     *       "size": 0,
     *       "modified": "",
     *       "outsideLinkTarget": "/outside/path",
     *       "outsideLinkEncloses": true,
     *       "hidden": "ignored"
     *     }
     *   ],
     *   "path": "/project/path"
     * }
     */
    fun parseListing(json: String): FileDirectoryListing? {
        if (json.isBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.has("error")) return null

            val path = root.get("path")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val entriesArray = root.getAsJsonArray("entries") ?: return FileDirectoryListing(path = path)

            val entries = ArrayList<FileEntry>(entriesArray.size())
            for (element in entriesArray) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val isDir = obj.get("isDir")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                val size = obj.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val modified = obj.get("modified")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val outsideTarget = obj.get("outsideLinkTarget")?.takeIf { !it.isJsonNull }?.asString
                val outsideEncloses = obj.get("outsideLinkEncloses")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                val hidden = obj.get("hidden")?.takeIf { !it.isJsonNull }?.asString

                entries.add(
                    FileEntry(
                        name = name,
                        isDir = isDir,
                        size = size,
                        modified = modified,
                        outsideLinkTarget = outsideTarget,
                        outsideLinkEncloses = outsideEncloses,
                        hidden = hidden
                    )
                )
            }
            FileDirectoryListing(path = path, entries = sortEntries(entries))
        }.getOrNull()
    }

    /**
     * 解析错误响应体里的 error 信息。
     */
    fun parseError(json: String): String? {
        if (json.isBlank()) return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            root.get("error")?.takeIf { !it.isJsonNull }?.asString
                ?: root.get("detail")?.takeIf { !it.isJsonNull }?.asString
                ?: root.get("message")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    /**
     * 把文件/目录路径编码进 `/api/files/<path>` 的路由段。
     * 对标 @agegr/pi-web `encodeFilePathForApi` (lib/file-paths.ts):
     * 1. 统一换为正斜杠并过滤空段（关键！防止盘符根目录产生末尾斜杠导致反代/云端网关 404）；
     * 2. 保留 UNC 根 `//host` 作为首段；
     * 3. 逐段调用 URI 编码。
     */
    fun encodeFilePath(absPath: String): String {
        val normalized = absPath.trim().replace('\\', '/')
        val segments = normalized.split('/').filter { it.isNotEmpty() }.toMutableList()
        if (normalized.startsWith("//") && segments.isNotEmpty()) {
            segments[0] = "//${segments[0]}"
        }
        return segments.joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
    }

    /**
     * `type=read` 的响应是不是 JSON（文本块），而不是原始文件流。
     *
     * pi-web 的 `GET /api/files/<path>?type=read` 只对**文本类**回 `{content,nextOffset,...}`；
     * 一旦 `getImageMime()` 命中（**包括 SVG**）就直接把文件原样流回（`Content-Type: image/svg+xml`）。
     * 早期客户端一律当 JSON 解，于是 SVG 永远报「读取文件失败: HTTP 200」—— 状态码是 200，
     * 内容却是 XML。这里把判定抽成纯函数，便于单测。
     */
    fun isJsonBody(contentType: String, body: String): Boolean {
        if (contentType.contains("json", ignoreCase = true)) return true
        val firstMeaningful = body.firstOrNull { !it.isWhitespace() } ?: return false
        return firstMeaningful == '{'
    }

    /** 原始字节里是否出现了 NUL（与 pi-web `lib/text-preview.ts` 的二进制嗅探同义） */
    fun looksBinary(raw: String, sniffChars: Int = 8000): Boolean {
        val limit = minOf(raw.length, sniffChars)
        for (i in 0 until limit) {
            if (raw[i].code == 0) return true
        }
        return false
    }

    /**
     * 解析文件索引搜索结果。
     * 服务端格式：{ "matches": [ { "path": "src/App.tsx", "isDir": false } ] }
     */
    fun parseIndexMatches(json: String): List<FileIndexMatch> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            val matchesArray = root.getAsJsonArray("matches") ?: return emptyList()
            val result = ArrayList<FileIndexMatch>(matchesArray.size())
            for (element in matchesArray) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                val path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val isDir = obj.get("isDir")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                result.add(FileIndexMatch(path = path, isDir = isDir))
            }
            result
        }.getOrElse { emptyList() }
    }

    /**
     * 排序目录项：目录在前，文件在后；同类别按名称不区分大小写字典序排序。
     */
    fun sortEntries(entries: List<FileEntry>): List<FileEntry> {
        return entries.sortedWith(Comparator { a, b ->
            if (a.isDir != b.isDir) {
                if (a.isDir) -1 else 1
            } else {
                a.name.compareTo(b.name, ignoreCase = true)
            }
        })
    }

    /**
     * 拼接路径，并防止出现 `..` 目录穿越段。
     */
    fun joinPath(parent: String, child: String): String {
        val cleanParent = parent.replace('\\', '/').trimEnd('/')
        val cleanChild = child.replace('\\', '/').trimStart('/')
        val segments = cleanChild.split('/')
        if (segments.any { it == ".." }) {
            throw IllegalArgumentException("路径包含非法父级遍历段 (..)")
        }
        return when {
            cleanParent.isEmpty() -> cleanChild
            cleanChild.isEmpty() -> cleanParent
            // Windows 盘符根目录，例如 "C:" 拼 "foo" 应为 "C:/foo"
            cleanParent.length == 2 && cleanParent[1] == ':' && cleanParent[0].isLetter() -> "$cleanParent/$cleanChild"
            else -> "$cleanParent/$cleanChild"
        }
    }

    /**
     * 获取父级路径。根路径时返回 null。
     * 涵盖：
     * - POSIX: `/a/b` -> `/a`, `/a` -> `/`, `/` -> null
     * - Windows 盘符: `D:/a/b` -> `D:/a`, `D:/a` -> `D:/`, `D:/` -> null
     * - UNC: `//host/share/sub` -> `//host/share`, `//host/share` -> null
     */
    fun parentPath(path: String): String? {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty() || normalized == "/") return null

        // UNC: //host/share...
        if (normalized.startsWith("//")) {
            val content = normalized.substring(2).trim('/')
            val parts = content.split('/').filter { it.isNotEmpty() }
            if (parts.size <= 2) return null // //host 或 //host/share 是 UNC 根
            val parentParts = parts.dropLast(1)
            return "//" + parentParts.joinToString("/")
        }

        // Windows drive: C: 或 C:/
        if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) {
            val afterDrive = normalized.substring(2).trimStart('/')
            if (afterDrive.isEmpty()) return null // C: 或 C:/ 已是盘符根
            val idx = afterDrive.lastIndexOf('/')
            return if (idx < 0) {
                normalized.substring(0, 2) + "/"
            } else {
                normalized.substring(0, 2) + "/" + afterDrive.substring(0, idx)
            }
        }

        // POSIX 相对或绝对路径
        val idx = normalized.lastIndexOf('/')
        if (idx < 0) return null
        if (idx == 0) return "/"
        return normalized.substring(0, idx)
    }

    /**
     * 获取文件名或当前段展示名。
     */
    fun displayName(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty()) return ""
        val idx = normalized.lastIndexOf('/')
        return if (idx < 0) normalized else normalized.substring(idx + 1)
    }

    /**
     * 相对路径转换：若 fullPath 位于 cwd 内，返回其相对路径；否则返回原路径。
     */
    fun relativePath(fullPath: String, cwd: String): String {
        val normFull = fullPath.replace('\\', '/').trimEnd('/')
        val normCwd = cwd.replace('\\', '/').trimEnd('/')
        if (normCwd.isEmpty()) return normFull
        val isWindows = isWindowsStyle(normCwd)

        if (normFull.equals(normCwd, ignoreCase = isWindows)) return ""
        val prefix = if (normCwd.endsWith('/')) normCwd else "$normCwd/"
        val matchesPrefix = if (isWindows) {
            normFull.startsWith(prefix, ignoreCase = true)
        } else {
            normFull.startsWith(prefix)
        }
        return if (matchesPrefix) normFull.substring(prefix.length) else normFull
    }

    private fun isWindowsStyle(path: String): Boolean {
        return (path.length >= 2 && path[1] == ':' && path[0].isLetter()) || path.startsWith("//")
    }
}
