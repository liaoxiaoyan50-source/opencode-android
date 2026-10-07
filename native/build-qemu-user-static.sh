#!/usr/bin/env bash
# =============================================================================
# native/build-qemu-user-static.sh — qemu-user (仅 aarch64 guest) NDK 静态交叉编译
#
# 路径契约：release.yml job_android 以 `bash ../build-qemu-user-static.sh`
# （cwd=native/proot-src）调用本脚本 —— 本脚本用 BASH_SOURCE 自定位，不依赖 cwd。
#
# 契约依据（P1-架构总纲，均不得变更）：
#   §3.3 C3  — libqemu_aarch64.so：arm64-v8a；强制 --static；仅 aarch64 guest；
#              meson 构建；编译时长预算 ~20min；链接器 flags 含
#              -Wl,-z,max-page-size=16384；TARGET_PAGE_BITS_VARY 必须启用。
#   §2 D7    — qemu-user 默认 TARGET_PAGE_BITS=12（4KB），在 16KB host page 上
#              MAP_FIXED/mmap offset 失败；必须启用页位宽可变（本脚本以
#              "qemu>=9.0 源码内置机制 + 构建前源码断言"落实，见 step 4）。
#
# TARGET_PAGE_BITS_VARY 启用方式（写实，非描述性）：
#   qemu 9.0 起合入 Richard Henderson《linux-user: Improve host and guest page
#   size handling》（patch 29/30），在 target/arm/cpu-param.h 的
#   `#ifdef CONFIG_USER_ONLY && #ifdef TARGET_AARCH64` 分支内源码级定义：
#       #  define TARGET_PAGE_BITS_VARY
#       #  define TARGET_PAGE_BITS_MIN 12
#   即：target 选 aarch64-linux-user 时无需任何 meson option / configure 开关，
#   运行时将按 host 真实 page size（4KB/16KB/64KB）自适应 guest 映射。
#   本脚本锁定 qemu v9.2.0（>=9.0），并在 configure 前对该宏做源码断言；
#   若未来 qemu 改变机制导致断言失败，构建即刻失败而非静默产出不可用库。
#
# 依赖链（bionic 静态化，全部预编为 .a 安装到 STAGING）：
#   zlib 1.3.1 → libiconv 1.17 → pcre2 10.43 → glib 2.78.4 → qemu v9.2.0
#   （bionic API 26 无 iconv；qemu-user 硬依赖 glib；glib 依赖 pcre2/iconv/zlib。
#    依赖链与 Termux/社区 qemu-user Android 配方一致。）
#
# 用法：
#   NDK_ROOT=/path/to/android-ndk-r26c bash native/build-qemu-user-static.sh
#   环境变量：NDK_ROOT / ANDROID_NDK_HOME / API_LEVEL(=26) / QEMU_REF(=v9.2.0) /
#   ZLIB_VERSION(=1.3.1) / LIBICONV_VERSION(=1.17) / PCRE2_VERSION(=10.43) /
#   GLIB_VERSION(=2.78.4) / JOBS / JNILIBS_DIR / BUILD_DIR / KEEP_BUILD
# =============================================================================
set -euo pipefail

# ───────────────────────────── 参数区（可被环境变量覆盖） ─────────────────────
API_LEVEL="${API_LEVEL:-26}"
QEMU_REF="${QEMU_REF:-v9.2.0}"          # >=9.0 才内置 TARGET_PAGE_BITS_VARY；锁 tag，升级需人工回归
ZLIB_VERSION="${ZLIB_VERSION:-1.3.1}"
LIBICONV_VERSION="${LIBICONV_VERSION:-1.17}"
PCRE2_VERSION="${PCRE2_VERSION:-10.43}"
GLIB_VERSION="${GLIB_VERSION:-2.78.4}"
# 注意：zlib/libiconv/pcre2/glib 的官方 sha256 已填入 2026-10-07 官方源实算权威值
# （主理人查证；talloc/proot 的哈希在 build-proot.sh 已有 termux 权威值）。
ZLIB_SHA256="${ZLIB_SHA256:-9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23}"
LIBICONV_SHA256="${LIBICONV_SHA256:-8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313}"
PCRE2_SHA256="${PCRE2_SHA256:-889d16be5abb8d05400b33c25e151638b8d4bac0e2d9c76e9d6923118ae8a34e}"
GLIB_SHA256="${GLIB_SHA256:-24b8e0672dca120cc32d394bccb85844e732e04fe75d18bb0573b2dbc7548f63}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$SCRIPT_DIR/.build-qemu}"
STAGING="$BUILD_DIR/staging"
JNILIBS_DIR="${JNILIBS_DIR:-$REPO_ROOT/app/src/main/jniLibs/arm64-v8a}"
ALIGN_LDFLAG="-Wl,-z,max-page-size=16384"   # P1 C3 硬性要求
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
CROSS_TARGET_TRIPLE="aarch64-linux-android" # autotools --host 用
CROSS_FILE="$BUILD_DIR/cross-aarch64-android.ini"

