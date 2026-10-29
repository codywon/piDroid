package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import java.text.NumberFormat
import java.util.Locale

/**
 * 工作区模型（按项目根目录划分，对标 pi-web SessionTree GroupHeader + ActivitySummary）
 */
data class WorkspaceInfo(
    val path: String,
    val name: String,
    val sessionCount: Int = 0,
    val runningCount: Int = 0,
    val awaitingCount: Int = 0,
    val isCurrent: Boolean = false
) {
    val displayName: String
        get() = name.ifBlank { path.replace('\\', '/').substringAfterLast('/') }
}

data class ProjectInfo(
    val name: String,
    val path: String,
    val isCurrent: Boolean = false
)

/**
 * Pi 历史任务会话模型（对标 pi-web SessionInfo & Runtime 状态）
 */
data class Session(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("firstMessage") val firstMessage: String? = null,
    @SerializedName("cwd") val cwd: String? = null,
    @SerializedName("project") val project: String? = null,
    @SerializedName("projectRoot") val projectRoot: String? = null,
    @SerializedName("projectKey") val projectKey: String? = null,
    @SerializedName("branch") val branch: String? = null,
    @SerializedName("created") val created: String? = null,
    @SerializedName("modified") val modified: String? = null,
    @SerializedName("messageCount") val messageCount: Int = 0,
    @SerializedName("tokenUsage") val tokenUsage: Long = 0L,
    @SerializedName("cost") val cost: Double = 0.0,
    @SerializedName("active") val active: Boolean = false,
    val isRunning: Boolean = false,
    val awaitingInput: Boolean = false
) {
    val displayTitle: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: firstMessage?.replace('\n', ' ')?.trim()?.take(42)
            ?: "任务: ${id.take(8)}"

    val workspacePath: String
        get() {
            val raw = projectRoot?.takeIf { it.isNotBlank() }
                ?: cwd?.takeIf { it.isNotBlank() }
                ?: projectKey?.takeIf { it.isNotBlank() }
                ?: "默认工作区"
            return raw.replace('\\', '/').trimEnd('/')
        }

    val workspaceName: String
        get() {
            val p = workspacePath
            return p.substringAfterLast('\\').substringAfterLast('/').ifBlank { p }
        }

    /**
     * 真实运行态（列表徽章、筛选、计数、删除保护一律以此为准）。
     *
     * pi-web 已把「谁在跑」从会话条目里的 `active` 字段搬到 `GET /api/sessions` 顶层的
     * `runningSessionIds`，条目本身不再带 `active`——所以 `PiApiClient.getSessions()` 解析后
     * 只能把它落到 [isRunning]。而本地新建会话（MainActivity 里 `active = true`）与旧缓存
     * 只有 `active` 有值。因此运行态必须两者取或：只读 `active` 会让「运行中」永远为 0、
     * 全部任务都被判成「已完成」。
     */
    val isLive: Boolean
        get() = isRunning || active
}

/**
 * GET /api/agent/running 的轻量运行态快照（实测约 230 字节）。
 *
 * 列表页/会话页的实时对账都用它，不再每几秒拉 200KB+ 的 /api/sessions：
 * 只有 `sessionListVersion` 真的变了，才需要重新拉一次完整会话列表。
 */
data class RunningSessionsSnapshot(
    val sessionListVersion: Long = 0L,
    val runningIds: Set<String> = emptySet(),
    val awaitingInputIds: Set<String> = emptySet()
)

/**
 * pi 凭据库中某个 Provider 的真实配置状态（GET /api/auth/providers → apiKeyProviders）。
 *
 * @param configured 是否已配置可用凭据
 * @param source pi 侧的凭据来源：stored(auth.json) / env(环境变量) / models_json_key 等
 */
data class ProviderAuthState(
    val providerId: String,
    val configured: Boolean = false,
    val source: String? = null,
    val modelCount: Int = 0
) {
    /** 给 UI 看的中文来源说明 */
    val sourceLabel: String
        get() = when (source?.lowercase()) {
            "stored" -> "已保存"
            "env" -> "环境变量"
            "models_json_key" -> "models.json"
            "models_json_command" -> "models.json 命令"
            "oauth" -> "OAuth"
            null, "" -> ""
            else -> source ?: ""
        }
}

/**
 * agent 运行时的权威状态（POST /api/agent/{id} {"type":"get_state"} → data）。
 *
 * `percent/contextWindow/contextTokens` 来自 pi 的 `getContextUsage()`，是上下文占用的唯一
 * 可信来源：contextWindow 为**模型真实窗口**（例如 deepseek-flash 是 1,000,000），
 * 而 App 早前用 200,000 硬默认值做分母，导致占用率永远顶格 100%。
 */
