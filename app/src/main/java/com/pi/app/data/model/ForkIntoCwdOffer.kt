package com.pi.app.data.model

/**
 * 「会话的工作目录已被删除」时给出的那次 Fork 机会。
 *
 * 对齐 pi-web 0.18.0：`POST /api/sessions/[id]/fork` 多认一个可选 `cwd`，把整份历史复制到
 * 另一个目录下的新会话继续（上游 `forkSessionIntoCwd()`，即 pi 的 Fork）；源会话保持不动。
 * 上游拒绝的两种情况照样存在（`cwd-exists` = 源目录其实还在；`cwd-invalid` = 目标目录不存在），
 * 所以这里只提供候选目录，真正能不能用由服务端判定。
 */
data class ForkIntoCwdOffer(
    val sessionId: String,
    val sessionTitle: String,
    /** 已不存在的那个目录（上游 `cwd-missing` 的 `cwd`） */
    val missingCwd: String,
    /** 候选目标目录：App 已知的工作区，用户也可以手输别的 */
    val knownCwds: List<String> = emptyList(),
)
