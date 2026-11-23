package com.opautocloud.claim

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
    const val ACT_DIAG = "com.opautocloud.claim.DIAG"
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

    /** 宿主 Application（模块跑在宿主进程里）：读宿主 APK、拿 applicationInfo 用。 */
    @Volatile var hostApp: Application? = null

    /**
     * 多 WebView 领导选举：福利中心 H5 会在多个 WebView/Activity 里同时加载 profit 页，
     * 每个文档各自注入脚本会重复点同一任务（2026-10-13 真机复现 click OPEN ×2）。
     * 同一时刻只允许「最后 attach 且仍在 profit 的」那个实例扫描，其余 standby。
     */
    val leaderLock = Any()
    @Volatile var activeWebView: WeakReference<WebView>? = null

    /**
     * 福利中心 H5 的 URL 判定。
     *
     * 观测到的任务页是 `…/profit/index.html`；SPA 子路由可能是 `…/profit`（无尾斜杠）
     * 或 `…/profit/detail`。按「路径段」精确匹配，防两种误判：
     *  - 入口 URL 可能带 query（如 `?from=/profit`），子串匹配会把福利中心落地页
     *    误认成任务页 → 自动导航被跳过、脚本被注入到错误页面后空转到底；
     *  - `/profits`、`/xprofit/` 之类相邻段。
     * fragment（`…#/profit`）是 SPA 子路由的合法写法，单独按段判定。
     */
    fun isProfitUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val cut = url.indexOfAny(charArrayOf('?', '#'))
        val path = if (cut >= 0) url.substring(0, cut) else url
        val hash = url.indexOf('#')
        val fragmentPath = if (hash >= 0) {
            "/" + url.substring(hash + 1).substringBefore('?')
        } else null
        return hasProfitSegment(path) || (fragmentPath != null && hasProfitSegment(fragmentPath))
    }

    /** 路径里是否存在恰好为 profit 的路径段（含 profit.html 变体）。 */
    private fun hasProfitSegment(path: String): Boolean =
        path.split('/').any { it == "profit" || it == "profit.html" }

    /**
     * 福利中心二级落地页（…/cloud/welfarecenter.html）。
     * 2026-10-13 真机：welfarecenter 打开后是「做任务免费领空间」落地页，
     * 不会总能自动跳转到 profit 任务列表，需要自动点击入口卡片。
     */
    fun isWelfareUrl(url: String?): Boolean =
        !url.isNullOrBlank() && url.contains("/welfarecenter")

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
 *  - 福利中心 URL 由 wo.c.f.q (旧 build 名 f40427f)(WebUrlConstants$UrlType.WELFARE_CENTER) 构建，
 *    路径固定 "/cloud/welfarecenter.html"，域名来自服务端 cloudconfig.g()（webH5Url）。
 *  - 首页菜单点击最终调 yl.d.g.r (旧 build 名 f41385g)(activity, url)，经 zl.c → fo.a.g → WebProvider
 *    启动 CloudWebBusinessActivity（extra_url）。CloudWebBusinessActivity 虽 exported=false，
 *    但 hook 跑在 com.heytap.cloud 同进程同 UID，可直接调用。
 *  - 模块运行在宿主进程内，因此可在 START 后自动触发这条导航，用户无需手动进入福利中心。
 *
 * 触发时机：收到 START 且当前不在福利中心页时，等宿主 Activity 前台化后自动跳转。
 */
object AutoNav {
    private const val MAX_ATTEMPTS = 12

    /**
     * 宿主 API 的按签名发现。
     *
     * 宿主的混淆名每版都变（11.3.5 的 `wo.c` / `yl.d` 在 11.4.5 里已完全消失），
     * 硬编码等于每个版本都要改一次代码。这里改成**按方法签名找**：
     *  - 福利中心 URL 构造函数：接收 `WebUrlConstants$UrlType` 枚举、返回 String；
     *  - 打开网页的方法：接收 (Activity, String)。
     *
     * 这些特征跨版本稳定。扫描宿主自己的 APK（模块跑在宿主进程里，读得到），
     * 结果缓存进模块自己的 SharedPreferences，每个版本只扫一次。
     */
    object HostApi {
        private const val URL_TYPE_CLASS =
            "com.heytap.cloud.server.config.module.url.WebUrlConstants\$UrlType"
        private const val URL_TYPE_CONST = "WELFARE_CENTER"

        /** 服务端运营位配置里「福利中心入口」的 key（服务端约定的常量，跨版本稳定）。 */
        private const val CONFIG_KEY = "keHuDuanFuLiZhongXinRuKouText"

        private var urlBuilderCache: DexScan.MethodRef? = null
        private var launcherCache: DexScan.MethodRef? = null
        private var configMapCache: DexScan.MethodRef? = null
        private var scanned = false

        private fun apkPath(): String? = State.hostApp?.applicationInfo?.sourceDir

