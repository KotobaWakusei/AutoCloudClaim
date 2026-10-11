package com.opautocloud.claim

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.UUID

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
    const val CONTROL_PERMISSION = "com.opautocloud.claim.permission.CONTROL"

    @Volatile var sessionToken = ""

    @Volatile var running = false
    @Volatile var maxTasks = 3
    @Volatile var dwellSec = 60
    @Volatile var autoUninstall = false
    @Volatile var dryRun = false

    /**
     * 11.3.5 TaskWall SDK 的 H5 结果回执。
     *
     * 成功码经反编译核对（云服务 11.3.5, TaskWallResultInfoTransferInterceptor → jr/d.java）：
     *   if (cloudTaskWallH5ResultD.f14358b == 200) { aVar.b(); } else { aVar.fail(...); }
     *   this.f28194a.send(cloudTaskWallH5ResultD.f14358b, bundleE);
     * 即 `Bundle.getInt("code") == 200` 才是成功路径。此前写成 13097 是误匹配 Material
     * 资源 ID（R.attr 值域），SDK 回执这条确认通道永远不触发。
     *
     * 重要限制：该拦截器（jr/d）只在 `z11 == true` 时注册（ir/e.java:321），
     * 而 profit 页的 `startCoinTaskWallPage` JS API 走的是 `z11 == false`
     * （StartCoinTaskWallPage.java:60 → TaskWallProvider.f(..., false)），
     * 因此这条通道在「点领取」时通常不会触发——领取结果是 H5 直接发 HTTP 到服务端，
     * 不经过原生桥。领取确认仍以碎片数变化 + 领取按钮消失这两条 DOM 证据为准，
     * 本 hook 作为 `CloudPermissionRouteActivity` 等 `z11=true` 路径的补充。
     */
    const val TASKWALL_SUCCESS_CODE = 200
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

    /**
     * 福利中心 H5 的 URL 判定。
     *
     * 路径 `/profit/` 由服务端下发，APK 里没有硬编码（反编译核对无 `profit` 字面量），
     * 这里按观测到的 `…/profit/index.html` 匹配。SPA 子路由可能是 `…/profit`（无尾斜杠）
     * 或 `…/profit/detail`，因此只要求路径里出现 `/profit` 这一段即可。
     */
    fun isProfitUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val i = url.indexOf("/profit")
        if (i < 0) return false
        val end = i + "/profit".length
        // `/profit` 后面要么结束，要么是 `/`、`?`、`#`，避免误匹配 `/profits` 之类
        return end >= url.length || url[end] == '/' || url[end] == '?' || url[end] == '#'
    }

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
            i.putExtra("session", sessionToken)
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

/**
 * 自动导航到福利中心。
 *
 * 反编译核对（云服务 11.3.5）：
 *  - 福利中心 URL 由 wo.c.f40427f.q(WebUrlConstants$UrlType.WELFARE_CENTER) 构建，
 *    路径固定 "/cloud/welfarecenter.html"，域名来自服务端 cloudconfig.g()（webH5Url）。
 *  - 首页菜单点击最终调 yl.d.f41385g.r(activity, url)，经 zl.c → fo.a.g → WebProvider
 *    启动 CloudWebBusinessActivity（extra_url）。CloudWebBusinessActivity 虽 exported=false，
 *    但 hook 跑在 com.heytap.cloud 同进程同 UID，可直接调用。
 *  - 模块运行在宿主进程内，因此可在 START 后自动触发这条导航，用户无需手动进入福利中心。
 *
 * 触发时机：收到 START 且当前不在福利中心页时，等宿主 Activity 前台化后自动跳转。
 */
object AutoNav {
    private const val MAX_ATTEMPTS = 12
    private const val RETRY_DELAY_MS = 2_000L

    @Volatile private var pending = false
    @Volatile private var attempts = 0
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var currentActivity: WeakReference<Activity>? = null

    fun bindClassLoader(cl: ClassLoader) {
        classLoader = cl
    }

    fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        if (pending) tryNavigate()
    }

    fun requestNavigate() {
        pending = true
        attempts = 0
        State.main.postDelayed({ tryNavigate() }, 1_000L)
    }

    fun cancel() {
        pending = false
        attempts = 0
    }

    private fun tryNavigate() {
        if (!pending || !State.running) return

        val onProfit = runCatching {
            State.injected.any { State.isProfitUrl(it.url) }
        }.getOrDefault(false)
        if (onProfit) {
            pending = false
            XposedBridge.log("[AutoNav] already on welfare center page, skip navigation")
            return
        }

        if (attempts >= MAX_ATTEMPTS) {
            pending = false
            XposedBridge.log("[AutoNav] gave up after $MAX_ATTEMPTS attempts")
            State.publish(
                currentActivity?.get() ?: return,
                phase = "自动跳转福利中心失败，请手动进入",
                line = "AutoNav: 已达最大重试次数",
            )
            return
        }
        attempts++

        val activity = currentActivity?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            State.main.postDelayed({ tryNavigate() }, RETRY_DELAY_MS)
            return
        }

        val cl = classLoader
        if (cl == null) {
            State.main.postDelayed({ tryNavigate() }, RETRY_DELAY_MS)
            return
        }

        val url = runCatching { welfareCenterUrl(cl) }.getOrNull()
        if (url.isNullOrBlank()) {
            XposedBridge.log("[AutoNav] welfare center URL not ready (attempt $attempts), retry")
            State.publish(activity, phase = "等待福利中心配置加载…")
            State.main.postDelayed({ tryNavigate() }, RETRY_DELAY_MS)
            return
        }

        val ok = runCatching { openUrl(cl, activity, url) }.getOrElse {
            XposedBridge.log("[AutoNav] open failed: " + it.stackTraceToString())
            false
        }
        if (ok) {
            pending = false
            XposedBridge.log("[AutoNav] navigation triggered url=$url")
            State.publish(
                activity,
                phase = "正在自动进入福利中心…",
                line = "AutoNav: 已触发跳转",
            )
        } else {
            State.main.postDelayed({ tryNavigate() }, RETRY_DELAY_MS)
        }
    }

    /** wo.c.f40427f.q(WebUrlConstants$UrlType.WELFARE_CENTER) */
    private fun welfareCenterUrl(cl: ClassLoader): String? {
        val woC = XposedHelpers.findClass("wo.c", cl)
        val instance = XposedHelpers.getStaticObjectField(woC, "f40427f")
        val urlTypeCl = XposedHelpers.findClass(
            "com.heytap.cloud.server.config.module.url.WebUrlConstants\$UrlType", cl
        )
        val welfare = XposedHelpers.getStaticObjectField(urlTypeCl, "WELFARE_CENTER")
        return XposedHelpers.callMethod(instance, "q", welfare) as? String
    }

    /** yl.d.f41385g.r(activity, url) */
    private fun openUrl(cl: ClassLoader, activity: Activity, url: String): Boolean {
        val ylD = XposedHelpers.findClass("yl.d", cl)
        val manager = XposedHelpers.getStaticObjectField(ylD, "f41385g")
        XposedHelpers.callMethod(manager, "r", activity, url)
        return true
    }
}

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != State.TARGET_PKG) return

        AutoNav.bindClassLoader(lpparam.classLoader)

        // 1) 注册手动触发的广播（在目标 App 进程里动态注册，避免给模块自己加组件）
        //    attach() 是 Application 的 final 方法，必定被 ActivityThread 调用，比 onCreate 可靠
        XposedHelpers.findAndHookMethod(
            Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as Application
                    Trigger.register(app)
                    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(activity: Activity) {
                            AutoNav.onActivityResumed(activity)
                        }
                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                        override fun onActivityStarted(activity: Activity) {}
                        override fun onActivityPaused(activity: Activity) {}
                        override fun onActivityStopped(activity: Activity) {}
                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                        override fun onActivityDestroyed(activity: Activity) {}
                    })
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
                    if (State.isProfitUrl(url)) {
                        AutoNav.cancel()
                        Injector.attach(wv, url)
                    }
                }
            }
        )

        // 4) 兜底：SPA 首屏不一定走子类的 onPageFinished，loadUrl 后轮询一次
        XposedHelpers.findAndHookMethod(
            WebView::class.java, "loadUrl", String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val wv = param.thisObject as WebView
                    val url = param.args[0] as? String ?: return
                    // JavaScript interfaces added after onPageFinished are not exposed to the
                    // current document until a subsequent navigation. Install before loadUrl.
                    Injector.prepareForNavigation(wv, url)
                }

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
                        // Clear the previous task's result before starting a new timing session.
                        // Otherwise the JS bridge could mistake stale duration/timeLeft values
                        // for a fresh completion when only the start callback has fired.
                        State.sdkTrackDurationSec = -1L
                        State.sdkTrackTimeLeftSec = -1L
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
            if (State.isProfitUrl(url)) Injector.attach(wv, url)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}