log()  { printf '\n\033[1;34m[build-qemu]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[build-qemu][FATAL]\033[0m %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "缺少工具: $1 （请在 CI/宿主机预装后重试）"; }

# ───────────────────────────── 工具与 NDK 定位 ────────────────────────────────
for t in git tar xz curl sha256sum pkg-config ninja python3 make awk; do need "$t"; done
if ! command -v meson >/dev/null 2>&1; then
  log "未找到 meson，尝试 python3 -m pip 安装（glib 构建需要）"
  python3 -m pip install --quiet "meson>=0.62,<1.7" || die "meson 安装失败；请 apt install meson 或 pip3 install meson"
fi
python3 -c 'import pip' 2>/dev/null || die "缺少 python3-pip（qemu configure 需安装 vendored meson/pyelftools）"

find_ndk_root() {
  local c newest
  if [[ -n "${NDK_ROOT:-}" ]]; then
    [[ -d "$NDK_ROOT" ]] || die "NDK_ROOT=$NDK_ROOT 不存在"
    printf '%s\n' "$NDK_ROOT"; return 0
  fi
  for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK:-}" \
           "${ANDROID_HOME:-}/ndk"/* "${HOME:-}/Android/Sdk/ndk"/*; do
    [[ -n "$c" && -d "$c" ]] && newest="$c"
  done
  [[ -n "${newest:-}" ]] || die "未定位到 Android NDK；请显式导出 NDK_ROOT=<ndk 路径>"
  printf '%s\n' "$newest"
}
NDK_ROOT="$(find_ndk_root)"

case "$(uname -s)" in Linux) _os=linux ;; Darwin) _os=darwin ;; *) _os=linux ;; esac
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/${_os}-x86_64"
[[ -d "$TOOLCHAIN" ]] || die "NDK 工具链目录不存在: $TOOLCHAIN"

CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"   # 契约 C3：android26-clang
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
NM="$TOOLCHAIN/bin/llvm-nm"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"
[[ -x "$CC" ]] || die "NDK 编译器不存在: $CC"

log "NDK: $NDK_ROOT"
log "CC : $CC"
log "qemu: $QEMU_REF · 依赖: zlib $ZLIB_VERSION / libiconv $LIBICONV_VERSION / pcre2 $PCRE2_VERSION / glib $GLIB_VERSION"

mkdir -p "$BUILD_DIR" "$STAGING/lib" "$STAGING/include" "$STAGING/lib/pkgconfig" "$JNILIBS_DIR"

fetch() { # fetch <url> <dest> <sha256|empty>
  local url="$1" dest="$2" sum="${3:-}" i
  for i in 1 2 3; do
    curl -fL --retry 3 --connect-timeout 20 -o "$dest" "$url" && break
    rm -f "$dest"; [[ "$i" == 3 ]] && die "下载失败: $url"
  done
  if [[ -n "$sum" ]]; then
    echo "$sum  $dest" | sha256sum -c - >/dev/null 2>&1 \
      || die "sha256 校验失败: $dest (期望 $sum)"
  else
    log "警告: $url 未配置 sha256，跳过校验（合入前请按官方发布页核对补齐 *_SHA256）"
  fi
}

# ───────────────────────────── meson cross file ──────────────────────────────
# pkg-config 强制 --static：qemu --static 链接 glib 时必须拉出
# Requires.private（pcre2/iconv/zlib），否则静态链接期符号缺失。
# 用 wrapper 方案对所有检测统一加 --static，比依赖 meson 传参更可靠。
PKGCFG_WRAPPER="$BUILD_DIR/pkg-config-cross"
cat > "$PKGCFG_WRAPPER" <<'EOF'
#!/bin/sh
exec /usr/bin/pkg-config --static "$@"
EOF
chmod +x "$PKGCFG_WRAPPER"

cat > "$CROSS_FILE" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
nm = '$NM'
strip = '$STRIP'
pkg-config = '$PKGCFG_WRAPPER'

[properties]
pkg_config_libdir = ['$STAGING/lib/pkgconfig']
c_args = ['-I$STAGING/include']
c_link_args = ['-L$STAGING/lib', '$ALIGN_LDFLAG']
cpp_args = ['-I$STAGING/include']
cpp_link_args = ['-L$STAGING/lib', '$ALIGN_LDFLAG']

[host_machine]
system = 'linux'      # qemu/meson 按 linux 分支走 host 侧代码；bionic 由编译器 triple 决定
cpu = 'aarch64'
endian = 'little'

