package com.pi.app.data.model

/**
 * 会话收尾等待的判据（对齐 pi-web `useAgentSession.ts` 的 `rh()`）。
 *
 * pi-web 在发完指令后不是「sleep 一下看一眼」，而是：
 * 先等 800ms，然后**每 600ms 探一次 agent 状态，最多 20s**；
 * 只要某一次读到「不在跑」就立刻重读正文（`rp()` → `loadSession`），
 * 20s 内始终没 settle 就静默放弃（不强行收尾、不报错）。
 *
 * 为什么要把这个判据抽成纯函数：它是这条链路上唯一有分支的地方，
 * 而它一旦判错，界面要么在任务还在跑时翻回空闲（下一次发送必被内核拒），
 * 要么永远停在「正在等待模型…」。抽出来就能用 JVM 单测覆盖全部组合。
 */
enum class AgentSettleDecision {
    /** 还没 settle，按 [AgentSettlePolicy.POLL_INTERVAL_MS] 继续轮询。 */
    Continue,

    /** 服务端明确「不在跑」，可以收尾并重读正文了。 */
    Settle,

    /** 20s 内没等到结论：静默放弃（下一次边界事件或重连对账会兜住）。 */
    Abandon
}

object AgentSettlePolicy {
    /** pi-web `rh()` 的首个等待（`await dC(800)`）。 */
    const val INITIAL_DELAY_MS = 800L

    /** pi-web `rh()` 的轮询间隔（`await dC(600)`）。 */
    const val POLL_INTERVAL_MS = 600L

    /** pi-web `rh()` 的总上限（`Date.now()-n < 2e4`）。 */
    const val TIMEOUT_MS = 20_000L

    /**
     * 判定这一轮轮询该怎么走。
     *
     * - `stateKnown=false`（读状态失败 / 隧道抖动）**绝不能**当成「跑完了」：
     *   旧实现把 `?: false` 当成结束，任务还在跑时界面就翻回空闲态。
     * - 本地明确 `isToolExecuting` / `isAutoRetrying` 时不收尾，哪怕服务端说没在跑
     *   （工具卡与重试气泡的收尾各走各的路径，提前收会把它们定格成「已完成」）。
     * - settle 优先于 timeout：刚好在 20s 边界读到「不在跑」仍然收尾。
     */
    fun decide(
        elapsedMs: Long,
        stateKnown: Boolean,
        running: Boolean,
        isToolExecuting: Boolean = false,
        isAutoRetrying: Boolean = false
    ): AgentSettleDecision {
        if (stateKnown && !running && !isToolExecuting && !isAutoRetrying) {
            return AgentSettleDecision.Settle
        }
        if (elapsedMs >= TIMEOUT_MS) {
            return AgentSettleDecision.Abandon
        }
        return AgentSettleDecision.Continue
    }
}
