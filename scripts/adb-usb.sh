#!/usr/bin/env bash
# 把 USB 连接的 Android 手机透传进 WSL2，之后直接用 WSL 里的 adb / fastboot。
#
# 依赖（Windows 侧，已装好）：usbipd-win
#   winget install --id dorssel.usbipd-win
#
# 用法：
#   scripts/adb-usb.sh list              # 列出 Windows 上可见的 USB 设备及 BUSID
#   scripts/adb-usb.sh attach 1-4        # 把指定 BUSID 的手机透传进 WSL 并等待授权
#   scripts/adb-usb.sh detach 1-4        # 从 WSL 收回给 Windows
#   scripts/adb-usb.sh adb               # 透传后直接看 adb devices
set -euo pipefail

USBIPD="/mnt/c/Program Files/usbipd-win/usbipd.exe"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

red()  { printf '\033[31m%s\033[0m\n' "$*" >&2; }
grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }
die()  { red "$*"; exit 1; }

need_usbipd() {
  [ -x "$USBIPD" ] || die "未找到 usbipd-win: $USBIPD
Windows 侧安装：winget install --id dorssel.usbipd-win"
}

wait_for_device() {
  "$ADB" start-server >/dev/null 2>&1 || true
  ylw "等待手机出现在 WSL（首次连接需要在手机上点『允许 USB 调试』）..."
  for i in $(seq 1 30); do
    state=$("$ADB" devices | awk 'NR==2{print $2}')
    case "$state" in
      device)       grn "✔ 已连接：$("$ADB" devices -l | sed -n 2p)"; "$ADB" devices -l; return 0 ;;
      unauthorized) ylw "  已识别但未授权，请在手机上确认弹窗（${i}/30）" ;;
      *)            printf '  %s/30 未就绪\n' "$i" ;;
    esac
    sleep 2
  done
  red "✘ 30 次轮询后仍未就绪。常见原因：手机没开 USB 调试 / 没点授权弹窗 / 数据线只充电。"
  "$ADB" devices -l || true
  return 1
}

cmd="${1:-list}"
case "$cmd" in
  list)
    need_usbipd
    "$USBIPD" list
    ;;
  attach)
    need_usbipd
    busid="${2:-}"
    [ -n "$busid" ] || die "用法：$0 attach <BUSID>（BUSID 见 $0 list）"
    # bind 是持久的，重复执行会提示 already bound，这里忽略即可。
    if ! "$USBIPD" bind --busid "$busid" 2>&1; then
      ylw "bind 失败：多为权限或第三方 USB 过滤驱动。尝试 --force，仍失败则用管理员 PowerShell 手动 bind。"
      "$USBIPD" bind --busid "$busid" --force || die "bind 失败，请用管理员 PowerShell 执行：usbipd bind --busid $busid --force"
    fi
    "$USBIPD" attach --wsl --busid "$busid" || die "attach 失败"
    wait_for_device
    ;;
  detach)
    need_usbipd
    busid="${2:-}"
    [ -n "$busid" ] || die "用法：$0 detach <BUSID>"
    "$USBIPD" detach --busid "$busid"
    ;;
  adb)
    "$ADB" devices -l
    ;;
  *)
    die "未知子命令：$cmd（可用：list | attach | detach | adb）"
    ;;
esac
