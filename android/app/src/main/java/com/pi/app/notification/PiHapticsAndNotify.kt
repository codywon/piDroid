package com.pi.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class PiHapticsAndNotify(private val context: Context) {
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
                "Pi 任务执行状态",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "展示 Pi Coding Agent 的长任务后台执行进度与审批通知"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 任务完成触觉反馈：轻震两次
     */
    fun vibrateTaskCompleted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 100, 100, 150)
            val amplitudes = intArrayOf(0, 150, 0, 200)
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 100, 100, 150), -1)
        }
    }

    /**
     * 需要审批危险命令触觉反馈：警示长震
     */
    fun vibrateApprovalRequired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(400)
        }
    }

    /**
     * 更新后台长任务通知
     */
    fun updateTaskNotification(title: String, content: String, isFinished: Boolean = false) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(!isFinished)
            .setAutoCancel(isFinished)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun clearNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "pi_task_channel"
        const val NOTIFICATION_ID = 1001
    }
}
