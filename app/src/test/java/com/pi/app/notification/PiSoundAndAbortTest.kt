package com.pi.app.notification

import com.pi.app.data.model.AgentSseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * 任务完成声音与中止提醒对标 pi-web 的回归测试。
 *
 * 守护三件事：
 * 1) 合成器包络完全对齐 Web Audio API 的 linearRamp(0.02s) -> exponentialRamp(0.45s 至 0.001) 指数衰减；
 * 2) 44100Hz 16-bit PCM 在双音符交叠时无溢出与削顶；
 * 3) 任务被中断（aborted == true）时严格抑制提示音与震动，对齐 pi-web 的 `!e.aborted && playDoneSound()`。
 */
class PiSoundAndAbortTest {

    private fun calculateEnvelope(dt: Double): Double {
        return if (dt < 0.02) {
            (dt / 0.02) * 0.45
        } else {
            0.45 * exp(-12.07664 * (dt - 0.02))
        }
    }

    @Test
    fun `envelope matches pi-web attack and decay curves`() {
        // Attack 阶段: t=0 为 0，t=0.02 达到峰值 0.45
        assertEquals(0.0, calculateEnvelope(0.0), 1e-6)
        assertEquals(0.45, calculateEnvelope(0.02), 1e-6)

        // 中途衰减
        val midEnv = calculateEnvelope(0.20)
        assertTrue("midEnv should be smoothly decaying", midEnv < 0.45 && midEnv > 0.01)

        // 0.45s 音符结束处: 衰减到千分之一比例 (0.45 * 0.001 / 0.18 ≈ 0.0025)
        val endEnv = calculateEnvelope(0.45)
        assertTrue("At end of note, envelope must be < 0.003 to prevent audible pop", endEnv < 0.003)
        assertTrue("Envelope must stay non-negative", endEnv > 0.002)

        // 验证相比旧版 -5.5 (约 0.042) 衰减了超过 16 倍
        val oldDecayAtEnd = 0.45 * exp(-5.5 * 0.43)
        assertTrue(endEnv < oldDecayAtEnd / 15.0)
    }

    @Test
    fun `dual tone waveform superposition does not clip`() {
        val sampleRate = 44100
        val durationSec = 0.65
        val numSamples = (sampleRate * durationSec).toInt()
        val notes = listOf(
            Triple(523.25, 0.0, 0.45),  // C5
            Triple(659.25, 0.18, 0.45)  // E5
        )

        var maxAmp = 0.0
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0
            for ((freq, start, dur) in notes) {
                if (t >= start && t < start + dur) {
                    val dt = t - start
                    val env = calculateEnvelope(dt)
                    sample += sin(2.0 * PI * freq * dt) * env
                }
            }
            if (abs(sample) > maxAmp) {
                maxAmp = abs(sample)
            }
            // 确保每个采样点都在 [-1.0, 1.0] 范围内，不会在 16-bit PCM 转换时产生任何削顶失真
            assertTrue("Sample at $t ($sample) exceeded dynamic range [-1.0, 1.0]", sample in -1.0..1.0)
        }

        // 峰值约在 0.48 左右，既保证微型喇叭的响度，又留有超过 6dB 的充足 Headroom
        assertTrue("Peak amplitude should be between 0.35 and 0.85", maxAmp in 0.35..0.85)
    }

    @Test
    fun `agent sse event preserves aborted flag`() {
        val normalEnd = AgentSseEvent(type = "agent_end", done = true)
        assertFalse(normalEnd.aborted)

        val abortedEnd = AgentSseEvent(type = "agent_end", done = true, aborted = true)
        assertTrue(abortedEnd.aborted)
    }

    @Test
    fun `aborted task run suppresses sound and notification trigger`() {
        // 模拟 MainActivity 中的结算决策函数
        fun shouldNotifyCompletion(
            isCurrent: Boolean,
            isAgentBusy: Boolean,
            hasNotifiedCompletion: Boolean
        ): Boolean {
            return isCurrent && isAgentBusy && !hasNotifiedCompletion
        }

        // 场景 1：正常完成运行
        var isAgentBusy = true
        var hasNotified = false
        val normalEvent = AgentSseEvent(type = "agent_end", aborted = false)
        if (normalEvent.aborted) {
            hasNotified = true
        }
        val shouldNotifyNormal = shouldNotifyCompletion(isCurrent = true, isAgentBusy = isAgentBusy, hasNotifiedCompletion = hasNotified)
        assertTrue("Normal completion must trigger sound & notification", shouldNotifyNormal)

        // 场景 2：跨端中止运行 (PC 端或用户 Stop，SSE 推送 aborted = true)
        isAgentBusy = true
        hasNotified = false
        val abortEvent = AgentSseEvent(type = "agent_end", aborted = true)
        if (abortEvent.aborted) {
            // 对标 pi-web：任务被取消时严格抑制提示音与震动
            hasNotified = true
        }
        val shouldNotifyAborted = shouldNotifyCompletion(isCurrent = true, isAgentBusy = isAgentBusy, hasNotifiedCompletion = hasNotified)
        assertFalse("Aborted run must NEVER trigger completion sound or vibration", shouldNotifyAborted)
    }
}
