package com.pi.app.data.model

import java.util.Locale

/** 全局常用置顶工作区虚拟标识。 */
const val PINNED_WORKSPACE_KEY = "__global_pinned__"

/**
 * 工作区路径的跨端归一化与分组（**以 pi-web 的实现为基准**）。
 *
 * 为什么需要这一层：同一个目录在两条链路上会拿到两种写法 ——
 * - 会话/pi-web 侧：`projectRoot` 来自 `git rev-parse --show-toplevel`，Windows 上是 `D:/myscript/termius`；
 * - App 侧：`/api/cwd/browse` 的盘符目录返回 `D:\`（`path.join` 用平台分隔符），
 *   手机端点选目录后原样落库成 `D:\myscript\termius`。
 * 若直接拿字符串当分组键，同一个目录就会出现两行（截图里 `termius` 出现两次、会话分家）。
 *
 * pi-web 的做法（0.13.0 服务端 chunk `74244.z` = `projectKey`，客户端 `page-*.js` 的
 * `f()/m()` = 分组函数）：
 * - 分组/比较用 `projectKey`：分隔符统一、去尾部斜杠、win32 上 `toLowerCase`；
 * - 展示仍用 `projectRoot`（`/` 形态）—— 不是把存储值改掉。
 * 这里逐条对齐：只在「比较 / 分组」时归一，**存储与展示的写法原样保留**，
 * 所以旧数据里已有的 `d:\` 写法不会被改写失效，只是归到同一个项目下。
 */
object WorkspacePaths {

    /** 盘符形态 `D:/`、`D:\`。 */
    private val WINDOWS_DRIVE_PREFIX = Regex("^[a-zA-Z]:[\\\\/]")

    /** 盘符但无分隔符：`D:`。 */
    private val WINDOWS_DRIVE_ONLY = Regex("^[a-zA-Z]:")

    /**
     * 是否 Windows 形态的路径（盘符 / UNC）。
     *
     * 与 pi-web `isAbsoluteWindowsPath`（`/^[a-zA-Z]:[\\/]/` 或 `\\` 开头）同口径，
     * 额外把 `//server/share` 也算进去（与 [com.pi.app.ui.components.FileLinks.isWindowsBaseDir] 一致）：
     * 手机端只连一台网关，靠路径自身形态判断平台比猜 host 更可靠。
     */
    fun isWindowsPath(path: String?): Boolean {
        val value = path ?: return false
        return WINDOWS_DRIVE_PREFIX.containsMatchIn(value) ||
            WINDOWS_DRIVE_ONLY.matches(value) ||
            value.startsWith("\\\\") ||
            value.startsWith("//")
    }

    /**
     * 与 pi-web `normalizeFilePathSlashes` 一致：把 `\` 折成 `/`（不判断平台，反斜杠在 POSIX 路径里不会是分隔符）。
     */
    fun toSlashes(path: String): String = path.replace('\\', '/')

    /**
     * 展示用写法：`\` 折成 `/`、去掉尾部斜杠；`/` 根与盘符根保持可读（`/`、`D:`）。
     *
     * 不改变大小写（`d:` 就是 `d:`）——大小写只在 [identityKey] 里忽略。
     */
    fun displayPath(path: String?): String {
        val slashed = toSlashes(path?.trim() ?: return "")
        if (slashed.isEmpty()) return ""
        val trimmed = slashed.trimEnd('/')
        return trimmed.ifEmpty { "/" }
    }

    /**
     * 分组 / 比较用的身份键，对齐 pi-web `projectKey`：
     * 分隔符统一为 `/`、去尾部斜杠，Windows 形态路径忽略大小写（Linux 上大小写是两个目录，不动）。
     */
    fun identityKey(path: String?): String {
        val display = displayPath(path)
        if (display.isEmpty()) return ""
        return if (isWindowsPath(display)) display.lowercase(Locale.ROOT) else display
    }

    /** 两个写法是否指向同一个目录（`D:\a\b` 与 `d:/a/b/` 视为相同）。 */
    fun samePath(a: String?, b: String?): Boolean = identityKey(a) == identityKey(b)
}

