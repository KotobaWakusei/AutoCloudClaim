#!/usr/bin/env bash
# 在无 LSPosed 管理器 GUI 的情况下，用 adb 启用本模块并授予作用域。
#
# 原理：LSPosed 的模块启用状态与作用域都存在 /data/adb/lspd/config/modules_config.db
#   modules        —— 已安装的模块（包名 + apk 路径）
#   modules_state  —— 是否启用（enabled=1）
#   scope          —— 作用域（模块 → 宿主 App）
#
# 流程：停守护进程 → 导出库 → 打补丁 → 写回（清掉 WAL）→ 重启手机
# 重启是必须的：LSPosed 的 hook 在 zygote 启动时注入，守护进程改动无法热生效。
#
# 用法：tools/enable-module.sh [--no-reboot]
set -euo pipefail

MODULE_PKG="com.opautocloud.claim"
TARGET_PKG="com.heytap.cloud"
LSPD_DIR="/data/adb/lspd/config"
LSPD_BIN="/data/adb/modules/zygisk_lsposed"
WORK="${TMPDIR:-/tmp}/ac-lsposed-$$"

REBOOT=1
[ "${1:-}" = "--no-reboot" ] && REBOOT=0

need_device() {
  adb get-state >/dev/null 2>&1 || { echo "!! 没有连上设备" >&2; exit 1; }
}

need_device
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

echo "== 1/5 停掉 LSPosed 守护进程 =="
adb shell "su -c '$LSPD_BIN/lspctl stop'" || true

echo "== 2/5 导出配置库 =="
for f in modules_config.db modules_config.db-wal modules_config.db-shm; do
  adb pull "$LSPD_DIR/$f" "$WORK/$f" >/dev/null 2>&1 || true
done
[ -f "$WORK/modules_config.db" ] || { echo "!! 取不到配置库" >&2; exit 1; }
# 把 WAL 合并进主库，再删掉副本，保证我们改的是最新状态
cp "$WORK/modules_config.db" "$WORK/work.db"
[ -f "$WORK/modules_config.db-wal" ] && cp "$WORK/modules_config.db-wal" "$WORK/work.db-wal"
[ -f "$WORK/modules_config.db-shm" ] && cp "$WORK/modules_config.db-shm" "$WORK/work.db-shm"
sqlite3 "$WORK/work.db" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null 2>&1 || true

echo "== 3/5 写回启用状态与作用域 =="
APK_PATH="$(adb shell pm path "$MODULE_PKG" | sed -n 's/^package://p' | tr -d '\r' | head -1)"
[ -n "$APK_PATH" ] || { echo "!! 找不到模块 APK（模块没装？）" >&2; exit 1; }
echo "   模块 APK: $APK_PATH"

sqlite3 "$WORK/work.db" <<SQL
INSERT OR REPLACE INTO modules (module_pkg_name, apk_path)
  VALUES ('$MODULE_PKG', '$APK_PATH');
INSERT OR REPLACE INTO modules_state (module_pkg_name, user_id, enabled, scope_request_blocked)
  VALUES ('$MODULE_PKG', 0, 1, 0);
INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id)
  VALUES ('$MODULE_PKG', '$TARGET_PKG', 0);
SQL

sqlite3 -header "$WORK/work.db" \
  "select * from modules_state where module_pkg_name='$MODULE_PKG';
   select * from scope where module_pkg_name='$MODULE_PKG';"

echo "== 4/5 推送配置库 =="
adb push "$WORK/work.db" /data/local/tmp/modules_config.db >/dev/null
adb shell "su -c 'cp /data/local/tmp/modules_config.db $LSPD_DIR/modules_config.db &&
                    rm -f $LSPD_DIR/modules_config.db-wal $LSPD_DIR/modules_config.db-shm &&
                    rm -f /data/local/tmp/modules_config.db'"

if [ "$REBOOT" = "1" ]; then
  echo "== 5/5 重启手机（LSPosed 的 hook 在 zygote 启动时注入，必须重启）=="
  adb reboot
  echo "   已下发重启，等设备回来后用 adb wait-for-device 等待。"
else
  echo "== 5/5 跳过重启（--no-reboot）=="
fi