object Injector {
    private const val MAX_PROBE_ATTEMPTS = 6
    private val probeDelaysMs = longArrayOf(0L, 200L, 500L, 1000L, 2000L, 4000L)
    private val attachedUrls = WeakHashMap<WebView, String>()
    private val preparedWebViews: MutableSet<WebView> =
        Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    private val bridges = WeakHashMap<WebView, Bridge>()

    /**
     * 每个 WebView 只保留一个 Bridge 实例。
     *
     * `addJavascriptInterface` 传同一个对象是幂等的；若每次 loadUrl 都 new 一个，
     * 旧实例会带着 WebView 引用被丢弃，频繁导航时白白堆积。
     */
    private fun bridgeFor(wv: WebView): Bridge =
        bridges.getOrPut(wv) { Bridge(wv) }

    /**
     * Must run before the profit document starts loading. Android WebView only exposes a newly
     * added JavascriptInterface to a document after navigation; attaching it in onPageFinished
     * leaves window.autocloud undefined for the already-loaded page.
     */
    fun prepareForNavigation(wv: WebView, url: String) {
        if (!State.isProfitUrl(url)) return
        try {
            wv.addJavascriptInterface(bridgeFor(wv), "autocloud")
            preparedWebViews.add(wv)
            XposedBridge.log("[AutoCloud] bridge prepared before navigation url=$url")
        } catch (t: Throwable) {
            XposedBridge.log("[AutoCloud] pre-navigation bridge install failed: " + t)
        }
    }

    fun attach(wv: WebView, url: String) {
        State.main.post {
            try {
                val current = wv.url ?: url
                val previous = attachedUrls[wv]
                if (previous == current) {
                    probeAndInject(wv, current, 0)
                    return@post
                }
                // Do not replace a pre-navigation bridge after the document has loaded:
                // that replacement would itself wait for another navigation to become visible.
                if (!preparedWebViews.contains(wv)) {
                    wv.addJavascriptInterface(bridgeFor(wv), "autocloud")
                    XposedBridge.log("[AutoCloud] bridge installed as post-load fallback url=$current")
                } else {
                    XposedBridge.log("[AutoCloud] using pre-navigation bridge url=$current")
                }
                attachedUrls[wv] = current
                State.injected.add(wv)
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
                if (!State.isProfitUrl(wv.url)) {
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

                    // evaluateJavascript 回调的 raw 是 JSON 编码后的字符串：
                    // JS 返回 "{\"bridge\":\"object\"}" 时，raw 实际内容是
                    // "\"{\\\"bridge\\\":\\\"object\\\"}\""，
                    // 因此必须匹配带反斜杠的转义形式。
                    val hasBridge =
                        raw.contains("\\\"bridge\\\":\\\"object\\\"") ||
                        raw.contains("\\\"bridge\\\": \\\"object\\\"") ||
                        raw.contains("\"bridge\":\"object\"") ||
                        raw.contains("\"bridge\": \"object\"")
                    if (raw == null || raw == "null" || !hasBridge) {
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
                if (result?.contains("injected") == true && result?.contains("inject-error") != true) {
                    State.publish(
                        wv.context.applicationContext,
                        phase = if (State.running) "福利中心页面已就绪" else "福利中心页面已检测到",
                        line = "福利中心 WebView 已挂载并完成脚本注入",
                    )
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(
                "[AutoCloud] script evaluation failed url=$url: " + t.stackTraceToString()
            )
        }
    }
}

object Trigger {
    // 两个接收器各自独立登记。原先包在同一个 runCatching 里，第一个成功、第二个抛异常时
    // 整体不会被标记为已注册，下次 attach 会把第一个注册两次，START 就被执行两遍。
    private var controlRegistered = false
    private var shellRegistered = false

    fun register(app: Application) {
        fun filter() = IntentFilter().apply {
            addAction(State.ACT_START)
            addAction(State.ACT_STOP)
        }

        // The GUI sender must hold our signature permission. Keep a separate DUMP-protected
        // receiver for adb shell/root debugging, so ordinary apps cannot start/stop automation.
        val sdk = android.os.Build.VERSION.SDK_INT

        if (!controlRegistered) {
            runCatching {
                registerProtectedReceiver(app, Receiver, filter(), State.CONTROL_PERMISSION, sdk)
            }.onSuccess {
                controlRegistered = true
                XposedBridge.log("[AutoCloud] control trigger receiver registered")
            }.onFailure {
                XposedBridge.log("[AutoCloud] control receiver registration failed: " + it)
            }
        }

        if (!shellRegistered) {
            runCatching {
                registerProtectedReceiver(
                    app, ShellReceiver, filter(), android.Manifest.permission.DUMP, sdk
                )
            }.onSuccess {
                shellRegistered = true
                XposedBridge.log("[AutoCloud] shell trigger receiver registered")
            }.onFailure {
                XposedBridge.log("[AutoCloud] shell receiver registration failed: " + it)
            }
        }
    }

    private fun registerProtectedReceiver(
        app: Application,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        permission: String,
        sdk: Int,
    ) {
        if (sdk >= 33) {
            app.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter, permission, null)
        }
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handle(ctx, intent, allowShellSession = false)
        }
    }

    private object ShellReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            handle(ctx, intent, allowShellSession = true)
        }
    }