data class AgentRuntimeState(
    val percent: Double = 0.0,
    val contextWindow: Long = 0L,
    val contextTokens: Long = 0L,
    val hasContextUsage: Boolean = false,
    val provider: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
    val isStreaming: Boolean = false,
    val isCompacting: Boolean = false,
    val isBashRunning: Boolean = false,
    val pendingMessageCount: Int = 0,
    /**
     * 该会话在 pi-web 里是否**正在执行**（唯一可信的任务运行态）。
     *
     * 口径与 pi-web 前端完全一致：`running && (isStreaming || isPromptRunning)`。
     * 注意 `GET /api/sessions/{id}/state` 顶层的 `running` **不是**运行态，它只表示
     * 「pi-web 内存里有没有一个活着的 agent 进程」（会话被 PC 打开过就会是 true）。
     * 早前把它当成运行态，导致所有打开过的会话在手机上永远显示「正在等待模型…」。
     */
    val running: Boolean = false,
    /** 顶层 running：pi-web 侧是否存在活跃 agent 运行时（不代表正在生成） */
    val runtimeAlive: Boolean = false,
    /** pi 的自动上下文压缩开关（agent get_state.autoCompactionEnabled） */
    val autoCompactionEnabled: Boolean = false,
    /** pi 的自动重试开关（agent get_state.autoRetryEnabled） */
    val autoRetryEnabled: Boolean = false,
    /** 正在排队等待下发的引导消息（pi 的 queuedMessages.steering） */
    val steering: List<String> = emptyList(),
    /** 正在排队的后续消息（pi 的 queuedMessages.followUp） */
    val followUp: List<String> = emptyList()
)

/**
 * 发给 pi 的图片附件（严格遵循 pi-web 的请求校验规则）：
 * `images: [{type:"image", data:<纯 base64，不带 data: 前缀>, mimeType:"image/png"}]`
 * - 单条消息最多 10 张；
 * - 每张解码后 ≤ 10 MB；
 * - data 必须是合法 base64（不能带 `data:image/png;base64,` 前缀）。
 */
data class ChatImageInput(
    val mimeType: String,
    val base64Data: String
)

/**
 * pi-web 的会话 UI 状态（GET /api/sessions/ui-state）：置顶 / 归档。
 *
 * pi-web 把这两项存在自己的状态文件里（`pi-web-session-state.json`，带 revision），
 * App 之前只在本地 SharedPreferences 里自建一份，所以两边永远不同步。
 */
data class SessionUiFlags(
    val pinned: Boolean = false,
    val archived: Boolean = false
)

/**
 * pi-web 的会话 UI 状态快照（GET /api/sessions/ui-state）：
 * 会话置顶/归档 + 项目（工作区）置顶与排序，全部与 PC 端共用同一份状态。
 */
data class SessionUiSnapshot(
    val revision: Int = 0,
    val sessionFlags: Map<String, SessionUiFlags> = emptyMap(),
    /** 已置顶的项目 key（pi-web 的 projectKey，通常是工作区绝对路径小写） */
    val pinnedProjectKeys: Set<String> = emptySet(),
    /** 项目排序（同样是小写绝对路径） */
    val projectOrder: List<String> = emptyList()
)

/**
 * 服务端全文搜索的一条命中（GET /api/sessions/search?q=）。
 *
 * pi-web 的返回是**按命中条目**展开的：`{results:[{session, entryId, blockIndex, match, before, after}], truncated}`，
 * 每条都带会话本身 + 命中片段前后各 80 字符，所以手机端能直接展示「命中了什么」。
 */
data class SessionSearchHit(
    val session: Session,
    val entryId: String? = null,
    val match: String = "",
    val before: String = "",
    val after: String = ""
) {
    /** 拼成一行可读片段 */
    val snippet: String
        get() = ("…" + before + match + after + "…").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
}

/** pi 的斜杠命令（agent get_commands）：扩展命令 / prompt 模板 / 技能 */
data class SlashCommand(
    val name: String,
    val description: String = "",
    /** extension | prompt | skill */
    val source: String = ""
) {
    val sourceLabel: String
        get() = when (source) {
            "extension" -> "扩展"
            "prompt" -> "模板"
            "skill" -> "技能"
            else -> source
        }
}

/** 文件元信息（GET /api/files/<path>?type=meta） */
data class FileMeta(
    val size: Long = 0L,
    val language: String = "",
    val mime: String = ""
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val sizeLabel: String
        get() = formatBytes(size)
}

/**
 * 文本预览分块（GET /api/files/<path>?type=read&offset=）。
 *
 * pi-web 的 `readTextPreviewChunk` 一次最多返回 256KB（`TEXT_PREVIEW_MAX_BYTES`），
 * 并把「下一块起点」与「是否还有剩余」一并告给前端；前端底部那个
 * `nextOffset / size` + 「Load more」按钮就靠这两个字段续读。
 * 解析 JSON 时不能只取 content，否则长报告会在 256KB 处静默截断。
 */
