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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opautocloud.claim.State
import kotlinx.coroutines.delay

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
    var tick by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<String>() }

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
    // tick 在组合里被读取一次，才能让「已连接」按秒自动过期
    val connected = run {
        @Suppress("UNUSED_EXPRESSION") tick
        lastAck > 0 && System.currentTimeMillis() - lastAck < 12_000
    }

    // 接收云服务进程的状态回执
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                lastAck = System.currentTimeMillis()
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

    // START 广播发出后等回执，判断云服务进程是否活着
    var startTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(startTick) {
        if (startTick == 0) return@LaunchedEffect
        delay(3500)
        if (lastAck == 0L || System.currentTimeMillis() - lastAck > 3000) {
            push("! 无回执：请先打开云服务 App 并进入「福利中心 · 赚碎片兑云空间」")
        }
    }

    fun sendStart() {
        val i = Intent(State.ACT_START)
            .setPackage(State.TARGET_PKG)
            .putExtra("max", maxTasks)
            .putExtra("dwell", dwell)
            .putExtra("uninstall", uninstall)
            .putExtra("dry", dry)
        runCatching { ctx.sendBroadcast(i) }
            .onFailure { push("! 发送失败: " + it.message) }
        push("→ START max=" + maxTasks + " dwell=" + dwell +
            " uninstall=" + uninstall + " dry=" + dry)
        startTick++
    }

    fun sendStop() {
        val i = Intent(State.ACT_STOP).setPackage(State.TARGET_PKG)
        runCatching { ctx.sendBroadcast(i) }
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
        push("→ 已拉起云服务，请进入「福利中心」")
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

            Spacer(Modifier.height(12.dp))

            // ---- 主操作 ----
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { sendStart() },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(if (running) "运行中…" else "开始自动完成") }

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
                        "还没有日志。先打开云服务进入福利中心，再点「开始自动完成」。",
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
