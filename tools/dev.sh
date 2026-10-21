#!/usr/bin/env bash
# 真机调试脚本：构建 → 安装 → 拉起云服务 → 下发 START → 抓 LSPosed 日志。
#
# 用法：
#   tools/dev.sh build            只构建 APK
#   tools/dev.sh install          构建并安装（覆盖旧版本）
#   tools/dev.sh dry              干跑：只 dump 页面结构，不点任何东西（用来校准选择器）
#   tools/dev.sh run [--max N] [--dwell S] [--uninstall on|off]
#                                 正式跑：领任务
#   tools/dev.sh log              持续抓 LSPosed 日志（Ctrl-C 退出）
#   tools/dev.sh dump             打印最近一次 dry run 的 DUMP 内容
#   tools/dev.sh stop             停止当前运行
#
# 环境变量：
#   JAVA_HOME / GRADLE           覆盖构建工具链
#   SERIAL                      指定设备（多设备时）
set -euo pipefail

cd "$(dirname "$0")/.."

PKG_MODULE="com.opautocloud.claim"
PKG_CLOUD="com.heytap.cloud"
# 不硬编码 Activity 名：模块内部用宿主自己的方法导航到福利中心，
# 这里只要把云服务 App 拉起来即可（START 到达后 AutoNav 会自己跳）。
LOG_FILE="${HOME}/.ac-dev/last-run.log"

GRADLE="${GRADLE:-gradle}"
if [ -x /home/kali/.ac-toolchain/gradle-8.14.5/bin/gradle ]; then
  GRADLE=/home/kali/.ac-toolchain/gradle-8.14.5/bin/gradle
  export JAVA_HOME="${JAVA_HOME:-/home/kali/.ac-toolchain/jdk-17}"
fi

adb_() {
  if [ -n "${SERIAL:-}" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

need_device() {
  if ! adb_ get-state >/dev/null 2>&1; then
    echo "!! 没有连上设备（adb devices 为空）。插好线并确认已授权 USB 调试。" >&2
    exit 1
  fi
}

build() {
  echo "== 构建 =="
  "$GRADLE" assembleRelease --offline -q
  ls -lh app/build/outputs/apk/release/app-release.apk
}

install_module() {
  need_device
  echo "== 安装模块 =="
  adb_ install -r app/build/outputs/apk/release/app-release.apk
  adb_ shell pm list packages | grep "$PKG_MODULE" || true
  cat <<'EOF'

-- 首次安装请手动做一次 --
  1) LSPosed 管理器 → 模块 → 勾选 AutoCloud Claim
  2) 作用域里勾选「云服务」(com.heytap.cloud)
  3) 重启云服务 App（长按图标 → 强行停止 → 再打开）
EOF
}

start_cloud() {
  need_device
  echo "== 拉起云服务 =="
  adb_ shell am force-stop "$PKG_CLOUD" >/dev/null 2>&1 || true
  sleep 1
  adb_ shell monkey -p "$PKG_CLOUD" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 3
}

send_start() {
  need_device
  local extra="$*"
  echo "== 下发 START $extra =="
  adb_ shell am broadcast -a "$PKG_MODULE.START" $extra
  sleep 2
}

capture() {
  need_device
  mkdir -p "$(dirname "$LOG_FILE")"
  echo "== 抓日志（60 秒）-> $LOG_FILE =="
  adb_ logcat -c 2>/dev/null || true
  timeout 60 adb_ logcat -v brief 2>/dev/null | grep -E "AutoCloud|AutoNav|AutoEntry|LSPosed" | tee "$LOG_FILE" || true
}

case "${1:-}" in
  build)   build ;;
  install) build; install_module ;;
  dry)
    need_device
    start_cloud
    send_start --ez dry true
    capture
    ;;
  run)
    shift || true
    need_device
    start_cloud
    send_start "$@"
    capture
    ;;
  log)
    need_device
    adb_ logcat -v brief | grep -E "AutoCloud|AutoNav|AutoEntry|LSPosed" || true
    ;;
  dump)
    if [ -f "$LOG_FILE" ]; then
      grep -E "DUMP|fragments=" "$LOG_FILE" || echo "(日志里没有 DUMP，先跑 tools/dev.sh dry)"
    else
      echo "(还没有日志文件，先跑 tools/dev.sh dry)"
    fi
    ;;
  stop)
    need_device
    adb_ shell am broadcast -a "$PKG_MODULE.STOP"
    ;;
  *)
    sed -n '2,25p' "$0"
    exit 1
    ;;
esac
