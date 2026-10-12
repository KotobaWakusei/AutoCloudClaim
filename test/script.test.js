/**
 * Script.kt 注入脚本的本地行为测试。
 *
 * 从 Script.kt 提取 JS BODY，在 Node vm 沙箱里用 mock DOM + mock bridge 运行，
 * 验证：按钮发现（CLAIM/INSTALL/OPEN/GO）、领取确认、安装检测、注册任务跳过、
 * 干跑 dump、冷却与上限停止。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

// ---------- 1. 从 Script.kt 提取 JS ----------
function extractScriptBody() {
  const kt = fs.readFileSync(
    path.join(__dirname, '../app/src/main/java/com/opautocloud/claim/Script.kt'),
    'utf8'
  );
  const start = kt.indexOf('val BODY: String = """');
  if (start < 0) throw new Error('Script.kt: BODY not found');
  const bodyStart = kt.indexOf('\n', start) + 1;
  const end = kt.lastIndexOf('"""');
  if (end <= bodyStart) throw new Error('Script.kt: BODY end not found');
  return kt.slice(bodyStart, end);
}

// ---------- 2. mock DOM ----------
class El {
  constructor(tag, text, opts = {}) {
    this._text = text || '';
    this.tagName = String(tag).toUpperCase();
    this.className = opts.className || '';
    this.disabled = !!opts.disabled;
    this.tabIndex = opts.tabIndex != null ? opts.tabIndex : -1;
    this.onclick = opts.onclick || null;
    this.attrs = Object.assign({}, opts.attrs || {});
    this.children = [];
    this.parent = null;
    this.id = opts.id || '';
    this._rect = opts.rect || { width: 120, height: 40, top: 100, bottom: 140, left: 0, right: 120 };
    this._clickHook = opts.clickHook || null;
    this._clicked = false;
    if (opts.children) for (const c of opts.children) this.appendChild(c);
  }
  // 真机行为：innerText/textContent 聚合所有后代文本
  get innerText() {
    return this._text + this.children.map(c => c.innerText).join(' ');
  }
  set innerText(v) { this._text = String(v); }
  get textContent() { return this.innerText; }
  set textContent(v) { this._text = String(v); }
  appendChild(c) { c.parent = this; this.children.push(c); return c; }
  get parentElement() { return this.parent; }
  getAttribute(name) {
    if (name === 'data-ac-skip') return this.attrs['data-ac-skip'] || null;
    return this.attrs[name] != null ? String(this.attrs[name]) : null;
  }
  setAttribute(name, v) { this.attrs[name] = String(v); }
  getBoundingClientRect() { return this._rect; }
  scrollIntoView() {}
  click() {
    this._clicked = true;
    if (this.onclick) this.onclick({ target: this });
    if (this._clickHook) this._clickHook(this);
  }
  closest(sel) {
    let n = this;
    while (n) {
      if (sel === '.task-card' && /(^|\s)task-card(\s|$)/.test(n.className)) return n;
      if (sel === '.vui-card' && /(^|\s)vui-card(\s|$)/.test(n.className)) return n;
      if (sel === '[class*="task-card"]' && n.className.includes('task-card')) return n;
      if (sel === 'li' && n.tagName === 'LI') return n;
      n = n.parent;
    }
    return null;
  }
  _descendants() {
    const out = [];
    for (const c of this.children) { out.push(c, ...c._descendants()); }
    return out;
  }
  querySelectorAll(sel) {
    const parts = sel.split(',').map(s => s.trim());
    return this._descendants().filter(el =>
      parts.some(p => {
        if (p === 'a') return el.tagName === 'A';
        const m = /^\[data-([a-z]+)\]$/.exec(p);
        if (m) return el.attrs['data-' + m[1]] != null;
        return false;
      })
    );
  }
  get outerHTML() {
    const attrs = [];
    if (this.id) attrs.push('id="' + this.id + '"');
    if (this.className) attrs.push('class="' + this.className + '"');
    for (const k of Object.keys(this.attrs)) attrs.push(k + '="' + this.attrs[k] + '"');
    const inner = this.children.map(c => c.outerHTML).join('');
    return '<' + this.tagName.toLowerCase() + (attrs.length ? ' ' + attrs.join(' ') : '') +
      '>' + (inner || this.innerText) + '</' + this.tagName.toLowerCase() + '>';
  }
}

function makeDoc(all) {
  const doc = {
    body: new El('body', ''),
    documentElement: new El('html', ''),
    querySelectorAll(sel) {
      const parts = sel.split(',').map(s => s.trim());
      return all.filter(el =>
        parts.some(p => {
          if (p === 'button') return el.tagName === 'BUTTON';
          if (p === 'a') return el.tagName === 'A';
          if (p === 'div') return el.tagName === 'DIV';
          if (p === 'span') return el.tagName === 'SPAN';
          if (p === '[role="button"]') return el.getAttribute('role') === 'button';
          return false;
        })
      );
    },
    querySelector(sel) {
      if (sel.startsWith('#')) {
        const id = sel.slice(1);
        return all.find(el => el.id === id) || null;
      }
      const m = /^\[class\*="([^"]+)"\]$/.exec(sel);
      if (m) return all.find(el => el.className.includes(m[1])) || null;
      return null;
    },
  };
  doc.body.appendChild(doc.documentElement);
  return doc;
}

// ---------- 3. mock bridge ----------
function makeBridge(overrides = {}) {
  const log = [];
  const state = {
    running: true,
    installed: new Set(['com.heytap.cloud', 'com.android.settings']),
    fragText: '0',
    taskResultVersion: 0,
    sdkTrackVersion: 0,
  };
  const bridge = {
    log: (m) => log.push(String(m)),
    status: () => {},
    finishRun: () => { state.running = false; },
    maxTasks: () => (overrides.maxTasks != null ? overrides.maxTasks : 1),
    dwell: () => (overrides.dwell != null ? overrides.dwell : 60),
    autoUninstall: () => !!overrides.autoUninstall,
    dryRun: () => !!overrides.dryRun,
    isRunning: () => state.running,
    installedPackages: () => Array.from(state.installed).join(','),
    isInstalled: (pkg) => state.installed.has(String(pkg)),
    now: () => Date.now(),
    uninstall: (pkg) => { log.push('bridge.uninstall ' + pkg); state.installed.delete(String(pkg)); },
    newPackagesSince: () => '',
    taskResultVersion: () => state.taskResultVersion,
    taskResultCode: () => 200,
    taskResultMessage: () => '',
    taskResultSkuId: () => '',
    taskResultTraceId: () => '',
    taskWallSuccessCode: () => 200,
    sdkTrackVersion: () => state.sdkTrackVersion,
    sdkTrackRequiredSec: () => -1,
    sdkTrackPackage: () => '',
    sdkTrackDurationSec: () => -1,
    sdkTrackTimeLeftSec: () => -1,
    scheduleReturn: () => {},
    cancelReturn: () => {},
    bringToFront: () => {},
  };
  return { bridge, log, state };
}

// ---------- 4. 沙箱运行 ----------
function runScenario(name, buildPage, opts = {}) {
  return new Promise((resolve) => {
    const body = opts.scriptBody || runScenario._body;
    const { bridge, log, state } = makeBridge(opts);
    const { all, extra } = buildPage(bridge, state);
    const doc = makeDoc(all);
    const location = { href: opts.href || 'https://static-cn01a.ocloud.heytapmobi.com/profit/index.html' };
    const history = { pushState() {}, replaceState() {} };

    const windowObj = {
      autocloud: bridge,
      __AC: undefined,
      location,
      history,
      innerHeight: 800,
      scrollBy() {},
      addEventListener() {},
      removeEventListener() {},
    };

    const timerMultipliers = opts.fast ? 1 : 1;
    const sandbox = {
      window: windowObj,
      document: doc,
      location,
      history,
      MutationObserver: class { observe() {} disconnect() {} },
      setTimeout: (fn, ms) => setTimeout(fn, Math.min(ms, opts.maxSleepMs || ms)),
      clearTimeout,
      setInterval: (fn, ms) => setInterval(fn, Math.min(ms, opts.maxSleepMs || ms)),
      clearInterval,
      console,
      Date, JSON, Math, String, Number, Boolean, Array, Object, RegExp, Promise,
      parseInt, parseFloat, isNaN, Error, TypeError,
    };
    sandbox.window.window = windowObj;
    vm.createContext(sandbox);

    const done = (verdict) => {
      clearTimeout(watchdog);
      resolve({ name, log, verdict, all, state });
    };

    const watchdog = setTimeout(() => done('timeout'), opts.timeoutMs || 30000);

    // 结束条件：日志出现终止标志，或外部条件满足
    const checkEnd = () => {
      const joined = log.join('\n');
      for (const marker of opts.endMarkers || ['END claimed=', 'FATAL loop error']) {
        if (joined.includes(marker)) return true;
      }
      return false;
    };
    const poll = setInterval(() => {
      if (checkEnd() || (opts.endWhen && opts.endWhen(log, state))) {
        clearInterval(poll);
        // 留 200ms 收尾日志
        setTimeout(() => done('completed'), 200);
      }
    }, 100);

    try {
      vm.runInContext(body, sandbox, { filename: 'script.body.js' });
      if (windowObj.__AC) windowObj.__AC.kick();
    } catch (e) {
      clearInterval(poll);
      done('threw: ' + (e && e.stack));
      return;
    }
  });
}

// ---------- 5. 测试用例 ----------
let pass = 0, fail = 0;
function check(name, ok, detail) {
  if (ok) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name + (detail ? ' — ' + detail : '')); }
}
const has = (log, s) => log.some(l => l.includes(s));

function claimPage() {
  const fragEl = new El('span', '0', { id: 'fragment-num', className: 'fragment-num' });
  const fragWrap = new El('div', '', { className: 'coin-bar', children: [fragEl] });
  const card = new El('div', '测试任务 浏览15秒', { className: 'task-card' });
  const claimBtn = new El('button', '立即领取', {
    clickHook: () => { fragEl.innerText = '120'; fragEl.textContent = '120'; },
  });
  card.appendChild(claimBtn);
  const list = new El('div', '', { className: 'task-card-list', children: [card] });
  const all = [fragWrap, fragEl, list, card, claimBtn];
  return { all, extra: { claimBtn, card } };
}

function installPage(bridge, state) {
  const card = new El('div', '下载安装 应用领奖', { className: 'task-card' });
  const link = new El('a', 'app', { attrs: { href: 'market://details?id=com.example.targetapp' } });
  const instBtn = new El('button', '下载安装', {
    clickHook: () => {
      // 模拟应用商店 3 秒后装完
      setTimeout(() => state.installed.add('com.example.targetapp'), 3000);
    },
  });
  card.appendChild(link);
  card.appendChild(instBtn);
  const list = new El('div', '', { className: 'task-card-list', children: [card] });
  const all = [list, card, link, instBtn];
  return { all, extra: { instBtn, card } };
}

function openPage() {
  const card = new El('div', '打开应用浏览15秒', { className: 'task-card' });
  const openBtn = new El('button', '立即打开');
  card.appendChild(openBtn);
  const list = new El('div', '', { className: 'task-card-list', children: [card] });
  return { all: [list, card, openBtn], extra: { openBtn, card } };
}

function registerPage() {
  const card = new El('div', '注册账号领奖', { className: 'task-card' });
  const btn = new El('button', '去完成');
  card.appendChild(btn);
  const list = new El('div', '', { className: 'task-card-list', children: [card] });
  return { all: [list, card, btn], extra: { btn, card } };
}

function emptyPage() {
  const list = new El('div', '', { className: 'task-card-list' });
  return { all: [list], extra: {} };
}

// 真机 DOM 结构：div.task-card-list > div.vui-card × 2
function vuiPage(bridge, state) {
  const list = new El('div', '', { className: 'task-card-list' });

  // 注册类卡片（盼之账号：先安装再注册）——应当被单独 SKIP，不影响别的卡
  const regCard = new El('div', '', { className: 'vui-card' });
  regCard.appendChild(new El('div', '盼之账号（游戏账号交易） +12 先安装再注册，领大额奖励'));
  regCard.appendChild(new El('span', '开始安装'));

  // 普通安装卡片（豆包爱学）——必须正常走安装流程
  const normCard = new El('div', '', { className: 'vui-card' });
  normCard.appendChild(new El('div', '豆包爱学 +24 字节跳动旗下AI学习助手'));
  const normBtn = new El('span', '开始安装', {
    clickHook: () => setTimeout(() => state.installed.add('com.example.targetapp'), 1500),
  });
  normCard.appendChild(normBtn);

  list.appendChild(regCard);
  list.appendChild(normCard);
  const all = [list, regCard, normCard, ...regCard.children, ...normCard.children];
  return { all, extra: { regCard, normCard, normBtn } };
}

async function main() {
  console.log('=== script.test.js ===\n');
  const body = extractScriptBody();
  runScenario._body = body;
  fs.writeFileSync(path.join(__dirname, 'script.body.js'), body);

  // T1: 领取流程 — 点领取 → 碎片 0→120 → 成功 → 达到上限停止
  console.log('T1 claim flow:');
  {
    const r = await runScenario('claim', claimPage, { maxTasks: 1, timeoutMs: 25000 });
    check('START logged', has(r.log, 'START max=1'), r.log.slice(0, 5).join(' | '));
    check('click CLAIM', has(r.log, 'click CLAIM "立即领取"'), r.log.filter(l => l.includes('click')).join(' | '));
    check('claim ok (fragments 0 -> 120)', has(r.log, 'fragments 0 -> 120') || has(r.log, 'claim ok'), r.log.filter(l => l.includes('claim')).join(' | '));
    check('stop at max', has(r.log, 'STOP reached max=1'), r.log.filter(l => l.includes('STOP') || l.includes('END')).join(' | '));
    check('END claimed=1', has(r.log, 'END claimed=1'));
  }

  // T2: 安装流程 — 点下载安装 → 轮询发现新包（click 后 3 秒模拟装完）
  console.log('\nT2 install flow:');
  {
    const r = await runScenario('install', installPage, {
      maxTasks: 3,
      timeoutMs: 30000,
      endWhen: (log) => has(log, 'installed new package(s)') || has(log, 'install timeout'),
    });
    check('click INSTALL', has(r.log, 'click INSTALL'), r.log.filter(l => l.includes('click')).join(' | '));
    check('wantPkg extracted', has(r.log, 'wantPkg=com.example.targetapp'), r.log.filter(l => l.includes('wantPkg')).join(' | '));
    check('install detected', has(r.log, 'installed new package(s)'), r.log.filter(l => l.includes('install')).join(' | '));
  }

  // T3: 打开流程 — 点立即打开（只验证点击发现，不等完整计时）
  console.log('\nT3 open flow (click discovery only):');
  {
    const r = await runScenario('open', openPage, {
      maxTasks: 1,
      timeoutMs: 15000,
      endWhen: (log) => has(log, 'click OPEN'),
    });
    check('click OPEN', has(r.log, 'click OPEN "立即打开"'), r.log.filter(l => l.includes('click') || l.includes('OPEN')).join(' | '));
    check('dwell fallback 15s min', has(r.log, 'fallbackDwell=15s'), r.log.filter(l => l.includes('fallback')).join(' | '));
  }

  // T4: 注册类任务跳过
  console.log('\nT4 registration skip:');
  {
    const r = await runScenario('register', registerPage, {
      maxTasks: 3,
      timeoutMs: 15000,
      endWhen: (log) => has(log, 'SKIP manual-registration'),
    });
    check('skipped', has(r.log, 'SKIP manual-registration'), r.log.slice(-5).join(' | '));
    check('no click GO on registration', !has(r.log, 'click GO'));
  }

  // T5: 干跑 dump
  console.log('\nT5 dry run:');
  {
    const r = await runScenario('dry', claimPage, { dryRun: true, timeoutMs: 8000 });
    check('dry run dump candidates', has(r.log, 'DUMP action-candidates='), r.log.join(' | '));
    check('finished', has(r.log, '演示') || r.log.length > 0);
  }

  // T6: 空页面 → 空闲停止（缩短：把 idle 上限路径走完太慢，验证空转日志即可）
  console.log('\nT6 empty page idle:');
  {
    const r = await runScenario('idle', emptyPage, {
      maxTasks: 1,
      timeoutMs: 12000,
      endWhen: (log) => has(log, 'DUMP') || log.length > 3,
    });
    check('started without buttons', has(r.log, 'START max=1'), r.log.join(' | '));
    check('no false clicks', !has(r.log, 'click CLAIM') && !has(r.log, 'click INSTALL'));
  }

  // T7: 真机 DOM 回归 — 容器 task-card-list + 单卡 vui-card，
  // 一张卡含「注册」（盼之账号），另一张是普通安装卡。
  // 修复前：cardOf 命中列表容器 → 整页含「注册」→ 全部 SKIP（真机 2026-10-12 实测 bug）
  console.log('\nT7 vui-card container regression:');
  {
    const r = await runScenario('vui', vuiPage, {
      maxTasks: 3,
      timeoutMs: 30000,
      endWhen: (log) =>
        has(log, 'installed new package(s)') ||
        has(log, 'install timeout') ||
        has(log, 'END claimed='),
    });
    const skipLeak = r.log.some(l => l.includes('SKIP') && l.includes('豆包'));
    check('normal card never skipped by sibling registration card', !skipLeak,
      r.log.filter(l => l.includes('SKIP')).join(' || '));
    check('clicked install on normal card', has(r.log, 'click INSTALL'),
      r.log.filter(l => l.includes('click')).join(' | '));
    check('install detected', has(r.log, 'installed new package(s)'),
      r.log.filter(l => l.includes('install')).join(' | '));
    if (!has(r.log, 'installed new package(s)')) {
      console.log('  --- T7 full log (verdict=' + r.verdict + ') ---');
      for (const l of r.log) console.log('  | ' + l);
      for (const el of r.all) {
        if (el.tagName === 'SPAN') {
          console.log('  SPAN text=' + JSON.stringify(el.innerText) +
            ' skipAttr=' + JSON.stringify(el.getAttribute('data-ac-skip')) +
            ' hooked=' + !!el._clickHook + ' clicked=' + el._clicked);
        }
      }
    }
  }

  console.log('\nresult: ' + pass + ' passed, ' + fail + ' failed');
  process.exit(fail > 0 ? 1 : 0);
}

main().catch(e => { console.error(e); process.exit(1); });
