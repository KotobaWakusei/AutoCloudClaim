package com.opautocloud.claim

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 注入到 WebView 的 JS 桥，暴露给页面 window.autocloud。
 *
 * 这里只提供「观察设备状态 + 执行本机操作（打开/返回/卸载）」的能力，
 * 不提供任何伪造 report-event / grant-award 的方法：
 * 碎片是否发放，完全由服务端按任务状态机判定。
 */
class Bridge(private val wv: WebView) {

    private val app: Context get() = wv.context.applicationContext
    private val main: Handler get() = Handler(Looper.getMainLooper())

    /**
     * 延时切回云服务的待执行任务。
     *
     * 必须放在 Kotlin 侧而不是 JS 侧：注入脚本在应用退后台后依赖 WebView 的 setTimeout，
     * 但 WebView 渲染进程在 ColorOS 等 ROM 上会被冻结，导致 JS 的 bringToFront 永远执行不到。
     * 主线程 Handler 的消息在进程解冻后仍会派发，比 JS 定时器可靠。
     */
    @Volatile private var pendingReturn: Runnable? = null

    /** 包可见性：宿主进程被过滤时告警一次，并退回 root shell 查询（带短缓存）。 */
    @Volatile private var warnedVisibility = false
    @Volatile private var shellPkgsCache = ""
    @Volatile private var shellPkgsAt = 0L

    @JavascriptInterface fun isRunning(): Boolean = State.running
    @JavascriptInterface fun maxTasks(): Int = State.maxTasks
    @JavascriptInterface fun dwell(): Int = State.dwellSec
    @JavascriptInterface fun autoUninstall(): Boolean = State.autoUninstall
    @JavascriptInterface fun dryRun(): Boolean = State.dryRun

    /** 11.3.5 TaskWall SDK 最近一次 H5 结果的版本号。 */
    @JavascriptInterface fun taskResultVersion(): Long = State.taskResultVersion
    @JavascriptInterface fun taskResultCode(): Int = State.taskResultCode
    @JavascriptInterface fun taskResultMessage(): String = State.taskResultMessage
    @JavascriptInterface fun taskResultSkuId(): String = State.taskResultSkuId
    @JavascriptInterface fun taskResultTraceId(): String = State.taskResultTraceId
    @JavascriptInterface fun taskWallSuccessCode(): Int = State.TASKWALL_SUCCESS_CODE

    @JavascriptInterface fun sdkTrackVersion(): Long = State.sdkTrackVersion
    @JavascriptInterface fun sdkTrackPackage(): String = State.sdkTrackPackage
    @JavascriptInterface fun sdkTrackRequiredSec(): Int = State.sdkTrackRequiredSec
    @JavascriptInterface fun sdkTrackDurationSec(): Long = State.sdkTrackDurationSec
    @JavascriptInterface fun sdkTrackTimeLeftSec(): Long = State.sdkTrackTimeLeftSec

    @JavascriptInterface
    fun log(msg: String) {
        XposedBridge.log("[AutoCloud][JS] " + msg)
        State.publish(app, line = msg)
    }

    /** 由注入脚本上报当前阶段 / 已领取次数 / 碎片数，供控制面板显示。 */
    @JavascriptInterface
    fun status(phase: String, claimed: Int, fragments: Int) {
        State.publish(app, phase = phase, claimed = claimed, fragments = fragments, line = phase)
    }

    /** 本轮任务自然结束（达到上限 / 无任务可做 / 看门狗），复位运行标志让面板恢复可点。 */
    @JavascriptInterface
    fun finishRun() {
        State.running = false
        State.publish(app, phase = "已停止")
    }

    @JavascriptInterface
    fun now(): Long = System.currentTimeMillis()

