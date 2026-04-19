package com.pic.catcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.pic.catcher.config.ModuleConfig
import com.pic.catcher.util.ShellUtil

/**
 * 自动搬运服务 - 前台 Shell 驱动版
 * 彻底解决 Service 被杀和 Java File API 权限受限问题
 */
class PicWatcherService : Service() {

    companion object {
        private const val TAG = "PicWatcher"
        private const val CHANNEL_ID = "pic_watcher_service"
        // 预设多个可能的根路径，增强兼容性
        private val DATA_ROOTS = arrayOf("/sdcard/Android/data", "/storage/emulated/0/Android/data")
        private const val MODULE_PRIVATE_ROOT = "/sdcard/Android/data/com.evo.piccatcher/files/Pictures"
        private const val PUBLIC_ROOT = "/sdcard/Pictures/PicCatcher"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val harvestRunnable = object : Runnable {
        override fun run() {
            try {
                harvestAllViaShell()
            } catch (e: Exception) {
                Log.wtf(TAG, "Critical Loop Error", e)
            } finally {
                // 每 15 秒循环一次，无论成功失败
                handler.postDelayed(this, 15000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        Log.wtf(TAG, "Service Created - Start Monitoring")
        
        // 5秒后开始第一次扫描
        handler.postDelayed(harvestRunnable, 5000)
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "PicCatcher Monitor", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PicCatcher is monitoring")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .build()
            
            // Android 14 (API 34) 必须显式传入 foregroundServiceType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(101, notification)
            }
        }
    }

    private fun harvestAllViaShell() {
        Log.wtf(TAG, "--- Start Shell Scan [Heartbeat] ---")
        
        if (!ShellUtil.hasShizukuPermission()) {
            Log.e(TAG, "Shizuku permission NOT granted, skipping harvest")
            return
        }

        // 自动探测可访问的 Android/data 路径
        var activeDataRoot = ""
        for (root in DATA_ROOTS) {
            val check = ShellUtil.runCommand("[ -d \"$root\" ] && echo 'OK'")
            if (check.stdout.contains("OK")) {
                activeDataRoot = root
                break
            }
        }

        if (activeDataRoot.isEmpty()) {
            Log.e(TAG, "Unable to find a valid Android/data path via Shell")
            return
        }

        Log.i(TAG, "Using Data Root: $activeDataRoot")
        
        // 1. 获取所有包名
        val lsResult = ShellUtil.runCommand("ls $activeDataRoot")
        if (!lsResult.isSuccess) {
            Log.e(TAG, "LS $activeDataRoot failed: ${lsResult.stderr}")
            return
        }

        val packages = lsResult.stdout.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        
        for (pkg in packages) {
            if (pkg == "com.evo.piccatcher") continue

            val cachePath = "$activeDataRoot/$pkg/cache/PicCatcher"
            
            // 2. 检查该包是否有缓存目录
            val checkDir = ShellUtil.runCommand("[ -d \"$cachePath\" ] && echo 'YES'")
            if (checkDir.stdout.contains("YES")) {
                Log.i(TAG, "Found target: $pkg at $cachePath")
                
                // 3. 检查是否有文件
                val checkFiles = ShellUtil.runCommand("ls \"$cachePath\" | wc -l")
                val count = checkFiles.stdout.trim().toIntOrNull() ?: 0
                
                if (count > 0) {
                    Log.wtf(TAG, "Harvesting $count files from $pkg")
                    doHarvest(pkg, cachePath)
                }

                // 4. 存活检查及清理 (如果宿主进程不在了，清理其残留缓存)
                if (!isPackageRunning(pkg)) {
                    Log.i(TAG, "Host $pkg stopped, cleaning up cache")
                    ShellUtil.runCommand("rm -rf \"$cachePath\"")
                }
            }
        }
    }

    private fun doHarvest(pkg: String, cachePath: String) {
        val isInternal = ModuleConfig.getInstance().isSaveToInternal
        val targetRoot = if (isInternal) MODULE_PRIVATE_ROOT else PUBLIC_ROOT
        val targetDir = "$targetRoot/$pkg"

        // 搬运逻辑：cp -> rm
        val cmd = "mkdir -p \"$targetDir\" && " +
                  "touch \"$targetRoot/.nomedia\" && " +
                  "cp -f \"$cachePath\"/* \"$targetDir/\" && " +
                  "rm -f \"$cachePath\"/*"
        
        val res = ShellUtil.runCommand(cmd)
        if (res.isSuccess) {
            Log.wtf(TAG, "SUCCESS: $pkg -> $targetDir")
        } else {
            Log.e(TAG, "MOVE FAIL: $pkg, err: ${res.stderr}")
        }
    }

    private fun isPackageRunning(pkg: String): Boolean {
        val res = ShellUtil.runCommand("ps -A | grep \"$pkg\"")
        return res.stdout.split("\n").any { it.contains(pkg) && !it.contains("grep") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(harvestRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
