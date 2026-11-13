package com.pi.app.network

/**
 * 关流之后要不要重挂 `/api/agent/[id]/events`。
 *
 * pi-web 0.16.0 起，会话结束时服务端先推一条 `session_ended`，紧接着主动关流
 * （lib/agent-event-stream.ts：`encode(event); cleanup(true)`）。而「隧道断了」
 * 在 OkHttp 这一侧完全一样，都是 onClosed —— 只靠 onClosed 分不开这两件事。
 *
 * 分不开的代价：App 按退避（上限 10s）一直重连一个已经结束的会话，RECONNECTING
 * 持续超过阈值后还会弹「连不上网关：请确认电脑端服务正在运行」，而网关其实好着。
 */
object AgentEventReconnectPolicy {

    /**
     * @param sessionEnded 关流之前收到过 `session_ended`（pi-web 的「这个会话结束了」）
     * @param closedByUser 调用方主动关流（切会话、离开页面）
     */
    fun shouldReconnect(sessionEnded: Boolean, closedByUser: Boolean): Boolean =
        !sessionEnded && !closedByUser
}