    /** 某个包是否已安装（本机 PackageManager，非伪造）。 */
    @JavascriptInterface
    fun isInstalled(pkg: String): Boolean = try {
        app.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /** 返回当前已安装的非系统包名快照。由 JS 与安装前快照做差集，避免 firstInstallTime 时钟/恢复导致误判。 */
    @JavascriptInterface
    fun installedPackages(): String {
        return try {
            val visible = app.packageManager.getInstalledPackages(0)
                .asSequence()
                .filter { pi ->
                    if (pi.packageName == app.packageName) return@filter false
                    val flags = pi.applicationInfo?.flags ?: 0
                    (flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                        android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
                }
                .map { it.packageName }
                .sorted()
                .toList()

            // 包可见性受宿主（com.heytap.cloud）自己的 manifest 约束，模块的 <queries> 在这里无效。
            // 正常用户机上有几十个非系统包；只看到个位数说明被过滤了，安装差集检测会一直为空。
            // LSPosed 机通常有 su，退回 `pm list packages -3` 拿真实列表。
            if (visible.size >= 8) {
                visible.joinToString(",")
            } else {
                if (!warnedVisibility) {
                    warnedVisibility = true
                    XposedBridge.log(
                        "[AutoCloud] WARN only ${visible.size} non-system packages visible to " +
                            "${app.packageName}; package visibility is filtered. Falling back " +
                            "to `su -c pm list packages -3` for install detection."
                    )
                }
                val viaShell = packagesViaShell()
                if (viaShell.isNotEmpty()) viaShell else visible.joinToString(",")
            }
        } catch (err: Throwable) {
            ""
        }
    }

    /** root shell 拉真实非系统包列表；结果缓存 10 秒，避免安装轮询期间每 2 秒 fork 一次 su。 */
    private fun packagesViaShell(): String {
        val now = System.currentTimeMillis()
        if (shellPkgsCache.isNotEmpty() && now - shellPkgsAt < 10_000L) return shellPkgsCache
        val out = runCatching {
            shellCapture(arrayOf("su", "-c", "pm list packages -3"))
        }.getOrDefault("")
        val pkgs = out.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }
            .distinct()
            .sorted()
            .joinToString(",")
        if (pkgs.isNotEmpty()) {
            shellPkgsCache = pkgs
            shellPkgsAt = now
        }
        return pkgs
    }

    /** 保留旧接口兼容性；时间条件仅作辅助，不再作为唯一安装判据。 */
    @JavascriptInterface
    fun newPackagesSince(t: Long): String = try {
        installedPackages()
            .split(',')
            .filter { it.isNotBlank() }
            .filter { pkg ->
                runCatching {
                    app.packageManager.getPackageInfo(pkg, 0).firstInstallTime >= t
                }.getOrDefault(false)
            }
            .joinToString(",")
    } catch (err: Throwable) {
        ""
    }

    /**
     * 拉起指定应用。
     *
     * 注入脚本运行在 WebView 的 JavaBridge 线程上，startActivity 必须回主线程执行。
     * 这里用 latch 同步等待真实结果，否则安装/打开环节的失败要一直拖到 180 秒超时才暴露。
     */
    @JavascriptInterface
    fun launch(pkg: String): Boolean {
        if (!isValidPackage(pkg)) return false
        val intent = runCatching { app.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return false
        return onMainSync(2_000) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }
    }

    /** 将云服务 App 切回前台；同步返回真实的启动结果。 */
    @JavascriptInterface
    fun bringToFront(): Boolean = bringToFrontNow()

    private fun bringToFrontNow(): Boolean {
        val intent = runCatching {
            app.packageManager.getLaunchIntentForPackage(State.TARGET_PKG)
        }.getOrNull()
        if (intent == null) {
            XposedBridge.log("[AutoCloud] bringToFront: no launch intent for " + State.TARGET_PKG)
            return false
        }
        return onMainSync(2_000) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            app.startActivity(intent)
        }
    }

    /**
     * 排一次延时切回云服务（秒），由注入脚本在点击「立即打开」前调用。
     *
     * 必须走主线程 Handler：WebView 的 JS 定时器在应用退后台后会被 ROM 冻结
     * （ColorOS 尤甚），只靠脚本里的 bringToFront 会永远执行不到，用户得手动切回来。
     */
    @JavascriptInterface
    fun scheduleReturn(delaySec: Int): Boolean {
        val sec = delaySec.coerceIn(1, 600)
        cancelReturn()
        val task = Runnable {
            pendingReturn = null
            val ok = runCatching { bringToFrontNow() }.getOrElse {
                XposedBridge.log("[AutoCloud] scheduled return failed: " + it)
                false
            }
            XposedBridge.log("[AutoCloud] scheduled return fired after ${sec}s ok=$ok")
        }
        pendingReturn = task
        val queued = main.postDelayed(task, sec * 1000L)
        if (!queued) pendingReturn = null
        return queued
    }

    @JavascriptInterface
    fun cancelReturn(): Boolean {
        val task = pendingReturn ?: return true
        pendingReturn = null
        // Handler.removeCallbacks 返回 void，这里显式映射成 Boolean
        return runCatching { main.removeCallbacks(task); true }.getOrDefault(false)
    }

    // ---------- helpers ----------

    /** 在主线程同步执行 [block]，最多等 [timeoutMs]；已在主线程时直接执行。 */
    private fun onMainSync(timeoutMs: Long, block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching { block(); true }.getOrElse {
                XposedBridge.log("[AutoCloud] main action failed: " + it)
                false
            }
        }
        val latch = CountDownLatch(1)
        var ok = false
        val posted = main.post {
            ok = runCatching { block(); true }.getOrElse {
                XposedBridge.log("[AutoCloud] main action failed: " + it)
                false
            }
            latch.countDown()
        }
        if (!posted) return false
        val finished = runCatching {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) {
            XposedBridge.log("[AutoCloud] main action timed out after ${timeoutMs}ms")
        }
        return finished && ok
    }

    private fun isValidPackage(pkg: String): Boolean =
        pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))

    /**
     * 卸载本模块运行期间新装的应用。
     * LSPosed 设备通常已 root，优先 su；失败则退回 pm --user 0。
     * 需要用户显式 --ez uninstall true 才会执行。
     */
    @JavascriptInterface
    fun uninstall(pkg: String): Boolean {
        if (!State.autoUninstall || !isValidPackage(pkg)) return false
        if (pkg == State.TARGET_PKG || pkg == app.packageName) return false
        val a = shell(arrayOf("pm", "uninstall", "--user", "0", pkg))
        if (a) return true
        return shell(arrayOf("su", "-c", "pm uninstall " + pkg))
    }

    @JavascriptInterface
    fun haptic(): Boolean = true // 占位，避免页面调用报错

    private fun shell(command: Array<String>): Boolean {
        val (exit, output) = shellCaptureWithExit(command) ?: return false
        XposedBridge.log(
            "[AutoCloud] shell: " + command.joinToString(" ") +
                " -> exit=" + exit + " " + output.trim()
        )
        return exit == 0 && output.contains("Success", ignoreCase = true)
    }

    private fun shellCapture(command: Array<String>): String =
        shellCaptureWithExit(command)?.second.orEmpty()

    /** 返回 (退出码, 合并后的 stdout+stderr)；进程起不来时返回 null。 */
    private fun shellCaptureWithExit(command: Array<String>): Pair<Int, String>? = try {
        val p = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        exit to output
    } catch (t: Throwable) {
        XposedBridge.log(t)
        null
    }
}
