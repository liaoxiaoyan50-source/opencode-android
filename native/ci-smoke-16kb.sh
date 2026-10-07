#!/usr/bin/env bash
# =============================================================================
# ci-smoke-16kb.sh — OpenCode Android 16KB page 模拟器冒烟（device-validator / P6）
#
# 定位（P1 §3.3 C3 第 3 项 + M0-3 自动化；挂入 release.yml job_smoke_16kb 注释骨架：
#       `script: bash native/ci-smoke-16kb.sh`）：
#   C3-3 原文口径：16KB 模拟器上 libproot.so 直接可执行，退出码 0。
#   本脚本同时覆盖：D4 解包验证（库确实落进 nativeLibraryDir）、16KB 内核可加载
#   两库（链接期 max-page-size=16384 第一层，P2b §2.1）、qemu 运行时 guest 页宽自适应
#   （TARGET_PAGE_BITS_VARY 第二层，P2b §2.2）、proot -q 宿主侧绝对路径约定（P2b §5-2）。
#
# 用法：
#   bash native/ci-smoke-16kb.sh /path/to/app-full-release.apk
#   SMOKE_APK=/path/to.apk bash native/ci-smoke-16kb.sh          # 环境变量等价
# 参数（均可环境变量覆盖）：
#   SMOKE_APK     full 变体 APK 路径（必填，$1 优先）
#   APK_PKG       包名，默认 dev.opencode.mobile
#   ADB           adb 可执行，默认 adb（CI 可传 $ANDROID_HOME/platform-tools/adb）
#   BOOT_TIMEOUT  设备 boot 等待秒数，默认 180
# 退出码：0=全部 PASS；1=存在 FAIL；2=环境级 FATAL（非 16KB 模拟器/无设备等）。
# 失败诊断：按输出末尾指引对照 P2b §6 特征串表归因。
# =============================================================================
set -euo pipefail

ADB="${ADB:-adb}"
APK_PKG="${APK_PKG:-dev.opencode.mobile}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"
SMOKE_APK="${1:-${SMOKE_APK:-}}"
PROOT_TMP_DEV="/data/local/tmp/opencode-smoke"   # shell 侧 PROOT_TMP_DIR（P2b §5-3）

PASS=0; FAIL=0; FATAL_REASON=""
DEV_OUT=""; DEV_RC=1

# ── 工具函数（先定义后使用，供各校验点 FATAL 时立即收口）─────────────────────
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
fatal(){ FATAL_REASON="$1"; echo "  [FATAL] $1"; }

finish_summary() {
  echo "══ 冒烟摘要 ══"
  echo "  PASS=${PASS}  FAIL=${FAIL}  ${FATAL_REASON:+FATAL=${FATAL_REASON}}"
  if [ "${FAIL}" -eq 0 ] && [ -z "${FATAL_REASON}" ]; then
    echo "  结论: PASS — D4 解包 / 16KB 内核加载 / qemu TARGET_PAGE_BITS_VARY / proot -q 约定 全部成立"
    exit 0
  fi
  echo "  诊断指引: ① 按 [FAIL] 行内特征串对照 P2b-原生编译说明 §6 处置矩阵归因；"
  echo "            ② 库缺失/未解包 → 查 useLegacyPackaging=true（P2b §4, D4）；"
  echo "            ③ Failed to map / MAP_FIXED → TARGET_PAGE_BITS_VARY 未生效（P2b §6-5, 回报 native-builder）；"
  echo "            ④ 镜像环境问题 → 见 C3/C4 提示（须 _16k 标识镜像且 abilist 含 arm64-v8a）。"
  [ -n "${FATAL_REASON}" ] && exit 2
  exit 1
}

# 设备端执行并带回传退出码：stdout 存 DEV_OUT，退出码存 DEV_RC。
# 不依赖 adb shell 的退出码透传（各 API 级别行为不一），以 __RC__ 标记解析。
dev_run() {
  local raw
  raw=$("$ADB" shell "export PROOT_TMP_DIR=${PROOT_TMP_DEV}; $1; echo __RC__=\$?" 2>&1 | sed 's/\r$//') || true
  DEV_RC=$(printf '%s\n' "$raw" | { grep -o '__RC__=[0-9]*' || true; } | tail -n1 | cut -d= -f2)
  DEV_RC="${DEV_RC:-1}"
  DEV_OUT=$(printf '%s\n' "$raw" | { grep -v '__RC__=' || true; })
}

