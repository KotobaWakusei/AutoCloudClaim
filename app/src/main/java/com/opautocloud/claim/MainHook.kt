package com.opautocloud.claim

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

/**
 * 进程内共享状态。
 *
 * 触发方式：
 *  1) 控制面板 GUI（推荐）：桌面图标 AutoCloud Claim → 点「开始自动完成」
 *  2) adb / root shell：
 *     adb shell am broadcast -a com.opautocloud.claim.START --ei max 3 --ez uninstall false
 *     adb shell am broadcast -a com.opautocloud.claim.START --ez dry true   # 只打印页面结构
 *     adb shell am broadcast -a com.opautocloud.claim.STOP
 *  状态通过 com.opautocloud.claim.STATUS 回执给 GUI。
 */
object State {
    const val TARGET_PKG = "com.heytap.cloud"
    const val MODULE_PKG = "com.opautocloud.claim"
    const val ACT_START = "com.opautocloud.claim.START"
    const val ACT_STOP = "com.opautocloud.claim.STOP"
    const val ACT_STATUS = "com.opautocloud.claim.STATUS"

    @Volatile var running = false
    @Volatile var maxTasks = 3
    @Volatile var dwellSec = 60
    @Volatile var autoUninstall = false
    @Volatile var dryRun = false

    val main = Handler(Looper.getMainLooper())
    val injected: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    /** 把状态回执广播给控制面板（同 APK 的 ui.MainActivity）。 */
    fun publish(
        ctx: Context,
        phase: String? = null,
        claimed: Int = -1,
        fragments: Int = -1,
        line: String? = null,
    ) {
        try {
            val i = Intent(ACT_STATUS).setPackage(MODULE_PKG)
            i.putExtra("running", running)
            phase?.let { i.putExtra("phase", it) }
            if (claimed >= 0) i.putExtra("claimed", claimed)
            if (fragments >= 0) i.putExtra("fragments", fragments)
            line?.let { i.putExtra("line", it) }
            ctx.sendBroadcast(i)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != State.TARGET_PKG) return

        // 1) 注册手动触发的广播（在目标 App 进程里动态注册，避免给模块自己加组件）
        //    attach() 是 Application 的 final 方法，必定被 ActivityThread 调用，比 onCreate 可靠
        XposedHelpers.findAndHookMethod(
            Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as Application
                    Trigger.register(app)
                }
            }
        )

        // 2) WebView 页面加载完成后注入自动化脚本
        XposedHelpers.findAndHookMethod(
            "android.webkit.WebViewClient", lpparam.classLoader,
            "onPageFinished", WebView::class.java, String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wv = param.args[0] as? WebView ?: return
                    val url = param.args[1] as? String ?: return
                    if (url.contains("/profit/")) Injector.attach(wv, url)
                }
            }
        )

        // 3) 兜底：SPA 首屏不一定走子类的 onPageFinished，loadUrl 后轮询一次
        XposedHelpers.findAndHookMethod(
            WebView::class.java, "loadUrl", String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wv = param.thisObject as WebView
                    State.main.postDelayed({ tryAttach(wv) }, 1500)
                    State.main.postDelayed({ tryAttach(wv) }, 5000)
                }
            }
        )

        XposedBridge.log("[AutoCloud] loaded in " + lpparam.packageName)
    }

    private fun tryAttach(wv: WebView) {
        try {
            val url = wv.url ?: return
            if (url.contains("/profit/")) Injector.attach(wv, url)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}

object Injector {
    fun attach(wv: WebView, url: String) {
        State.main.post {
            try {
                if (!State.injected.contains(wv)) {
                    // JS 接口加一次即可；页面重载后 evaluateJavascript 需要重新执行
                    wv.addJavascriptInterface(Bridge(wv), "autocloud")
                    State.injected.add(wv)
                }
                wv.evaluateJavascript(Script.BODY, null)
                XposedBridge.log("[AutoCloud] attached $url")
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }
}

object Trigger {
    private var registered = false

    fun register(app: Application) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(State.ACT_START)
            addAction(State.ACT_STOP)
        }
        // RECEIVER_EXPORTED: 允许 adb/shell(uid 2000) 发送的广播进来
        val flags = if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0
        app.registerReceiver(Receiver, filter, flags)
        XposedBridge.log("[AutoCloud] trigger receiver registered")
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                State.ACT_START -> {
                    State.maxTasks = intent.getIntExtra("max", 3)
                    State.dwellSec = intent.getIntExtra("dwell", 60)
                    State.autoUninstall = intent.getBooleanExtra("uninstall", false)
                    State.dryRun = intent.getBooleanExtra("dry", false)
                    State.running = true
                    XposedBridge.log(
                        "[AutoCloud] START max=${State.maxTasks} dwell=${State.dwellSec}" +
                            " uninstall=${State.autoUninstall} dry=${State.dryRun}"
                    )
                    State.publish(
                        ctx,
                        phase = if (State.dryRun) "演示模式" else "运行中",
                        line = "收到 START（max=" + State.maxTasks +
                            " dwell=" + State.dwellSec + " uninstall=" + State.autoUninstall + "）",
                    )
                }
                State.ACT_STOP -> {
                    State.running = false
                    XposedBridge.log("[AutoCloud] STOP")
                    State.publish(ctx, phase = "已停止", line = "收到 STOP")
                }
            }
        }
    }
}