data class FileTextChunk(
    val content: String = "",
    val nextOffset: Long = 0L,
    val truncated: Boolean = false,
    val size: Long = 0L
)

/** 人类可读体积（B / KB / MB） */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 媒体播放源：URL + 鉴权头。
 *
 * `MediaPlayer` / `VideoView` 自己发 HTTP 请求，走不了 [com.pi.app.network.PiApiClient] 的
 * OkHttp 客户端（也就没有了 AuthInterceptor）；而伴侣网关除了 `/events` 之外只认
 * `Authorization: Bearer`（不接受 `?token=`），所以播放器必须显式带上这个头。
 */
data class FilePlaybackSource(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * PC 上工作目录的 git 变更（GET /api/git/status?cwd=）。
 * 实测返回：{isGitRepository, repositoryRoot, files:[{filePath,status,code,indexStatus,worktreeStatus}], additions, deletions}
 */
data class GitStatusResult(
    val isGitRepository: Boolean = false,
    val repositoryRoot: String = "",
    val files: List<GitFileChange> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0
)

data class GitFileChange(
    val filePath: String,
    /** modified / added / deleted / untracked / renamed … */
    val status: String = "",
    /** porcelain 的单字母状态，如 M / A / D / ?? */
    val code: String = ""
)

/** 单个文件的 diff（GET /api/git/diff?cwd=&path=）：{supported, status, patch} */
data class GitDiffResult(
    val supported: Boolean = false,
    val status: String = "",
    /** 统一 diff 文本（unified patch） */
    val patch: String = ""
)

/** 待上传到 PC 工作目录的文件（用于 /api/files?type=upload 的 multipart 上传） */
data class UploadFileInput(
    val name: String,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is UploadFileInput && other.name == name && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = name.hashCode() * 31 + bytes.contentHashCode()
}

/** 上传结果：成功/因重名跳过/失败明细 */
data class UploadFilesResult(
    val uploaded: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/**
 * 详细 Token 与上下文用量、缓存率监控
 */
data class TokenStats(
    val contextTokens: Long = 0L,
    val maxContextWindow: Long = 200000L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val cost: Double = 0.0
) {
    val contextUsagePercent: Float
        get() = if (maxContextWindow > 0) (contextTokens.toFloat() / maxContextWindow).coerceIn(0f, 1f) else 0f

    val cacheHitRate: Float
        get() {
            val totalPrompt = inputTokens + cacheReadTokens
            return if (totalPrompt > 0) (cacheReadTokens.toFloat() / totalPrompt) * 100f else 0f
        }

    companion object {
        fun formatTurnUsage(
            input: Long,
            output: Long,
            cacheRead: Long = 0L,
            cacheWrite: Long = 0L,
            cost: Double = 0.0
        ): String? {
            val nf = NumberFormat.getIntegerInstance(Locale.US)
            val parts = mutableListOf<String>()
            if (input > 0) parts.add("${nf.format(input)} in")
            if (output > 0) parts.add("${nf.format(output)} out")
            if (cacheRead > 0) parts.add("${nf.format(cacheRead)} cache R")
            if (cacheWrite > 0) parts.add("${nf.format(cacheWrite)} cache W")
            if (cost >= 0.0001) parts.add(String.format(Locale.US, "$%.4f", cost))
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        fun formatTokenStandard(tokens: Long): String {
            if (tokens <= 0L) return "0"
            return when {
                tokens >= 1_000_000_000_000_000L -> String.format(Locale.US, "%.1f PB", tokens / 1_000_000_000_000_000.0).replace(".0 PB", " PB")
                tokens >= 1_000_000_000_000L -> String.format(Locale.US, "%.1f TB", tokens / 1_000_000_000_000.0).replace(".0 TB", " TB")
                tokens >= 1_000_000_000L -> String.format(Locale.US, "%.1f B", tokens / 1_000_000_000.0).replace(".0 B", " B")
                tokens >= 1_000_000L -> String.format(Locale.US, "%.1f M", tokens / 1_000_000.0).replace(".0 M", " M")
                tokens >= 1_000L -> String.format(Locale.US, "%.1f K", tokens / 1000.0).replace(".0 K", " K")
                else -> tokens.toString()
            }
        }

        fun formatCacheCompact(cacheRead: Long): String {
            if (cacheRead <= 0L) return "0 cache R"
            return "${formatTokenCompact(cacheRead)} cache R"
        }

        fun formatTokenCompact(tokens: Long): String {
            if (tokens <= 0L) return "0"
            return when {
                tokens >= 1_000_000_000_000_000L -> String.format(Locale.US, "%.1fP", tokens / 1_000_000_000_000_000.0).replace(".0P", "P")
                tokens >= 1_000_000_000_000L -> String.format(Locale.US, "%.1fT", tokens / 1_000_000_000_000.0).replace(".0T", "T")
                tokens >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", tokens / 1_000_000_000.0).replace(".0B", "B")
                tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0).replace(".0M", "M")
                tokens >= 10_000L -> "${tokens / 1000}K"
                tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1000.0).replace(".0K", "K")
                else -> tokens.toString()
            }
        }
    }
}

/**
 * 真实 pi-web 动态模型信息
 */
data class ModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("provider") val provider: String,
    @SerializedName("contextWindow") val contextWindow: Long = 200000L,
    @SerializedName("maxTokens") val maxTokens: Long = 8192L,
    @SerializedName("supportsThinking") val supportsThinking: Boolean = true,
    @SerializedName("supportsVision") val supportsVision: Boolean = false,
    @SerializedName("input") val input: List<String> = emptyList(),
    @SerializedName("isActive") val isActive: Boolean = false,
    @SerializedName("baseUrl") val baseUrl: String? = null,
    @SerializedName("apiType") val apiType: String? = null
) {
    val displayName: String
        get() = name.ifBlank { id }

    val hasVision: Boolean
        get() = supportsVision || input.contains("image")
}