# ════════════════════════════ 校验点 ═════════════════════════════════════════

echo "══ OpenCode 16KB 模拟器冒烟（P6 / C3-3 / M0-3）· pkg=${APK_PKG} ══"

echo "── [C1] adb 运行环境 ──"
command -v "$ADB" >/dev/null 2>&1 || { fatal "adb 不可用：command -v ${ADB} 失败（CI 传 ADB=<路径>）"; finish_summary; }
DEVLIST=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
NDEV=$(printf '%s' "${DEVLIST}" | { grep -c . || true; })
if [ "${NDEV}" -eq 0 ]; then fatal "无在线 adb 设备（确认模拟器已起，或多设备时 export ANDROID_SERIAL=<serial>）"
elif [ "${NDEV}" -gt 1 ]; then fatal "检测到 ${NDEV} 台设备：export ANDROID_SERIAL=<16KB 模拟器 serial> 后重跑"
else ok "恰好 1 台设备在线: ${DEVLIST}"; fi
[ -n "${FATAL_REASON}" ] && finish_summary

echo "── [C2] 设备 boot 完成 ──"
for _ in $(seq 1 "${BOOT_TIMEOUT}"); do
  [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')" = "1" ] && break
  sleep 2
done
if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')" = "1" ]; then
  ok "sys.boot_completed=1"
else
  fatal "${BOOT_TIMEOUT}s 内设备未完成 boot"; finish_summary
fi

echo "── [C3] 16KB page 内核断言（P2b §3.3：非 16KB 模拟器即终止）──"
dev_run "getconf PAGESIZE"
if [ "${DEV_RC}" = "0" ] && [ "${DEV_OUT// /}" = "16384" ]; then
  ok "getconf PAGESIZE = 16384"
else
  fatal "非 16KB 模拟器: PAGESIZE='${DEV_OUT// /}'（期望 16384）。须选带 _16k 标识的系统镜像（Android 15/API 35）"
  finish_summary
fi

echo "── [C4] ABI 兼容预检（防 INSTALL_FAILED_NO_MATCHING_ABIS）──"
dev_run "getprop ro.product.cpu.abilist"
if [ "${DEV_RC}" = "0" ] && printf '%s' "${DEV_OUT}" | grep -q 'arm64-v8a'; then
  ok "abilist 含 arm64-v8a: ${DEV_OUT}"
else
  fatal "镜像 abilist='${DEV_OUT}' 不含 arm64-v8a，而本 APK 仅 arm64-v8a（C3 abiFilters）。"
  echo "         镜像选型: x86_64 host → API≥34 带 ARM translation 的 _16k 镜像；"
  echo "                   arm64 host（M 系列/ARM runner）→ arm64-v8a_16k 镜像。"
  finish_summary
fi

echo "── [C5] 安装 full APK（参数化路径: ${SMOKE_APK:-<未提供>}）──"
if [ -z "${SMOKE_APK}" ] || [ ! -f "${SMOKE_APK}" ]; then
  fatal "SMOKE_APK 未提供或文件不存在: '${SMOKE_APK}'。用法: bash native/ci-smoke-16kb.sh <full.apk>"
  finish_summary
fi
INSTALL_OUT=$("$ADB" install -r "${SMOKE_APK}" 2>&1 | sed 's/\r$//' | tail -n1 || true)
if [ "${INSTALL_OUT}" = "Success" ]; then
  ok "adb install -r 成功"
else
  bad "adb install 失败: ${INSTALL_OUT}（NO_MATCHING_ABIS→看 C4；签名冲突→先 adb uninstall ${APK_PKG}）"
fi

echo "── [C6] pm path 确认安装并定位 nativeLibraryDir（D4 解包验证）──"
dev_run "pm path ${APK_PKG} | grep base.apk | head -n1 | sed 's/^package://;s|/base.apk\$||'"
APK_DIR="${DEV_OUT}"
LIB_DIR=""
if [ "${DEV_RC}" = "0" ] && [ -n "${APK_DIR}" ]; then
  dev_run "test -d '${APK_DIR}/lib/arm64' && test -f '${APK_DIR}/lib/arm64/libproot.so' && echo found"
  [ "${DEV_OUT}" = "found" ] && LIB_DIR="${APK_DIR}/lib/arm64"
fi
if [ -z "${LIB_DIR}" ]; then   # 回退：release.yml Gate 1b 同款 glob 口径（P2b §3.3 原文）
  dev_run "ls -d /data/app/*/lib/arm64 2>/dev/null | while read -r d; do test -f \"\$d/libproot.so\" && echo \"\$d\" && break; done"
  LIB_DIR=$(printf '%s' "${DEV_OUT}" | tail -n1 | tr -d ' ')
fi
if [ -n "${LIB_DIR}" ] && [ "${DEV_RC}" = "0" ]; then
  dev_run "ls ${LIB_DIR}"
  ok "库已解包至 nativeLibraryDir: ${LIB_DIR} ($(printf '%s' "${DEV_OUT}" | tr '\n' ' '))"
else
  bad "nativeLibraryDir 未找到两库 → extractNativeLibs 未生效（D4 漏配 = 全链路不可用）"
  echo "         核对 app/build.gradle.kts packaging.jniLibs.useLegacyPackaging=true 与 release.yml Gate 4"
  finish_summary
fi

echo "── [C7] libproot.so --version 直接可执行（C3-3 原文口径）──"
dev_run "\"${LIB_DIR}/libproot.so\" --version"
if [ "${DEV_RC}" = "0" ]; then
  ok "proot 退出码 0: $(printf '%s' "${DEV_OUT}" | head -n1)"
else
  bad "退出码 ${DEV_RC}: ${DEV_OUT}（Exec format error→对齐/ABI, P2b §6-2；Permission denied→权限/SELinux, §6-1）"
fi

echo "── [C8] libqemu_aarch64.so --version（qemu 自身 16KB 可加载可运行）──"
dev_run "\"${LIB_DIR}/libqemu_aarch64.so\" --version"
if [ "${DEV_RC}" = "0" ]; then
  ok "qemu 退出码 0: $(printf '%s' "${DEV_OUT}" | head -n1)"
  if printf '%s' "${DEV_OUT}" | grep -q 'OpenCode-Android'; then
    echo "         版本归因 OK（--with-pkgversion 命中, P2b §5-4）"
  else
    echo "         [警告] 输出未含 OpenCode-Android pkgversion, 请核对构建版本（不阻断）"
  fi
else
  bad "qemu 退出码 ${DEV_RC}: ${DEV_OUT}（对齐/ABI 问题对照 P2b §6-1/§6-2）"
fi

echo "── [C9] proot 接受 -q 宿主侧绝对路径（P2b §5-2 约定, CLI 层）──"
dev_run "\"${LIB_DIR}/libproot.so\" -q \"${LIB_DIR}/libqemu_aarch64.so\" --version"
if [ "${DEV_RC}" = "0" ]; then
  ok "proot -q <abs> --version 退出码 0"
else
  bad "退出码 ${DEV_RC}: ${DEV_OUT}（-q 必须为宿主侧绝对路径, proot 不做 guest 路径翻译）"
fi

echo "── [C10] qemu 运行时真链路: qemu 执行 arm64 ELF（TARGET_PAGE_BITS_VARY 第二层）──"
dev_run "\"${LIB_DIR}/libqemu_aarch64.so\" \"${LIB_DIR}/libproot.so\" --version"
if [ "${DEV_RC}" = "0" ]; then
  ok "qemu 加载 arm64 ELF 成功（16KB host 上 guest 映射建立 = VARY 生效, P2b §2.2）"
else
  bad "退出码 ${DEV_RC}: ${DEV_OUT}"
  echo "         日志含 Failed to map / MAP_FIXED → TARGET_PAGE_BITS_VARY 未生效（P2b §6-5/§2.2, 回报 native-builder）"
fi

echo "── [C11] QEMU 模式全链: proot → qemu → arm64 产物（引擎 QEMU 模式同构形态）──"
dev_run "\"${LIB_DIR}/libproot.so\" -q \"${LIB_DIR}/libqemu_aarch64.so\" \"${LIB_DIR}/libproot.so\" --version"
if [ "${DEV_RC}" = "0" ]; then
  ok "proot -q qemu 三层链退出码 0（M0-3 qemu 模式项最小闭环）"
else
  bad "退出码 ${DEV_RC}: ${DEV_OUT}（对照 P2b §6 特征串表逐条归因）"
fi

finish_summary
