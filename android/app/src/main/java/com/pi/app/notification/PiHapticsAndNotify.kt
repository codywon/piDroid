package com.pi.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.pi.app.data.storage.AppPreferences

class PiHapticsAndNotify(private val context: Context) {
    private val prefs = AppPreferences(context)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pi 任务完成与审批提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "展示 Pi Coding Agent 的任务完成提醒与安全审批通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120, 80, 180)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 任务完成触觉反馈：清脆双振脉冲
     * 显式附带 NOTIFICATION 级 VibrationAttributes / AudioAttributes，
     * 确保在 MIUI/HyperOS/ColorOS/OneUI 等系统关闭普通触摸震动时依然能正常驱动马达震动。
     */
    fun vibrateTaskCompleted(force: Boolean = false) {
        if (!force && !prefs.hapticsEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            val timings = longArrayOf(0, 110, 90, 170)
            val amplitudes = intArrayOf(0, 200, 0, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                    .build()
                vib.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                @Suppress("DEPRECATION")
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vib.vibrate(effect, audioAttrs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 任务完成提示音：对标 pi-web 任务完成 ACK 提示音
     */
    fun playTaskCompletedSound(force: Boolean = false) {
        if (!force && !prefs.completionSoundEnabled) return
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { toneGen.release() }
            }, 260L)
        } catch (_: Exception) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone?.play()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 需要审批危险命令触觉反馈：警示长震
     */
    fun vibrateApprovalRequired() {
        if (!prefs.hapticsEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            val timings = longArrayOf(0, 220, 100, 280)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vib.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                @Suppress("DEPRECATION")
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vib.vibrate(effect, audioAttrs)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(450)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 更新后台长任务通知（静默更新通知文本，震动与提示音统一由 vibrateTaskCompleted / playTaskCompletedSound 控制）
     */
    fun updateTaskNotification(title: String, content: String, isFinished: Boolean = false) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(!isFinished)
                .setAutoCancel(isFinished)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {
        }
    }

    fun clearNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_ID = "pi_task_channel_v2"
        const val NOTIFICATION_ID = 1001
    }
}
