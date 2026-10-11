# AutoCloud Claim（LSPosed 模块）

针对云服务 App（`com.heytap.cloud`，福利中心 H5 `…/profit/index.html`）的**任务辅助模块**。

## 它做什么 / 不做什么

**做**（全部是本机真实动作，通过页面上本来就有的按钮）：

1. 扫描福利中心「赚碎片兑云空间」任务卡片，按状态点击：
   `下载安装` → 真实下载安装 → `立即打开` → 按卡片要求停留 N 秒 → 回到云服务 → `领取`
2. 用 `PackageManager` 轮询确认新包确实已安装；宿主进程看不到包列表时自动退回 `su -c pm list packages -3`
3. 领取成功（三重证据确认）后，可选自动卸载本次新装的应用
4. 全程受 `max` / 40 分钟看门狗 / `STOP` 广播约束；同一张卡片失败后进入冷却，**不会无限重点击**

**不做**（硬边界，代码里根本没有对应实现）：

- 不构造、不改写、不重放 `/cloudtask-api/…/report-event`、`…/grant-award`、`…/award/v1/resend`
- 不绕过 `bspWwas` 设备签名、验证码 `5610401~5610404`、`40301` 续期
- 不点任何花钱按钮（`兑换/支付/购买/开通/续费/升级/套餐/优惠/金币` 在黑名单里）
- 不伪造「我已注册/立即注册」这类无法真实验证的动作，直接跳过

能不能领到碎片由**服务端任务状态机**（`taskActionStatus` / `completeConditions` /
`taskRecordId`）决定：下载安装与归因来自商店/广告平台回调，`grant-award` 的 `coinAmount`
也是服务端算的。模块只是帮你点按钮，服务端不认就领不到。

## 构建

### 方式 A：GitHub Actions（推荐）

**每次 push 自动构建**：

- **任意分支的任意提交** → 跑 CI，产物挂在该次 run 的 *Artifacts*（保留 30 天）
- **push 到 `main`** → 额外创建一个 GitHub Release，tag 形如 `build-<run_number>`，
  资产是签名好的 `AutoCloudClaim-build-<run_number>.apk`，标记为 latest