/**
 * 思考深度级别（7档官方规格）
 */
enum class ThinkingLevel(val value: String, val label: String, val budgetTokens: Int) {
    OFF("off", "关闭思考", 0),
    MINIMAL("minimal", "微弱 (512)", 512),
    LOW("low", "轻度 (1k)", 1024),
    MEDIUM("medium", "适度 (4k)", 4096),
    HIGH("high", "深度 (16k)", 16384),
    XHIGH("xhigh", "超深 (32k)", 32768),
    MAX("max", "极限 (64k)", 65536);

    companion object {
        fun fromValue(v: String?): ThinkingLevel {
            return entries.firstOrNull { it.value.equals(v, ignoreCase = true) } ?: HIGH
        }
    }
}

/**
 * 排队中的消息
 */
data class QueuedMessage(
    val kind: String, // "steer" or "followUp"
    val text: String,
    val images: List<String> = emptyList()
)

/**
 * 技能 (Skills)
 */
data class SkillInstallInfo(
    @SerializedName("package") val `package`: String? = null,
    @SerializedName("scope") val scope: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("sourceType") val sourceType: String? = null,
    @SerializedName("skillsShUrl") val skillsShUrl: String? = null,
    @SerializedName("skillPath") val skillPath: String? = null,
    @SerializedName("ref") val ref: String? = null,
    @SerializedName("versionHash") val versionHash: String? = null,
    @SerializedName("canCheckForUpdates") val canCheckForUpdates: Boolean = false
)

data class SkillSourceInfo(
    @SerializedName("path") val path: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("scope") val scope: String? = null,
    @SerializedName("origin") val origin: String? = null,
    @SerializedName("baseDir") val baseDir: String? = null
)

data class SkillSearchResult(
    @SerializedName("package") val `package`: String,
    @SerializedName("installs") val installs: String = "",
    @SerializedName("url") val url: String = ""
) {
    val skillName: String
        get() {
            val atIdx = `package`.lastIndexOf('@')
            return if (atIdx >= 0) `package`.substring(atIdx + 1) else `package`
        }
    val repoName: String
        get() {
            val atIdx = `package`.lastIndexOf('@')
            return if (atIdx >= 0) `package`.substring(0, atIdx) else ""
        }
}

data class SkillUpdateInfo(
    @SerializedName("package") val `package`: String,
    @SerializedName("scope") val scope: String = "global",
    @SerializedName("state") val state: String = "up-to-date", // "up-to-date", "update-available", "error", "unsupported"
    @SerializedName("currentVersion") val currentVersion: String? = null,
    @SerializedName("latestVersion") val latestVersion: String? = null,
    @SerializedName("message") val message: String? = null
)

data class SkillInfo(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("alias") var alias: String? = null, // 用户自定义别名
    @SerializedName("filePath") val filePath: String? = null,
    @SerializedName("baseDir") val baseDir: String? = null,
    @SerializedName("enabled") var enabled: Boolean = true,
    @SerializedName("category") val category: String? = "全局技能",
    @SerializedName("disableModelInvocation") var disableModelInvocation: Boolean = false,
    @SerializedName("content") val content: String? = null,
    @SerializedName("scope") val scope: String? = "global",
    @SerializedName("install") val install: SkillInstallInfo? = null,
    @SerializedName("sourceInfo") val sourceInfo: SkillSourceInfo? = null
) {
    val displayTitle: String
        get() = alias?.takeIf { it.isNotBlank() } ?: name

    val isProjectScope: Boolean
        get() = scope.equals("project", true) || category?.contains("项目") == true || category?.contains("工作区") == true

    val isGlobalScope: Boolean
        get() = !isProjectScope

    val isVisibleToModel: Boolean
        get() = !disableModelInvocation && enabled

    val skillsShUrl: String?
        get() = install?.skillsShUrl

    val versionShort: String?
        get() = install?.versionHash?.take(8)

    val canCheckForUpdates: Boolean
        get() = install?.canCheckForUpdates == true
}