/**
 * 一个工作区分组：`key` 是身份键，`path` 是展示路径，`sessions` 是归到该项目的会话。
 */
data class WorkspaceGroup(
    val key: String,
    val path: String,
    val sessions: List<Session>
)

/**
 * 按 pi-web 的口径把会话与用户手动添加的远程目录合并成工作区列表。
 *
 * - 分组标识取 [WorkspacePaths.identityKey]（pi-web `projectKey`）；
 * - 展示路径优先取组内**最近修改**的会话的 `projectRoot/cwd` 写法（pi-web 客户端 `m()` 同款）,
 *   没有会话的自定义目录则用自己的写法；
 * - 同一目录被写成两种形状（旧版本 `d:\` 与 `d:/` 并存）时只出一行，旧数据不会失效。
 */
fun groupWorkspaces(
    sessions: List<Session>,
    customPaths: Collection<String> = emptyList()
): List<WorkspaceGroup> {
    val sessionsByKey = LinkedHashMap<String, MutableList<Session>>()
    for (session in sessions) {
        sessionsByKey.getOrPut(session.workspaceKey) { mutableListOf() }.add(session)
    }

    val customDisplayByKey = LinkedHashMap<String, String>()
    for (raw in customPaths) {
        val display = WorkspacePaths.displayPath(raw)
        if (display.isBlank()) continue
        val key = WorkspacePaths.identityKey(display)
        sessionsByKey.getOrPut(key) { mutableListOf() }
        val existing = customDisplayByKey[key]
        customDisplayByKey[key] = when {
            existing == null -> display
            // 两份写法都留着（旧 key 未清理）时，优先正斜杠那份：它和会话侧展示写法一致。
            existing.contains('\\') && !display.contains('\\') -> display
            else -> existing
        }
    }

    val grouped = LinkedHashMap<String, WorkspaceGroup>()
    for ((key, list) in sessionsByKey) {
        val latest = list.maxByOrNull { it.modified ?: it.created ?: "" }
        val fromSession = latest?.workspacePath?.takeIf { it.isNotBlank() }
        val finalPath = fromSession ?: customDisplayByKey[key] ?: key
        val pathKey = WorkspacePaths.identityKey(finalPath)
        val existing = grouped[pathKey]
        if (existing == null) {
            grouped[pathKey] = WorkspaceGroup(
                key = key,
                path = finalPath,
                sessions = list
            )
        } else {
            // 防御：若存在大小写或斜杠变体导致同路径，合并会话并保留有会话的那份路径展示
            val preferredPath = if (existing.sessions.isEmpty() && list.isNotEmpty()) finalPath else existing.path
            grouped[pathKey] = WorkspaceGroup(
                key = existing.key,
                path = preferredPath,
                sessions = existing.sessions + list
            )
        }
    }
    return grouped.values.toList()
}

/**
 * 决策工作区的初始选中路径。
 *
 * 优先使用候选路径 [preferredPath]（如果有效且仍在可用列表 [sortedWorkspacePaths] 中，或者是全局置顶专区 [PINNED_WORKSPACE_KEY]）；
 * 若候选路径失效或为空，则回退到当前已排序列表的第一项（尊重用户置顶与拖拽排序后的真实展示首位，绝不盲目按历史会话数兜底）。
 */
fun resolveInitialWorkspaceSelection(
    preferredPath: String?,
    sortedWorkspacePaths: List<String>
): String {
    val candidate = preferredPath?.trim().orEmpty()
    if (candidate.isBlank()) {
        return sortedWorkspacePaths.firstOrNull().orEmpty()
    }
    if (candidate == PINNED_WORKSPACE_KEY) {
        return PINNED_WORKSPACE_KEY
    }
    val matched = sortedWorkspacePaths.firstOrNull { WorkspacePaths.samePath(it, candidate) }
    return matched ?: sortedWorkspacePaths.firstOrNull().orEmpty()
}

/**
 * 首帧 / 切会话返回时「该显示哪个工作区」的判决结果。
 *
 * 拆成显式类型是为了让这条规则**可以被单测钉住**：它涉及的几个变量（上次选区、当前指针、
 * 数据是否就绪）此前散落在 `LaunchedEffect` 的 if/else 里，改一句就可能把冷启动重新
 * 推回「永远落到第一个自定义工作区（历史 termius）」的老毛病。
 */
