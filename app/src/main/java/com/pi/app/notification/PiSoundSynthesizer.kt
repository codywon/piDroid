package com.pi.app.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.RingtoneManager
import android.os.Build

/**
 * 1:1 纯正还原 pi-web (Web Audio API) 的正弦波双音任务完成音效合成器
 *
 * 算法完全对标 pi-web:
 * - 两个正弦波音符: C5 (523.25 Hz) 和 E5 (659.25 Hz)
 * - 第 1 音在 t = 0.0s 起始，持续 0.45s，0.02s 线性渐入，指数衰减
 * - 第 2 音在 t = 0.18s 起始，持续 0.45s，0.02s 线性渐入，指数衰减
 * - 采用 44100Hz 16-bit PCM 预合成，通过 AudioTrack 媒体通道流畅输出，不受静音振动模式误拦截
 */
object PiSoundSynthesizer {

    private const val SAMPLE_RATE = 44100
    private const val DURATION_SEC = 0.65

    private val pcmBuffer: ShortArray by lazy {
        val numSamples = (SAMPLE_RATE * DURATION_SEC).toInt()
        val data = ShortArray(numSamples)
        val notes = listOf(
            Triple(523.25, 0.0, 0.45),   // C5
            Triple(659.25, 0.18, 0.45)   // E5
        )

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            var sample = 0.0
            for ((freq, start, dur) in notes) {
                if (t >= start && t < start + dur) {
                    val dt = t - start
                    val env = if (dt < 0.02) {
                        (dt / 0.02) * 0.45
                    } else {
                        0.45 * Math.exp(-5.5 * (dt - 0.02))
                    }
                    sample += Math.sin(2.0 * Math.PI * freq * dt) * env
                }
            }
            data[i] = (sample.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
        data
    }

    fun play(context: Context) {
        Thread {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val streamType = AudioManager.STREAM_MUSIC
                val currentVol = audioManager?.getStreamVolume(streamType) ?: 1

                if (currentVol == 0) {
                    // 若媒体通道被用户完全静音，尝试使用系统默认通知音兜底
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    RingtoneManager.getRingtone(context, uri)?.play()
                    return@Thread
                }

                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(pcmBuffer.size * 2)

                val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        streamType,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STATIC
                    )
                }

                track.write(pcmBuffer, 0, pcmBuffer.size)
                track.play()

                Thread.sleep(700)
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            } catch (_: Exception) {
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    RingtoneManager.getRingtone(context, uri)?.play()
                } catch (_: Exception) {}
            }
        }.start()
    }
}
