# AutoCloudClaim 代码审计报告

审计范围：`/home/kali/Desktop/AutoCloudClaim`（Android Xposed 模块，针对宿主 `com.heytap.cloud`）
审计方法：find-bugs（全量源码阅读 + 攻击面梳理 + 关键逻辑实证验证）
分级标准：Critical / High / Medium / Low
原则：每条结论可复核、不隐瞒、不夸大；修复均经真实机 APK 数据实证。

---

## 一、发现总览

| # | 级别 | 问题 | 状态 |
|---|---|---|---|
| 1 | Critical | `DexScan.methodId()` 幽灵 `access_flags` 字段 | ✅ 已修 |
| 2 | High | `methodsReferencingString()` virtual 索引跨列表累积错误 | ✅ 已修 |
| 3 | High | Bridge `window.autocloud` 跨导航持久暴露 | ✅ 已修 |
| 4 | High | `HostApi.openUrl()` 无条件 `callStaticMethod` | ✅ 已修 |
| 5 | Medium | `launcherCache` 无兜底 | ✅ 已修 |
| 6 | Medium | `singletonOf()` fallback 盲选 | ✅ 已修 |
| 7 | Low | `forEachDex()` 全静默吞异常 | ✅ 已修 |
| 8 | Low | `UpdateChecker.check()` 异常静默吞 | ✅ 已修 |

**8/8 全部修复。编译验证通过（BUILD SUCCESSFUL），APK 已产出并验签通过。**

---

## 二、修复详情

### 1. DexScan.kt `methodId()` — 幽灵 `access_flags` 字段（Critical）

**位置**：`DexScan.kt:232-236`（修复前）
**问题**：`methodId()` 将 `Quad(u16(off), u16(off+2), u32(off+4), u16(off+6))` 的第 4 项 `u16(off+6)` 当作 `method_id_item` 的 `access_flags`。
**证据**：官方 DEX 规范（`.bugref/dexfmt.html` / source.android.com）：`method_id_item` 仅 8 字节 `class_idx(u2) + proto_idx(u2) + name_idx(u4)`，**无 access_flags**。`off+6` 实际是 `name_idx` 高 16 位垃圾值。
**影响**：`isStatic = (accessFlags and 0x0008) != 0` 100% 错判，下游 `urlBuilderCache`/`launcherCache`/`configMapCache`/`openUrl` 全量受牵连。
**修复**：`methodId()` 改 `Triple(u16(off), u16(off+2), u32(off+4))`；`findMethods` 改遍历 `class_def → class_data` 读真实 access_flags；`u16/u32/uleb` 可见度 `private → internal`。

### 2. DexScan.kt `methodsReferencingString()` — virtual 索引跨列表累积错误（High）

**位置**：`DexScan.kt:256-295`（修复前）
**问题**：单一 `methodIdxAcc`：direct 区直接赋 `diff`，virtual 区累加 `methodIdxAcc + diff`，direct 末元素索引未正确接力，virtual 首元素基点错误。
**证据**（真机 APK 云服务 11.3.5，5 dex / 252,854 方法，三口径对照，`method_id.class_idx == 声明类` 做裁判）：
- direct#1 `idx=diff`：100% 命中
- direct#2..n 列表内累积：100%（现行口径逐元素替换 0.00%，76,132 类不符）
- virtual#1 独立列表：100%；现行口径 38.71%；跨列表连续累积 10.49%
**修复**：拆 `directAcc`/`virtualAcc` 两个独立累积器。

### 3. Bridge `window.autocloud` 跨导航持久暴露（High）

**位置**：`MainHook.kt` Injector 区 + `Bridge.kt`
**问题**：桥仅在 profit 页安装，但 WebView 语义是"桥一经安装，后续任何导航的页面都能看到它"。从 profit 页跳出的第三方页仍可调用 `installedPackages`（枚举应用）、`launch`（启动任意包）、`uninstall`（autoUninstall=true 时卸载任意第三方应用）、`shellCaptureWithExit`（任意 shell）。
**修复**（双层）：
- ** Injector 侧**：新增 `observedUrls`（WeakHashMap，锁保护）记录每页观测 URL；`detachIfBridge()` 在非 profit 页主线程 `removeJavascriptInterface("autocloud")` 并清空注入状态；挂载点 `onPageFinishedHook` else 分支 + `loadUrlHook.before`（双点覆盖重定向）。门禁读观测 URL 而非 `wv.getUrl()`（避免 JavaBridge 线程同步读死锁）。
- **Bridge 侧**：`gated() = Injector.bridgeGate(wv)`；7 个敏感方法开头加门禁返回假值——`isInstalled/installedPackages/newPackagesSince`（枚举）、`launch/bringToFront/scheduleReturn`（启动/切前台）、`uninstall`（门禁排最前）。`scheduleReturn` 定时回调内部走 `bringToFrontNow()` 不过门禁（用户切走时页面仍属 profit 任务，属预期）。

### 4. `HostApi.openUrl()` — 无条件 `callStaticMethod`（High）