sealed interface WorkspaceSelectionDecision {
    /** 首份**完整**会话快照还没到：保持现状，绝不拿半成品列表判决。 */
    data object Defer : WorkspaceSelectionDecision

    /** 保持当前指针（hint 已应用过 / 当前指针仍然有效）。 */
    data object Keep : WorkspaceSelectionDecision

    /** 应用「上次停留的工作区」提示，并把它记为已应用。 */
    data class ApplyHint(val path: String) : WorkspaceSelectionDecision

    /** 被动兜底：只移动内存里的选区指针，绝不回写磁盘。 */
    data class Fallback(val path: String) : WorkspaceSelectionDecision
}

/**
 * 工作区选区仲裁（纯函数）。
 *
 * 历史故障（1.8.88 只修掉了一半）：冷启动首帧 `sessions` 还在网络在途中，而离线自定义目录
 * （用户曾通过浏览添加过 `D:\myscript\termius`）已经同步加载，于是列表里只有 `["termius"]`，
 * 「上次停留的工作区」被误判成「已被物理删除」→ 兜底取首项 → 指针被钉在 termius。
 * 1.8.88 拿掉了「兜底反写磁盘」，但那次加的 `if (isLoading && sessions.isEmpty()) return` 门禁
 * 对冷启动**无效**：冷启动走的是静默拉取（`loadSessions(silent = true)`），`isLoading` 恒为 false。
 * 一旦指针被钉在 termius，后续真数据到达也不会纠偏（兜底分支只在「当前指针无效」时才动手），
 * 于是每次重新进入 App 都停在 termius —— 这正是 [dataReady] 参数存在的原因。
 *
 * @param currentSelection 当前内存里的选区指针（`selectedWorkspacePath`）
 * @param hint 上次停留的工作区（磁盘持久化值，冷启动时注入）
 * @param sortedWorkspacePaths 当前已知的工作区（抽屉展示顺序）
 * @param hintAlreadyApplied 本次组合实例里 hint 是否已经应用过（应用后不抢用户手动切换）
 * @param dataReady 首份**完整**会话快照是否已到达（不是「有没有请求在飞」）
 */
fun decideWorkspaceSelection(
    currentSelection: String?,
    hint: String?,
    sortedWorkspacePaths: List<String>,
    hintAlreadyApplied: Boolean,
    dataReady: Boolean
): WorkspaceSelectionDecision {
    if (!dataReady) return WorkspaceSelectionDecision.Defer

    val current = currentSelection?.trim().orEmpty()
    val target = hint?.trim().orEmpty()
    val hintUsable = target.isNotBlank() &&
        (target == PINNED_WORKSPACE_KEY || sortedWorkspacePaths.any { WorkspacePaths.samePath(it, target) })

    if (hintUsable) {
        if (hintAlreadyApplied) return WorkspaceSelectionDecision.Keep
        // 回到上次的工作区时用列表里真实的那份写法（可能与会话快照里的写法差个分隔符）
        val resolved = if (target == PINNED_WORKSPACE_KEY) {
            target
        } else {
            sortedWorkspacePaths.firstOrNull { WorkspacePaths.samePath(it, target) } ?: target
        }
        return WorkspaceSelectionDecision.ApplyHint(resolved)
    }

    // hint 已不可用（目录被删/改名，或磁盘里就没有记忆）：只有当前指针也失效时才被动兜底。
    val currentUsable = current.isNotBlank() &&
        (current == PINNED_WORKSPACE_KEY || sortedWorkspacePaths.any { WorkspacePaths.samePath(it, current) })
    if (currentUsable) return WorkspaceSelectionDecision.Keep

    val fallback = resolveInitialWorkspaceSelection(
        preferredPath = current.ifBlank { target },
        sortedWorkspacePaths = sortedWorkspacePaths
    )
    return if (fallback.isBlank()) {
        WorkspaceSelectionDecision.Keep
    } else {
        WorkspaceSelectionDecision.Fallback(fallback)
    }
}