/**
 * 插件扩展 (Plugins)
 */
data class PluginPackage(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("version") val version: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("scope") val scope: String? = "global",
    @SerializedName("status") val status: String? = "loaded",
    @SerializedName("disabled") val disabled: Boolean = false,
    @SerializedName("counts") val counts: PluginCounts? = null
)

data class PluginCounts(
    @SerializedName("extensions") val extensions: Int = 0,
    @SerializedName("skills") val skills: Int = 0,
    @SerializedName("prompts") val prompts: Int = 0,
    @SerializedName("themes") val themes: Int = 0
)

/**
 * MCP 协议
 */
data class McpServerInfo(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("transport") val transport: String? = "http",
    @SerializedName("url") val url: String? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("toolExposureCount") val toolExposureCount: Int = 0,
    @SerializedName("status") val statusObj: McpServerStatus? = null,
    @SerializedName("command") val command: String? = null,
    @SerializedName("args") val args: List<String>? = null,
    @SerializedName("env") val env: Map<String, String>? = null,
    @SerializedName("scope") val scope: String? = "global"
) {
    val isConnected: Boolean
        get() = statusObj?.state == "connected" || enabled
}

data class McpTestToolResult(
    val success: Boolean,
    val message: String,
    val toolCount: Int = 0,
    val tools: List<String> = emptyList()
)

data class McpServerStatus(
    @SerializedName("state") val state: String? = "connected"
)

data class McpCodeModeConfig(
    val mode: String = "automatic", // automatic, always, off
    val scriptMode: Boolean = true,
    val tokenBudget: Int = 3000
)

data class RemoteDirEntry(
    val name: String,
    val path: String
)

data class RemoteBrowseData(
    val currentPath: String,
    val parentPath: String?,
    val directories: List<RemoteDirEntry>
)

/**
 * 富媒体附件模型（对标 pi-web ImageContent / SVG / PDF / 脑图预览与大图查看器）
 */
enum class MediaKind {
    IMAGE,
    SVG,
    PDF,
    DIAGRAM
}

data class MediaAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val kind: MediaKind = MediaKind.IMAGE,
    val mimeType: String = "image/png",
    val dataBase64: String? = null,
    val url: String? = null,
    val filePath: String? = null,
    val title: String = "",
    val svgOrCode: String? = null,
    val rawContent: String? = svgOrCode
)

/**
 * 聊天消息项（对标 pi-web MessageView）
 */
data class ChatItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** pi 会话文件里该条目（entry）的真实 ID：用于「从此处编辑」的树导航（navigate_tree） */
    val entryId: String? = null,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val thinkingDurationSec: Int? = null,
    val toolCall: ToolCallInfo? = null,
    val mediaList: List<MediaAttachment> = emptyList(),
    val modelName: String? = null,
    val usageSummary: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    /**
     * 这条 AGENT 气泡属于哪一条 pi assistant message（身份 = message.timestamp / id）。
     * 对齐 pi-web 的 streamingMessage：`message_start` 补发的整条快照要覆盖**同一条消息**，
     * 而不是在工具卡后面再追加一份正文。详见 [StreamingMessageMerge]。
     */
    val streamMessageId: String? = null
)

enum class MessageRole {
    USER,
    AGENT,
    TOOL,
    SYSTEM
}

/**
 * 工具调用与审批卡片数据（对标 pi-web ToolCallBlock）
 */