    private fun handle(ctx: Context, intent: Intent, allowShellSession: Boolean) {
        val suppliedSession = intent.getStringExtra("session").orEmpty()
        when (intent.action) {
            State.ACT_START -> {
                val session = when {
                    suppliedSession.isNotBlank() -> suppliedSession
                    allowShellSession -> "shell:" + UUID.randomUUID().toString()
                    else -> {
                        XposedBridge.log("[AutoCloud] rejected START without session token")
                        return
                    }
                }
                State.sessionToken = session
                State.maxTasks = intent.getIntExtra("max", 3).coerceIn(1, 10)
                State.dwellSec = intent.getIntExtra("dwell", 60).coerceIn(15, 300)
                State.autoUninstall = intent.getBooleanExtra("uninstall", false)
                State.dryRun = intent.getBooleanExtra("dry", false)
                State.running = true
                XposedBridge.log(
                    "[AutoCloud] START max=${State.maxTasks} dwell=${State.dwellSec}" +
                        " uninstall=${State.autoUninstall} dry=${State.dryRun}"
                )
                val pageReady = runCatching {
                    State.injected.any { webView -> State.isProfitUrl(webView.url) }
                }.getOrDefault(false)
                val phase = when {
                    State.dryRun && pageReady -> "演示模式 · 福利中心页面已检测到"
                    State.dryRun -> "演示模式 · 等待福利中心页面"
                    pageReady -> "福利中心页面已检测到"
                    else -> "正在自动进入福利中心…"
                }
                if (!pageReady) {
                    AutoNav.requestNavigate()
                }
                State.publish(
                    ctx,
                    phase = phase,
                    line = "收到 START（max=" + State.maxTasks +
                        " dwell=" + State.dwellSec + " uninstall=" + State.autoUninstall + "）",
                )
            }
            State.ACT_STOP -> {
                val matchesUiSession =
                    suppliedSession.isNotBlank() && suppliedSession == State.sessionToken
                val stopsShellSession =
                    allowShellSession && suppliedSession.isBlank() &&
                        State.sessionToken.startsWith("shell:")
                if (!matchesUiSession && !stopsShellSession) {
                    XposedBridge.log("[AutoCloud] rejected STOP with stale/missing session")
                    return
                }
                State.running = false
                AutoNav.cancel()
                XposedBridge.log("[AutoCloud] STOP")
                State.publish(ctx, phase = "已停止", line = "收到 STOP")
                State.sessionToken = ""
            }
        }
    }
}