**位置**：`MainHook.kt:296-304`（修复前）
**问题**：不检查 `ref.isStatic` 无条件 `callStaticMethod`；实例方法时查找失败抛异常被吞 → 返回 false → AutoNav 重试至失败。
**修复**：按 `ref.isStatic` 分支 `callStaticMethod` / `singletonOf+callMethod`（与 `buildUrl`/`configUrl` 一致）。连带修复 `configUrl` 静态分支误用 `callMethod(clazz,...)`（以 Class 对象为接收者必然找不到方法）。

### 5. `HostApi.launcherCache` 无兜底（Medium）

**位置**：`MainHook.kt:196-232`（修复前）
**问题**：`urlBuilderCache` 有 `?: firstOrNull()` 兜底，`launcherCache` 没有；isStatic 数据源错误时（Bug #1）直接 null → 导航失败。
**修复**：补同型 `?: findMethods(...).firstOrNull()` 兜底。

### 6. `singletonOf()` fallback 盲选（Medium）

**位置**：`MainHook.kt:235-248`（修复前）
**问题**：fallback 取"第一个非空 static 字段"，可能是无关类型实例，`callMethod` 必败且静默。
**修复**：fallback 增加 `clazz.isInstance(v)` 门禁，非本类实例不再盲选。

### 7. `forEachDex()` 全静默吞异常（Low）

**位置**：`DexScan.kt:31-51`（修复前）
**问题**：单 dex 解析失败 / APK 读取失败均空 catch 吞掉，部分解析失败时漏检且无感知。
**修复**：失败处加 `Log.w("AutoCloudDexScan", ...)`（含 dex 名与异常），并加 `finally close`。

### 8. `UpdateChecker.check()` 异常静默吞（Low）

**位置**：`UpdateChecker.kt:78`；`MainActivity` 调用点
**问题**：HTTP 非 200 / 网络异常全静默，更新检查长期失败不可见。
**修复**：HTTP 非 200 加 `Log.w("AutoCloudUpdate", "release check HTTP $code")`；`MainActivity` 的 `runCatching` 补 `onFailure` 日志。

---

## 三、验证

### 测试回归（绿）
- `test/probe.test.js`：**10 passed**
- `test/script.test.js`：**40 passed**（12 场景展开 40 断言：claim/install/open/register skip/dry/idle/vui-card/standby/智能卸载/nextday/kinds dump/kinds 路由）

### 编译验证（通过）
- 工具链：`/home/kali/.ac-toolchain/gradle-8.14.5` + `jdk-17` + `android-sdk`（local.properties 指定）
- 过程要点（如实记录）：`/tmp` 为 tmpfs 会话中途被清空过一次，Gradle 缓存改放工作区 `.gradle-home/`；`dl.google.com` 间歇 TLS 断连，靠循环重试逐步补齐依赖缓存。
- 结果：**`assembleRelease` BUILD SUCCESSFUL in 1m 52s**（50 tasks），修复过程中连带修掉 `uleb` 可见度编译错误（`private→internal`）。
- 产物：`app/build/outputs/apk/release/app-release.apk`（17.5MB）
- 验签：`apksigner verify` EXIT=0（debug 证书，本机无 release keystore，符合仓库默认签名配置）
- badging：`com.opautocloud.claim` versionName 0.1.0 / minSdk 29 / targetSdk 34，正确。

---

## 四、修改清单（未提交，工作区改动）

| 文件 | 修改点 |
|---|---|
| `DexScan.kt` | #1 #2 #7：methodId 精简、findMethods 改真实 access_flags、directAcc/virtualAcc、uleb internal、forEachDex 日志 |
| `MainHook.kt` | #3 #4 #5 #6：桥 detach/门禁机制、openUrl/configUrl 静态分支、launcherCache 兜底、singletonOf 实例校验 |
| `Bridge.kt` | #3：`gated()` 门禁，7 个敏感方法开头返回假值 |
| `UpdateChecker.kt` | #8：HTTP 非 200 日志 |
| `ui/MainActivity.kt` | #8：更新检查 onFailure 日志 |
| `BUG_AUDIT_REPORT.md` / `local.properties` | 本报告 / 编译环境配置（非源码） |

改动量：5 源文件，+212/−59 行。

---

## 五、遗留与建议

1. **未提交**：全部改动在工作区，等主人指示提交（建议 commit message：`fix: 8 findings from full audit — dex scan correctness, bridge gating, host api call paths`）。
2. **debug 签名**：本机无 release keystore，产物为 debug 签名；CI（KEYSTORE_B64）环境可出正式签名包。
3. **真机冒烟**：建议 LSPosed 激活后跑一轮 `tools/dev.sh run`，确认宿主内 URL 门禁与导航链路端到端正常（脚本层测试无法覆盖 Xposed 运行时行为）。
4. `.gradle-home/`（约 1.5G 编译缓存）与 `.bugref/` 为本机编译/审计辅助，建议加入 `.gitignore`，不随仓库分发。
