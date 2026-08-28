package com.wps.enhancer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { deployFiles(this) }.start()
        scheduleRootCheckin()
        startConfigWatcher(this)
        setContent {
            MiuixTheme {
                MainScreen()
            }
        }
    }
}

fun scheduleRootCheckin() {
    Thread {
        try {
            if (isRootTimerAlive()) return@Thread
            // 读 /data/local/tmp/ 的配置副本（WPS 私有目录受 SELinux 限制不可读）
            val cfg = readLocalTmpFile("/data/local/tmp/wps-miuix-checkin.txt")
            if (cfg.isNullOrEmpty()) return@Thread
            val lines = cfg.lines()
            if (lines.isEmpty() || lines[0].trim() != "true") return@Thread
            ProcessBuilder("su", "-c",
                "CLASSPATH=/data/local/tmp/CheckinWorker.dex app_process / CheckinWorker schedule").start().waitFor()
        } catch (_: Exception) {}
    }.apply { isDaemon = true; name = "wyu-root-scheduler" }.start()
}

fun isRootTimerAlive(): Boolean {
    return try {
        val pid = readLocalTmpFile("/data/local/tmp/wps-checkin-timer.pid")?.trim()
        if (pid.isNullOrEmpty()) return false
        val p = ProcessBuilder("su", "-c", "kill -0 $pid 2>/dev/null").start()
        p.waitFor() == 0
    } catch (_: Exception) { false }
}

