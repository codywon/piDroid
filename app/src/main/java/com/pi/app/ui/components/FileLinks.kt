package com.pi.app.ui.components

import android.net.Uri
import java.util.Locale

/**
 * 本地文件链接解析器：pi-web `lib/file-links.ts` 的 Kotlin 移植。
 *
 * pi-web 里 MarkdownBody 的 `<a>` 会用 `resolveLocalFileHref(href, cwd)` 把链接目标
 * 换算成「本机绝对路径」，命中就 `onOpenFile(path)` 在右侧面板打开文件预览，而不是
 * 让浏览器去跳转（file:// 会被浏览器拦掉）。手机端要复刻这套行为，就必须先有同一份
 * 解析规则——两边的差异会让同一条 `[报告](docs/x.md)` 在 PC 上能点、在手机上打不开。
 *
 * 规则与 pi-web 保持一致：
 * 1. 先剥掉 `#fragment` 与 `?query`（PDF 页号另有 [parsePdfPageFragment] 单独解析）；
 * 2. 支持 `file://` URL、Windows 盘符绝对路径（`D:/a/b.md`）、UNC（`\\\\host\\share`）、
 *    POSIX 绝对路径（`/home/a/b.md`）；
 * 3. 相对路径（`./x.md`、`../x.md`、`docs/x.md`、`x.md`）以会话工作目录为基准拼接；
 * 4. 相对路径解析结果必须仍在工作目录内（防止 `../../etc/passwd` 越界）；
 * 5. 剥离 `:12` / `:12:5` 这类源码行列后缀；
 * 6. `http(s):`、`mailto:`、`/api/`、`/_next/` 一律不当成本地文件。
 */
object FileLinks {

    private val WINDOWS_DRIVE_PREFIX = Regex("^[a-zA-Z]:[\\\\/]")
    private val WINDOWS_DRIVE_SLASH = Regex("^[a-zA-Z]:/")
    private val LEADING_DRIVE_SLASH = Regex("^/[a-zA-Z]:/")
    private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val SOURCE_LOCATION_SUFFIX = Regex(":\\d+(?::\\d+)?$")
    private val RELATIVE_FILE_HREF = Regex("""(^|/)\.?[^/]+\.[^/.]+$""")
    private val PDF_PAGE_FRAGMENT = Regex("^page=(\\d+)$", RegexOption.IGNORE_CASE)
    private val WINDOWS_ROOT_REGEX = Regex("^([a-zA-Z]:)(?:/|$)")
    private val UNC_ROOT_REGEX = Regex("^(//[^/]+/[^/]+)(?:/|$)")

    /**
     * 判断一个路径是不是 Git Bash / MSYS 形态的绝对路径（`/d/myscript/proj/a.md`）：
     * 首段只有单个盘符字母。
     */
    private fun msysDriveLetter(path: String): Char? {
        if (path.length < 2 || path[0] != '/') return null
        val letter = path[1]
        if (!(letter in 'a'..'z' || letter in 'A'..'Z')) return null
        if (path.length > 2 && path[2] != '/') return null
        return letter
    }

    /** 基准目录本身是不是 Windows 形态（盘符路径或 UNC）。 */
    fun isWindowsBaseDir(baseDir: String?): Boolean {
        if (baseDir.isNullOrBlank()) return false
        val normalized = normalizeSlashes(baseDir)
        return WINDOWS_DRIVE_SLASH.containsMatchIn(normalized) || normalized.startsWith("//")
    }

    /**
     * 把 MSYS 形态的路径换回 Windows 盘符路径：`/d/myscript/proj/a.md` → `D:/myscript/proj/a.md`。
     *
     * 为什么必须做：pi 在 Windows 上（Git Bash、`ls`/`find` 的输出、工具回显）经常把路径
     * 写成 `/d/...`。它既不是盘符绝对路径，也不是合法的 Windows 路径，原样送给
     * `/api/files/<path>` 会被 pi-web 的“允许根”检查判成不在任何会话工作目录内，
     * 直接 `403 Access denied` —— 文件其实就在工作区里（就是用户看到的现象：
     * 报告 md 点开报 Access denied、「用其他应用打开」下载 403）。
     *
     * 只在基准目录（会话 cwd / 当前文档目录）本身就是 Windows 路径时才转：同样的
     * `/d/x` 在 Linux 网关上就是真正的 POSIX 路径，不能动。
     */
    fun normalizeMsysDrivePath(path: String, baseDir: String?): String {
        val letter = msysDriveLetter(path) ?: return path
        if (!isWindowsBaseDir(baseDir)) return path
        return "${letter.uppercaseChar()}:" + path.substring(2)
    }

    /** Unicode 千分位不需要；这里只做大小写归一，便于前缀比较。 */
    private fun lower(value: String): String = value.lowercase(Locale.ROOT)

