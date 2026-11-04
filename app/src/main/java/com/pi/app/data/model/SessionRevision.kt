package com.pi.app.data.model

/**
 * 会话快照的「新鲜度」判定（纯 JVM，可单测）。
 *
 * 1:1 对齐 pi-web `hooks/useAgentSession.ts:813-818` 的 `revisionUnchanged`：
 * 服务端在 `GET /api/sessions/{id}` 里给一个不透明的 `snapshotRevision`
 * （`lib/session-revision.ts`：文件指纹 + 读取源 + entry 数 + 最新 entry id + leaf 的 sha1），
 * 客户端只做**等值比较**，不解析、不匹配：
 *
 * - 没有 revision（服务端判定快照不可靠）→ 一律当作「不新鲜」，正常重取；
 * - revision 相同且本地已加载条目不少于新窗口 → **保留已翻页的历史**，只刷新
 *   tree / leaf / stats / 模型 / 思考档，不把列表缩回首屏窗口；
 * - 否则 → 用服务端窗口整体对账。
 */
object SessionRevision {

    fun keepLoadedWindow(
        cachedRevision: String?,
        incomingRevision: String?,
        cachedEntryCount: Int,
        incomingEntryCount: Int
    ): Boolean {
        if (cachedRevision.isNullOrBlank()) return false
        if (incomingRevision.isNullOrBlank()) return false
        if (cachedRevision != incomingRevision) return false
        return cachedEntryCount >= incomingEntryCount
    }
}