// 读 /data/local/tmp/ 下的文件（app 进程有权限）
private fun readLocalTmpFile(path: String): String? {
    return try {
        val f = File(path)
        if (!f.exists()) return null
        f.readText().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

private fun deployFiles(ctx: android.content.Context) {
    // 部署 CheckinWorker.dex（每次启动都检查，确保版本一致）
    try {
        val assetSize = ctx.assets.open("CheckinWorker.dex").use { it.available().toLong() }
        val checkResult = ProcessBuilder("su", "-c", "stat -c %s /data/local/tmp/CheckinWorker.dex 2>/dev/null").start()
        val sizeStr = checkResult.inputStream.bufferedReader().readText().trim()
        checkResult.waitFor()
        val deviceSize = sizeStr.toLongOrNull() ?: 0L
        if (deviceSize != assetSize) {
            val tmpDex = File(ctx.cacheDir, "CheckinWorker.dex")
            ctx.assets.open("CheckinWorker.dex").use { input ->
                FileOutputStream(tmpDex).use { output -> input.copyTo(output) }
            }
            ProcessBuilder("su", "-c", "cp ${tmpDex.absolutePath} /data/local/tmp/CheckinWorker.dex && chmod 666 /data/local/tmp/CheckinWorker.dex").start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            tmpDex.delete()
        }
    } catch (_: Exception) {}
    // 预创建 flag 文件
    val files = arrayOf(
        "/data/local/tmp/wps-miuix.log",
        "/data/local/tmp/wps-miuix-config.txt",
        "/data/local/tmp/wps-miuix-session.txt",
        "/data/local/tmp/wps-miuix-petpos.txt",
        "/data/local/tmp/wps-miuix-checkin.txt",
        "/data/local/tmp/wyu-pet-enabled",
        "/data/local/tmp/wyu-checkin-enabled",
        "/data/local/tmp/wyu-monet-enabled",
        "/data/local/tmp/wyu-root-hide",
        "/data/local/tmp/wyu-watermark"
    )
    try {
        val cmd = files.joinToString("") { "touch $it && chmod 666 $it && " } + "echo ok"
        ProcessBuilder("su", "-c", cmd).start().waitFor()
    } catch (_: Exception) {}
}

@Composable
fun MainScreen() {
    var rootStatus by remember { mutableStateOf(RootState.Requesting) }
    var petEnabled by remember { mutableStateOf(false) }
    var checkinEnabled by remember { mutableStateOf(false) }
    var monetEnabled by remember { mutableStateOf(false) }
    var rootHideEnabled by remember { mutableStateOf(false) }
    var watermarkEnabled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        petEnabled = readFlag("wyu-pet-enabled")
        checkinEnabled = readFlag("wyu-checkin-enabled")
        monetEnabled = readFlag("wyu-monet-enabled")
        rootHideEnabled = readFlag("wyu-root-hide")
        watermarkEnabled = readFlag("wyu-watermark")
        rootStatus = requestRoot()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderSection(rootStatus)

            Spacer(modifier = Modifier.height(8.dp))

            SectionTitle("功能开关")

            FeatureSwitch(
                icon = "🐱",
                title = "Claude Code",
                subtitle = "AI 助手集成",
                checked = petEnabled,
                onToggle = { enabled ->
                    petEnabled = enabled
                    scope.launch { saveFlag("wyu-pet-enabled", enabled) }
                }
            )

            FeatureSwitch(
                icon = "⏰",
                title = "自动打卡",
                subtitle = "定时自动提交",
                checked = checkinEnabled,
                onToggle = { enabled ->
                    checkinEnabled = enabled
                    scope.launch { saveFlag("wyu-checkin-enabled", enabled) }
                }
            )

            FeatureSwitch(
                icon = "🎨",
                title = "莫奈取色",
                subtitle = "动态主题颜色",
                checked = monetEnabled,
                onToggle = { enabled ->
                    monetEnabled = enabled
                    scope.launch { saveFlag("wyu-monet-enabled", enabled) }
                }
            )

            FeatureSwitch(
                icon = "🔒",
                title = "去除检测",
                subtitle = "隐藏 Root 状态",
                checked = rootHideEnabled,
                onToggle = { enabled ->
                    rootHideEnabled = enabled
                    scope.launch { saveFlag("wyu-root-hide", enabled) }
                }
            )

            FeatureSwitch(
                icon = "📝",
                title = "去除水印",
                subtitle = "移除文档水印",
                checked = watermarkEnabled,
                onToggle = { enabled ->
                    watermarkEnabled = enabled
                    scope.launch { saveFlag("wyu-watermark", enabled) }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun HeaderSection(rootStatus: RootState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "WYU-Monet",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "WPS Xposed Module",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            val (statusText, statusColor) = when (rootStatus) {
                RootState.Requesting -> "请求中" to Color(0xFFFFA726)
                RootState.Granted -> "已授权" to Color(0xFF66BB6A)
                RootState.Denied -> "未授权" to Color(0xFFEF5350)
            }
            Text(
                statusText,
                fontSize = 12.sp,
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun FeatureSwitch(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                icon,
                fontSize = 24.sp,
                modifier = Modifier.width(40.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onToggle
            )
        }
    }
}

enum class RootState { Requesting, Granted, Denied }

// 持久化开关状态到 /data/local/tmp/（world-readable，WPS 进程可直接读取）
private suspend fun saveFlag(key: String, value: Boolean) = withContext(Dispatchers.IO) {
    val path = "/data/local/tmp/$key"
    val content = if (value) "1" else "0"
    try {
        // 预创建文件后应用进程可直接写（无需 su）
        FileWriter(path).use { it.write(content) }
    } catch (_: Exception) {
        // 兜底：文件未预创建 / 目录无写权限时走 su 强制写入
        try {
            ProcessBuilder("su", "-c", "echo -n '$content' > $path").start().waitFor()
        } catch (_: Exception) {}
    }
    // chmod 666 让 WPS 进程（不同 UID）也能读写
    try { ProcessBuilder("su", "-c", "chmod 666 $path").start().waitFor() } catch (_: Exception) {}
}

private fun readFlag(key: String): Boolean {
    return try {
        val f = File("/data/local/tmp/$key")
        f.exists() && f.readText().trim() == "1"
    } catch (_: Exception) { false }
}

suspend fun requestRoot(): RootState = withContext(Dispatchers.IO) {
    try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor()
        if (exit == 0 && output.contains("uid=0")) RootState.Granted else RootState.Denied
    } catch (_: Exception) { RootState.Denied }
}

// 监听配置文件变化，自动设置闹钟（解决 Android 14+ 跨进程广播限制）
private var configWatcher: android.os.FileObserver? = null
fun startConfigWatcher(ctx: android.content.Context) {
    val configFile = java.io.File("/data/local/tmp/wps-miuix-checkin.txt")
    if (!configFile.exists()) return
    configWatcher?.stopWatching()
    configWatcher = object : android.os.FileObserver(configFile.absolutePath, CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            try {
                val content = configFile.readText().trim()
                val lines = content.lines()
                if (lines.isEmpty() || lines[0].trim() != "true") return
                val hour = lines[1].trim().toIntOrNull() ?: return
                val minute = lines[2].trim().toIntOrNull() ?: return
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                cal.set(java.util.Calendar.MINUTE, minute)
                cal.set(java.util.Calendar.SECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
                val intent = android.content.Intent("com.wps.enhancer.CHECKIN_ACTION")
                intent.setPackage("com.wps.enhancer")
                val pi = android.app.PendingIntent.getBroadcast(
                    ctx, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pi)
                android.util.Log.d("WYU", "FILE_OBSERVER: alarm set at ${cal.time}")
            } catch (t: Throwable) {
                android.util.Log.e("WYU", "FILE_OBSERVER error: ${t.message}")
            }
        }
    }
    configWatcher?.startWatching()
    // 立即读取已有配置并设闹钟
    try {
        val content = configFile.readText().trim()
        val lines = content.lines()
        if (lines.isNotEmpty() && lines[0].trim() == "true") {
            val hour = lines[1].trim().toIntOrNull() ?: 0
            val minute = lines[2].trim().toIntOrNull() ?: 0
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val am = ctx.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent("com.wps.enhancer.CHECKIN_ACTION")
            intent.setPackage("com.wps.enhancer")
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pi)
            android.util.Log.d("WYU", "INIT: alarm set at ${cal.time}")
        }
    } catch (_: Throwable) {}
}

// 接收 WPS 模块的广播，用模块 app 的 context 设闹钟（模块 app 有 SCHEDULE_EXACT_ALARM 权限）
class ScheduleReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        if (intent.action != "com.wps.enhancer.SCHEDULE_CHECKIN") return
        val hour = intent.getIntExtra("hour", 0)
        val minute = intent.getIntExtra("minute", 0)
        val weekly = intent.getBooleanExtra("weekly", false)
        try {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            if (weekly) {
                while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val checkIntent = android.content.Intent("com.wps.enhancer.CHECKIN_ACTION")
            checkIntent.setPackage("com.wps.enhancer")
            val pi = android.app.PendingIntent.getBroadcast(
                context, 0, checkIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pi)
            android.util.Log.d("WYU", "SCHEDULE set at ${cal.time}")
        } catch (t: Throwable) {
            android.util.Log.e("WYU", "SCHEDULE failed: ${t.message}")
        }
    }
}

// 跨进程 Service：WPS 模块调用 startService() 来设闹钟（模块 app 有 SCHEDULE_EXACT_ALARM 权限）
class ScheduleService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?) = null
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return android.app.Service.START_NOT_STICKY }
        val hour = intent.getIntExtra("hour", -1)
        val minute = intent.getIntExtra("minute", -1)
        try {
            // 读取配置文件获取时间（优先用 intent extra，fallback 到文件）
            val configFile = java.io.File("/data/local/tmp/wps-miuix-checkin.txt")
            val h: Int
            val m: Int
            if (hour >= 0 && minute >= 0) {
                h = hour; m = minute
            } else if (configFile.exists()) {
                val lines = configFile.readText().trim().lines()
                if (lines.isEmpty() || lines[0].trim() != "true") { stopSelf(); return android.app.Service.START_NOT_STICKY }
                h = lines[1].trim().toIntOrNull() ?: 0
                m = lines[2].trim().toIntOrNull() ?: 0
            } else { stopSelf(); return android.app.Service.START_NOT_STICKY }

            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, m)
            cal.set(java.util.Calendar.SECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            val am = getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val checkIntent = android.content.Intent("com.wps.enhancer.CHECKIN_ACTION")
            checkIntent.setPackage("com.wps.enhancer")
            val pi = android.app.PendingIntent.getBroadcast(
                this, 0, checkIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(cal.timeInMillis, null), pi)
            android.util.Log.d("WYU", "SERVICE: alarm set at ${cal.time}")
        } catch (t: Throwable) {
            android.util.Log.e("WYU", "SERVICE error: ${t.message}")
        }
        stopSelf()
        return android.app.Service.START_NOT_STICKY
    }
}
