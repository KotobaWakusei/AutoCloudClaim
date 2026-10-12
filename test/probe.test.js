/**
 * 模拟 WebView.evaluateJavascript 回调的字符串编码行为，
 * 验证 MainHook.kt 中 probe 条件的修复是否正确。
 *
 * evaluateJavascript 回调收到的是 JSON 编码后的字符串：
 *   JS 返回 '{"bridge":"object"}'  → Kotlin 收到 "{\"bridge\":\"object\"}"
 */
'use strict';

// 模拟 evaluateJavascript：把 JS 返回值编码成 Android 回调收到的形式
function androidCallbackRaw(jsReturnValue) {
  // JSON.stringify 二次编码，模拟 evaluateJavascript 的行为
  return JSON.stringify(jsReturnValue);
}

// ---- 旧逻辑（bug）：搜真实引号，永远失败 ----
function probeOld(raw) {
  if (raw == null || raw === 'null') return false;
  return !(!raw.includes('"bridge":"object"') && !raw.includes('"bridge": "object"'));
}

// ---- 新逻辑（修复）：同时匹配转义与未转义；先判 null 再算 hasBridge ----
function probeNew(raw) {
  if (raw == null || raw === 'null') return false;
  const hasBridge =
    raw.includes('\\"bridge\\":\\"object\\"') ||
    raw.includes('\\"bridge\\": \\"object\\"') ||
    raw.includes('"bridge":"object"') ||
    raw.includes('"bridge": "object"');
  return hasBridge;
}

let pass = 0, fail = 0;
function check(name, actual, expected) {
  if (actual === expected) { pass++; console.log('  PASS ' + name); }
  else { fail++; console.log('  FAIL ' + name + ' — expected ' + expected + ', got ' + actual); }
}

console.log('=== probe.test.js ===\n');

// 场景 1：JS 返回 JSON.stringify 结果（正常路径）
const jsResult = JSON.stringify({ bridge: 'object', ac: 'undefined', readyState: 'complete', href: 'https://x/profit/' });
const raw1 = androidCallbackRaw(jsResult);
console.log('raw1 = ' + raw1);
check('old logic matches encoded string (should be false = bug)', probeOld(raw1), false);
check('new logic matches encoded string', probeNew(raw1), true);

// 场景 2：某些 WebView 直接回调未编码的 JSON（裸对象形式）
const raw2 = '{"bridge":"object","ac":"object"}';
console.log('raw2 = ' + raw2);
check('old logic matches unencoded', probeOld(raw2), true);
check('new logic matches unencoded', probeNew(raw2), true);

// 场景 3：bridge 是 undefined
const jsResult3 = JSON.stringify({ bridge: 'undefined', ac: 'undefined' });
const raw3 = androidCallbackRaw(jsResult3);
check('old rejects bridge=undefined', probeOld(raw3), false);
check('new rejects bridge=undefined', probeNew(raw3), false);

// 场景 4：null / "null"
check('new rejects null', probeNew(null), false);
check('new rejects "null"', probeNew('null'), false);

// 场景 5：probe-error
const raw5 = JSON.stringify({ bridge: 'probe-error', error: 'x' });
check('new rejects probe-error', probeNew(raw5), false);

// 场景 6：带空格的形式
const raw6 = JSON.stringify({ bridge: 'object' }).replace('":', '": ');
check('new handles "bridge": "object" escaped', probeNew(raw6), true);

console.log('\nresult: ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail > 0 ? 1 : 0);
