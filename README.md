# AutoCloud Claim（LSPosed 模块）

针对云服务 App（`com.heytap.cloud`，福利中心 H5 `…/profit/index.html`）的**任务辅助模块**。

## 它做什么 / 不做什么

**做**（全部是本机真实动作，通过页面上本来就有的按钮）：

1. 扫描福利中心「赚碎片兑云空间」任务卡片，按状态点击：
   `下载安装` → 真实下载安装 → `立即打开` → 按卡片要求停留 N 秒 → 回到云服务 → `领取`
2. 用 `PackageManager` 轮询确认新包确实已安装（`firstInstallTime >= t0`）
3. 领取成功（页面碎片数变化）后，可选自动卸载本次新装的应用
4. 全程受 `max` / 40 分钟看门狗 / `STOP` 广播约束

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
cd autocloud
# 用 Android Studio 打开，或先生成 wrapper：
gradle wrapper --gradle-version 8.14.5
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
   `[AutoCloud] loaded in com.heytap.cloud` / `trigger receiver registered`。
3. 打开「福利中心 / 赚碎片兑云空间」页，日志出现
   `[AutoCloud] attached …/profit/index.html` 即注入成功。

## 使用（GUI 控制面板）

模块 APK 同时是一个 Material 3 / Jetpack Compose 应用，桌面图标 **AutoCloud Claim**：

```
┌───────────────────────────────────────┐
│ AutoCloud Claim            ● 已连接    │  ← 顶栏：连接指示 + 当前阶段
│ 已连接 · 领取成功 · 120 碎片            │
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

1. 设好 `max / dwell / uninstall / dry`，直接点 **开始自动完成**；面板会自动拉起云服务。
2. 模块会重试发送启动指令，直到目标进程的 Hook 接收器回执或等待超时。
3. 日志会区分「云服务进程已连接」和「福利中心页面已检测到/已就绪」；若停在等待页面，进入福利中心后会自动继续。
4. 看日志滚动态，随时 **停止**。单独的「打开云服务」按钮仍可用于手动排查。

## 使用（adb / 命令行，备选）

GUI 是首选；命令行可用于调试或无界面环境（参数语义完全一致）：

```bash
# 1) 先干跑一次：只打印任务卡片 HTML 和所有按钮文字，不点任何东西（用来校准选择器）
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

## 文件结构

```
autocloud/
├── .github/workflows/build.yml               CI：每次 push 构建，main 发 Release
├── app/src/main/assets/xposed_init            模块入口类名
├── app/src/main/res/values/arrays.xml         xposed_scope = com.heytap.cloud
├── app/src/main/res/values[-night]/themes.xml Material 系统主题（配 edge-to-edge）
├── app/src/main/java/com/opautocloud/claim/
│   ├── MainHook.kt         入口：State(含 publish) / Trigger / Injector + 三个 hook
│   ├── Bridge.kt           JS 桥 window.autocloud（状态查询 + 启动/回前台/卸载 + status 上报）
│   ├── Script.kt           注入的自动化脚本（任务状态机循环）
│   └── ui/MainActivity.kt  Material 3 控制面板（Compose）
```

## 选择器校准

H5 是 Vite + Vue3，卡片类名来自 CSS module（抓到过的有
`task-card-list` / `task-card-footer` / `task-card-title` / `task-action-area` /
`task-download-progress`）。脚本用「按钮文字精确匹配 + 最小面积」兜底，不依赖具体类名；
但文案随活动配置会变。**第一次务必先开「演示模式」**（GUI 里的 dry 开关，或 adb
`--ez dry true`），把日志里 `DUMP buttons=` 那一行贴出来，再按实际文案补
`CLAIM / INSTALL / OPEN / GO` 四个正则。

## 已知限制

- **次留 / 注册类任务无法自动化**：次留要等 24 小时且服务端复核，注册无法被真实验证，
  这两类脚本会 `SKIP`，请手工做。
- **领完立刻卸载可能让次留/审核类任务被服务端判为未完成**，所以 `uninstall` 默认关闭。
- 任务状态由服务端定时刷新，脚本遇到「无按钮」会滚动 + 轮询，不会硬点。
- 违反云服务用户协议的风险自负：这是自动化你自己的设备操作，不是伪造服务端事件，
  但刷量行为本身仍可能触发风控（验证码 / `5610401~5610404`），账号被封会连带影响已有云空间。
