package com.wps.enhancer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.FileOutputStream

// 开机自启：重启后自动派发 root 定时器（CheckinWorker 独立打卡，无需打开任何 app）
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            // 后台线程：重试等待 su 就绪（小米设备开机后 Magisk 需要时间初始化）
            Thread {
                // 等 su 就绪（最多 60 次 x 5 秒 = 5 分钟）
                var suReady = false
                for (i in 1..60) {
                    try {
                        val p = ProcessBuilder("su", "-c", "id").start()
                        val out = p.inputStream.bufferedReader().readText()
                        p.waitFor()
                        if (out.contains("uid=0")) { suReady = true; break }
                    } catch (_: Exception) {}
                    Thread.sleep(5000)
                }
                if (!suReady) return@Thread

                // 部署 CheckinWorker.dex（从模块 APK assets 复制到 /data/local/tmp/，确保文件存在且有效）
                try {
                    val target = File("/data/local/tmp/CheckinWorker.dex")
                    val needDeploy = !target.exists() || target.length() < 8000
                    if (needDeploy) {
                        context.assets.open("CheckinWorker.dex").use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                        // 设置权限让 app_process 能读取
                        ProcessBuilder("su", "-c", "chmod 666 /data/local/tmp/CheckinWorker.dex").start().waitFor()
                    }
                } catch (_: Exception) {}

                // 预创建日志文件（WPS 私有目录）
                try {
                    ProcessBuilder("su", "-c", "touch /data/user/0/com.wps.koa/files/wps-miuix.log && chmod 666 /data/user/0/com.wps.koa/files/wps-miuix.log").start().waitFor()
                } catch (_: Exception) {}

                // 部署斗界彩蛋视频
                try {
                    val videoTarget = File("/data/local/tmp/doujie.mp4")
                    if (!videoTarget.exists() || videoTarget.length() < 1000) {
                        context.assets.open("doujie.mp4").use { input ->
                            FileOutputStream(videoTarget).use { output -> input.copyTo(output) }
                        }
                        ProcessBuilder("su", "-c", "chmod 666 /data/local/tmp/doujie.mp4").start().waitFor()
                    }
                } catch (_: Exception) {}

                // 派发 root 定时器
                scheduleRootCheckin()
            }.apply { isDaemon = true; name = "wyu-boot-scheduler" }.start()
        }
    }
}
