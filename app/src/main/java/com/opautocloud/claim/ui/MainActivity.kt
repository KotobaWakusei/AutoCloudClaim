package com.opautocloud.claim.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opautocloud.claim.BuildConfig
import com.opautocloud.claim.State
import com.opautocloud.claim.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

// ---------------------------------------------------------------- 主题

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B63CE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF565E71),
    background = Color(0xFFF7F9FF),
    surface = Color(0xFFF7F9FF),
    surfaceVariant = Color(0xFFE1E2EC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00305F),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC),
    background = Color(0xFF10141A),
    surface = Color(0xFF10141A),
    surfaceVariant = Color(0xFF2A2F3A),
)

@Composable
fun AutoCloudTheme(content: @Composable () -> Unit) {
    val scheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

// ---------------------------------------------------------------- Activity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AutoCloudTheme { ControlScreen() } }
    }
}

// ---------------------------------------------------------------- 界面

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen() {
    val ctx = LocalContext.current

    // 控制参数
    var maxTasks by remember { mutableIntStateOf(3) }
    var dwell by remember { mutableIntStateOf(60) }
    var uninstall by remember { mutableStateOf(false) }
    var dry by remember { mutableStateOf(false) }

    // 远端状态（由云服务进程回执）
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("未连接") }
    var claimed by remember { mutableIntStateOf(0) }
    var fragments by remember { mutableIntStateOf(0) }
    var lastAck by remember { mutableLongStateOf(0L) }
    var lastAckSession by remember { mutableStateOf("") }
    var sessionToken by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<String>() }

    // GitHub 更新检查
    var updateResult by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var updateDismissed by rememberSaveable { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }

    fun push(line: String) {
        logs.add(0, line)
        while (logs.size > 200) logs.removeAt(logs.lastIndex)
    }

    // 每秒刷新连接指示
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    // 启动时检查 GitHub 更新
    LaunchedEffect(Unit) {
        updateChecking = true
        val result = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.check(BuildConfig.BUILD_NUMBER) }.getOrNull()
        }
        updateChecking = false
        if (result != null && result.hasUpdate) {
            updateResult = result
            push("↑ 发现新版本 " + result.latestTag + "（当前 build-" + BuildConfig.BUILD_NUMBER + "）")
        }
    }
    // tick 在组合里被读取一次，才能让「已连接」按秒自动过期
    val connected = run {
        @Suppress("UNUSED_EXPRESSION") tick
        lastAck > 0 && System.currentTimeMillis() - lastAck < 12_000
    }

    val latestAckSession by rememberUpdatedState(lastAckSession)
    val latestPhase by rememberUpdatedState(phase)

    // 接收云服务进程的状态回执
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // STATUS is sent from the cloud-service UID, so the receiver must be exported.
                // Accept only replies carrying the unpredictable token generated for this run.
                if (sessionToken.isBlank() || i.getStringExtra("session") != sessionToken) return
                lastAck = System.currentTimeMillis()
                lastAckSession = i.getStringExtra("session").orEmpty()
                running = i.getBooleanExtra("running", running)
                i.getStringExtra("phase")?.let { phase = it }
                if (i.hasExtra("claimed")) claimed = i.getIntExtra("claimed", claimed)
                if (i.hasExtra("fragments")) fragments = i.getIntExtra("fragments", fragments)
                i.getStringExtra("line")?.let { push(it) }
            }
        }
        val filter = IntentFilter(State.ACT_STATUS)
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        ctx.registerReceiver(receiver, filter, flags)
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    // 点击开始后先拉起目标 App，再持续重发 START，解决进程启动期间动态接收器尚未注册的竞态。
    var startTick by remember { mutableIntStateOf(0) }
    var startInProgress by remember { mutableStateOf(false) }
    LaunchedEffect(startTick) {
        if (startTick == 0) return@LaunchedEffect
        val token = sessionToken
        val max = maxTasks
        val waitSec = dwell
        val removeAfterClaim = uninstall
        val dryRun = dry

        var launchAttempted = false
        for (attempt in 1..15) {
            if (latestAckSession == token) {
                startInProgress = false
                if (latestPhase.contains("等待福利中心页面")) {
                    push("! 云服务进程已连接，但尚未检测到福利中心网页；进入福利中心后模块会自动继续。")
                } else {
                    push("✓ 已收到云服务回执：" + latestPhase)
                }
                return@LaunchedEffect
            }

            // Give an already-running cloud process two seconds to answer before foregrounding it.
            if (attempt == 3 && !launchAttempted) {
                launchAttempted = true
                val launchIntent = runCatching {
                    ctx.packageManager.getLaunchIntentForPackage(State.TARGET_PKG)
                }.getOrNull()
                if (launchIntent == null) {
                    push("! 找不到云服务 App 的启动入口；继续重试控制指令。")
                } else {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(launchIntent) }
                        .onSuccess { push("→ 前两次未收到回执，已自动拉起云服务") }
                        .onFailure { push("! 自动拉起云服务失败: " + (it.message ?: it.javaClass.simpleName)) }
                }
            }

            val command = Intent(State.ACT_START)
                .setPackage(State.TARGET_PKG)
                .putExtra("session", token)
                .putExtra("max", max)
                .putExtra("dwell", waitSec)
                .putExtra("uninstall", removeAfterClaim)
                .putExtra("dry", dryRun)
            runCatching { ctx.sendBroadcast(command) }
                .onFailure { if (attempt == 1) push("! 发送启动指令失败: " + it.message) }

            if (attempt == 1) {
                push("→ 正在等待云服务 Hook 接收器（自动重试，最多 15 秒）")
            }
            delay(1000)
        }

        startInProgress = false
        if (latestAckSession != token) {
            push("! 已自动拉起云服务，但 15 秒内未收到 Hook 回执。请检查 LSPosed 模块是否启用、作用域是否包含 com.heytap.cloud，并强行停止后重开云服务。")
        }
    }

    fun sendStart() {
        val token = UUID.randomUUID().toString()
        sessionToken = token
        lastAck = 0L
        lastAckSession = ""
        running = false
        phase = "正在启动云服务"
        startInProgress = true
        // 新一轮开始：清掉上一轮的计数，避免状态卡长期显示旧值
        claimed = 0
        fragments = 0

        push("→ START max=" + maxTasks + " dwell=" + dwell +
            " uninstall=" + uninstall + " dry=" + dry)
        startTick++
    }

    fun sendStop() {
        val token = sessionToken
        if (token.isBlank()) {
            push("! 当前没有可停止的运行会话")
            return
        }
        val i = Intent(State.ACT_STOP)
            .setPackage(State.TARGET_PKG)
            .putExtra("session", token)
        runCatching { ctx.sendBroadcast(i) }
            .onFailure { push("! 停止失败: " + it.message) }
        // 先本地复位：云服务进程若已被强停就收不到回执，
        // 不复位的话 running 永远为 true，「开始」按钮会被永久锁死。
        // 云端若其实还活着，它下一次 status 回执会把 running 重新置真。
        running = false
        startInProgress = false
        phase = "已停止"
        push("→ STOP")
    }

    fun openTarget() {
        val it = ctx.packageManager.getLaunchIntentForPackage(State.TARGET_PKG)
        if (it == null) {
            push("! 找不到云服务 App")
            return
        }
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(it) }
            .onSuccess { push("→ 已请求打开云服务，请进入「福利中心」") }
            .onFailure { push("! 打开云服务失败: " + (it.message ?: it.javaClass.simpleName)) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AutoCloud Claim", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (connected) ("已连接 · " + phase) else "未连接 · 等待云服务回执",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (connected) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(10.dp),
                    ) {}
                    Spacer(Modifier.width(0.dp))
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- 状态卡 ----
            CardBox("当前状态") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(running, phase)
                    Spacer(Modifier.width(12.dp))
                    Metric("已领取", claimed.toString() + " 次")
                    Spacer(Modifier.width(12.dp))
                    Metric("碎片", fragments.toString())
                }
            }

            // ---- 更新提示 ----
            if (!updateDismissed && updateResult != null) {
                Spacer(Modifier.height(8.dp))
                UpdateBanner(
                    result = updateResult!!,
                    currentBuild = BuildConfig.BUILD_NUMBER,
                    onOpen = {
                        UpdateChecker.openReleasePage(ctx, updateResult!!.releaseUrl)
                    },
                    onDismiss = { updateDismissed = true },
                )
            } else if (updateChecking) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "正在检查更新…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---- 主操作 ----
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { sendStart() },
                    enabled = !running && !startInProgress,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(when { running -> "运行中…"; startInProgress -> "正在连接云服务…"; else -> "开始自动完成" }) }

                FilledTonalButton(
                    onClick = { sendStop() },
                    enabled = running || connected,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("停止") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { openTarget() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("打开云服务 · 去福利中心") }

            Spacer(Modifier.height(12.dp))

            // ---- 参数 ----
            CardBox("参数") {
                SliderRow("最多领取次数", maxTasks.toFloat(), maxTasks.toString() + " 次",
                    1f..10f, 1f) { maxTasks = it.toInt() }
                SliderRow("默认停留秒数", dwell.toFloat(), dwell.toString() + " 秒",
                    15f..300f, 5f) { dwell = it.toInt() }
                SwitchRow(
                    "领取成功后卸载本次新装的应用",
                    "需要 root；次留/审核类任务可能因此被判未完成",
                    uninstall,
                ) { uninstall = it }
                SwitchRow(
                    "演示模式（dry run）",
                    "只打印任务卡片结构，不点击任何按钮",
                    dry,
                ) { dry = it }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 日志 ----
            CardBox("运行日志") {
                if (logs.isEmpty()) {
                    Text(
                        "点击「开始自动完成」会自动启动云服务；如需进入福利中心，日志会明确提示。",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 280.dp),
                    ) {
                        items(logs) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = when {
                                    line.startsWith("!") -> MaterialTheme.colorScheme.error
                                    line.startsWith("→") -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "只做本机动作（点击、启动、计时、卸载），不伪造 report-event / grant-award；" +
                    "能否领到碎片由服务端任务状态机判定。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CardBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------- 小部件

@Composable
private fun StatusChip(running: Boolean, phase: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (running) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = RoundedCornerShape(50),
                color = if (running) Color(0xFF2E7D32) else Color(0xFF9E9E9E),
            ) {}
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) phase else "空闲",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).toInt() - 1,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UpdateBanner(
    result: UpdateChecker.Result,
    currentBuild: Int,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↑ 发现新版本",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        "忽略",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
            }
            Text(
                result.latestTag + "（当前 build-" + currentBuild + "）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            if (result.releaseName.isNotBlank() && result.releaseName != result.latestTag) {
                Text(
                    result.releaseName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpen,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("前往下载") }
        }
    }
}
