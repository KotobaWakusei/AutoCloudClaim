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
    lastT: 0, beforePkgs: '', stop: '', lastUrl: location.href
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
    var all = pool(), best = null, bestArea = 1e12;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!vis(el)) continue;
      if (el.getAttribute('data-ac-skip')) continue;   // 已判定需人工，跳过
      var t = txt(el);
      if (!t || t.length > 24) continue;
      if (!re.test(t)) continue;
      if (BLACK.test(t)) continue;
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
      if (el.className && String(el.className).indexOf('disabled') >= 0) continue;
      var r = el.getBoundingClientRect();
      var a = r.width * r.height;
      if (a < 40) continue;
      if (a < bestArea) { best = el; bestArea = a; }
    }
    return best;
  }

  function cardOf(el) {
    return el.closest('.task-card') || el.closest('[class*="task-card"]') ||
           el.closest('li') || document.body;
  }

  // 从卡片文案里解析「浏览 N 秒」
  function dwellOf(card) {
    var m = /(\d+)\s*秒/.exec(txt(card));
    var n = m ? parseInt(m[1], 10) : 0;
    if (!n) n = B.dwell();
    return Math.max(15, Math.min(n, 300));
  }

  function frag() {
    var el = document.getElementById('fragment-num');
    var v = el ? parseInt(txt(el), 10) : NaN;
    return isNaN(v) ? 0 : v;
  }

  function click(el) {
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    el.click();
  }

  function scrollNext() { window.scrollBy(0, 360); }

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
    var list = document.querySelector('.task-card-list') ||
               document.querySelector('[class*="task-card"]');
    log('DUMP html=' + (list ? String(list.outerHTML).slice(0, 4000) : 'none'));
    var all = pool(), out = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!vis(el)) continue;
      var t = txt(el);
      if (t && t.length <= 24) out.push(t);
    }
    log('DUMP buttons=' + out.join(' | '));
    log('DUMP fragments=' + frag());
  }

  // 领取成功后（服务端已确认）再考虑卸载本次新装的应用
  function cleanupNewPackages() {
    if (!B.autoUninstall() || !S.lastT) return;
    var pkgs = B.newPackagesSince(S.lastT);
    if (!pkgs) return;
    var arr = pkgs.split(',');
    for (var i = 0; i < arr.length; i++) {
      if (!arr[i]) continue;
      log('uninstall ' + arr[i]);
      B.uninstall(arr[i]);
    }
    S.lastT = 0;
  }

  async function loop() {
    S.run = true;
    S.started = Date.now();
    S.claimed = 0;
    S.idle = 0;
    S.busy = 0;
    S.stop = '';
    log('START max=' + B.maxTasks() + ' dwell=' + B.dwell() +
        ' uninstall=' + B.autoUninstall() + ' dry=' + B.dryRun());
    st(B.dryRun() ? '演示模式' : '运行中');

    if (B.dryRun()) { dump(); S.run = false; fin('演示模式完成'); return; }

    while (S.run) {
      if (Date.now() - S.started > 40 * 60 * 1000) { log('STOP watchdog 40min'); S.stop = '已停止 · 看门狗超时'; break; }
      if (!B.isRunning()) { log('STOP trigger off'); S.stop = '已停止'; break; }
      if (S.claimed >= B.maxTasks()) { log('STOP reached max=' + B.maxTasks()); S.stop = '完成 · 已达上限'; break; }

      // 1) 优先领取（只有服务端已置为可领取时才会出现这个按钮）
      var claim = findBtn(CLAIM);
      if (claim) {
        S.busy = 0;
        var before = frag();
        log('click CLAIM "' + txt(claim) + '" frag=' + before);
        st('领取中…');
        click(claim);

        // H5 的奖励回执可能晚于点击事件；3 秒单点采样会把“已成功但 UI 尚未刷新”
        // 错判成失败。这里轮询一段时间，只在碎片数真正增加时计数。
        var success = false;
        var after = before;
        for (var wait = 0; wait < 12; wait++) {
          await sleep(1000);
          after = frag();
          if (after > before) { success = true; break; }
        }

        if (success) {
          S.claimed++;
          S.idle = 0;
          log('claim confirmed frag=' + before + ' -> ' + after);
          st('领取成功 · ' + after + ' 碎片');
          cleanupNewPackages();
        } else {
          log('claim not confirmed after 12s; keep task uncounted');
          st('等待服务端确认…');
          await sleep(2500);
        }
        await sleep(1000);
        continue;
      }

      // 2) 打开刚装好的应用，按卡片要求的时长停留，再回到云服务
      var open = findBtn(OPEN);
      if (open) {
        S.busy = 0;
        var oc = cardOf(open);
        var sec = dwellOf(oc);
        log('click OPEN "' + txt(open) + '" dwell=' + sec + 's');
        st('浏览计时中 ' + sec + ' 秒');
        click(open);
        await sleep(4000);
        await sleep(sec * 1000);
        B.bringToFront();
        await sleep(4000);
        st('已回到云服务');
        S.idle = 0;
        continue;
      }

      // 3) 触发下载/安装（真实下载，由应用商店与服务端归因校验）
      var inst = findBtn(INSTALL);
      if (inst) {
        S.busy = 0;
        var ic = cardOf(inst);
        if (/注册/.test(txt(ic)) && !/下载/.test(txt(ic))) {
          log('SKIP manual-registration: ' + txt(ic).slice(0, 80));
          inst.setAttribute('data-ac-skip', '1');
          S.idle = 0;
          await sleep(800);
          continue;
        }
        // 以安装前包集合为基准做差集；这比 firstInstallTime 可靠，尤其是恢复备份/系统时间异常时。
        S.beforePkgs = B.installedPackages();
        S.lastT = B.now();
        log('click INSTALL "' + txt(inst) + '"');
        st('下载安装中');
        click(inst);
        var installed = '';
        for (var i = 0; i < 90; i++) {
          await sleep(2000);
          installed = packageDiff(S.beforePkgs);
          if (installed) {
            log('installed new package(s): ' + installed);
            st('已安装 · 待打开');
            break;
          }
          if (!B.isRunning()) break;
        }
        if (!installed) log('install timeout: no new package detected');
        S.beforePkgs = '';
        S.idle = 0;
        await sleep(2000);
        continue;
      }

      // 4) 通用「去完成/去浏览」：页内浏览型任务
      var go = findBtn(GO);
      if (go) {
        S.busy = 0;
        var gc = cardOf(go);
        if (/注册/.test(txt(gc))) {
          log('SKIP manual-registration task: ' + txt(gc).slice(0, 80));
          go.setAttribute('data-ac-skip', '1');
          S.idle = 0;
        } else {
          var gsec = dwellOf(gc);
          log('click GO "' + txt(go) + '" wait=' + gsec + 's');
          click(go);
          await sleep(gsec * 1000 + 3000);
        }
        S.idle = 0;
        continue;
      }

      // 5) 处理中（下载中/审核中…）：最多等 4 分钟，仍卡住就收工
      if (findBtn(BUSY)) {
        S.busy++;
        if (S.busy > 40) { log('STOP busy timeout'); S.stop = '停止 · 等待处理超时'; break; }
        await sleep(6000);
        continue;
      }

      S.idle++;
      if (S.idle > 6) { log('STOP no actionable task left'); S.stop = '无可做任务 · 已停止'; break; }
      scrollNext();
      await sleep(3500);
    }

    S.run = false;
    log('END claimed=' + S.claimed + ' fragments=' + frag());
    fin(S.stop || ('已停止 · 本次领取 ' + S.claimed + ' 次'));
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

  if (B.isRunning()) { setTimeout(function () { loop(); }, 800); }

  // 页面一直开着时，面板点「开始」也能把循环拉起来（3 秒轮询一次触发标志）
  setInterval(function () {
    if (!S.run && B.isRunning()) { log('restart by trigger'); loop(); }
  }, 3000);
})();
"""
}
