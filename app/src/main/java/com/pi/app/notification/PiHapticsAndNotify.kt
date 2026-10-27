package com.pi.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.pi.app.MainActivity
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
    /**
     * 高危命令待审批：长震（两段重震），与「任务完成」的两下轻震明显区分。
     */
    fun vibrateApprovalNeeded(force: Boolean = false) {
        if (!force && !prefs.hapticsEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        try {
            val timings = longArrayOf(0, 260, 140, 320)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                    .build()
                vib.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        } catch (_: Exception) {
        }
    }

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
        PiSoundSynthesizer.play(context)
    }

    /**
     * 等待人工审批触觉反馈：警示长振
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
     * 任务完成独立弹出通知（仅在 prefs.taskCompletedNotifyEnabled 开启时发送，避免与常驻通知栏重复堆叠）
     * 明确携带任务会话名称与项目信息，杜绝无名盲盒通知
     */
    fun notifyTaskCompleted(
        sessionTitle: String,
        project: String = "默认项目",
        sessionId: String? = null
    ) {
        if (!prefs.taskCompletedNotifyEnabled) return
        try {
            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!sessionId.isNullOrBlank()) {
                    putExtra("target_session_id", sessionId)
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("✓ 任务已完成 · $sessionTitle")
                .setContentText("项目: $project · 所有步骤已执行完毕")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {
        }
    }

    /**
     * 高危命令待审批：独立高优先级通知 + 长震，点通知直达该会话。
     *
     * 与「任务完成」不同，这条**不看** taskCompletedNotifyEnabled —— 审批是要人立刻做决定的事，
     * 被静音就等于任务卡死。
     */
    fun notifyApprovalNeeded(
        sessionTitle: String,
        project: String = "默认项目",
        toolName: String = "",
        commandPreview: String = "",
        sessionId: String? = null
    ) {
        try {
            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!sessionId.isNullOrBlank()) putExtra("target_session_id", sessionId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val body = listOf(toolName, commandPreview.take(80))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("⚠️ 待审批 · $sessionTitle")
                .setContentText(body.ifBlank { "有高危命令等待批准执行 · 项目: $project" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "项目: $project" }))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
            notificationManager.notify(APPROVAL_NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {
        }
    }

    /**
     * 更新后台长任务通知（仅在 prefs.taskCompletedNotifyEnabled 开启时发送，默认优先常驻前台服务通知）
     */
    fun updateTaskNotification(title: String, content: String, isFinished: Boolean = false) {
        if (!prefs.taskCompletedNotifyEnabled) return
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
    private const val APPROVAL_NOTIFICATION_ID = 4202
    }
}
