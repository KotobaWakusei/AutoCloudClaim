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

    /** 返回 firstInstallTime >= t 的新安装包名，逗号分隔。 */
    @JavascriptInterface
    fun newPackagesSince(t: Long): String = try {
        val sb = StringBuilder()
        for (pi in app.packageManager.getInstalledPackages(0)) {
            if (pi.firstInstallTime < t) continue
            if (pi.packageName == app.packageName) continue
            val sys = (pi.applicationInfo?.flags ?: 0) and
                (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
            if (sys != 0) continue
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(pi.packageName)
        }
        sb.toString()
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
