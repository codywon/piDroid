package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在压缩上下文」必须按会话隔离。
 *
 * 回归背景（v1.8.46）：MainActivity 只有一个全局 `isCompacting`，手动压缩的 POST 在飞时
 * （实测一次压缩 71s~333s），切到任何会话，界面都显示「正在压缩上下文...」。这里把那条
 * 隐式规则固化成数据结构的契约。
 */
class CompactionUiStateTest {

    private val a = "session-a"
    private val b = "session-b"

    @Test
    fun `request in flight only marks the requesting session`() {
        val state = CompactionUiState().requestStarted(a)

        assertTrue(state.isCompacting(a))
        assertFalse(state.isCompacting(b))
    }

    @Test
    fun `server flag covers compaction started from pc or auto compaction`() {
        val state = CompactionUiState().serverChanged(b, true)

        // 本机没有请求在飞，但服务端确实在压 —— 仍要显示
        assertTrue(state.isCompacting(b))
        assertFalse(state.isCompacting(a))
    }

    @Test
    fun `finishing the request does not clear the server fact`() {
        // 客户端 readTimeout 到点、回执丢了：服务端很可能仍在压。
        // 这时清掉“请求在飞”，但不能把“服务端在压缩”一起抹掉。
        val state = CompactionUiState()
            .requestStarted(a)
            .serverChanged(a, true)
            .requestFinished(a)

        assertTrue(state.isCompacting(a))
        assertTrue(a !in state.requestInFlight)
        assertTrue(a in state.serverCompacting)
    }

    @Test
    fun `compaction end clears the session completely`() {
        // SSE compaction_end 到达：压缩已经结束，即使那条 POST 还挂着（或永远拿不到回执），
        // 也不能让它把「正在压缩」继续挂在界面上。
        val state = CompactionUiState()
            .requestStarted(a)
            .serverChanged(a, true)
            .serverFinished(a)

        assertFalse(state.isCompacting(a))
        assertTrue(a !in state.requestInFlight)
        assertTrue(a !in state.serverCompacting)
    }

    @Test
    fun `a false state read does not cancel the request spinner early`() {
        // 刚发出 POST、服务端还没开始压的一次轮询读到 isCompacting=false：
        // 只清服务端标记，本次请求的转圈由请求自己收尾。
        val state = CompactionUiState()
            .requestStarted(a)
            .serverChanged(a, false)

        assertTrue(state.isCompacting(a))
    }

    @Test
    fun `blank session id is never compacting`() {
        val state = CompactionUiState().requestStarted("").serverChanged("", true)

        assertFalse(state.isCompacting(null))
        assertFalse(state.isCompacting(""))
        assertTrue(state.requestInFlight.isEmpty())
        assertTrue(state.serverCompacting.isEmpty())
    }

    @Test
    fun `forget drops a deleted session from both sets`() {
        val state = CompactionUiState()
            .requestStarted(a)
            .serverChanged(a, true)
            .forget(a)

        assertEquals(CompactionUiState(), state)
    }

    @Test
    fun `compaction cancelled by the user is not a failure`() {
        // 用户按了「停止压缩」后 pi 拒绝的两种文案（见 pi 的 compact() 与摘要 signal）
        assertTrue(CompactionFailure.isCancellation("Compaction cancelled"))
        assertTrue(CompactionFailure.isCancellation("Compaction canceled"))
        assertTrue(CompactionFailure.isCancellation("This operation was aborted"))
        assertTrue(CompactionFailure.isCancellation("aborted"))
        // 真正的失败不能被当成取消
        assertFalse(CompactionFailure.isCancellation("Compaction failed: Nothing to compact (session too small)"))
        assertFalse(CompactionFailure.isCancellation("Already compacted"))
        assertFalse(CompactionFailure.isCancellation(null))
        assertFalse(CompactionFailure.isCancellation(""))
    }
}