直接下载：**[Releases](https://github.com/KotobaWakusei/AutoCloudClaim/releases)**

流水线见 `.github/workflows/build.yml`：

1. `setup-java` JDK 17 + `setup-gradle` Gradle 8.14.5（仓库里没有 wrapper）
2. 从 `secrets.KEYSTORE_B64` 解出 keystore
3. `gradle assembleRelease`（签名单元读 `KEYSTORE_FILE/PASSWORD/ALIAS/PASSWORD` 环境变量）
4. `apksigner verify --print-certs` 校验签名
5. `upload-artifact`（所有分支）→ `softprops/action-gh-release`（仅 `main`）

需要的仓库 Secrets（已配置好）：`KEYSTORE_B64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
仓库是 Public 的，Secrets 不会被 workflow 日志回显，fork 的 PR 拿不到。

### 方式 B：本地

```bash
# 需要 JDK 17 + Android SDK（platform 34 / build-tools 34）
echo "sdk.dir=/path/to/android-sdk" > local.properties

gradle wrapper --gradle-version 8.14.5   # 仓库里没有 wrapper，先生成一次
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 本地没设 KEYSTORE_FILE 时会自动用 debug 签名，可直接 assembleInstall
```

依赖：`compileOnly de.robv.android.xposed:api:82`（仓库 `https://api.xposed.info/`），
Android Gradle Plugin 8.5.2 + Kotlin 2.0.21 + `kotlin.plugin.compose`，
Compose BOM 2024.09.03 / Material 3 / activity-compose 1.9.2，compileSdk 34，minSdk 29。

## 安装与配置

1. 安装 APK，在 **LSPosed 管理器**里启用模块，作用域只勾 `com.heytap.cloud`（Manifest 里
   `xposed_scope` 已预置）。
2. 强行停止云服务 App 再打开，LSPosed 日志里应出现：
   ```
   [AutoCloud] loaded in com.heytap.cloud
   [AutoCloud] control trigger receiver registered
   [AutoCloud] shell trigger receiver registered
   ```
3. 打开「福利中心 / 赚碎片兑云空间」页，日志出现
   `[AutoCloud] attached …/profit/index.html` 即注入成功。

## 使用（GUI 控制面板）

模块 APK 同时是一个 Material 3 / Jetpack Compose 应用，桌面图标 **AutoCloud Claim**：

```
┌───────────────────────────────────────┐
│ AutoCloud Claim            ● 已连接    │  ← 顶栏：连接指示 + 当前阶段
│ 已连接 · 领取成功 · 120 碎片            │
├───────────────────────────────────────┤
│ ↑ 发现新版本 build-48（当前 build-47） │  ← 有新 release 时显示
│ [          前往下载          ] [ 忽略 ] │
├───────────────────────────────────────┤
│ [ ● 运行中 ]  已领取      碎片          │  ← 状态卡
│                1 次      120           │
│ [   开始自动完成   ] [   停止   ]        │
│ [     打开云服务 · 去福利中心     ]      │
├───────────────────────────────────────┤
│ 参数                                   │
│  最多领取次数        3 次   ━━●━━━      │
│  默认停留秒数       60 秒   ━━●━━━      │
│  领取成功后卸载本次新装的应用      [●]   │
│  演示模式（dry run）              [ ]   │
├───────────────────────────────────────┤
│ 运行日志                               │
│  领取成功 · 120 碎片                    │
│  installed: com.xxx.yyy               │
│  click INSTALL "下载安装"               │
└───────────────────────────────────────┘
```

**更新检查**：启动时自动查询 GitHub Releases（`api.github.com/repos/…/releases/latest`），
有新版本时显示提示卡片，点「前往下载」跳转浏览器。CI 构建会注入 `-PbuildNumber=N`，
与 release tag `build-N` 比较；本地开发构建 buildNumber=0，永远提示有更新（可点「忽略」关闭）。

通信方式（控制指令受权限保护）：

- **面板 → 云服务**：`START/STOP` 使用显式目标包，并携带每次运行生成的随机会话令牌；
  接收器要求本模块签名权限，普通第三方 App 无法伪造控制指令
- **ADB shell/root → 云服务**：保留单独的调试接收器，并要求系统 `DUMP` 权限；
  命令行会建立独立的 shell 会话，不会冒充控制面板会话
- **云服务 → 面板**：`STATUS` 广播携带当前会话令牌。由于 Hook 在云服务的 UID 中运行，
  面板接收器必须导出；面板会拒绝会话令牌不匹配的回执，以阻止普通应用伪造状态
- 面板收到有效回执刷新顶栏连接点，**12 秒无回执自动置灰**；点「开始」后 3.5 秒
  无回执会在日志里提示「请先打开云服务 App 并进入福利中心」

操作顺序：

1. 设好 `max / dwell / uninstall / dry`，直接点 **开始自动完成**。
2. 面板先尝试与已运行的云服务进程握手；前两次没有回执时才自动拉起云服务，并重试启动指令，直到 Hook 接收器回执或等待超时。
3. **START 后模块会自动导航到福利中心**：Hook 在宿主进程内调用 app 自己的打开方法
   （`wo.c` 构建 URL → `yl.d.r()` 启动 `CloudWebBusinessActivity`），无需手动进入。
   日志出现 `[AutoNav] navigation triggered` 即跳转成功；若失败会自动重试（最多 12 次，
   间隔 2 秒），超时后提示「请手动进入」。
4. 看日志滚动态，随时 **停止**。云服务进程即使已被强停，「停止」也会先在本地复位，不会把「开始」按钮锁死。

## 使用（adb / 命令行，备选）

GUI 是首选；命令行可用于调试或无界面环境（参数语义完全一致）：

```bash
# 1) 先干跑一次：只打印任务卡片 HTML 和按钮文字，不点任何东西（用来校准选择器）
adb shell am broadcast -a com.opautocloud.claim.START --ez dry true

# 2) 正式跑：最多领 3 次，浏览时长 60s，领完不卸载
adb shell am broadcast -a com.opautocloud.claim.START --ei max 3 --ei dwell 60

# 3) 领完自动卸载本次新装的应用（需要 root 才一定能成）
adb shell am broadcast -a com.opautocloud.claim.START --ei max 3 --ez uninstall true

# 4) 随时停止
adb shell am broadcast -a com.opautocloud.claim.STOP
```

root 机可用 `su -c 'am broadcast …'`。状态在 LSPosed 日志里实时可见
（`[AutoCloud][JS] …`）。

## 参数（GUI 滑杆/开关 与 adb extra 一一对应）

| Intent extra | GUI | 类型 | 默认 | 含义 |
|---|---|---|---|---|
| `max` | 最多领取次数 | int | 3 | 一次运行最多领几次碎片（看门狗之外的第二道闸） |
| `dwell` | 默认停留秒数 | int | 60 | 卡片没写「浏览 N 秒」时的默认停留秒数 |
| `uninstall` | 卸载开关 | boolean | false | 领取成功后卸载本次新装的应用 |
| `dry` | 演示模式开关 | boolean | false | 只 dump 页面结构，不点击 |

广播 action：`com.opautocloud.claim.START` / `.STOP` → 云服务；`com.opautocloud.claim.STATUS` ← 云服务。

## 首次校准（必做）

H5 是 Vite + Vue3，卡片类名来自 CSS module（抓到过的有
`task-card-list` / `task-card-footer` / `task-card-title` / `task-action-area` /
`task-download-progress`）。脚本用「按钮文字精确匹配 + 最小面积」兜底，不依赖具体类名；
但**文案随活动配置会变**，四个正则必须对上真实按钮才会动手。

1. 打开 GUI，勾上 **演示模式（dry run）**，点开始。它只 dump、不点击。
2. 在 LSPosed 日志里找这三行，它们是校准的全部输入：
   ```
   [AutoCloud][JS] DUMP fragments=120 counter=found
   [AutoCloud][JS] DUMP action-candidates=button:领取 | button:下载安装 | button:立即打开 | button:去完成
   [AutoCloud][JS] DUMP other-text=浏览商品15秒 | 今日已完成 | …
   ```
   - `action-candidates` 只列 `<button>` / `<a>` / `[role=button]` 的短文字，已经带 `tag:` 前缀，
     **直接照抄进对应正则即可**，基本不用再筛。
   - `other-text` 是其余短文本（卡片标题、奖励数、进度等），用来确认有没有漏掉的非标准按钮。
   - `counter=found` 表示碎片数元素找到了；若显示 `MISSING`，领取确认会退化为
     「领取按钮消失 / 完成态文案」这一条 DOM 通道（SDK 回执通道见下文说明，通常不触发），
     功能不受影响，但建议顺手把真实选择器补进 `Script.kt` 的 `FRAG_SEL` 数组。
3. 把没对上的文案补进 `Script.kt` 顶部的四个正则：
   ```kotlin
   var CLAIM   = /^(立即领取|领取|继续领取|去领取|领奖)$/;
   var INSTALL = /^(下载安装|开始安装|安装|立即下载|下载|继续安装|重新安装|…)$/;
   var OPEN    = /^(立即打开|打开|继续打开|开始体验|去体验|立即体验)$/;
   var GO      = /^(去完成|去下载|去安装|去打开|去浏览|去做任务)$/;
   ```
4. 重新构建安装，再跑一次正式模式确认能点到。

## 排障手册

按「日志里最后出现什么」对号入座：

| 现象 | 日志特征 | 原因与处理 |
|---|---|---|
| 点开始后面板一直转 | 无 `control trigger receiver registered` | LSPosed 未启用模块或作用域没勾 `com.heytap.cloud`。强停云服务重开 |
| 自动跳转没生效 | `[AutoNav] welfare center URL not ready` 反复出现 | 宿主云配置（`cloudconfig.g()`）还没加载完，等几秒再试；若一直失败，手动进福利中心 |
| 连上了但页面不动 | `[AutoNav] gave up after 12 attempts` | 自动跳转重试耗尽。点「打开云服务 · 去福利中心」或手动进入 |
| 脚本挂了、日志停住 | `[AutoCloud][JS] FATAL loop error: …` | 注入脚本抛异常。把这行贴出来，多半是页面结构变了 |
| 按钮一个都没点 | 有 `START` 但没有 `click …`，且 `action-candidates` 里有没见过的文案 | 文案对不上四个正则 → 按上一节校准 |
| 碎片数一直是 0 | `counter=MISSING` | 碎片元素选择器变了。补 `FRAG_SEL`；期间确认会退化为「按钮消失/完成态文案」这一条 DOM 通道 |
| 领取点了很多次都不认 | `claim not confirmed after 15s (evidence=none)` + `cooldown 20s` | 服务端没推进状态。**这是正常的**——冷却机制在拦着不让你刷风控；同时检查是否触发了验证码 |
| 一直下载安装不结束 | `install timeout: no new package detected (wantPkg=…)` | 商店没真装上，或包名没挖到。看 `wantPkg` 是否 `unknown`；卡片里通常没有 `market://details?id=` 链接 |
| 安装检测不到 | `WARN only N non-system packages visible to com.heytap.cloud` | 宿主包可见性被过滤，已自动退回 `su -c pm list packages -3`。**没 root 就装不上检测**，请确认已 root |
| 切不出去 / 回不来 | `scheduled return fired after Ns ok=false` | Kotlin 侧延时切前台失败。检查是否禁了云服务的后台启动权限 |
| 一跑就被验证码拦 | 服务端返回 `5610401~5610404` | 触发风控了。停手，隔天再试；`max` 调小、`dwell` 调大更接近真人 |

抓完整日志：

```bash
adb logcat -s LSPosed-Bridge:V | grep AutoCloud
# 或在 LSPosed 管理器 → 模块 → 日志里看
```

## 内部机制（改代码前先看）

以下结论已用 11.3.5 真机包反编译核对（`/home/kali/Desktop/out`，apktool + jadx），
不是猜的。

**领取成功的证据**（任一成立即计数，`Script.kt` 的 `doClaim`）：

1. **碎片数上升**（主通道）—— `FRAG_SEL` 里任一选择器读到的数字变大
2. **卡片出现完成态文案 / 领取按钮消失**（DOM 通道）—— 点击前记下卡片指纹，15 秒后
   `claimSettled()` 做两次采样（间隔 2 秒）确认同指纹卡片里不再有「领取」类按钮，
   或出现了 `已完成 / 已领取 / 领取成功 / 明日再来` 之类标记。两次采样是为了避开
   SPA 重渲染时按钮瞬时消失造成的误判。
3. TaskWall SDK 回执 —— Hook `CloudTaskWallH5Result.e()`，成功码 **200**

关于第 3 条，有个必须知道的限制：**这条通道在 profit 页通常不会触发**。
反编译显示该拦截器（`jr/d`）只在 `z11 == true` 时注册（`ir/e.java:321`），
而 profit 页的 `startCoinTaskWallPage` JS API 走的是 `z11 == false`
（`StartCoinTaskWallPage.java:60` → `TaskWallProvider.f(..., false)`）。
领取动作本身是 H5 直接发 HTTP 到服务端，不经过原生桥，所以没有原生回执。
（此前代码里写的成功码 13097 是误匹配 Material 库的资源 ID，已改为 200。）

也就是说：**碎片数与按钮/文案变化是仅有的可靠信号**，这也是为什么 `FRAG_SEL`
校准和演示模式 dump 这么重要。

**冷却**：同一张卡片（指纹 = 卡片文案数字归一化后取前 64 字符）确认失败后冷却 15–20 秒；
连续失败 3 次（安装 2 次）就给该元素打 `data-ac-skip`，本轮不再碰。这挡住了「服务端不认 →
3 秒后再点一次」的死循环。

**延时切前台**：点「立即打开」前先调 `Bridge.scheduleReturn(sec)`，把 `bringToFront` 排进
**Kotlin 主线程 Handler**。WebView 的 JS 定时器在应用退后台后会被 ROM 冻结（ColorOS 尤甚），
只靠脚本里的 `setTimeout` 永远等不到回来的那一刻。

**打开计时**：`openAppCountTime` 这条链路是原生的，Hook 能拿到真实数据——
`PayOpenAppCountTimeExecute.openAppAndStartTracking` 记下 `packageName` + `remainingTime`，
应用回到前台时 `TimeTrackingManager` 的 `onActivityResumed` 回调里带着
`{duration, packageName, timeLeft}`。脚本用这个真实 `remainingTime` 覆盖页面文案里的
「浏览 N 秒」，比解析中文文案准。

**包可见性**：`Bridge.installedPackages()` 跑在云服务进程里，读的是**宿主**的 PackageManager，
模块自己 Manifest 里的 `<queries>` 在这里无效。11.3.5 反编译核对：宿主已声明
`QUERY_ALL_PACKAGES` + `MAIN/LAUNCHER` intent 查询，所以正常设备上能拿到完整非系统包列表，
**不需要 root**。仅当该权限被系统/用户回收时可见包数会掉到个位数，此时自动退回
`su -c pm list packages -3`（结果缓存 10 秒，避免安装轮询期间反复 fork `su`）。

**URL 匹配**：`/profit` 路径由服务端下发，APK 里没有硬编码（反编译无该字面量）。
`State.isProfitUrl()` 要求路径里出现 `/profit` 且后面是结束、`/`、`?` 或 `#`，
兼容 SPA 子路由与无尾斜杠，同时排除 `/profits` 之类误匹配。

**自动导航**（`AutoNav` 对象）：START 后若不在福利中心页，Hook 在宿主进程内自动跳转。
链路（反编译确认）：`wo.c.f40427f.q(WebUrlConstants$UrlType.WELFARE_CENTER)` 构建 URL
（路径固定 `/cloud/welfarecenter.html`，域名来自服务端 `cloudconfig.g()`），
再调 `yl.d.f41385g.r(activity, url)` → 经 `zl.c` / `fo.a.g` / `WebProvider`
启动 `CloudWebBusinessActivity`。`CloudWebBusinessActivity` 虽 `exported=false`，
但 Hook 跑在 `com.heytap.cloud` 同进程同 UID，可直接调用。
Activity 前台化由 `Application.ActivityLifecycleCallbacks` 跟踪；
URL 未就绪（云配置未加载）时每 2 秒重试，最多 12 次；进入福利中心页后自动取消。

**执行结构**：
```
loop()          ← try/finally 复位 S.run，异常不会让脚本假死
 └─ scanOnce()  ← 主循环
     ├─ doClaim()    优先级 1
     ├─ doOpen()     优先级 2
     ├─ doInstall()  优先级 3
     ├─ doGo()       优先级 4
     └─ BUSY / idle  兜底
```

## 文件结构

```
app/src/main/assets/xposed_init            模块入口类名
app/src/main/res/values/arrays.xml         xposed_scope = com.heytap.cloud
app/src/main/res/values[-night]/themes.xml Material 系统主题（配 edge-to-edge）
app/src/main/java/com/opautocloud/claim/
├── MainHook.kt         入口：State(含 publish) / AutoNav / Trigger / Injector + 三个 hook
├── Bridge.kt           JS 桥 window.autocloud（状态查询 + 启动/延时返回/卸载 + status 上报）
├── Script.kt           注入的自动化脚本（任务状态机循环 + 冷却 + 三重领取确认）
├── UpdateChecker.kt    GitHub Release 更新检查（查询 latest release tag）
└── ui/MainActivity.kt  Material 3 控制面板（Compose）
```

## 已知限制

- **root 是可选的，但有两处会用到**：① 只有当宿主的包可见性权限被回收时，安装检测才需要
  `su -c pm list packages -3` 兜底（11.3.5 反编译确认宿主已声明 `QUERY_ALL_PACKAGES`，
  正常设备不需要）；② 「领取后卸载」依赖 `su pm uninstall`。都不 root 也能跑，
  只是这两种场景会失效。
- **次留 / 注册类任务无法自动化**：次留要等 24 小时且服务端复核，注册无法被真实验证，
  这两类脚本会 `SKIP`，请手工做。
- **领完立刻卸载可能让次留/审核类任务被服务端判为未完成**，所以 `uninstall` 默认关闭。
- **停留时长可能被高估**：应用退后台后 JS 定时器会被冻结，醒来时实际停留时间通常比设定的
  长。对「至少停留 N 秒」类任务是安全方向，但如果服务端要求「不超过 N 秒」就会判失败。
- 任务状态由服务端定时刷新，脚本遇到「无按钮」会滚动 + 轮询，不会硬点。
- 违反云服务用户协议的风险自负：这是自动化你自己的设备操作，不是伪造服务端事件，
  但刷量行为本身仍可能触发风控（验证码 / `5610401~5610404`），账号被封会连带影响已有云空间。