        /** 扫一次宿主 APK，找出关键方法并缓存。 */
        private fun ensureScanned(cl: ClassLoader) {
            if (scanned) return
            scanned = true
            val apk = DexScan.hostApkPath(apkPath()) ?: run {
                XposedBridge.log("[AutoNav] host apk path unavailable, signature discovery skipped")
                return
            }
            runCatching {
                urlBuilderCache = DexScan.findMethods(apk) { params, ret ->
                    ret == "java.lang.String" && params.size == 1 &&
                        params[0].endsWith("WebUrlConstants\$UrlType")
                }.firstOrNull { it.isStatic }
                    ?: DexScan.findMethods(apk) { params, ret ->
                        ret == "java.lang.String" && params.size == 1 &&
                            params[0].endsWith("WebUrlConstants\$UrlType")
                    }.firstOrNull()

                launcherCache = DexScan.findMethods(apk) { params, ret ->
                    params.size == 2 && params[0] == "android.app.Activity" &&
                        params[1] == "java.lang.String"
                }.firstOrNull { it.isStatic }
                    // isStatic 已由 DexScan 修复（读真实 access_flags），这里仍保留
                    // 无过滤兜底：静态匹配缺失时退回任意签名匹配，调用侧按
                    // isStatic 分支选择 callStaticMethod / callMethod。
                    ?: DexScan.findMethods(apk) { params, ret ->
                        params.size == 2 && params[0] == "android.app.Activity" &&
                            params[1] == "java.lang.String"
                    }.firstOrNull()

                // 运营位配置：反查引用了配置 key 的方法（返回 Map 的那个）
                configMapCache = DexScan.findMethodsReferencingString(apk, CONFIG_KEY)
                    .firstOrNull { it.ret == "java.util.Map" }

                XposedBridge.log(
                    "[AutoNav] signature discovery: urlBuilder=" +
                        (urlBuilderCache?.let { "${it.className}#${it.methodName}" } ?: "none") +
                        " launcher=" +
                        (launcherCache?.let { "${it.className}#${it.methodName}" } ?: "none") +
                        " configMap=" +
                        (configMapCache?.let { "${it.className}#${it.methodName}" } ?: "none")
                )
            }.onFailure {
                XposedBridge.log("[AutoNav] signature discovery failed: " + it)
            }
        }

        /** 单例实例：找静态字段里类型等于自身（或任意静态字段）的实例。 */
        private fun singletonOf(clazz: Class<*>): Any? {
            var fallback: Any? = null
            for (f in clazz.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                val v = runCatching {
                    f.isAccessible = true
                    f.get(null)
                }.getOrNull() ?: continue
                if (f.type == clazz) return v
                // 只有 clazz（或其子类）的实例才能充当方法接收者；其他类型的静态
                // 字段拿去 callMethod 必然 NoSuchMethodError。此前的盲选 fallback
                // 正是这种，宁可返回 null 让调用侧走失败分支。
                if (fallback == null && clazz.isInstance(v)) fallback = v
            }
            return fallback
        }

