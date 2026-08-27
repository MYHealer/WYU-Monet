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
        // 预创建 WPS 进程需要写入的文件（chmod 666 让不同 UID 可读写）
        Thread {
            deployFiles(this)
            scheduleRootCheckin()
        }.apply { isDaemon = true; name = "wyu-init" }.start()
        setContent {
            MiuixTheme {
                MainScreen()
            }
        }
    }
}

// 模块 app（有 root）派发 root 常驻轮询定时器：每分钟检查配置到点打卡，永不退出，不依赖任何 app
fun scheduleRootCheckin() {
    Thread {
        try {
            // 防重复：已有 root 定时器在跑则跳过（常驻循环会一直运行）
            if (isRootTimerAlive()) {
                return@Thread
            }
            // 确认打卡已开启
            val cfg = readWpsFile("/data/user/0/com.wps.koa/files/wps-miuix-checkin.txt")
            if (cfg.isNullOrEmpty()) return@Thread
            val lines = cfg.lines()
            if (lines.isEmpty() || lines[0].trim() != "true") return@Thread

            // 调用 CheckinWorker 的 schedule 模式派发轮询定时器（脚本在 dex 内，无转义问题）
            ProcessBuilder("su", "-c",
                "CLASSPATH=/data/local/tmp/CheckinWorker.dex app_process / CheckinWorker schedule").start().waitFor()
        } catch (_: Exception) {}
    }.apply { isDaemon = true; name = "wyu-root-scheduler" }.start()
}

// 检查 root 定时器是否存活（读 PID 文件，su kill -0 探测）
fun isRootTimerAlive(): Boolean {
    return try {
        val pid = readWpsFile("/data/user/0/com.wps.koa/files/wps-checkin-timer.pid")?.trim()
        if (pid.isNullOrEmpty()) return false
        val p = ProcessBuilder("su", "-c", "kill -0 $pid 2>/dev/null").start()
        p.waitFor() == 0
    } catch (_: Exception) { false }
}

// 模块 app 用 su 读取 WPS 私有目录文件
fun readWpsFile(path: String): String? {
    return try {
        val p = ProcessBuilder("su", "-c", "cat $path").start()
        p.inputStream.bufferedReader().readText().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

private fun deployFiles(ctx: android.content.Context) {
    // 部署 CheckinWorker.dex（BootReceiver 只在开机触发，app 内也要部署）
    try {
        val checkResult = ProcessBuilder("su", "-c", "stat -c %s /data/local/tmp/CheckinWorker.dex 2>/dev/null").start()
        val sizeStr = checkResult.inputStream.bufferedReader().readText().trim()
        checkResult.waitFor()
        val size = sizeStr.toLongOrNull() ?: 0L
        if (size < 8000) {
            val tmpDex = File(ctx.cacheDir, "CheckinWorker.dex")
            ctx.assets.open("CheckinWorker.dex").use { input ->
                FileOutputStream(tmpDex).use { output -> input.copyTo(output) }
            }
            ProcessBuilder("su", "-c", "cp ${tmpDex.absolutePath} /data/local/tmp/CheckinWorker.dex && chmod 666 /data/local/tmp/CheckinWorker.dex").start().waitFor()
            tmpDex.delete()
        }
    } catch (_: Exception) {}
    // 预创建 flag 文件
    val files = arrayOf(
        "/data/local/tmp/wps-miuix.log",
        "/data/local/tmp/wps-miuix-config.txt",
        "/data/local/tmp/wps-miuix-session.txt",
        "/data/local/tmp/wps-miuix-petpos.txt",
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
