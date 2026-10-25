package com.pi.app.keepalive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

class KeepAliveManager(private val context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 检查是否已经获得“忽略电池优化”权限 (类似 Termius / MQTT 助手)
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    /**
     * 引导用户一键将 App 加入系统忽略电池优化白名单，防止息屏/切后台时被厂商系统扼杀
     */
    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // 如果特定机型不支持直接弹窗，回退到系统电池优化管理列表
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e("KeepAliveManager", "Failed to open battery optimization settings", e2)
                }
            }
        }
    }

    /**
     * 在 Agent 执行长任务时申请唤醒锁与 WiFi 锁，防止息屏后 CPU 休眠或网络被系统切断
     */
    @Synchronized
    fun acquireWakeLock(timeoutMs: Long = 15 * 60 * 1000L) { // 默认最长保护 15 分钟
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "piDroid:TaskExecutionWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(timeoutMs)
            Log.d("KeepAliveManager", "WakeLock acquired for long-running task")
        }

        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "piDroid:WifiKeepAliveLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock?.acquire()
            Log.d("KeepAliveManager", "WifiLock acquired")
        }
    }

    /**
     * 任务完成时释放锁，避免无意义的电量消耗
     */
    @Synchronized
    fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("KeepAliveManager", "WakeLock released")
        }
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
            Log.d("KeepAliveManager", "WifiLock released")
        }
    }
}
