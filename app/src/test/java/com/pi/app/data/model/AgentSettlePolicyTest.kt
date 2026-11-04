package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 收尾等待判据（对齐 pi-web `rh()`）。
 *
 * 这些用例的价值在于「不该收尾时绝不收尾」：
 * 收尾会重读正文并震动/提示，判早了就是把还在跑的任务当成完成，
 * 而下一次发送必然被内核拒掉（pi 侧仍在跑）。
 */
class AgentSettlePolicyTest {

    @Test
    fun `settles as soon as the server says it is not running`() {
        assertEquals(
            AgentSettleDecision.Settle,
            AgentSettlePolicy.decide(elapsedMs = 0, stateKnown = true, running = false)
        )
    }

    @Test
    fun `keeps waiting while it is still running`() {
        assertEquals(
            AgentSettleDecision.Continue,
            AgentSettlePolicy.decide(elapsedMs = 5_000, stateKnown = true, running = true)
        )
    }

    @Test
    fun `an unknown runtime state is never treated as finished`() {
        // 读状态失败（隧道抖动 / 502）≠ 跑完了
        assertEquals(
            AgentSettleDecision.Continue,
            AgentSettlePolicy.decide(elapsedMs = 1_200, stateKnown = false, running = false)
        )
    }

    @Test
    fun `a local tool execution blocks settling even when the server says idle`() {
        assertEquals(
            AgentSettleDecision.Continue,
            AgentSettlePolicy.decide(
                elapsedMs = 2_000,
                stateKnown = true,
                running = false,
                isToolExecuting = true
            )
        )
    }

    @Test
    fun `an auto retry in flight blocks settling`() {
        assertEquals(
            AgentSettleDecision.Continue,
            AgentSettlePolicy.decide(
                elapsedMs = 2_000,
                stateKnown = true,
                running = false,
                isAutoRetrying = true
            )
        )
    }

    @Test
    fun `gives up silently at the pi-web deadline`() {
        assertEquals(
            AgentSettleDecision.Abandon,
            AgentSettlePolicy.decide(
                elapsedMs = AgentSettlePolicy.TIMEOUT_MS,
                stateKnown = true,
                running = true
            )
        )
        assertEquals(
            AgentSettleDecision.Abandon,
            AgentSettlePolicy.decide(
                elapsedMs = AgentSettlePolicy.TIMEOUT_MS + 600,
                stateKnown = false,
                running = false
            )
        )
    }

    @Test
    fun `a settle right at the deadline still wins over the deadline`() {
        assertEquals(
            AgentSettleDecision.Settle,
            AgentSettlePolicy.decide(
                elapsedMs = AgentSettlePolicy.TIMEOUT_MS,
                stateKnown = true,
                running = false
            )
        )
    }

    @Test
    fun `cadence matches pi-web`() {
        assertEquals(800L, AgentSettlePolicy.INITIAL_DELAY_MS)
        assertEquals(600L, AgentSettlePolicy.POLL_INTERVAL_MS)
        assertEquals(20_000L, AgentSettlePolicy.TIMEOUT_MS)
    }
}