        /** 用发现的 URL 构造函数拼福利中心地址。 */
        fun buildUrl(cl: ClassLoader): String? {
            ensureScanned(cl)
            val ref = urlBuilderCache ?: return null
            return runCatching {
                val urlType = XposedHelpers.findClass(URL_TYPE_CLASS, cl)
                val welfare = XposedHelpers.getStaticObjectField(urlType, URL_TYPE_CONST)
                val clazz = XposedHelpers.findClass(ref.className, cl)
                val url = if (ref.isStatic) {
                    XposedHelpers.callStaticMethod(clazz, ref.methodName, welfare)
                } else {
                    val inst = singletonOf(clazz) ?: return null
                    XposedHelpers.callMethod(inst, ref.methodName, welfare)
                } as? String
                url?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /**
         * 从服务端运营位配置里取福利中心入口 URL。
         *
         * 这是 11.3.5 真机验证过「真正能出地址」的那条路：本地兜底构造器依赖
         * cloudconfig 的域名，未加载时恒为空。配置 key 是服务端约定的常量，
         * 跨版本稳定，所以按「谁引用了这个 key」反查它的读取方。
         */
        fun configUrl(cl: ClassLoader): String? {
            ensureScanned(cl)
            val ref = configMapCache ?: return null
            return runCatching {
                val clazz = XposedHelpers.findClass(ref.className, cl)
                // 静态方法必须走 callStaticMethod：以 java.lang.Class 对象为接收者
                // 调 callMethod 时，Xposed 只会在 Class 类自身里找方法，必然失败
                // （此前 isStatic 数据源错误掩盖了这条静态路径）。
                val target: Any = if (ref.isStatic) clazz else (singletonOf(clazz) ?: return null)
                // 形参：一个 String（运营位类型）时传 "10"，无参时不传
                val map = when {
                    ref.params.isEmpty() && ref.isStatic ->
                        XposedHelpers.callStaticMethod(clazz, ref.methodName)
                    ref.params.isEmpty() ->
                        XposedHelpers.callMethod(target, ref.methodName)
                    ref.params.size == 1 && ref.params[0] == "java.lang.String" && ref.isStatic ->
                        XposedHelpers.callStaticMethod(clazz, ref.methodName, "10")
                    ref.params.size == 1 && ref.params[0] == "java.lang.String" ->
                        XposedHelpers.callMethod(target, ref.methodName, "10")
                    else -> return null
                } as? Map<*, *> ?: return null
                val star = map[CONFIG_KEY] ?: return null
                val showData = XposedHelpers.callMethod(star, "getShowData")
                XposedHelpers.callMethod(showData, "getUrl") as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        /** 用发现的 (Activity, String) 方法打开网页。 */
        fun openUrl(cl: ClassLoader, activity: Activity, url: String): Boolean {
            ensureScanned(cl)
            val ref = launcherCache ?: return false
            return runCatching {
                val clazz = XposedHelpers.findClass(ref.className, cl)
                if (ref.isStatic) {
                    XposedHelpers.callStaticMethod(clazz, ref.methodName, activity, url)
                } else {
                    // 实例方法按实例调用：无条件 callStaticMethod 对实例方法必然抛错，
                    // 且被 runCatching 吞成静默失败（bug 审计 High-2）。
                    val inst = singletonOf(clazz) ?: return@runCatching false
                    XposedHelpers.callMethod(inst, ref.methodName, activity, url)
                }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * 版本无关的导航兜底。
     *
     * 宿主 11.3.5 的福利中心 URL 由混淆类（wo.c / yl.d）拼出来，这些名字每
     * 个 build 都会变（11.4.5 极可能已变），一失效就完全没法自动跳转。
     *
     * 这里换一条不依赖任何混淆名的路：
     *  1) 钩住宿主自己的 WebView.loadUrl —— 宿主每次加载页面我们都能看到 URL；
     *  2) 见到 /welfarecenter 或 /profit 的 URL 就学下来（内存 + 模块自己的
     *     SharedPreferences，跨进程/跨版本都还在）；
     *  3) 混淆链路失效时，用学到的 URL 丢给宿主自己的 WebView 去加载——
     *     WebView 是宿主进程的，Cookie、JS 桥、归因上下文全都一样。
     *
     * 代价：宿主刚更新、还没加载过任何网页时没有可学的 URL，只能提示用户手动进一次。
     */
    object NavCache {
        private const val PREFS = "ac_nav_cache"
        private const val KEY_WELFARE = "welfare_url"
        private const val KEY_PROFIT = "profit_url"

        @Volatile var welfareUrl: String? = null
        @Volatile var profitUrl: String? = null

        /** 宿主最近用过的 WebView（弱引用）：兜底跳转时用它来 loadUrl。 */
        @Volatile var lastWebView: WeakReference<WebView>? = null

        private var prefs: SharedPreferences? = null

        fun init(ctx: Context) {
            if (prefs != null) return
            runCatching {
                // 写在模块自己的包名下，不污染宿主 App 的数据目录
                val modCtx = ctx.createPackageContext(
                    "com.opautocloud.claim", Context.CONTEXT_IGNORE_SECURITY
                )
                val sp = modCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs = sp
                welfareUrl = sp.getString(KEY_WELFARE, null)
                profitUrl = sp.getString(KEY_PROFIT, null)
            }.onFailure {
                XposedBridge.log("[AutoNav] cache init failed: " + it)
            }
        }

        /** 记下宿主加载过的 WebView 与 URL。 */
        fun observe(wv: WebView?, url: String?) {
            if (wv != null) lastWebView = WeakReference(wv)
            if (url.isNullOrBlank()) return
            runCatching {
                when {
                    State.isProfitUrl(url) -> {
                        if (profitUrl != url) {
                            profitUrl = url
                            prefs?.edit()?.putString(KEY_PROFIT, url)?.apply()
                            XposedBridge.log("[AutoNav] learned profit url: " + url)
                        }
                    }
                    url.contains("/welfarecenter") -> {
                        if (welfareUrl != url) {
                            welfareUrl = url
                            prefs?.edit()?.putString(KEY_WELFARE, url)?.apply()
                            XposedBridge.log("[AutoNav] learned welfare url: " + url)
                        }
                    }
                }
            }
        }

        /** 用宿主自己的 WebView 打开一个已知 URL。成功返回 true。 */
        fun openInHostWebView(url: String): Boolean {
            val wv = lastWebView?.get() ?: return false
            return runCatching {
                State.main.post {
                    runCatching {
                        wv.loadUrl(url)
                        XposedBridge.log("[AutoNav] fallback loadUrl: " + url)
                    }.onFailure {
                        XposedBridge.log("[AutoNav] fallback loadUrl failed: " + it)
                    }
                }
                true
            }.getOrDefault(false)
        }
    }

    /** 退避间隔：前几次尝试得快，之后放宽——少打扰用户、给宿主云配置留出加载时间。 */
    private fun retryDelayMs(): Long = when (attempts) {
        in 1..2 -> 2_000L
        in 3..4 -> 4_000L
        in 5..6 -> 6_000L
        else -> 8_000L
    }

    @Volatile private var pending = false
    @Volatile private var attempts = 0
    @Volatile private var fallbackTries = 0
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var currentActivity: WeakReference<Activity>? = null

    fun bindClassLoader(cl: ClassLoader) {
        classLoader = cl
    }

    /** 最近一次前台化的宿主 Activity（供 Bridge 切回任务页用，排除启动器首页）。 */
    fun currentCloudActivity(): Activity? {
        val act = currentActivity?.get() ?: return null
        return if (act.javaClass.name.contains("Launcher")) null else act
    }

    fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        if (pending) tryNavigate()
    }

    fun requestNavigate() {
        pending = true
        attempts = 0
        fallbackTries = 0
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
            State.main.postDelayed({ tryNavigate() }, retryDelayMs())
            return
        }

        val cl = classLoader
        if (cl == null) {
            State.main.postDelayed({ tryNavigate() }, retryDelayMs())
            return
        }

        val url = runCatching { welfareCenterUrl(cl) }.getOrNull()
        if (url.isNullOrBlank()) {
            // 混淆链路失效（宿主换版本）→ 走版本无关的兜底：
            // 用学到的福利中心/任务页 URL，丢给宿主自己的 WebView 加载。
            // 不在这里清 pending：页面加载成功后 onPageFinished 会自动 cancel。
            val cached = NavCache.welfareUrl ?: NavCache.profitUrl
            if (!cached.isNullOrBlank() && fallbackTries < 3) {
                if (NavCache.openInHostWebView(cached)) {
                    fallbackTries++
                    XposedBridge.log(
                        "[AutoNav] using cached url (obfuscated path unavailable, try " +
                            "$fallbackTries): " + cached
                    )
                    State.publish(
                        activity, phase = "正在自动进入福利中心…",
                        line = "AutoNav: 用缓存地址跳转",
                    )
                    State.main.postDelayed({ tryNavigate() }, 3_000L)
                    return
                }
            }
            XposedBridge.log("[AutoNav] welfare center URL not ready (attempt $attempts), retry")
            State.publish(activity, phase = "等待福利中心配置加载…")
            State.main.postDelayed({ tryNavigate() }, retryDelayMs())
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
            State.main.postDelayed({ tryNavigate() }, retryDelayMs())
        }
    }

    /**
     * 福利中心 URL。
     *
     * 与首页菜单 HomePageMenuEntryViewModel.O() 相同的优先级：
     *  1) 服务端运营位 yl.d.g.h (旧 build 名 f41385g)("10")["keHuDuanFuLiZhongXinRuKouText"].showData.url
     *  2) 本地兜底 wo.c.f.q (旧 build 名 f40427f)(WELFARE_CENTER)（依赖 cloudconfig 域名，可能为空）
     *  2026-10-12 真机验证：只走兜底时 cloudconfig.g() 返回空域名，URL 恒为空。
     */
    /**
     * 静态单例字段查找（按候选名依次尝试）。
     * 2026-10-13 真机验证：设备安装的 11.3.5 build 与手头 jadx 源的混淆名不同——
     * yl.d 单例是 `g`（jadx 源写 f41385g）、wo.c 单例是 `f`（jadx 源写 f40427f），
     * 类名/方法名/枚举则完全一致。字段名会随 build 变化，故做多候选兜底。
     */
    private fun staticInstance(cl: ClassLoader, className: String, vararg names: String): Any? {
        val clazz = XposedHelpers.findClass(className, cl)
        var lastErr: Throwable? = null
        for (n in names) {
            val r = runCatching { XposedHelpers.getStaticObjectField(clazz, n) }
            r.getOrNull()?.let { return it }
            lastErr = r.exceptionOrNull()
        }
        throw lastErr ?: NoSuchFieldError("$className#" + names.joinToString(","))
    }

    private fun welfareCenterUrl(cl: ClassLoader): String? {
        runCatching {
            val manager = staticInstance(cl, "yl.d", "g", "f41385g")
            val map = XposedHelpers.callMethod(manager, "h", "10") as? Map<*, *>
            val star = map?.get("keHuDuanFuLiZhongXinRuKouText")
            if (star != null) {
                val showData = XposedHelpers.callMethod(star, "getShowData")
                val url = XposedHelpers.callMethod(showData, "getUrl") as? String
                if (!url.isNullOrBlank()) return url
            }
        }.onFailure {
            XposedBridge.log("[AutoNav] server url lookup failed: " + it)
        }
        // 硬编码兜底：11.3.5 的 wo.c.f.q(UrlType)
        runCatching { localWelfareUrl(cl) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        // 通用发现 ①：服务端运营位配置里的入口 URL（真机验证过真正能出地址的路）
        runCatching { HostApi.configUrl(cl) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            XposedBridge.log("[AutoNav] welfare url from config discovery: " + it)
            return it
        }
        // 通用发现 ②：按签名找 (UrlType) -> String 的方法，跨版本稳定
        runCatching { HostApi.buildUrl(cl) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            XposedBridge.log("[AutoNav] welfare url from signature discovery: " + it)
            return it
        }
        XposedBridge.log("[AutoCloud] local url lookup failed")
        return null
    }

    /** wo.c.f.q(WebUrlConstants$UrlType.WELFARE_CENTER) */
    private fun localWelfareUrl(cl: ClassLoader): String? {
        val instance = staticInstance(cl, "wo.c", "f", "f40427f")
        val urlTypeCl = XposedHelpers.findClass(
            "com.heytap.cloud.server.config.module.url.WebUrlConstants\$UrlType", cl
        )
        val welfare = XposedHelpers.getStaticObjectField(urlTypeCl, "WELFARE_CENTER")
        return XposedHelpers.callMethod(instance, "q", welfare) as? String
    }

    /** yl.d.g.r(activity, url) */
    private fun openUrl(cl: ClassLoader, activity: Activity, url: String): Boolean {
        // 硬编码候选（11.3.5）
        runCatching {
            val manager = staticInstance(cl, "yl.d", "g", "f41385g")
            XposedHelpers.callMethod(manager, "r", activity, url)
            return true
        }.onFailure {
            XposedBridge.log("[AutoNav] hardcoded open failed: " + it)
        }
        // 通用发现：按签名找 (Activity, String) -> void 的方法
        val ok = runCatching { HostApi.openUrl(cl, activity, url) }.getOrDefault(false)
        if (ok) XposedBridge.log("[AutoNav] opened via signature discovery")
        return ok
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
                    State.hostApp = app
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

        // 3) WebView 页面加载完成后注入自动化脚本（基类钩子）。
        //    注意：宿主若在子类里覆写 onPageFinished 且不回调 super，基类钩子收不到；
        //    下面的 setWebViewClient 钩子会动态给运行期子类补挂。
        XposedHelpers.findAndHookMethod(
            "android.webkit.WebViewClient", lpparam.classLoader,
            "onPageFinished", WebView::class.java, String::class.java,
            onPageFinishedHook()
        )

        // 3b) 动态挂钩 WebViewClient 子类：云服务自己的 client 覆写 onPageFinished 的概率很高
        //     （改标题/进度条），不回调 super 时 profit/be 页面在 goBack、reload 之类场景会漏注入。
        XposedHelpers.findAndHookMethod(
            WebView::class.java, "setWebViewClient",
            android.webkit.WebViewClient::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val client = param.args[0] as? android.webkit.WebViewClient ?: return
                    hookClientOnPageFinished(client.javaClass)
                }
            },
        )

        // 4) 兜底：SPA 首屏不一定走子类的 onPageFinished，loadUrl 后轮询一次。
        //    同时挂 loadUrl(String, Map) 重载——某些 web 框架带 referer 头加载，
        //    不挂钩的话进入页面的桥会缺位。
        XposedHelpers.findAndHookMethod(
            WebView::class.java, "loadUrl", String::class.java, loadUrlHook()
        )
        runCatching {
            XposedHelpers.findAndHookMethod(
                WebView::class.java, "loadUrl",
                String::class.java, java.util.Map::class.java, loadUrlHook()
            )
        }.onFailure {
            XposedBridge.log("[AutoCloud] loadUrl(Map) hook unavailable: " + it)
        }

        XposedBridge.log("[AutoCloud] loaded in " + lpparam.packageName)
    }

