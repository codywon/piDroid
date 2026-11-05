package com.pi.app.data.model

/**
 * 历史消息的稳定 key 规则（纯 JVM，可单测）。
 *
 * 对齐上游 pi-web `hooks/useAgentSession.ts` 的 `messageKeys`（构建产物里是 `r8` 那段）：
 *
 * ```js
 * keys.push(entryId === undefined
 *     ? di(lastEntryId, s - messages.length)   // di = (e, t) => `live:${e ?? ""}:${t}`
 *     : alias.get(entryId) ?? entryId)
 * ```
 *
 * 即：**一条消息的身份是它所属的会话条目（`entry.id`），不是它在当前渲染窗口里的下标。**
 *
 * 为什么这在 Android 端是硬约束，而在 pi-web 那边只是习惯：
 * - pi-web 渲染在 React 里，重复 key 只是一条 console warning；
 * - piDroid 用 `LazyColumn(key = { it.id })`，重复 key 是**主线程致命异常**，进程直接被杀
 *   （`IllegalArgumentException: Key ... was already used`，见
 *   `server/logs/crash-2026-11-05T05-17-00-673Z.log`）。
 *
 * 历史 id 以前写成 `hist_<sessionId>_<窗口内下标>_<role>`，而渲染窗口的起点会随会话增长前移
 * （[SessionBranchWindow.visibleTailStart] 与 `floorStart`），压缩、自动重试换手、
 * entries 树与 context 窗口两条解析路径切换都会让起点跳变 —— 同一个下标在两次快照里
 * 就指向不同消息，对账后列表里出现两条相同 id。
 */
object HistoryItemKeys {

    /** 历史（服务端快照）消息统一的前缀；翻页页会再套一层 `older_<cursor>_`。 */
    const val PREFIX = "hist_"

    /**
     * 一条历史消息的稳定 id。
     *
     * @param sessionId 会话 id
     * @param entryId pi 会话条目 id（`entry.id`）。正常快照里一定有，缺失时才退化到 [fallbackSeed]
     * @param kind 同一 entry 拆出的多个渲染单元各自的种类（`agent` / `tool_<toolCallId>` / …），
     *   保证「一个条目 → 正文气泡 + 多张工具卡」之间互不撞 key
     * @param fallbackSeed `entryId` 缺失时的确定性锚点（用该条消息的时间戳）。
     *   **绝不用窗口内下标**：那正是上一个版本的缺陷来源
     */
    fun of(sessionId: String, entryId: String?, kind: String, fallbackSeed: Long): String {
        val identity = entryId?.takeIf { it.isNotBlank() } ?: "noid_$fallbackSeed"
        return "$PREFIX${sessionId}_${identity}_$kind"
    }
}
