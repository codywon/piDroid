package com.pi.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 pi-web 0.16.0 的一条新语义：会话结束时，服务端先推一条 `session_ended`，再主动关流
 * （lib/agent-event-stream.ts：`encode(event); cleanup(true)`）。
 *
 * 关流在 OkHttp 这一侧和「隧道断了」完全一样，都是 onClosed。分不清的代价是 App 按退避
 * （上限 10s）一直重连一个已经结束的会话，RECONNECTING 持续超过阈值后还会弹
 * 「连不上网关：请确认电脑端服务正在运行」——一条纯粹的误报。
 */
class AgentEventReconnectPolicyTest {

    @Test
    fun `a stream closed after session_ended is not reconnected`() {
        assertFalse(
            AgentEventReconnectPolicy.shouldReconnect(sessionEnded = true, closedByUser = false)
        )
    }

    @Test
    fun `an ordinary disconnect is still reconnected`() {
        assertTrue(
            AgentEventReconnectPolicy.shouldReconnect(sessionEnded = false, closedByUser = false)
        )
    }

    @Test
    fun `a stream the user closed is not reconnected`() {
        assertFalse(
            AgentEventReconnectPolicy.shouldReconnect(sessionEnded = false, closedByUser = true)
        )
    }
}