    /** 与 pi-web `normalizeFilePathSlashes` 一致：只对 Windows 形态的路径把 `\\` 折成 `/`。 */
    fun normalizeSlashes(path: String): String =
        if (WINDOWS_DRIVE_PREFIX.containsMatchIn(path) || path.startsWith("\\\\")) {
            path.replace('\\', '/')
        } else {
            path
        }

    private fun safeDecode(value: String): String = try {
        Uri.decode(value)
    } catch (_: Exception) {
        value
    }

    private fun stripLineSuffix(path: String): String = path.replace(SOURCE_LOCATION_SUFFIX, "")

    /**
     * 归一化 `.` / `..`，同时保住盘符、UNC 与根斜杠的语义。
     * 移植自 pi-web 同名私有函数，行为逐条对齐。
     */
    fun normalizeLocalPath(path: String): String {
        val normalized = normalizeSlashes(path)
        val isWindowsDrive = WINDOWS_DRIVE_SLASH.containsMatchIn(normalized)
        val isUnc = normalized.startsWith("//")
        val leadingSlash = normalized.startsWith("/") && !isWindowsDrive && !isUnc
        val parts = mutableListOf<String>()

        for (part in normalized.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> {
                    if (parts.isNotEmpty() && (!isWindowsDrive || parts.size > 1) && parts.last() != "..") {
                        parts.removeAt(parts.size - 1)
                    } else if (!leadingSlash && !isWindowsDrive && !isUnc) {
                        parts.add(part)
                    }
                }
                else -> parts.add(part)
            }
        }

