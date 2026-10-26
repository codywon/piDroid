package com.pi.app.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pi.app.MainActivity

class PiForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "piDroid · 网关守护运行中"
        val content = intent?.getStringExtra(EXTRA_CONTENT) ?: "保持长连接与后台实时同步..."
        val subText = intent?.getStringExtra(EXTRA_SUB_TEXT)
        val bigText = intent?.getStringExtra(EXTRA_BIG_TEXT)
        val targetSessionId = intent?.getStringExtra(EXTRA_TARGET_SESSION_ID)

        val notification = buildNotification(title, content, subText, bigText, targetSessionId)
        isRunning = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // 防止极少数 ROM 后台启动限制抛异常崩溃
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(
        title: String,
        content: String,
        subText: String? = null,
        bigText: String? = null,
        targetSessionId: String? = null
    ): Notification {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!targetSessionId.isNullOrBlank()) {
                putExtra("target_session_id", targetSessionId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        if (!bigText.isNullOrBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "piDroid 常驻通知栏保活服务",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "在通知栏常驻显示网关连接与后台 Agent 任务状态，防止锁屏断连"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "pi_keepalive_persistent_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_START = "ACTION_START_KEEP_ALIVE"
        const val ACTION_STOP = "ACTION_STOP_KEEP_ALIVE"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"
        const val EXTRA_SUB_TEXT = "extra_sub_text"
        const val EXTRA_BIG_TEXT = "extra_big_text"
        const val EXTRA_TARGET_SESSION_ID = "extra_target_session_id"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(
            context: Context,
            title: String = "piDroid · 网关守护已就绪",
            content: String = "长连接保活与实时同步运行中",
            subText: String? = null,
            bigText: String? = null,
            targetSessionId: String? = null
        ) {
            try {
                // 如果前台服务已经在运行，直接利用 NotificationManager 静默无缝刷新通知，避免闪烁
                if (isRunning) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (manager != null) {
                        val clickIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            if (!targetSessionId.isNullOrBlank()) {
                                putExtra("target_session_id", targetSessionId)
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
                            .setContentTitle(title)
                            .setContentText(content)
                            .setContentIntent(pendingIntent)
                            .setOngoing(true)
                            .setOnlyAlertOnce(true)
                            .setShowWhen(true)
                            .setCategory(NotificationCompat.CATEGORY_SERVICE)
                            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

                        if (!subText.isNullOrBlank()) {
                            builder.setSubText(subText)
                        }
                        if (!bigText.isNullOrBlank()) {
                            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                        }
                        manager.notify(NOTIFICATION_ID, builder.build())
                        return
                    }
                }

                val intent = Intent(context, PiForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_CONTENT, content)
                    putExtra(EXTRA_SUB_TEXT, subText)
                    putExtra(EXTRA_BIG_TEXT, bigText)
                    putExtra(EXTRA_TARGET_SESSION_ID, targetSessionId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, PiForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