data class ToolCallInfo(
    val toolCallId: String? = null,
    val toolName: String,
    val preview: String? = null,
    val command: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
    val output: String? = null,
    val mediaList: List<MediaAttachment> = emptyList(),
    val durationMs: Long = 0L,
    val isError: Boolean = false,
    val isRunning: Boolean = false,
    val requiresApproval: Boolean = false,
    val isApproved: Boolean = false,
    /** 长输出的临时文件路径（pi 的 fullOutputPath），可用它取完整输出 */
    val fullOutputPath: String? = null
) {
    /**
     * 从工具入参里认出「这次操作的文件绝对路径」，用于手机端一键预览。
     * 只认 read/edit/write 这类明确带路径的工具，且必须以 / 或盘符开头，避免误判。
     */
    val filePath: String?
        get() {
            if (toolName !in PATH_TOOLS) return null
            val candidate = fields.firstOrNull { (k, _) ->
                k.equals("path", true) || k.equals("filePath", true) ||
                    k.equals("file_path", true) || k.equals("target", true)
            }?.second?.trim()?.trim('"') ?: return null
            val isAbs = candidate.startsWith("/") || WINDOWS_DRIVE_PATTERN.containsMatchIn(candidate)
            return candidate.takeIf { isAbs && it.isNotBlank() }
        }

    companion object {
        private val PATH_TOOLS = setOf("read", "edit", "write", "ls", "find", "grep")
        private val WINDOWS_DRIVE_PATTERN = Regex("^[A-Za-z]:[\\\\/]")
    }

    val formattedDuration: String?
        get() {
            if (durationMs < 100L) return null
            val seconds = durationMs / 1000.0
            if (seconds < 60.0) return String.format(Locale.US, "%.1fs", seconds)
            val totalSeconds = seconds.toLong()
            val minutes = totalSeconds / 60L
            val rem = totalSeconds % 60L
            if (minutes < 60L) return "${minutes}m ${rem}s"
            return "${minutes / 60L}h ${minutes % 60L}m ${rem}s"
        }
}

/**
 * SSE 实时流连接状态
 */
enum class SseConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * SSE 推送事件结构（兼容 pi-web 全量事件流）
 */
data class AgentSseEvent(
    val type: String,
    val delta: String? = null,
    val text: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    val tool: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val command: String? = null,
    val input: String? = null,
    val preview: String? = null,
    val output: String? = null,
    val mediaList: List<MediaAttachment> = emptyList(),
    val durationMs: Long? = null,
    val isError: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    val aborted: Boolean = false,
    val isStreaming: Boolean? = null,
    val phaseLabel: String? = null,
    val modelName: String? = null,
    val usageSummary: String? = null,
    val tokenStats: TokenStats? = null,
    val connectionState: String? = null,
    /** pi assistant message 的身份（message.timestamp / id），用于把正文写回同一条气泡。 */
    val messageId: String? = null
) {
    val effectiveText: String
        get() = delta ?: text ?: content ?: ""

    val effectiveToolName: String
        get() = tool ?: toolName ?: "bash"

    val effectiveCommand: String?
        get() = command ?: input
}

/**
 * 思考等级映射状态 (7 档：off, minimal, low, medium, high, xhigh, max)
 */
enum class ThinkingLevelMode {
    DEFAULT,   // 默认 (不在 map 中，跟随系统默认)
    DISABLED,  // 禁用 (map 中存 false)
    CUSTOM     // 自定义 (map 中存自定义字符串，如 "none", "xhigh", "max")
}

data class ThinkingLevelItem(
    val levelKey: String, // "off", "minimal", "low", "medium", "high", "xhigh", "max"
    val mode: ThinkingLevelMode = ThinkingLevelMode.DEFAULT,
    val customValue: String = ""
)

/**
 * 自定义 Header 键值对
 */
data class HeaderEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val key: String = "",
    val value: String = ""
)

/**
 * 1:1 对标 pi-web 的单个模型配置实体
 */
data class ModelConfigItem(
    val id: String,
    val name: String = "",
    val reasoning: Boolean = false,
    val hasVision: Boolean = false,
    val contextWindow: Long = 200000L,
    val maxTokens: Long = 8192L,
    val costInput: Double = 0.0,
    val costOutput: Double = 0.0,
    val costCacheRead: Double = 0.0,
    val costCacheWrite: Double = 0.0,
    val apiOverride: String = "", // 空代表默认/跟随 Provider
    val headers: List<HeaderEntry> = emptyList(),
    val deepSeekThinkingCompat: Boolean = false,
    val developerRole: Boolean = false,
    val thinkingLevel: String = "",
    val thinkingLevelMap: List<ThinkingLevelItem> = defaultThinkingLevelMap()
) {
    companion object {
        fun defaultThinkingLevelMap(): List<ThinkingLevelItem> = listOf(
            ThinkingLevelItem("off"),
            ThinkingLevelItem("minimal"),
            ThinkingLevelItem("low"),
            ThinkingLevelItem("medium"),
            ThinkingLevelItem("high"),
            ThinkingLevelItem("xhigh"),
            ThinkingLevelItem("max")
        )
    }
}

/**
 * 1:1 对标 pi-web 的 Provider 配置实体
 */
data class ProviderConfigItem(
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val api: String = "openai-completions",
    val headers: List<HeaderEntry> = emptyList(),
    val models: List<ModelConfigItem> = emptyList()
)

/**
 * models-config 与 JSON 双向解析转换器 (纯正对标 pi-web models.json 规范)
 */
object ModelConfigConverter {

