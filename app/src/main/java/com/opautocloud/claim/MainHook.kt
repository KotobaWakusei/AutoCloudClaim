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

    // 11.3.5 TaskWall SDK 的 H5 结果回执：code=13097 为成功路径。
    const val TASKWALL_SUCCESS_CODE = 13097
    @Volatile var taskResultVersion = 0L
    @Volatile var taskResultCode = Int.MIN_VALUE
    @Volatile var taskResultMessage = ""
    @Volatile var taskResultSkuId = ""
    @Volatile var taskResultTraceId = ""

    // TaskWall SDK openAppCountTime 的真实计时状态（11.3.5）。
    @Volatile var sdkTrackVersion = 0L
    @Volatile var sdkTrackPackage = ""
    @Volatile var sdkTrackRequiredSec = -1
    @Volatile var sdkTrackDurationSec = -1L
    @Volatile var sdkTrackTimeLeftSec = -1L

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

        // 2) 直接接入 11.3.5 内置 TaskWall SDK 的结果回执。
        //    H5 完成任务后，SDK 会构造 CloudTaskWallH5Result -> Bundle(code,msg,skuId,traceID)。
        installTaskWallResultHook(lpparam.classLoader)
        installTaskWallTrackingHooks(lpparam.classLoader)

        // 3) WebView 页面加载完成后注入自动化脚本
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

        // 4) 兜底：SPA 首屏不一定走子类的 onPageFinished，loadUrl 后轮询一次
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

    private fun installTaskWallResultHook(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.heytap.cloud.taskwall.api.CloudTaskWallH5Result",
                classLoader,
                "e",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bundle = param.result as? android.os.Bundle ?: return
                        val code = bundle.getInt("code", Int.MIN_VALUE)
                        val msg = bundle.getString("msg").orEmpty()
                        val skuId = bundle.getString("skuId").orEmpty()
                        val traceId = bundle.getString("traceID").orEmpty()

                        State.taskResultCode = code
                        State.taskResultMessage = msg
                        State.taskResultSkuId = skuId
                        State.taskResultTraceId = traceId
                        State.taskResultVersion = System.nanoTime()

                        XposedBridge.log(
                            "[AutoCloud] TaskWall result code=$code skuId=$skuId " +
                                "traceId=$traceId msg=" + msg.take(120)
                        )
                    }
                },
            )
        }.onFailure {
            // 兼容未来移除/改名的 TaskWall 类：DOM 自动化仍然可以运行。
            XposedBridge.log("[AutoCloud] TaskWall result hook unavailable: " + it)
        }
    }

    private fun installTaskWallTrackingHooks(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.oplus.pay.opensdk.taskwall.jsapi.PayOpenAppCountTimeExecute",
                classLoader,
                "openAppAndStartTracking",
                android.app.Activity::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                "eb0.a",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        State.sdkTrackPackage = param.args[1] as? String ?: ""
                        State.sdkTrackRequiredSec = (param.args[2] as? Int) ?: -1
                        State.sdkTrackVersion = System.nanoTime()
                        XposedBridge.log(
                            "[AutoCloud] SDK tracking package=" + State.sdkTrackPackage +
                                " required=" + State.sdkTrackRequiredSec + "s"
                        )
                    }
                },
            )
        }.onFailure {
            XposedBridge.log("[AutoCloud] SDK openAppCountTime hook unavailable: " + it)
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.oplus.pay.opensdk.taskwall.jsapi.PayOpenAppCountTimeExecute" + "$" + "a",
                classLoader,
                "a",
                org.json.JSONObject::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.args[0] as? org.json.JSONObject ?: return
                        val packageName = result.optString("packageName")
                        val duration = result.optLong("duration", -1L)
                        val timeLeft = result.optLong("timeLeft", -1L)

                        State.sdkTrackPackage = packageName
                        State.sdkTrackDurationSec = duration
                        State.sdkTrackTimeLeftSec = timeLeft
                        State.sdkTrackVersion = System.nanoTime()

                        XposedBridge.log(
                            "[AutoCloud] SDK tracking result package=" + packageName +
                                " duration=" + duration + "s timeLeft=" + timeLeft + "s"
                        )
                    }
                },
            )
        }.onFailure {
            XposedBridge.log("[AutoCloud] SDK tracking result hook unavailable: " + it)
        }
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
    private const val MAX_PROBE_ATTEMPTS = 6
    private val probeDelaysMs = longArrayOf(0L, 200L, 500L, 1000L, 2000L, 4000L)
    private val attachedUrls = WeakHashMap<WebView, String>()

    fun attach(wv: WebView, url: String) {
        State.main.post {
            try {
                val current = wv.url ?: url
                val previous = attachedUrls[wv]
                if (previous == current) {
                    probeAndInject(wv, current, 0)
                    return@post
                }
                wv.addJavascriptInterface(Bridge(wv), "autocloud")
                attachedUrls[wv] = current
                State.injected.add(wv)
                XposedBridge.log("[AutoCloud] bridge installed url=$current")
                probeAndInject(wv, current, 0)
            } catch (t: Throwable) {
                XposedBridge.log("[AutoCloud] attach failed url=$url: " + t.stackTraceToString())
            }
        }
    }

    private fun probeAndInject(wv: WebView, url: String, attempt: Int) {
        val delay = probeDelaysMs[attempt.coerceIn(0, probeDelaysMs.lastIndex)]
        State.main.postDelayed({
            try {
                if (wv.url?.contains("/profit/") != true) {
                    XposedBridge.log("[AutoCloud] probe skipped: url changed to ${wv.url}")
                    return@postDelayed
                }

                val probe = """
                    (function () {
                        try {
                            return JSON.stringify({
                                bridge: typeof window.autocloud,
                                ac: typeof window.__AC,
                                readyState: document.readyState,
                                href: location.href
                            });
                        } catch (e) {
                            return JSON.stringify({
                                bridge: "probe-error",
                                error: String(e && (e.stack || e.message || e))
                            });
                        }
                    })()
                """.trimIndent()

                wv.evaluateJavascript(probe) { raw ->
                    val result = raw ?: "null"
                    XposedBridge.log("[AutoCloud][JSProbe] attempt=$attempt result=$result")

                    if (raw == null || raw == "null" ||
                        (!raw.contains("\"bridge\":\"object\"") &&
                         !raw.contains("\"bridge\": \"object\""))) {
                        if (attempt + 1 < MAX_PROBE_ATTEMPTS) {
                            XposedBridge.log(
                                "[AutoCloud][JSProbe] bridge not ready; retry " +
                                    "${attempt + 1}/${MAX_PROBE_ATTEMPTS - 1}"
                            )
                            probeAndInject(wv, url, attempt + 1)
                        } else {
                            XposedBridge.log(
                                "[AutoCloud][JSProbe] FAILED bridge unavailable after " +
                                    "$MAX_PROBE_ATTEMPTS attempts url=$url"
                            )
                        }
                        return@evaluateJavascript
                    }

                    evaluateScript(wv, url)
                }
            } catch (t: Throwable) {
                XposedBridge.log(
                    "[AutoCloud][JSProbe] evaluate failed attempt=$attempt: " +
                        t.stackTraceToString()
                )
                if (attempt + 1 < MAX_PROBE_ATTEMPTS) {
                    probeAndInject(wv, url, attempt + 1)
                }
            }
        }, delay)
    }

    private fun evaluateScript(wv: WebView, url: String) {
        val wrapped = """
            (function () {
                try {
                    window.addEventListener("error", function (e) {
                        try {
                            if (window.autocloud) {
                                window.autocloud.log(
                                    "window.error: " + String(e.message || e.error || e)
                                );
                            }
                        } catch (_) {}
                    });
                    window.addEventListener("unhandledrejection", function (e) {
                        try {
                            if (window.autocloud) {
                                window.autocloud.log(
                                    "unhandledrejection: " + String(e.reason || e)
                                );
                            }
                        } catch (_) {}
                    });
                    __SCRIPT_BODY__
                    return "injected";
                } catch (e) {
                    try {
                        window.autocloud.log(
                            "INJECT EXCEPTION: " + String(e && (e.stack || e.message || e))
                        );
                    } catch (_) {}
                    return "inject-error:" + String(e && (e.message || e));
                }
            })()
        """.trimIndent().replace("__SCRIPT_BODY__", Script.BODY)

        try {
            wv.evaluateJavascript(wrapped) { result ->
                XposedBridge.log(
                    "[AutoCloud][JSProbe] script result=${result ?: "null"} url=$url"
                )
                XposedBridge.log("[AutoCloud] attached $url")
            }
        } catch (t: Throwable) {
            XposedBridge.log(
                "[AutoCloud] script evaluation failed url=$url: " + t.stackTraceToString()
            )
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