        val joined = parts.joinToString("/")
        return when {
            isWindowsDrive -> joined
            isUnc -> "//$joined"
            leadingSlash -> "/$joined"
            else -> joined
        }
    }

    /** 候选路径是否落在 root 目录内（Windows 路径大小写不敏感）。 */
    fun isPathInside(candidate: String, root: String): Boolean {
        val normalizedCandidate = normalizeLocalPath(candidate).trimEnd('/')
        val normalizedRoot = normalizeLocalPath(root).trimEnd('/')
        val caseInsensitive = WINDOWS_DRIVE_SLASH.containsMatchIn(normalizedCandidate) ||
            WINDOWS_DRIVE_SLASH.containsMatchIn(normalizedRoot)
        val filePath = if (caseInsensitive) lower(normalizedCandidate) else normalizedCandidate
        val rootPath = if (caseInsensitive) lower(normalizedRoot) else normalizedRoot
        return filePath == rootPath || filePath.startsWith("$rootPath/")
    }

    private fun looksLikeRelativeFileHref(href: String): Boolean {
        if (href.startsWith("#") || href.startsWith("?")) return false
        if (href.startsWith("./") || href.startsWith("../")) return true
        if (href.contains("/")) return true
        return RELATIVE_FILE_HREF.containsMatchIn(href)
    }

    private fun fileUrlToPath(href: String): String? = try {
        val uri = Uri.parse(href)
        if (!"file".equals(uri.scheme, ignoreCase = true)) {
            null
        } else {
            val rawPath = uri.encodedPath ?: uri.path ?: ""
            val pathname = safeDecode(rawPath)
            val host = uri.host
            when {
                !host.isNullOrBlank() -> "//$host" + if (pathname.startsWith("/")) pathname else "/$pathname"
                LEADING_DRIVE_SLASH.containsMatchIn(pathname) -> pathname.substring(1)
                else -> pathname
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 把 Markdown 链接目标解析成本机绝对路径；不是本地文件时返回 null。
     *
     * @param href 链接原始目标（可能带 `#anchor`、`?query`、百分号编码）
     * @param baseDir 相对路径的基准目录（会话工作目录 / 当前被预览文档所在目录）
     * @param relativeRoot 相对路径必须落在其内的根目录，默认与 [baseDir] 相同
     */
    fun resolveLocalFileHref(
        href: String?,
        baseDir: String?,
        relativeRoot: String? = baseDir
    ): String? {
        if (href.isNullOrBlank()) return null

        val cleanHref = href.substringBefore('#').substringBefore('?').trim()
        if (cleanHref.isEmpty()) return null

        val decodedHref = safeDecode(cleanHref)
        val isBackslashUncPath = decodedHref.startsWith("\\\\")
        val normalizedHref = normalizeSlashes(decodedHref)
        val lowerHref = lower(normalizedHref)

        // 应用自身的 API / 静态资源不是文件。
        if (lowerHref.startsWith("/api/") || lowerHref.startsWith("/_next/")) return null
        // 协议相对 URL（//cdn.example.com/a.png）也不是本地文件。
        if (!isBackslashUncPath && normalizedHref.startsWith("//")) return null
        // http(s)/mailto/data 等：只有 file: 与 Windows 盘符例外。
        if (URL_SCHEME.containsMatchIn(normalizedHref) &&
            !lowerHref.startsWith("file:") &&
            !WINDOWS_DRIVE_SLASH.containsMatchIn(normalizedHref)
        ) {
            return null
        }

        var candidate: String? = null
        var candidateIsRelative = false

        when {
            lowerHref.startsWith("file:") -> candidate = fileUrlToPath(cleanHref)
            WINDOWS_DRIVE_SLASH.containsMatchIn(normalizedHref) -> candidate = normalizedHref
            normalizedHref.startsWith("/") ->
                candidate = normalizeMsysDrivePath(normalizedHref, baseDir)
            // 相对路径：只有长得像文件才拼接，避免把 `docs`（目录）误当文件。
            !baseDir.isNullOrBlank() && looksLikeRelativeFileHref(normalizedHref) -> {
                val base = normalizeSlashes(baseDir).trimEnd('/')
                candidate = "$base/$normalizedHref"
                candidateIsRelative = true
            }
        }

        val rawCandidate = candidate ?: return null
        val filePath = stripLineSuffix(normalizeLocalPath(rawCandidate))
        if (candidateIsRelative) {
            val root = relativeRoot
            // 相对路径必须落在基准目录内：`../../../../etc/passwd` 这类链接不能当成本地文件打开。
            if (root.isNullOrBlank() || !isPathInside(filePath, root)) return null
        }
        return filePath
    }

    /** 解析「本来就是路径」（不是 href）的字符串，例如工具入参里的相对路径。 */
    fun resolveLocalFilePath(filePath: String?, baseDir: String?): String? {
        if (filePath.isNullOrBlank()) return null

        val windowsStyle = WINDOWS_DRIVE_PREFIX.containsMatchIn(filePath) ||
            filePath.startsWith("\\\\") ||
            (baseDir != null && (WINDOWS_DRIVE_PREFIX.containsMatchIn(baseDir) || baseDir.startsWith("\\\\")))
        val normalize = { value: String -> if (windowsStyle) value.replace('\\', '/') else value }

        val normalizedPath = normalize(filePath)
        val normalizedBase = baseDir?.let { normalize(it).trimEnd('/') }

        val candidate = when {
            WINDOWS_DRIVE_SLASH.containsMatchIn(normalizedPath) || normalizedPath.startsWith("//") ->
                normalizedPath
            // `/d/proj/a.md`（Git Bash 形态）直接换成 `D:/proj/a.md`，
            // 否则会被下面的“补盘符”规则拼成 `D:/d/proj/a.md`，同样 403。
            msysDriveLetter(normalizedPath) != null && isWindowsBaseDir(baseDir) ->
                normalizeMsysDrivePath(normalizedPath, baseDir)
            normalizedPath.startsWith("/") -> {
                val windowsRoot = normalizedBase?.let { base ->
                    WINDOWS_ROOT_REGEX.find(base)?.groupValues?.get(1)
                        ?: UNC_ROOT_REGEX.find(base)?.groupValues?.get(1)
                }
                if (windowsRoot != null) "$windowsRoot$normalizedPath" else normalizedPath
            }
            else -> {
                if (normalizedBase.isNullOrBlank()) return null
                "$normalizedBase/$normalizedPath"
            }
        }

        return normalizeLocalPath(candidate)
    }

    /** 读取 PDF 的 `#page=12` 页号（其余 fragment 一律忽略）。 */
    fun parsePdfPageFragment(href: String?): Int? {
        if (href.isNullOrBlank()) return null
        val hashIndex = href.indexOf('#')
        if (hashIndex < 0) return null
        val match = PDF_PAGE_FRAGMENT.find(href.substring(hashIndex + 1).trim()) ?: return null
        val page = match.groupValues[1].toIntOrNull() ?: return null
        return if (page > 0) page else null
    }

    /** 文件所在目录；用于「预览的 md 里再有相对链接」时换算基准目录。 */
    fun parentDirectory(filePath: String): String {
        val normalized = normalizeSlashes(filePath).trimEnd('/')
        val lastSlash = normalized.lastIndexOf('/')
        if (lastSlash < 0) return ""
        if (lastSlash == 0) return "/"
        if (lastSlash == 2 && WINDOWS_DRIVE_SLASH.containsMatchIn(normalized)) return normalized.substring(0, 3)
        return normalized.substring(0, lastSlash)
    }

    /** 展示用文件名。 */
    fun displayName(filePath: String): String {
        val normalized = normalizeSlashes(filePath).trimEnd('/')
        return normalized.substringAfterLast('/').ifBlank { normalized }
    }

    /** 是否应该交给系统浏览器打开。 */
    fun isWebUrl(href: String?): Boolean {
        if (href.isNullOrBlank()) return false
        val trimmed = href.trim()
        return lower(trimmed).startsWith("http://") || lower(trimmed).startsWith("https://")
    }

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdx", "mdown")

    /** 是否是可渲染的 Markdown 文档（pi-web 用预览模式渲染 .md 而不是当纯文本）。 */
    fun isMarkdownPath(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val ext = displayName(filePath).substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in MARKDOWN_EXTENSIONS
    }
}