    private val STANDARD_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    fun parseProvidersConfig(rootObj: JsonObject): List<ProviderConfigItem> {
        val providersObj = if (rootObj.has("providers") && rootObj.get("providers").isJsonObject) {
            rootObj.getAsJsonObject("providers")
        } else {
            rootObj
        }

        val resultList = mutableListOf<ProviderConfigItem>()
        for (providerKey in providersObj.keySet()) {
            val pElement = providersObj.get(providerKey)
            if (!pElement.isJsonObject) continue
            val pObj = pElement.asJsonObject

            val baseUrl = pObj.get("baseUrl")?.asString ?: ""
            val apiKey = pObj.get("apiKey")?.asString ?: ""
            val api = pObj.get("api")?.asString ?: "openai-completions"

            // 解析 Provider 级别的 headers
            val pHeaders = mutableListOf<HeaderEntry>()
            if (pObj.has("headers") && pObj.get("headers").isJsonObject) {
                val hObj = pObj.getAsJsonObject("headers")
                for (hKey in hObj.keySet()) {
                    pHeaders.add(HeaderEntry(key = hKey, value = hObj.get(hKey)?.asString ?: ""))
                }
            }

            // 解析 Provider 下属的 models
            val modelsList = mutableListOf<ModelConfigItem>()
            if (pObj.has("models") && pObj.get("models").isJsonArray) {
                val modelsArr = pObj.getAsJsonArray("models")
                for (mElement in modelsArr) {
                    if (!mElement.isJsonObject) continue
                    val mObj = mElement.asJsonObject
                    val mId = mObj.get("id")?.asString?.trim() ?: continue
                    if (mId.isEmpty()) continue

                    val mName = mObj.get("name")?.asString ?: ""
                    val reasoning = mObj.get("reasoning")?.asBoolean ?: false

                    val hasVision = if (mObj.has("input") && mObj.get("input").isJsonArray) {
                        mObj.getAsJsonArray("input").any { it.isJsonPrimitive && it.asString == "image" }
                    } else false

                    val contextWindow = mObj.get("contextWindow")?.asLong ?: 200000L
                    val maxTokens = mObj.get("maxTokens")?.asLong ?: 8192L

                    // 价格
                    var costInput = 0.0
                    var costOutput = 0.0
                    var costCacheRead = 0.0
                    var costCacheWrite = 0.0
                    if (mObj.has("cost") && mObj.get("cost").isJsonObject) {
                        val costObj = mObj.getAsJsonObject("cost")
                        costInput = costObj.get("input")?.asDouble ?: 0.0
                        costOutput = costObj.get("output")?.asDouble ?: 0.0
                        costCacheRead = costObj.get("cacheRead")?.asDouble ?: 0.0
                        costCacheWrite = costObj.get("cacheWrite")?.asDouble ?: 0.0
                    }

                    // api override
                    val apiOverride = mObj.get("api")?.asString ?: ""

                    // headers
                    val mHeaders = mutableListOf<HeaderEntry>()
                    if (mObj.has("headers") && mObj.get("headers").isJsonObject) {
                        val hObj = mObj.getAsJsonObject("headers")
                        for (hKey in hObj.keySet()) {
                            mHeaders.add(HeaderEntry(key = hKey, value = hObj.get(hKey)?.asString ?: ""))
                        }
                    }

                    // 兼容性
                    var deepSeekThinking = false
                    var developerRole = false
                    if (mObj.has("compat") && mObj.get("compat").isJsonObject) {
                        val compatObj = mObj.getAsJsonObject("compat")
                        deepSeekThinking = compatObj.get("supportsThinking")?.asBoolean ?: false
                        developerRole = compatObj.get("developerRole")?.asBoolean ?: false
                    }

                    val thinkingLevel = mObj.get("thinkingLevel")?.asString ?: ""

                    // 思考等级映射
                    val levelMapItems = mutableListOf<ThinkingLevelItem>()
                    val rawLevelMap = if (mObj.has("thinkingLevelMap") && mObj.get("thinkingLevelMap").isJsonObject) {
                        mObj.getAsJsonObject("thinkingLevelMap")
                    } else null

                    for (lvl in STANDARD_LEVELS) {
                        if (rawLevelMap != null && rawLevelMap.has(lvl)) {
                            val elem = rawLevelMap.get(lvl)
                            if (elem.isJsonPrimitive) {
                                val prim = elem.asJsonPrimitive
                                if (prim.isBoolean && !prim.asBoolean) {
                                    levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DISABLED, ""))
                                } else if (prim.isString) {
                                    levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.CUSTOM, prim.asString))
                                } else {
                                    levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DEFAULT, ""))
                                }
                            } else {
                                levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DEFAULT, ""))
                            }
                        } else {
                            levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DEFAULT, ""))
                        }
                    }

                    modelsList.add(
                        ModelConfigItem(
                            id = mId,
                            name = mName,
                            reasoning = reasoning,
                            hasVision = hasVision,
                            contextWindow = contextWindow,
                            maxTokens = maxTokens,
                            costInput = costInput,
                            costOutput = costOutput,
                            costCacheRead = costCacheRead,
                            costCacheWrite = costCacheWrite,
                            apiOverride = apiOverride,
                            headers = mHeaders,
                            deepSeekThinkingCompat = deepSeekThinking,
                            developerRole = developerRole,
                            thinkingLevel = thinkingLevel,
                            thinkingLevelMap = levelMapItems
                        )
                    )
                }
            }

            resultList.add(
                ProviderConfigItem(
                    name = providerKey,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    api = api,
                    headers = pHeaders,
                    models = modelsList
                )
            )
        }
        return resultList
    }

    fun serializeProvidersConfig(providers: List<ProviderConfigItem>, existingRoot: JsonObject? = null): JsonObject {
        val root = existingRoot?.deepCopy() ?: JsonObject()
        val providersObj = JsonObject()

        for (provider in providers) {
            val pKey = provider.name.trim()
            if (pKey.isEmpty()) continue

            val pObj = JsonObject()
            if (provider.baseUrl.isNotBlank()) pObj.addProperty("baseUrl", provider.baseUrl.trim())
            if (provider.apiKey.isNotBlank()) pObj.addProperty("apiKey", provider.apiKey.trim())
            if (provider.api.isNotBlank()) pObj.addProperty("api", provider.api.trim())

            // Provider 级别 headers
            val validPHeaders = provider.headers.filter { it.key.isNotBlank() }
            if (validPHeaders.isNotEmpty()) {
                val hObj = JsonObject()
                for (h in validPHeaders) {
                    hObj.addProperty(h.key.trim(), h.value)
                }
                pObj.add("headers", hObj)
            }

            // models
            val modelsArr = JsonArray()
            for (m in provider.models) {
                val mId = m.id.trim()
                if (mId.isEmpty()) continue

                val mObj = JsonObject()
                mObj.addProperty("id", mId)
                if (m.name.isNotBlank()) mObj.addProperty("name", m.name.trim())
                if (m.reasoning) mObj.addProperty("reasoning", true)

                // input
                val inputArr = JsonArray()
                inputArr.add("text")
                if (m.hasVision) {
                    inputArr.add("image")
                }
                mObj.add("input", inputArr)

                // contextWindow & maxTokens 真实写入！
                mObj.addProperty("contextWindow", m.contextWindow)
                mObj.addProperty("maxTokens", m.maxTokens)

                // 价格 (cost)
                val costObj = JsonObject().apply {
                    addProperty("input", m.costInput)
                    addProperty("output", m.costOutput)
                    addProperty("cacheRead", m.costCacheRead)
                    addProperty("cacheWrite", m.costCacheWrite)
                }
                mObj.add("cost", costObj)

                // api override
                if (m.apiOverride.isNotBlank() && m.apiOverride != "none" && m.apiOverride != "— 默认 / none —") {
                    mObj.addProperty("api", m.apiOverride.trim())
                }

                // headers
                val validMHeaders = m.headers.filter { it.key.isNotBlank() }
                if (validMHeaders.isNotEmpty()) {
                    val hObj = JsonObject()
                    for (h in validMHeaders) {
                        hObj.addProperty(h.key.trim(), h.value)
                    }
                    mObj.add("headers", hObj)
                }

                // compat
                val compatObj = JsonObject()
                if (m.deepSeekThinkingCompat) compatObj.addProperty("supportsThinking", true)
                if (m.developerRole) compatObj.addProperty("developerRole", true)
                if (compatObj.size() > 0) {
                    mObj.add("compat", compatObj)
                }

                if (m.thinkingLevel.isNotBlank()) {
                    mObj.addProperty("thinkingLevel", m.thinkingLevel.trim())
                }

                // thinkingLevelMap
                val levelMapObj = JsonObject()
                for (item in m.thinkingLevelMap) {
                    when (item.mode) {
                        ThinkingLevelMode.DISABLED -> levelMapObj.addProperty(item.levelKey, false)
                        ThinkingLevelMode.CUSTOM -> {
                            if (item.customValue.isNotBlank()) {
                                levelMapObj.addProperty(item.levelKey, item.customValue.trim())
                            }
                        }
                        ThinkingLevelMode.DEFAULT -> {} // 不写入
                    }
                }
                if (levelMapObj.size() > 0) {
                    mObj.add("thinkingLevelMap", levelMapObj)
                }

                modelsArr.add(mObj)
            }

            pObj.add("models", modelsArr)
            providersObj.add(pKey, pObj)
        }

        root.add("providers", providersObj)
        // 移除可能造成破坏的顶层残留
        root.remove("config")
        return root
    }
}
