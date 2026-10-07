package com.opautocloud.claim

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.InputStreamReader

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
    fun installedPackages(): String = try {
        app.packageManager.getInstalledPackages(0)
            .asSequence()
            .filter { pi ->
                if (pi.packageName == app.packageName) return@filter false
                val flags = pi.applicationInfo?.flags ?: 0
                (flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
            }
            .map { it.packageName }
            .sorted()
            .joinToString(",")
    } catch (err: Throwable) {
        ""
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

    /** 拉起目标应用（必须在主线程）。 */
    @JavascriptInterface
    fun launch(pkg: String): Boolean {
        val r = BooleanArray(1)
        main.post {
            r[0] = try {
                val it = app.packageManager.getLaunchIntentForPackage(pkg) ?: return@post
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(it)
                true
            } catch (t: Throwable) {
                XposedBridge.log(t); false
            }
        }
        waitMain()
        return r[0]
    }

    /** 把云服务 App 拉回前台（回到 H5 继续浏览计时/领取）。 */
    @JavascriptInterface
    fun bringToFront(): Boolean {
        val r = BooleanArray(1)
        main.post {
            r[0] = try {
                val it = app.packageManager.getLaunchIntentForPackage(State.TARGET_PKG)
                    ?: return@post
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                app.startActivity(it)
                true
            } catch (t: Throwable) {
                XposedBridge.log(t); false
            }
        }
        waitMain()
        return r[0]
    }

    /**
     * 卸载本模块运行期间新装的应用。
     * LSPosed 设备通常已 root，优先 su；失败则退回 pm --user 0。
     * 需要用户显式 --ez uninstall true 才会执行。
     */
    @JavascriptInterface
    fun uninstall(pkg: String): Boolean {
        if (!State.autoUninstall) return false
        if (pkg.isBlank() || pkg == State.TARGET_PKG || pkg == app.packageName) return false
        val a = shell("pm uninstall --user 0 $pkg")
        if (a) return true
        return shell("su -c 'pm uninstall $pkg'")
    }

    @JavascriptInterface
    fun haptic(): Boolean = true // 占位，避免页面调用报错

    // ---------- helpers ----------

    private fun waitMain() {
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun shell(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
        p.waitFor()
        XposedBridge.log("[AutoCloud] shell: " + cmd + " -> " + out.trim() + " " + err.trim())
        out.contains("Success")
    } catch (t: Throwable) {
        XposedBridge.log(t)
        false
    }
}
