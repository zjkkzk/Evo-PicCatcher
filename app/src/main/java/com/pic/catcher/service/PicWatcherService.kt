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
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.pic.catcher.config.ModuleConfig
import com.pic.catcher.util.ShellUtil

/**
 * 自动搬运服务 - 优化版
 * 1. 运行在独立后台线程，杜绝界面卡死
 * 2. 脚本化批量搬运，极低 CPU 占用
 */
class PicWatcherService : Service() {

    companion object {
        private const val TAG = "PicWatcher"
        private const val CHANNEL_ID = "pic_watcher_service"
        private const val MODULE_PRIVATE_ROOT = "/sdcard/Android/data/com.evo.piccatcher/files/Pictures"
        private const val PUBLIC_ROOT = "/sdcard/Pictures/PicCatcher"
    }

    private lateinit var serviceHandler: Handler
    private lateinit var handlerThread: HandlerThread

    private val harvestRunnable = object : Runnable {
        override fun run() {
            try {
                performBatchHarvest()
            } catch (e: Exception) {
                Log.e(TAG, "Harvest error", e)
            } finally {
                // 将扫描频率降低到 60 秒，减少对系统的打扰和电量消耗
                serviceHandler.postDelayed(this, 60000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        
        // 关键修复：创建专门的后台线程，绝不在主线程运行 Shell
        handlerThread = HandlerThread("PicWatcherThread")
        handlerThread.start()
        serviceHandler = Handler(handlerThread.looper)
        
        serviceHandler.postDelayed(harvestRunnable, 5000)
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "PicCatcher Monitor", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PicCatcher is active")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(101, notification)
            }
        }
    }

    /**
     * 使用单条复杂的 Shell 脚本完成批量检测和搬运
     * 避免了为每个包名重复创建 su 进程导致的卡顿
     */
    private fun performBatchHarvest() {
        val authType = ModuleConfig.getInstance().shellAuthType
        if (authType.isEmpty()) return

        val isInternal = ModuleConfig.getInstance().isSaveToInternal
        val targetRoot = if (isInternal) MODULE_PRIVATE_ROOT else PUBLIC_ROOT

        // 核心脚本：
        // 1. 查找所有 Android/data 下的 PicCatcher 缓存目录
        // 2. 如果目录下有文件，则创建目标目录并移动文件
        // 3. 这里的 2>/dev/null 是为了防止没有权限的目录产生报错干扰
        val script = """
            DATA_ROOT="/sdcard/Android/data"
            TARGET_BASE="$targetRoot"
            mkdir -p "${"$"}TARGET_BASE"
            touch "${"$"}TARGET_BASE/.nomedia"
            
            for dir in ${"$"}DATA_ROOT/*/cache/PicCatcher; do
                [ -d "${"$"}dir" ] || continue
                # 检查目录是否为空
                if [ -n "$(ls -A "${"$"}dir" 2>/dev/null)" ]; then
                    pkg=$(echo "${"$"}dir" | cut -d'/' -f5)
                    dest="${"$"}TARGET_BASE/${"$"}pkg"
                    mkdir -p "${"$"}dest"
                    cp -f "${"$"}dir"/* "${"$"}dest/" 2>/dev/null && rm -f "${"$"}dir"/* 2>/dev/null
                fi
            done
        """.trimIndent()

        ShellUtil.runCommand(script)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