[built-in options]
default_library = 'static'
EOF
log "meson cross file 已生成: $CROSS_FILE"

autotools_flags=(CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP")

# ══════════════════ 第 1 步：依赖预编（zlib → libiconv → pcre2 → glib） ═════
log "step 1/5 — 预编静态依赖 → $STAGING"

# 1a. zlib（configure 读环境变量，--static 产 .a）
ZLIB_DIR="$BUILD_DIR/zlib-$ZLIB_VERSION"
if [[ ! -f "$STAGING/lib/libz.a" ]]; then
  fetch "https://zlib.net/fossils/zlib-${ZLIB_VERSION}.tar.gz" \
        "$BUILD_DIR/zlib.tar.gz" "$ZLIB_SHA256"
  rm -rf "$ZLIB_DIR"; tar -xzf "$BUILD_DIR/zlib.tar.gz" -C "$BUILD_DIR"
  (
    cd "$ZLIB_DIR"
    CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    ./configure --static --prefix="$STAGING"
    make -j"$JOBS" && make install
  )
fi

# 1b. libiconv（bionic API26 无 iconv，glib 必需）
ICONV_DIR="$BUILD_DIR/libiconv-$LIBICONV_VERSION"
if [[ ! -f "$STAGING/lib/libiconv.a" ]]; then
  fetch "https://ftp.gnu.org/pub/gnu/libiconv/libiconv-${LIBICONV_VERSION}.tar.gz" \
        "$BUILD_DIR/libiconv.tar.gz" "$LIBICONV_SHA256"
  rm -rf "$ICONV_DIR"; tar -xzf "$BUILD_DIR/libiconv.tar.gz" -C "$BUILD_DIR"
  (
    cd "$ICONV_DIR"
    autotools_flags+=(CFLAGS="-O2")
    ./configure --host="$CROSS_TARGET_TRIPLE" --prefix="$STAGING" \
                --enable-static --disable-shared "${autotools_flags[@]}"
    make -j"$JOBS" && make install
  )
fi

# 1c. pcre2（glib GRegex 后端；JIT 目标侧为 arm64 可用）
PCRE2_DIR="$BUILD_DIR/pcre2-$PCRE2_VERSION"
if [[ ! -f "$STAGING/lib/libpcre2-8.a" ]]; then
  fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-${PCRE2_VERSION}/pcre2-${PCRE2_VERSION}.tar.gz" \
        "$BUILD_DIR/pcre2.tar.gz" "$PCRE2_SHA256"
  rm -rf "$PCRE2_DIR"; tar -xzf "$BUILD_DIR/pcre2.tar.gz" -C "$BUILD_DIR"
  (
    cd "$PCRE2_DIR"
    autotools_flags+=(CFLAGS="-O2")
    ./configure --host="$CROSS_TARGET_TRIPLE" --prefix="$STAGING" \
                --disable-shared --enable-static --enable-jit "${autotools_flags[@]}"
    make -j"$JOBS" && make install
  )
fi

# 1d. glib（meson 交叉；qemu-user 硬依赖 glib-2.0 >= 2.56）
GLIB_DIR="$BUILD_DIR/glib-$GLIB_VERSION"
if [[ ! -f "$STAGING/lib/libglib-2.0.a" ]]; then
  fetch "https://download.gnome.org/sources/glib/2.78/glib-${GLIB_VERSION}.tar.xz" \
        "$BUILD_DIR/glib.tar.xz" "$GLIB_SHA256"
  rm -rf "$GLIB_DIR"; tar -xJf "$BUILD_DIR/glib.tar.xz" -C "$BUILD_DIR"
  (
    cd "$GLIB_DIR"
    rm -rf build-glib
    meson setup build-glib \
      --cross-file "$CROSS_FILE" \
      --prefix "$STAGING" \
      -Dtests=false -Dinstalled_tests=false \
      -Dgtk_doc=false -Dman=false \
      -Dselinux=disabled -Dlibmount=disabled \
      -Dnls=false -Ddtrace=false -Dsystemtap=false \
      -Dforce_posix_threads=true \
      -Diconv=native   # 指向 step1b 的 GNU libiconv（libc iconv 仅 API28+）
    ninja -C build-glib
    ninja -C build-glib install
  )
fi

# ══════════════════ 第 2 步：qemu 源码（锁 tag） ═════════════════════════════
log "step 2/5 — 检出 qemu $QEMU_REF"
QEMU_DIR="$BUILD_DIR/qemu-src"
if [[ ! -d "$QEMU_DIR" ]]; then
  git clone --depth 1 --branch "$QEMU_REF" \
            https://github.com/qemu/qemu "$QEMU_DIR"
fi

# ══════════════════ 第 3 步：TARGET_PAGE_BITS_VARY 源码断言（D7 硬性要求） ══
log "step 3/5 — 断言 TARGET_PAGE_BITS_VARY（aarch64 linux-user 内置页位宽可变）"
CPU_PARAM="$QEMU_DIR/target/arm/cpu-param.h"
[[ -f "$CPU_PARAM" ]] || die "未找到 $CPU_PARAM（qemu 源码结构异常）"
grep -q 'TARGET_PAGE_BITS_VARY' "$CPU_PARAM" \
  || die "cpu-param.h 无 TARGET_PAGE_BITS_VARY：qemu < 9.0 或结构变更，16KB host page 将 MAP_FIXED 失败（D7）"
USER_BLOCK="$(sed -n '/#ifdef CONFIG_USER_ONLY/,/#else/p' "$CPU_PARAM")"
printf '%s\n' "$USER_BLOCK" | grep -q 'TARGET_AARCH64' \
  || die "TARGET_PAGE_BITS_VARY 不在 CONFIG_USER_ONLY+TARGET_AARCH64 分支内，无法保证 aarch64 linux-user 生效"
printf '%s\n' "$USER_BLOCK" | grep -q 'TARGET_PAGE_BITS_MIN' \
  || die "缺少 TARGET_PAGE_BITS_MIN（page-vary 机制不完整）"
log "断言通过，cpu-param.h 相关定义："
printf '%s\n' "$USER_BLOCK" | grep -E 'TARGET_PAGE_BITS|TARGET_AARCH64' | sed 's/^/    /'

# ══════════════════ 第 4 步：meson configure + 编译（--static） ═════════════
log "step 4/5 — qemu configure (--static, aarch64-linux-user) 与 ninja 编译"
(
  cd "$QEMU_DIR"
  rm -rf build
  # 参数说明：
  #   --static ......................... C3 强制静态
  #   --target-list=aarch64-linux-user . 仅 aarch64 guest（C3）
  #   --without-default-features ....... 关闭全部可选依赖（glib 除外，为硬依赖）
  #   --enable-tcg ..................... TCG 是 qemu-user 执行后端，显式开启防
  #                                      without-default-features 连带关闭
  #   --extra-cflags=-O2 ............... meson buildtype 默认非优化，显式开 O2
  #   --with-pkgversion ................ 版本标识，便于设备端诊断报告归因
  ./configure \
    --cross-file "$CROSS_FILE" \
    --static \
    --target-list=aarch64-linux-user \
    --without-default-features \
    --enable-tcg \
    --disable-system \
    --disable-docs --disable-tools --disable-guest-agent \
    --disable-capstone --disable-gnutls --disable-gcrypt --disable-nettle \
    --disable-seccomp --disable-curl --disable-libssh --disable-slirp \
    --extra-cflags="-O2" \
    --with-pkgversion="OpenCode-Android-$QEMU_REF"
  ninja -C build -j"$JOBS" qemu-aarch64
)

# ══════════════════ 第 5 步：交付自检 + jniLibs 落地 ═════════════════════════
log "step 5/5 — 交付自检并落位 jniLibs"
QEMU_BIN="$QEMU_DIR/build/qemu-aarch64"
[[ -s "$QEMU_BIN" ]] || die "qemu 产物缺失: $QEMU_BIN"
"$STRIP" "$QEMU_BIN"

# (a) 架构断言
"$READELF" -h "$QEMU_BIN" | grep -q 'Machine:.*AArch64' \
  || die "架构断言失败：产物不是 AArch64"

# (b) C3 静态断言：NEEDED 必须为 0
NEEDED="$($READ_ELF_D_QEMU_BIN)"
if [[ -n "$NEEDED" ]]; then
  die "静态断言失败：产物存在动态 .so 依赖（违反 C3 --static）：
$NEEDED"
fi

# (c) D7 对齐断言（复用 check-page-align.sh 精确解析 LOAD 段 Align 字段）
if [[ -x "$SCRIPT_DIR/check-page-align.sh" ]]; then
  READELF="$READELF" bash "$SCRIPT_DIR/check-page-align.sh" "$QEMU_BIN"
else
  log "警告: check-page-align.sh 不在同级目录，跳过对齐自检（CI 侧必须补跑）"
fi

# (d) 落位
install -m 755 "$QEMU_BIN" "$JNILIBS_DIR/libqemu_aarch64.so"

KEEP_BUILD="${KEEP_BUILD:-0}"
[[ "$KEEP_BUILD" == "1" ]] || rm -rf "$BUILD_DIR"

log "完成 → $JNILIBS_DIR/libqemu_aarch64.so"
log "  架构: AArch64 / 静态链接 / NEEDED=0 / TARGET_PAGE_BITS_VARY 已启用"
log "  版本: qemu $QEMU_REF · glib $GLIB_VERSION · 仅 aarch64 guest"
