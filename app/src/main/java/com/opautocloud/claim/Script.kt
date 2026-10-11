package com.opautocloud.claim

/**
 * 注入到福利中心 H5（static-cn01a.ocloud.heytapmobi.com/profit/）的自动化脚本。
 *
 * 设计边界（重要）：
 *  - 脚本只做「点击页面上本就存在的按钮 + 启动刚装好的应用 + 计时 + 回到前台 + 卸载」；
 *  - 不构造、不改写、不重放 /cloudtask-api 的任何请求（report-event / grant-award / resend）；
 *  - 能不能领到碎片由服务端任务状态机（taskActionStatus / completeConditions / taskRecordId）判定，
 *    服务端认为没完成就领不到，本模块不做任何绕过。
 *
 * 注意：Kotlin 原始字符串里不能出现「美元符+标识符」或「${」，
 *       因此下面的 JS 不使用模板字符串，也不用 $ 作为标识符。
 */
object Script {
    val BODY: String = """
(function () {
  if (window.__AC) { window.__AC.kick(); return; }
  var B = window.autocloud;
  if (!B) { return; }

  var S = {
    run: false, started: 0, claimed: 0, idle: 0, busy: 0,
    lastT: 0, beforePkgs: '', newPkgs: '', stop: '', lastUrl: location.href
  };
  window.__AC = S;

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function log(m) { try { B.log(String(m)); } catch (e) {} }
  function st(p) { try { B.status(String(p), S.claimed, frag()); } catch (e) {} }
  // 结束本轮：复位服务端侧的运行标志，避免面板一直显示「运行中」
  function fin(p) { try { B.finishRun(); } catch (e) {} st(p || '已停止'); }
  function txt(el) { return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim(); }
  function vis(el) {
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.top < 30000 && r.bottom > -3000;
  }

  // 黑名单：花钱的、关闭类的、以及无法被真实验证的（注册类一律留给人工）
  var BLACK = /兑换|支付|购买|抢购|开通|续费|升级|套餐|优惠|金币|提现|关闭|收起|我已注册|确认完成|立即注册/;
  var CLAIM = /^(立即领取|领取|继续领取|去领取|领奖)$/;
  var INSTALL = /^(下载安装|开始安装|安装|立即下载|下载|继续安装|重新安装|点击继续安装应用领奖励)$/;
  var OPEN = /^(立即打开|打开|继续打开|开始体验|去体验|立即体验)$/;
  var GO = /^(去完成|去下载|去安装|去打开|去浏览|去做任务)$/;
  var BUSY = /安装中|等待中|下载中|加载中|审核中|刷新中|次留|次日/;

  function pool() { return document.querySelectorAll('button,[role="button"],a,div,span'); }

  // 在候选元素里找「文字精确匹配 + 可见 + 未禁用 + 面积最小」的那个（最小面积 ≈ 真正的按钮）
  function findBtn(re) {
    var all = pool(), best = null, bestScore = -1e9;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!vis(el)) continue;
      if (el.getAttribute('data-ac-skip')) continue;
      var t = txt(el);
      if (!t || t.length > 32 || !re.test(t) || BLACK.test(t)) continue;
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
      if (el.className && /(^|[ _-])disabled([ _-]|$)/i.test(String(el.className))) continue;
      var r = el.getBoundingClientRect(), area = r.width * r.height;
      if (area < 40) continue;

      var tag = String(el.tagName || '').toLowerCase();
      var role = String(el.getAttribute('role') || '').toLowerCase();
      var score = 0;
      if (tag === 'button') score += 1000;
      else if (role === 'button') score += 900;
      else if (tag === 'a') score += 700;
      else score += 100;
      if (el.onclick || el.getAttribute('onclick')) score += 100;
      if (el.tabIndex >= 0) score += 20;
      score -= Math.min(area / 100000, 20);
      if (score > bestScore) { best = el; bestScore = score; }
    }
    return best;
  }

  function cardOf(el) {
    return el.closest('.task-card') || el.closest('[class*="task-card"]') ||
           el.closest('li') || document.body;
  }

  // 卡片指纹：数字归一化，避免「剩余 120 秒 / 118 秒」这类变化让 key 抖动。
  // 用作冷却 key，也用作「领取按钮是否消失」的比对依据。
  function sigOf(el) {
    var c = cardOf(el);
    var src = (c && c !== document.body) ? txt(c) : txt(el);
    return src.replace(/\d+/g, '#').slice(0, 64);
  }

  // ---------- 冷却：同一张卡片不允许被无限重点击 ----------
  // 服务端没确认时（风控/状态机未推进），3 秒后再扫到同一个按钮会再次硬点，
  // 表现为反复刷 grant-award 并触发 5610401~5610404。这里用「时间冷却 + 连败上限」挡住。
  var cool = {};
  var failCnt = {};

  function onCool(key) { return (cool[key] || 0) > Date.now(); }

  function waitCool(key) {
    var left = (cool[key] || 0) - Date.now();
    var ms = Math.min(Math.max(left + 300, 800), 20000);
    log('cooldown ' + Math.round(ms / 1000) + 's key=' + key);
    return sleep(ms);
  }

  function markOk(key) { failCnt[key] = 0; cool[key] = 0; }

  function markFail(key, el, maxFails, cooldownMs) {
    failCnt[key] = (failCnt[key] || 0) + 1;
    if (failCnt[key] >= maxFails) {
      if (el) el.setAttribute('data-ac-skip', '1');
      log('SKIP giving up after ' + failCnt[key] + ' failures: ' + key);
    } else {
      cool[key] = Date.now() + cooldownMs;
    }
  }

  // 从卡片文案里解析「浏览 N 秒」。
  // 负向后顾排除「已浏览 45 秒 / 剩余 120 秒 / 还剩 30 秒」这类进度文案——
  // 它们描述的是当前进度，不是任务要求的停留时长，误抓会导致提前返回、服务端判未完成。
  function dwellOf(card) {
    var m = /(?<![已剩还当经]\s*)(?:浏览|停留|体验|观看|阅读|使用|打开)\s*\D{0,8}?(\d+)\s*秒/
              .exec(txt(card));
    var n = m ? parseInt(m[1], 10) : 0;
    if (!n) n = B.dwell();
    return Math.max(15, Math.min(n, 300));
  }

  // 碎片计数：官方 id 可能随活动配置变动，这里按优先级试一组选择器。
  var FRAG_SEL = [
    '#fragment-num',
    '[class*="fragment-num"]',
    '[class*="fragment-count"]',
    '[class*="fragmentCount"]',
    '[class*="coin-num"]',
    '[class*="coinCount"]'
  ];

  function fragEl() {
    for (var i = 0; i < FRAG_SEL.length; i++) {
      try {
        var el = document.querySelector(FRAG_SEL[i]);
        if (el) return el;
      } catch (e) {}
    }
    return null;
  }

  function frag() {
    var el = fragEl();
    if (!el) return 0;
    var m = /(\d+)/.exec(txt(el));
    return m ? parseInt(m[1], 10) : 0;
  }

  function fragFound() { return !!fragEl(); }

  // 尽力从卡片里挖出目标包名：链接 / data-* 属性 / market 深链 / 内联 JSON。
  // 用来绕开「云服务进程没有 QUERY_ALL_PACKAGES 时 getInstalledPackages 看不全新装包」的问题。
  var PKG_RE = /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/;

  function pkgOf(card) {
    if (!card || !card.querySelectorAll) return '';
    var nodes = card.querySelectorAll('a,[data-package],[data-pkg],[data-app]');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var dp = n.getAttribute('data-package') || n.getAttribute('data-pkg') || n.getAttribute('data-app');
      if (dp && PKG_RE.test(dp)) return dp;
      var m = /[?&]id=([A-Za-z0-9_.]+)/.exec(n.getAttribute('href') || '');
      if (m && PKG_RE.test(m[1])) return m[1];
    }
    var html = '';
    try { html = String(card.outerHTML || ''); } catch (e) {}
    var m2 = /market:\/\/details\?id=([A-Za-z0-9_.]+)/.exec(html);
    if (m2 && PKG_RE.test(m2[1])) return m2[1];
    var m3 = /["']package["']\s*:\s*["']([A-Za-z0-9_.]+)["']/.exec(html);
    if (m3 && PKG_RE.test(m3[1])) return m3[1];
    return '';
  }

  function click(el) {
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    el.click();
  }

  function scrollNext() {
    try { window.scrollBy(0, Math.max(280, Math.floor(window.innerHeight * 0.55))); } catch (e) {}
  }

  function packageDiff(before) {
    var now = B.installedPackages();
    if (!now) return '';
    var oldSet = {};
    var a = String(before || '').split(',');
    for (var i = 0; i < a.length; i++) if (a[i]) oldSet[a[i]] = true;
    var out = [];
    var b = now.split(',');
    for (var j = 0; j < b.length; j++) {
      if (b[j] && !oldSet[b[j]]) out.push(b[j]);
    }
    return out.join(',');
  }

  function dump() {
    log('DUMP fragments=' + frag() + ' counter=' + (fragFound() ? 'found' : 'MISSING'));
    if (!fragFound()) {
      log('DUMP frag-selector MISS — 领取确认会退化为「按钮消失 / TaskWall 回执」两条通道');
    }
    var list = document.querySelector('.task-card-list') ||
               document.querySelector('[class*="task-card"]');
    log('DUMP html=' + (list ? String(list.outerHTML).slice(0, 4000) : 'none'));

    // 优先输出「看起来像按钮」的元素，噪音小，直接拿来补 CLAIM/INSTALL/OPEN/GO 四个正则
    var all = pool(), seen = {}, cand = [], other = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!vis(el)) continue;
      var t = txt(el);
      if (!t || t.length > 12) continue;
      var tag = String(el.tagName || '').toLowerCase();
      var role = String(el.getAttribute('role') || '').toLowerCase();
      if (BLACK.test(t)) continue;
      if (tag === 'button' || tag === 'a' || role === 'button') {
        if (!seen[t]) { seen[t] = true; cand.push(tag + ':' + t); }
      } else if (!seen[t]) {
        seen[t] = true;
        other.push(t);
      }
    }
    log('DUMP action-candidates=' + (cand.length ? cand.join(' | ') : 'none'));
    log('DUMP other-text=' + (other.length ? other.slice(0, 60).join(' | ') : 'none'));
  }

  // 领取成功后（服务端已确认）再考虑卸载本次新装的应用
  function cleanupNewPackages() {
    if (!B.autoUninstall()) return;
    var pkgs = S.newPkgs;
    if (!pkgs && S.lastT) pkgs = B.newPackagesSince(S.lastT);
    if (!pkgs) return;
    var arr = pkgs.split(',');
    for (var i = 0; i < arr.length; i++) {
      if (!arr[i]) continue;
      log('uninstall ' + arr[i]);
      B.uninstall(arr[i]);
    }
    S.newPkgs = '';
    S.lastT = 0;
  }

  // 领取确认的第三条证据：点击前记录卡片指纹，等待窗口结束后同指纹卡片里
  // 是否还留着「领取」类按钮。服务端推进状态后按钮会换成「已完成」或直接消失。
  function claimStillPresent(sig) {
    var all = pool();
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!vis(el)) continue;
      if (el.getAttribute('data-ac-skip')) continue;
      var t = txt(el);
      if (!t || t.length > 32 || !CLAIM.test(t) || BLACK.test(t)) continue;
      if (sigOf(el) === sig) return true;
    }
    return false;
  }

  // 服务端推进后，卡片文案里通常会出现完成态标记。有标记时可以直接判定成功，
  // 比「按钮消失」更强——按钮消失也可能是页面重渲染的瞬时状态。
  var DONE_ON_CARD = /已完成|已领取|领取成功|已完成任务|明日再来|明天再来|已做|做过/;

  function cardMarkedDone(sig) {
    var all = pool();
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!vis(el)) continue;
      if (sigOf(el) !== sig) continue;
      if (DONE_ON_CARD.test(txt(el))) return true;
    }
    return false;
  }

  /**
   * 第三条证据的稳妥判定。
   *
   * 只查一次「按钮消失」不够：SPA 重渲染时按钮会短暂消失又出现，会被误判成成功，
   * 进而错误计数并触发卸载。这里要求连续两次采样都确认消失（间隔 2 秒），
   * 或者卡片上出现了明确的完成态文案。
   */
  async function claimSettled(sig) {
    if (cardMarkedDone(sig)) return true;
    if (claimStillPresent(sig)) return false;
    await sleep(2000);
    if (cardMarkedDone(sig)) return true;
    return !claimStillPresent(sig);
  }

  async function doClaim(claim) {
    var key = 'CLAIM|' + sigOf(claim);
    if (onCool(key)) { await waitCool(key); return; }

    S.busy = 0;
    var sig = sigOf(claim);
    var before = frag();
    var resultBefore = B.taskResultVersion();
    log('click CLAIM "' + txt(claim) + '" frag=' + before +
        ' counter=' + (fragFound() ? 'found' : 'MISSING') +
        ' taskResult=' + resultBefore);
    st('领取中…');
    click(claim);

    // 优先相信服务端/SDK 回执；页面数字刷新与按钮消失属于辅助证据。
    // 11.3.5 TaskWall SDK 的成功码是 200（反编译核对 jr/d.java:64 `code == 200`）。
    // 注意：这条通道只在 z11=true 路径触发，profit 页的 startCoinTaskWallPage 走 z11=false，
    // 通常拿不到回执——领取确认主要靠碎片数变化与领取按钮消失这两条 DOM 证据。
    var success = false;
    var after = before;
    var evidence = '';
    for (var wait = 0; wait < 15; wait++) {
      await sleep(1000);
      after = frag();
      if (after > before) {
        evidence = 'fragments ' + before + ' -> ' + after;
        success = true;
        break;
      }

      var resultNow = B.taskResultVersion();
      if (resultNow > resultBefore) {
        var rc = B.taskResultCode();
        var rm = B.taskResultMessage();
        var sku = B.taskResultSkuId();
        var trace = B.taskResultTraceId();
        log('TaskWall result code=' + rc + ' skuId=' + sku +
            ' traceId=' + trace + ' msg=' + rm);
        if (rc === B.taskWallSuccessCode()) {
          evidence = 'TaskWall SDK code=' + rc;
          success = true;
          break;
        }
      }
    }

    // 第三条证据：同指纹卡片里已经没有「领取」类按钮，或出现了完成态文案。
    // claimSettled 会做两次采样，避免把 SPA 重渲染时按钮的瞬时消失当成成功。
    if (!success && (await claimSettled(sig))) {
      evidence = cardMarkedDone(sig) ? 'card marked done' : 'claim button disappeared';
      success = true;
      log('claim confirmed by ' + evidence);
    }

    if (success) {
      S.claimed++;
      S.idle = 0;
      markOk(key);
      st('领取成功 · ' + after + ' 碎片');
      log('claim ok by ' + evidence + ' claimed=' + S.claimed);
      cleanupNewPackages();
    } else {
      log('claim not confirmed after 15s (evidence=none); cooling down');
      markFail(key, claim, 3, 20000);
      st('等待服务端确认…');
      await sleep(2500);
    }
    await sleep(1000);
  }

  async function doOpen(open) {
    var key = 'OPEN|' + sigOf(open);
    if (onCool(key)) { await waitCool(key); return; }

    S.busy = 0;
    var oc = cardOf(open);
    var sec = dwellOf(oc);
    var trackBefore = B.sdkTrackVersion();

    // 先在 Kotlin 主线程排一次延时切前台。WebView 的 JS 定时器在应用退后台后
    // 可能被厂商（尤其 ColorOS）冻结，若只靠下面的 bringToFront 会永远执行不到。
    try { B.scheduleReturn(sec + 5); } catch (e) {}

    log('click OPEN "' + txt(open) + '" fallbackDwell=' + sec + 's');
    st('正在启动任务应用');
    click(open);

    // H5 调用 pay.openAppCountTime 后，SDK 会立即记录 package + remainingTime。
    var sdkStarted = false;
    for (var startWait = 0; startWait < 20; startWait++) {
      await sleep(100);
      if (B.sdkTrackVersion() > trackBefore) {
        var sdkSec = B.sdkTrackRequiredSec();
        if (sdkSec > 0 && sdkSec <= 600) {
          sec = sdkSec;
          sdkStarted = true;
          try { B.cancelReturn(); B.scheduleReturn(sdkSec + 3); } catch (e) {}
          log('SDK timing captured package=' + B.sdkTrackPackage() +
              ' required=' + sec + 's');
        }
        break;
      }
    }

    st('浏览计时中 ' + sec + ' 秒' + (sdkStarted ? ' · SDK计时' : ''));
    await sleep(sec * 1000);

    // 返回云服务后，SDK 会在 Activity.onResume 回调中给出 duration/timeLeft。
    // Use the version captured before clicking OPEN. The SDK result can arrive
    // while the external app is foregrounded, before this WebView resumes.
    // Comparing against a snapshot taken here would miss that already-delivered result.
    try { B.cancelReturn(); } catch (e) {}
    B.bringToFront();

    var sdkVerified = false;
    var left = -1;
    for (var resultWait = 0; resultWait < 80; resultWait++) {
      var resultNow = B.sdkTrackVersion();
      var duration = B.sdkTrackDurationSec();
      left = B.sdkTrackTimeLeftSec();
      // A start-tracking callback also bumps the version. Only accept an actual
      // result payload, not the start event or stale values from a previous task.
      if (resultNow > trackBefore && duration >= 0 && left >= 0) {
        log('SDK timing result package=' + B.sdkTrackPackage() +
            ' duration=' + duration + 's timeLeft=' + left + 's');
        if (left <= 0) {
          sdkVerified = true;
          st('SDK 已确认计时完成');
        } else {
          st('SDK 判定仍差 ' + left + ' 秒');
        }
        break;
      }
      await sleep(100);
    }

    if (sdkVerified) {
      markOk(key);
    } else if (left > 0) {
      // 已经回到云服务且 SDK 明确报告不足时长，不把任务当成完成。
      // 不额外空等；冷却住这张卡片，下一轮扫描会去处理别的任务。
      st('计时不足，未确认完成');
      markFail(key, open, 3, 15000);
    } else {
      // 某些任务不会走 SDK 回调，保留原有页面流程作为兼容 fallback。
      await sleep(1500);
      st('已回到云服务 · 等待任务刷新');
      markOk(key);
    }

    await sleep(1500);
    S.idle = 0;
  }

  async function doInstall(inst) {
    var key = 'INSTALL|' + sigOf(inst);
    if (onCool(key)) { await waitCool(key); return; }

    S.busy = 0;
    var ic = cardOf(inst);
    if (/注册/.test(txt(ic)) && !/下载/.test(txt(ic))) {
      log('SKIP manual-registration: ' + txt(ic).slice(0, 80));
      inst.setAttribute('data-ac-skip', '1');
      S.idle = 0;
      await sleep(800);
      return;
    }

    var wantPkg = pkgOf(ic);
    // 以安装前包集合为基准做差集；这比 firstInstallTime 可靠，尤其是恢复备份/系统时间异常时。
    S.beforePkgs = B.installedPackages();
    S.lastT = B.now();
    log('click INSTALL "' + txt(inst) + '" wantPkg=' + (wantPkg || 'unknown') +
        ' baselinePkgs=' + String(S.beforePkgs || '').split(',').filter(Boolean).length);
    st('下载安装中');
    click(inst);

    var installed = '';
    for (var i = 0; i < 90; i++) {
      await sleep(2000);
      installed = packageDiff(S.beforePkgs);
      // 差集为空时退回「已知包名单点查询」：这一步不受包可见性列表限制
      if (!installed && wantPkg && B.isInstalled(wantPkg)) {
        installed = wantPkg;
        log('install detected by package probe: ' + wantPkg);
      }
      if (installed) {
        log('installed new package(s): ' + installed);
        st('已安装 · 待打开');
        break;
      }
      if (!B.isRunning()) break;
    }

    if (!installed) {
      log('install timeout: no new package detected (wantPkg=' +
          (wantPkg || 'unknown') + ')');
      markFail(key, inst, 2, 30000);
    } else {
      markOk(key);
      S.newPkgs = installed;
    }
    S.beforePkgs = '';
    S.idle = 0;
    await sleep(2000);
  }

  async function doGo(go) {
    var key = 'GO|' + sigOf(go);
    if (onCool(key)) { await waitCool(key); return; }

    S.busy = 0;
    var gc = cardOf(go);
    if (/注册/.test(txt(gc))) {
      log('SKIP manual-registration task: ' + txt(gc).slice(0, 80));
      go.setAttribute('data-ac-skip', '1');
      S.idle = 0;
      return;
    }

    var gsec = dwellOf(gc);
    log('click GO "' + txt(go) + '" wait=' + gsec + 's');
    click(go);
    await sleep(gsec * 1000 + 3000);
    markOk(key);
    S.idle = 0;
  }

  // 主循环。外层 loop() 负责 try/finally 复位 S.run：
  // 一旦这里抛异常，S.run 若停在 true，kick / setInterval / MutationObserver
  // 三条唤醒路径会同时失效，脚本永久假死（日志停在 START 后一动不动）。
  async function scanOnce() {
    S.started = Date.now();
    S.claimed = 0;
    S.idle = 0;
    S.busy = 0;
    S.newPkgs = '';
    S.stop = '';
    cool = {};
    failCnt = {};
    log('START max=' + B.maxTasks() + ' dwell=' + B.dwell() +
        ' uninstall=' + B.autoUninstall() + ' dry=' + B.dryRun() +
        ' fragCounter=' + (fragFound() ? 'found' : 'MISSING'));
    st(B.dryRun() ? '演示模式' : '运行中');

    if (B.dryRun()) { dump(); fin('演示模式完成'); return; }

    while (S.run) {
      if (Date.now() - S.started > 40 * 60 * 1000) { log('STOP watchdog 40min'); S.stop = '已停止 · 看门狗超时'; break; }
      if (!B.isRunning()) { log('STOP trigger off'); S.stop = '已停止'; break; }
      if (S.claimed >= B.maxTasks()) { log('STOP reached max=' + B.maxTasks()); S.stop = '完成 · 已达上限'; break; }

      // 1) 优先领取（只有服务端已置为可领取时才会出现这个按钮）
      var claim = findBtn(CLAIM);
      if (claim) { await doClaim(claim); continue; }

      // 2) 打开应用：优先使用 11.3.5 TaskWall SDK 给出的真实 remainingTime，
      //    页面文案只作为 SDK 未介入时的 fallback。
      var open = findBtn(OPEN);
      if (open) { await doOpen(open); continue; }

      // 3) 触发下载/安装（真实下载，由应用商店与服务端归因校验）
      var inst = findBtn(INSTALL);
      if (inst) { await doInstall(inst); continue; }

      // 4) 通用「去完成/去浏览」：页内浏览型任务
      var go = findBtn(GO);
      if (go) { await doGo(go); continue; }

      // 5) 处理中（下载中/审核中…）：最多等 4 分钟，仍卡住就收工
      if (findBtn(BUSY)) {
        S.busy++;
        if (S.busy > 40) { log('STOP busy timeout'); S.stop = '停止 · 等待处理超时'; break; }
        await sleep(6000);
        continue;
      }

      S.idle++;
      if (S.idle > 18) {
        log('STOP no actionable task left after extended scan');
        S.stop = '无可做任务 · 已停止';
        break;
      }
      scrollNext();
      await sleep(S.idle < 5 ? 2500 : 4000);
    }

    log('END claimed=' + S.claimed + ' fragments=' + frag());
    fin(S.stop || ('已停止 · 本次领取 ' + S.claimed + ' 次'));
  }

  async function loop() {
    S.run = true;
    try {
      await scanOnce();
    } catch (e) {
      log('FATAL loop error: ' + String(e && (e.stack || e.message || e)));
      S.stop = '异常终止 · 详见日志';
      // 必须同时结束运行态。scanOnce 是在 fin() 之前抛出来的，
      // 若 State.running 留在 true，下面 3 秒一轮的触发器会无限重启并反复刷同一个异常。
      try { B.finishRun(); } catch (e2) {}
      st(S.stop);
    } finally {
      // 无论正常结束还是抛异常，都必须复位，否则脚本永久假死
      S.run = false;
    }
  }

  S.kick = function () { if (S.run) return; loop(); };

  // 福利中心是 SPA：pushState/replaceState 不一定触发 onPageFinished。
  // 在页面内监听路由变化，重新唤醒扫描器，避免进入福利中心子路由后“看得到任务但不执行”。
  function hookHistory(name) {
    try {
      var old = history[name];
      if (!old.__acWrapped) {
        var wrapped = function () {
          var r = old.apply(this, arguments);
          setTimeout(function () {
            if (location.href !== S.lastUrl) {
              S.lastUrl = location.href;
              log('SPA route changed: ' + location.href);
            }
            S.kick();
          }, 300);
          return r;
        };
        wrapped.__acWrapped = true;
        history[name] = wrapped;
      }
    } catch (e) { log('history hook failed: ' + name); }
  }
  hookHistory('pushState');
  hookHistory('replaceState');
  window.addEventListener('popstate', function () {
    setTimeout(function () { S.lastUrl = location.href; S.kick(); }, 300);
  });

  try {
    var acObserver = new MutationObserver(function () {
      if (!S.run && B.isRunning()) S.kick();
    });
    acObserver.observe(document.documentElement || document.body, {
      childList: true, subtree: true
    });
  } catch (e) { log('mutation observer failed: ' + e); }

  if (B.isRunning()) { setTimeout(function () { S.kick(); }, 800); }

  // 页面一直开着时，面板点「开始」也能把循环拉起来（3 秒轮询一次触发标志）
  setInterval(function () {
    if (!S.run && B.isRunning()) { log('restart by trigger'); S.kick(); }
  }, 3000);
})();
"""
}