    /** 已经动态挂过钩的 WebViewClient 子类（弱集合，避免生命周期泄漏）。 */
    private val hookedClientClasses = Collections.newSetFromMap(
        WeakHashMap<Class<*>, Boolean>()
    )

    private fun onPageFinishedHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val wv = param.args[0] as? WebView ?: return
                val url = param.args[1] as? String ?: return
                // 页面加载完也算一次「学习」：前进/后退/重定向不一定经过 loadUrl
                AutoNav.NavCache.init(wv.context)
                AutoNav.NavCache.observe(wv, url)
                Injector.observe(wv, url)
                if (State.isProfitUrl(url)) {
                    AutoNav.cancel()
                    Injector.attach(wv, url)
                } else {
                    // 桥跨导航存活：离开 profit 页就摘桥，敏感能力不再对任务外页面开放
                    Injector.detachIfBridge(wv, url)
                    if (
                        State.isWelfareUrl(url) && State.running &&
                        !State.injected.any { State.isProfitUrl(it.url) }
                    ) {
                        XposedBridge.log("[AutoEntry] welfare landing detected url=$url")
                        Injector.clickWelfareEntry(wv)
                    }
                }
            }
        }
    }

    /**
     * 给运行期 WebViewClient 子类补挂 onPageFinished。
     * findAndHookMethod 只挂该类自己声明的方法；子类没覆写时基类钩子已覆盖，静默跳过即可。
     */
    private fun hookClientOnPageFinished(cls: Class<*>) {
        if (cls == android.webkit.WebViewClient::class.java) return
        if (!hookedClientClasses.add(cls)) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "onPageFinished",
                WebView::class.java, String::class.java,
                onPageFinishedHook(),
            )
            XposedBridge.log("[AutoCloud] hooked subclass onPageFinished " + cls.name)
        }.onFailure {
            // 未覆写的情况属正常：保留 add 的记录，避免每次 setWebViewClient 都刷失败日志
            XposedBridge.log(
                "[AutoCloud] subclass onPageFinished not declared (base hook covers): " +
                    cls.name
            )
        }
    }

    /** loadUrl(String[, Map]) 共用钩子：进页前装桥 + 导航后兜底轮询 attach。 */
    private fun loadUrlHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val wv = param.thisObject as WebView
                val url = param.args[0] as? String ?: return
                // 学习宿主加载过的地址 + 记住它的 WebView：
                // 混淆 API 换版本失效时，用这些学到的 URL 直接在宿主 WebView 里跳转。
                AutoNav.NavCache.init(wv.context)
                AutoNav.NavCache.observe(wv, url)
                Injector.observe(wv, url)
                // 目标不是 profit 页时在加载前摘桥：removeJavascriptInterface 对
                // 新文档立即生效，第三方页从源头上就看不到 window.autocloud。
                Injector.detachIfBridge(wv, url)
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

    /**
     * 按「参数形状」挂 hook，不写死方法签名。
     *
     * 11.4.5 实测：openAppAndStartTracking 的参数类型变了
     * （第三个混淆类型 `eb0.a` 换了），写死签名必然 NoSuchMethodError。
     * 方法名和参数*形状*（Activity + String + int + ...）跨版本稳定，
     * 这里反射取真实参数类型再挂，并对内嵌类一起处理。
     */
    private fun hookByShape(
        className: String,
        classLoader: ClassLoader,
        methodName: String?,
        matches: (List<Class<*>>) -> Boolean,
        hook: XC_MethodHook,
    ): Int {
        val clazz = XposedHelpers.findClass(className, classLoader)
        var hooked = 0
        val all = listOf(clazz) + clazz.declaredClasses.toList()
        for (c in all) {
            for (m in c.declaredMethods) {
                if (methodName != null && m.name != methodName) continue
                val params = m.parameterTypes.toList()
                if (!matches(params)) continue
                val args = ArrayList<Any>(params.size + 1)
                args.addAll(params)
                args.add(hook)
                runCatching {
                    XposedHelpers.findAndHookMethod(c, m.name, *args.toTypedArray())
                    hooked++
                    XposedBridge.log(
                        "[AutoCloud] hooked " + c.simpleName + "#" + m.name +
                            "(" + params.joinToString(",") { it.simpleName } + ")"
                    )
                }.onFailure {
                    XposedBridge.log("[AutoCloud] hook failed on ${c.name}#${m.name}: $it")
                }
            }
        }
        return hooked
    }

    private fun installTaskWallTrackingHooks(classLoader: ClassLoader) {
        // 1) 开始计时：Activity + 若干 String/int，参数位置可能变，按内容认
        val startHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 包名：第一个像包名的 String（含点）；时长：第一个数字参数
                var pkg = ""
                var sec = -1
                param.args.forEachIndexed { i, a ->
                    if (a is String && pkg.isEmpty() && a.contains('.') && !a.contains(' ')) {
                        pkg = a
                    }
                    if (a is Int && sec < 0) sec = a
                    if (a is Long && sec < 0) sec = a.toInt()
                }
                State.sdkTrackPackage = pkg
                State.sdkTrackRequiredSec = sec
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
        }
        runCatching {
            val n = hookByShape(
                "com.oplus.pay.opensdk.taskwall.jsapi.PayOpenAppCountTimeExecute",
                classLoader,
                "openAppAndStartTracking",
                { params ->
                    params.isNotEmpty() &&
                        params.any { it == android.app.Activity::class.java } &&
                        params.any { it == String::class.java } &&
                        params.any { it == Int::class.javaPrimitiveType || it == Int::class.java }
                },
                startHook,
            )
            if (n == 0) XposedBridge.log("[AutoCloud] SDK openAppCountTime shape not found")
        }.onFailure {
            XposedBridge.log("[AutoCloud] SDK openAppCountTime hook unavailable: " + it)
        }

        // 2) 计时结果：单参 JSONObject 的方法（外层类或其内嵌类）
        val resultHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.args.firstOrNull { it is org.json.JSONObject } as? org.json.JSONObject
                    ?: return
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
        }
        runCatching {
            val n = hookByShape(
                "com.oplus.pay.opensdk.taskwall.jsapi.PayOpenAppCountTimeExecute",
                classLoader,
                null,
                { params -> params.size == 1 && params[0] == org.json.JSONObject::class.java },
                resultHook,
            )
            if (n == 0) XposedBridge.log("[AutoCloud] SDK tracking result shape not found")
        }.onFailure {
            XposedBridge.log("[AutoCloud] SDK tracking result hook unavailable: " + it)
        }
    }

    private fun tryAttach(wv: WebView) {
        try {
            val url = wv.url ?: return
            Injector.observe(wv, url)
            if (State.isProfitUrl(url)) {
                Injector.attach(wv, url)
            } else if (
                State.isWelfareUrl(url) && State.running &&
                !State.injected.any { State.isProfitUrl(it.url) }
            ) {
                Injector.clickWelfareEntry(wv)
            }
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
    private val welfareEntryAttempts = WeakHashMap<WebView, Int>()

    /** 每个 WebView 最近一次被钩子观测到的 URL（钩子线程写，桥线程读，锁保护）。 */
    private val observedUrls = WeakHashMap<WebView, String>()

    /** 钩子每观测到一个 URL（loadUrl / onPageFinished）都记录，供桥门禁判定。 */
    fun observe(wv: WebView, url: String) {
        synchronized(observedUrls) { observedUrls[wv] = url }
    }

    /**
     * 桥调用门禁：WebView 仍处于 profit 任务页时返回其 URL，否则 null。
     *
     * 桥一旦安装就跨导航存活（profit 页跳出的第三方页同样能看到 window.autocloud），
     * 敏感方法因此必须在「调用时」再验一次当前页面，而不是只在安装时验一次。
     * 用观测 URL 而非 wv.getUrl()：后者内部派发到主线程，JavaBridge 线程同步读
     * 有与 Injector.attach 互等死锁的风险（见 Bridge.isLeader 注释）。
     */
    fun gateUrl(wv: WebView): String? {
        val last = synchronized(observedUrls) { observedUrls[wv] }
        return if (last != null && State.isProfitUrl(last)) last else null
    }

    /**
     * 离开 profit 页时摘桥。removeJavascriptInterface 要到下一次导航才生效，
     * 所以在 loadUrl before / onPageFinished 双点调用：前者让新文档直接见不到桥，
     * 后者覆盖重定向等不经过 loadUrl 的导航；调用时门禁（gateUrl）兜住生效间隙。
     */
    fun detachIfBridge(wv: WebView, url: String) {
        if (State.isProfitUrl(url)) return
        if (!bridges.containsKey(wv)) return
        State.main.post {
            runCatching {
                wv.removeJavascriptInterface("autocloud")
                XposedBridge.log("[AutoCloud] bridge removed on non-profit page url=$url")
            }
            bridges.remove(wv)
            preparedWebViews.remove(wv)
            attachedUrls.remove(wv)
            State.injected.remove(wv)
        }
    }

    /** 入口点击全局去抖：多个 WebView 同时看到福利页时只点一次，防止叠出多个 profit 页。 */
    @Volatile private var lastWelfareClickAt = 0L

    /**
     * 福利中心二级落地页入口点击。
     *
     * 页面是 SPA，卡片渲染晚于 onPageFinished，因此 JS 自带评分选择 + Kotlin 侧重试。
     * 只点「做任务免费领空间 / 免费领 200 GB 云空间」入口；
     * 「0.1元升级」等付费卡打负分，永远不会被点。
     */
    private const val WELFARE_ENTRY_JS = """(function () {
  var els = document.querySelectorAll('div,span,a,button,[role="button"]');
  var best = null, bestScore = 0, bestText = '';
  for (var i = 0; i < els.length; i++) {
    var el = els[i];
    var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
    if (!t || t.length > 24) continue;
    var s = 0;
    if (/做任务免费领空间/.test(t)) s += 100;
    if (/免费领\s*200\s*GB\s*云空间|免费领.{0,8}云空间/.test(t)) s += 80;
    if (/0\.1元|首单|升级|购买|支付|套餐|续费/.test(t)) s -= 200;
    if (s > bestScore) { best = el; bestScore = s; bestText = t; }
  }
  if (best) {
    try { best.click(); } catch (e) {}
    return 'clicked:' + bestScore + ':' + bestText;
  }
  return 'not-found';
})()"""

    fun clickWelfareEntry(wv: WebView) {
        val attempt = welfareEntryAttempts[wv] ?: 0
        if (attempt >= 25) return
        welfareEntryAttempts[wv] = attempt + 1
        State.main.postDelayed({
            try {
                if (
                    !State.running || !State.isWelfareUrl(wv.url) ||
                    State.injected.any { State.isProfitUrl(it.url) }
                ) {
                    // 已有 WebView 在 profit 任务页：不再重复点入口（防任务墙来回切换）
                    welfareEntryAttempts.remove(wv)
                    return@postDelayed
                }
                // 全局去抖：多个 WebView 几乎同时看到福利页时只允许一路点击，
                // 否则每次点击都会叠出一个新的 profit Activity（真机见 AutoEntry ×3）。
                val now = System.currentTimeMillis()
                val since = now - lastWelfareClickAt
                if (since < 4_000L) {
                    State.main.postDelayed(
                        { clickWelfareEntry(wv) },
                        4_000L - since + 200L
                    )
                    return@postDelayed
                }
                lastWelfareClickAt = now
                wv.evaluateJavascript(WELFARE_ENTRY_JS) { raw ->
                    val result = raw?.trim('"') ?: ""
                    if (result.startsWith("clicked")) {
                        XposedBridge.log("[AutoEntry] $result")
                        welfareEntryAttempts.remove(wv)
                    } else if (State.running && State.isWelfareUrl(wv.url)) {
                        // SPA 尚未渲染入口卡片，1s 后重试
                        clickWelfareEntry(wv)
                    } else {
                        welfareEntryAttempts.remove(wv)
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[AutoEntry] failed: " + t)
                welfareEntryAttempts.remove(wv)
            }
        }, 1_000L)
    }

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

    /**
     * 桥侧敏感能力门禁：桥方法开头调用，WebView 不在 profit 页时拒绝。
     * 配合 Injector.observe（钩子点持续刷新观测 URL）与 detachIfBridge 双保险。
     */
    fun bridgeGate(wv: WebView): Boolean = gateUrl(wv) != null

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
                synchronized(State.leaderLock) { State.activeWebView = WeakReference(wv) }
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
                    // 用 result（raw ?: "null"）计算，避免 raw 为 null 时先触发 NPE。
                    val hasBridge =
                        result.contains("\\\"bridge\\\":\\\"object\\\"") ||
                        result.contains("\\\"bridge\\\": \\\"object\\\"") ||
                        result.contains("\"bridge\":\"object\"") ||
                        result.contains("\"bridge\": \"object\"")
                    if (raw == null || result == "null" || !hasBridge) {
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
    private var diagRegistered = false

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
        // 诊断广播只在 shell 接收器上（要 DUMP 权限），GUI 侧不暴露
        if (!diagRegistered) {
            runCatching {
                val f = IntentFilter().apply { addAction(State.ACT_DIAG) }
                registerProtectedReceiver(
                    app, ShellReceiver, f, android.Manifest.permission.DUMP, sdk
                )
            }.onSuccess {
                diagRegistered = true
                XposedBridge.log("[AutoCloud] diag receiver registered")
            }.onFailure {
                XposedBridge.log("[AutoCloud] diag receiver registration failed: " + it)
            }
        }    }

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
            State.ACT_DIAG -> {
                // 诊断：宿主每版重新混淆，方法名/类名会变。
                // 这里在**宿主进程内**扫描宿主自己的 APK（进程内读自己的包无需权限），
                // 把「按签名还能定位到哪些方法」打出来——不用反编译、不用猜。
                if (!allowShellSession) return
                runCatching { runDiag(ctx, intent) }.onFailure {
                    XposedBridge.log("[AutoCloud] DIAG failed: " + it)
                }
            }
        }
    }

    /** DIAG：扫描宿主 APK，输出关键方法定位结果。 */
    private fun runDiag(ctx: Context, intent: Intent) {
        val apk = ctx.applicationInfo.sourceDir
        val findClass = intent.getStringExtra("find")
        val findStr = intent.getStringExtra("str")
        XposedBridge.log("[AutoCloud] DIAG scan $apk")

        if (!findClass.isNullOrBlank()) {
            val classes = DexScan.findClasses(apk, findClass)
            XposedBridge.log("[AutoCloud] DIAG classes~'$findClass' = ${classes.size}")
            classes.take(40).forEach { XposedBridge.log("[AutoCloud] DIAG   class $it") }
        }
        if (!findStr.isNullOrBlank()) {
            val methods = DexScan.findMethodsReferencingString(apk, findStr)
            XposedBridge.log("[AutoCloud] DIAG methods referencing '$findStr' = ${methods.size}")
            methods.take(40).forEach {
                XposedBridge.log(
                    "[AutoCloud] DIAG   ${it.className}#${it.methodName}" +
                        "(${it.params.joinToString(",")}) : ${it.ret}"
                )
            }
        }
        val builders = DexScan.findMethods(apk) { params, ret ->
            ret == "java.lang.String" && params.size == 1 &&
                params[0].endsWith("WebUrlConstants\$UrlType")
        }
        XposedBridge.log("[AutoCloud] DIAG url builders (UrlType->String) = ${builders.size}")
        builders.take(20).forEach {
            XposedBridge.log("[AutoCloud] DIAG   ${it.className}#${it.methodName} static=${it.isStatic}")
        }
        val launchers = DexScan.findMethods(apk) { params, _ ->
            params.size == 2 && params[0] == "android.app.Activity" &&
                params[1] == "java.lang.String"
        }
        XposedBridge.log("[AutoCloud] DIAG openers (Activity,String) = ${launchers.size}")
        launchers.take(30).forEach {
            XposedBridge.log(
                "[AutoCloud] DIAG   ${it.className}#${it.methodName} static=${it.isStatic} : ${it.ret}"
            )
        }
    }
}
