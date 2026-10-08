#!/usr/bin/env bash
# =============================================================================
# native/build-proot.sh — proot (Termux Android 适配 fork) NDK 交叉编译
#
# 契约依据（P1-架构总纲，均不得变更）：
#   §3.3 C3  — libproot.so：arm64-v8a；NDK aarch64-linux-android26-clang；
#              链接器 flags 含 -Wl,-z,max-page-size=16384；静态优先(-static)，
#              交付物零额外 .so 依赖（本脚本以 llvm-readelf -d 断言）。
#   §2 D7    — 16KB page：链接期 flag 管 host 侧 ELF LOAD 段对齐（本脚本）。
#   §2 D4    — 产物以 lib*.so 命名落 jniLibs/arm64-v8a（targetSdk>=29 唯一可执行区）。
#
# 上游与锁定：
#   proot 源码:  https://github.com/termux/proot   tag v5.1.107.96
#                （Termux 维护的 Android 适配 fork，upstream proot-me/PRoot，
#                 许可 GPL-2.0+；锁定方式 = git tag + 可选 commit pin，见 PROOT_REF/PROOT_COMMIT）
#   talloc 2.5.0: https://www.samba.org/ftp/talloc/ （proot 硬依赖，waf 构建，
#                 交叉配方与 cross-answers 逐字取自 termux-packages
#                 packages/libtalloc/build.sh，LGPL-2.1+，静态打包）
#   libandroid-shmem 0.7: https://github.com/termux/libandroid-shmem
#                 （proot 在 API<33 的 bionic 上无 POSIX shm_open，Termux 配方
#                 以 ashmem 模拟实现替换；BSD-3-Clause，单 C 文件，静态打包）
#
# 说明：
#   * Termux 官方 proot 为动态链 libtalloc.so；NDK sysroot 不含 talloc/shmem，
#     故本脚本将其二者静态编译为 .a 后一并链入，实现 C3 的"静态优先"，
#     并在编译后以 NEEDED 白名单断言（C3-N）确保零【自建库】动态依赖。
#     【ADR-C3-R2 修订】liblog/libandroid 平台库 NDK 仅提供 .so（无 .a），
#     且 lld 拒绝 -static 链接动态对象，全静态物理不可行 → 改为「半静态」：
#     自建库全静态吸入，仅允许系统库动态 NEEDED。详见 step 4 (b) 断言处。
#   * 不设 PROOT_UNBUNDLE_LOADER（Termux 用于分离 loader 的优化）：保持 loader
#     内嵌、运行期提取到 PROOT_TMP_DIR（engine-layer 契约已定 = app cache 目录）。
#
# 用法：
#   NDK_ROOT=/path/to/android-ndk-r26c bash native/build-proot.sh
#   常用环境变量（均可覆盖）：NDK_ROOT / ANDROID_NDK_HOME / API_LEVEL(=26) /
#   PROOT_REF(=v5.1.107.96) / PROOT_COMMIT(=a179d3e8a4e045aaa1fb8cc3284f23509d96d353) /
#   TALLOC_VERSION(=2.5.0) / SHMEM_VERSION(=0.7) / JOBS / JNILIBS_DIR / BUILD_DIR / KEEP_BUILD
# =============================================================================
set -euo pipefail

# ───────────────────────────── 参数区（可被环境变量覆盖） ─────────────────────
API_LEVEL="${API_LEVEL:-26}"                       # 契约：minSdk 26 → android26
PROOT_REF="${PROOT_REF:-v5.1.107.96}"              # 锁定 tag（升级需人工回归 + P1 版本记录）
PROOT_COMMIT="${PROOT_COMMIT:-a179d3e8a4e045aaa1fb8cc3284f23509d96d353}"  # tag 对应 commit，二次 pin 防标签漂移
TALLOC_VERSION="${TALLOC_VERSION:-2.5.0}"
TALLOC_SHA256="${TALLOC_SHA256:-912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007}" # termux-packages 官方
SHMEM_VERSION="${SHMEM_VERSION:-0.7}"
SHMEM_SHA256="${SHMEM_SHA256:-1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867}"  # termux-packages 官方

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$SCRIPT_DIR/.build-proot}" # 全部源码与中间产物隔离在此
STAGING="$BUILD_DIR/staging"                       # 头文件/静态库安装前缀
JNILIBS_DIR="${JNILIBS_DIR:-$REPO_ROOT/app/src/main/jniLibs/arm64-v8a}"
ALIGN_LDFLAG="-Wl,-z,max-page-size=16384"          # P1 C3 硬性要求
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

log()  { printf '\n\033[1;34m[build-proot]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[build-proot][FATAL]\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少工具: $1 （请在 CI/宿主机预装后重试）"; }

# ───────────────────────────── 工具与 NDK 定位 ────────────────────────────────
for t in git tar xz curl sha256sum make awk; do need "$t"; done

find_ndk_root() {
  local c newest
  if [[ -n "${NDK_ROOT:-}" ]]; then
    [[ -d "$NDK_ROOT" ]] || die "NDK_ROOT=$NDK_ROOT 不存在"
    printf '%s\n' "$NDK_ROOT"; return 0
  fi
  for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK:-}" \
           "${ANDROID_HOME:-}/ndk"/* "${HOME:-}/Android/Sdk/ndk"/*; do
    [[ -n "$c" && -d "$c" ]] && newest="$c"   # 目录名即版本号，glob 展开后取最大
  done
  [[ -n "${newest:-}" ]] || die "未定位到 Android NDK；请显式导出 NDK_ROOT=<ndk 路径>"
  printf '%s\n' "$newest"
}
NDK_ROOT="$(find_ndk_root)"

case "$(uname -s)" in Linux) _os=linux ;; Darwin) _os=darwin ;; *) _os=linux ;; esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/${_os}-x86_64"
[[ -d "$TOOLCHAIN" ]] || die "NDK 工具链目录不存在: $TOOLCHAIN"

CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"   # 契约 C3：android26-clang
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"
NM="$TOOLCHAIN/bin/llvm-nm"
[[ -x "$CC" ]] || die "NDK 编译器不存在: $CC"