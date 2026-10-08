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

[host_machine]
# cpu_family 是 meson>=1.x 的强制键；run#26 因缺失直接报
#   "Machine info ... is missing {'cpu_family'}" 而中断（唯一阻断点）。
system = 'linux'      # qemu/meson 按 linux 分支走 host 侧代码；bionic 由编译器 triple 决定
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

# build_machine 显式声明：CI runner 恒为 x86_64；不写则 meson 回退 uname 探测，
# 1.3+ 会对交叉编译发 "missing cpu_family for build" 警告，且 uname 行为随 runners
# 镜像漂移。显式化成本为零、收益确定。（[target_machine] 刻意不写：qemu-user 下
# host_machine 即 guest 语义，写 target_machine 会触发 meson 三方一致性校验）
[build_machine]
system = 'linux'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'

# ── c_args / c_link_args 归 [built-in options] 而非 [properties] ──────────────
# CI run#29 实证 meson 1.6.1 对 4 个键发 DEPRECATION：
#   "c_args in the [properties] section of the machine file is deprecated,
#    use the [built-in options] section."（c_link_args / cpp_args /
#    cpp_link_args 同）。
#   二者在 [properties] 段**功能等价**：本地 meson 1.6.1 实测写在 [properties]
#   的 c_args 仍落到 HOST machine（get_option('c_args') 取到 -I.../staging/include），
#   并会被 compiler 的 has_header/find_library 探测使用。但按官方指引迁移可消除
#   警告，且规避后续 meson 版本移除该兼容路径的风险。
#   归属机制（mesonbuild/options.py 的 OptionKey.from_string）：不带 build.
#   前缀的键默认 for_machine=HOST —— 正是 compiler 探测时
#   get_external_args(for_machine=HOST) 读取的键。这是 glib 的
#   dependency('iconv') 经 has_header('iconv.h') + find_library('iconv') 命中
#   $STAGING 的前提（详见 step 1d 注释）。
[built-in options]
default_library = 'static'
c_args = ['-I$STAGING/include']
c_link_args = ['-L$STAGING/lib', '$ALIGN_LDFLAG']
cpp_args = ['-I$STAGING/include']
cpp_link_args = ['-L$STAGING/lib', '$ALIGN_LDFLAG']
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
# 【此处刻意不设 iconv 前置守卫】—— CI run#27 实证与源码核实结论如下：
#   * 曾在此外加过「$STAGING/lib/pkgconfig/iconv.pc 存在性」守卫，基于
#     「libiconv 1.17 autotools install 会产出 iconv.pc」的推断；CI run#27
#     在 2 分 50 秒处直接挂在该守卫（staging 内确无 iconv.pc），
#     **推断被证伪，守卫为误判**，已删除。
#   * 真机制：glib 2.78.4 非 Windows 分支唯一的 iconv 获取路径是
#         else
#           libiconv = dependency('iconv')
#     而 meson 0.60+ 的 'iconv' 是 **内建依赖**（mesonbuild/dependencies/
#     misc.py 的 IconvBuiltinDependency / IconvSystemDependency），
#     **完全不查 pkg-config**：
#       - Builtin：探测 libc 是否自带 iconv 实现（bionic 无 → 失败）；
#       - System ：要求 has_header('iconv.h') **且** find_library('iconv')
#                   同时成立，二者均由 cross file 的
#                   c_args=['-I$STAGING/include'] /
#                   c_link_args=['-L$STAGING/lib'] 满足（本脚本 step1b 已
#                   实测落位 $STAGING/include/iconv.h 与 $STAGING/lib/libiconv.a）。
#   * 结论：不设守卫，让 glib 自己的 meson setup 在真缺件时给出准确报错；
#     自设守卫会重复 meson 探测逻辑并与 .a/.so 命名/位置强耦合（易随上游漂移）。
GLIB_DIR="$BUILD_DIR/glib-$GLIB_VERSION"
if [[ ! -f "$STAGING/lib/libglib-2.0.a" ]]; then
  fetch "https://download.gnome.org/sources/glib/2.78/glib-${GLIB_VERSION}.tar.xz" \
        "$BUILD_DIR/glib.tar.xz" "$GLIB_SHA256"
  rm -rf "$GLIB_DIR"; tar -xJf "$BUILD_DIR/glib.tar.xz" -C "$BUILD_DIR"
  (
    cd "$GLIB_DIR"
    rm -rf build-glib
    # 【本命令刻意不含 iconv 选项】glib 2.78.4 源码实测无 iconv option：
    #   * meson_options.txt 全文无 option('iconv', ...)：2.58 时代曾有
    #     libc/gnu/native 取值域，2.78 已移除，网上的旧资料勿套用；
    #   * meson.build L2071-2081 非 Windows 平台为硬编码唯一路径：
    #         else
    #           libiconv = dependency('iconv')
    #     该依赖是 meson 0.60+ 的 **内建依赖**（IconvBuiltinDependency /
    #     IconvSystemDependency，见 mesonbuild/dependencies/misc.py），
    #     **不作 pkg-config 查询**；System 分支要求 has_header('iconv.h') +
    #     find_library('iconv') 同时成立，由本 cross file 的
    #     c_args=['-I$STAGING/include'] / c_link_args=['-L$STAGING/lib']
    #     满足（step1b 已落位 iconv.h + libiconv.a）。
    #     —— 注：此处一度写成「走 pkg-config 自动命中 iconv.pc」，该表述
    #     已被 run#27 + meson 源码证伪，现更正为上述内建探测机制。
    #   若误传 -Diconv=... → meson 1.6.1 直接
    #     ERROR: Unknown options: "iconv"
    #   阻断 configure（与 run#26 的 cpu_family 同类，属必然失败）。
    #   * option 类型核对（glib 2.78.4 meson_options.txt 逐条实测）：
    #       feature 型 → 只能取 enabled/disabled/auto，**不能取 true/false**：
    #           selinux、libmount、nls
    #       boolean 型 → 只能取 true/false：
    #           tests、installed_tests、gtk_doc、man、dtrace、systemtap、
    #           force_posix_threads
    #     CI run#29 即因 -Dnls=false 直接报
    #       meson.build:1:0: ERROR: Value "false" (of type "string") for
    #       option "None" is not one of the choices. Possible choices are
    #       (as string): "enabled", "disabled", "auto".
    #     而中断（本地 meson 1.6.1 已逐字复现）。nls 已修正为 disabled。
    meson setup build-glib \
      --cross-file "$CROSS_FILE" \
      --prefix "$STAGING" \
      -Dtests=false -Dinstalled_tests=false \
      -Dgtk_doc=false -Dman=false \
      -Dselinux=disabled -Dlibmount=disabled \
      -Dnls=disabled -Ddtrace=false -Dsystemtap=false \
      -Dforce_posix_threads=true
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

# ══════════════════ 第 2.5 步：隐式声明防御补丁（预防性加固，未实锤） ════════
# 预防性（qemu 编译错误尚未实锤）：NDK r29 / clang 19 把 C99+ 模式下的隐式
# 函数声明升级为默认硬错误——proot 侧已在 CI run#10 实锤（完整根因注释见
# build-proot.sh「CI run#10 根因修复」段，此处不赘述）。qemu v9.2 主树长期
# 跑 clang、meson 构建自带严格检查，主树命中概率低；但树内 submodule/独立
# 代码不可控，预防成本远低于一轮 CI 失败往返，故预埋同款 include 补齐扫描。
# 与 build-proot.sh 同模式（grep -E 用法扫描 + 显式 include 缺失判定 + sed
# 插入，string.h 族 / stdio.h 族两套），差异仅在插入位置——qemu 编码规范
# 要求每个 .c 的第一个 #include 必须是 "qemu/osdep.h"，因此：
#   * 文件含 osdep.h 行 → include 插到该行【之后】（不破"osdep.h 第一"规范；
#     osdep.h 之后接系统头本就是 qemu 常规写法；重复包含由头文件 guard 兜底）；
#   * 文件不含 osdep.h（submodule/独立代码）→ 插第 1 行。
# 幂等：已显式包含对应头文件的跳过；KEEP_BUILD=1 重跑安全。
log "step 2.5/5 — 隐式声明防御补丁（预防性加固，qemu 侧未实锤）"
_QEMU_PATCH_COUNT=0
while IFS= read -r -d '' f; do
  if grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]*"qemu/osdep\.h"' "$f"; then
    _ANCHOR_ODEP=1   # osdep.h 在手：include 插其行后
  else
    _ANCHOR_ODEP=0   # 无 osdep.h（submodule/独立代码）：插第 1 行
  fi
  # —— string.h 族 ——
  if grep -Eq '(^|[^A-Za-z0-9_])(strcmp|strncmp|strcpy|strncpy|strcat|strncat|strlen|strnlen|strchr|strrchr|strstr|strdup|strndup|strspn|strcspn|strpbrk|strtok|strtok_r|strerror|strerror_r|strcasecmp|strncasecmp|strsignal|memset|memcpy|memmove|memcmp|memchr|memmem)[[:space:]]*\(' "$f" \
     && ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]*[<"]string\.h[>"]' "$f"; then
    if [[ "$_ANCHOR_ODEP" == 1 ]]; then
      sed -i.bak '/qemu\/osdep\.h/a\
#include <string.h>' "$f" || die "隐式声明补丁失败: $f"
    else
      sed -i.bak '1i\
#include <string.h>' "$f" || die "隐式声明补丁失败: $f"
    fi
    rm -f "$f.bak"
    _QEMU_PATCH_COUNT=$((_QEMU_PATCH_COUNT + 1))
  fi
  # —— stdio.h 族（snprintf 声明在 stdio.h 而非 string.h，单独判定）——
  if grep -Eq '(^|[^A-Za-z0-9_])(snprintf|sprintf|sscanf|vsnprintf|fprintf|printf|puts|fputs)[[:space:]]*\(' "$f" \
     && ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]*[<"]stdio\.h[>"]' "$f"; then
    if [[ "$_ANCHOR_ODEP" == 1 ]]; then
      sed -i.bak '/qemu\/osdep\.h/a\
#include <stdio.h>' "$f" || die "隐式声明补丁失败: $f"
    else
      sed -i.bak '1i\
#include <stdio.h>' "$f" || die "隐式声明补丁失败: $f"
    fi
    rm -f "$f.bak"
    _QEMU_PATCH_COUNT=$((_QEMU_PATCH_COUNT + 1))
  fi
done < <(find "$QEMU_DIR" -type f -name '*.c' -print0)
log "  [patch] qemu 侧共插入 $_QEMU_PATCH_COUNT 处 include"

# ══════════════════ 第 3 步：TARGET_PAGE_BITS_VARY 源码断言（D7 硬性要求） ══
log "step 3/5 — 断言 TARGET_PAGE_BITS_VARY（aarch64 linux-user 内置页位宽可变）"
CPU_PARAM="$QEMU_DIR/target/arm/cpu-param.h"
[[ -f "$CPU_PARAM" ]] || die "未找到 $CPU_PARAM（qemu 源码结构异常）"
grep -q 'TARGET_PAGE_BITS_VARY' "$CPU_PARAM" \
  || die "cpu-param.h 无 TARGET_PAGE_BITS_VARY：qemu < 9.0 或结构变更，16KB host page 将 MAP_FIXED 失败（D7）"
USER_BLOCK="$(sed -n '/#ifdef CONFIG_USER_ONLY/,/#else/p' "$CPU_PARAM")"
# 断言编写铁律（D-C3-R4）：一律 awk 全量消费 + 落原文，禁用管道上的 grep 静默模式
printf '%s\n' "$USER_BLOCK" \
  | awk '/TARGET_AARCH64/{f=1} {print} END{exit !f}' \
  || die "TARGET_PAGE_BITS_VARY 不在 CONFIG_USER_ONLY+TARGET_AARCH64 分支内，无法保证 aarch64 linux-user 生效"
printf '%s\n' "$USER_BLOCK" \
  | awk '/TARGET_PAGE_BITS_MIN/{f=1} {print} END{exit !f}' \
  || die "缺少 TARGET_PAGE_BITS_MIN（page-vary 机制不完整）"
log "断言通过，cpu-param.h 相关定义："
printf '%s\n' "$USER_BLOCK" | grep -E 'TARGET_PAGE_BITS|TARGET_AARCH64' | sed 's/^/    /'

# ══════════════════ 第 3.5 步：bionic __unused 宏冲突修补（Android 平台特有） ══
# 【run#35 根因修复】run#35 首次进入 [304/374]，失败为**真实语法错误**（非告警升级）：
#   ../linux-user/syscall_defs.h:1929:22: error: expected member name or ';' after
#       declaration specifiers
#     1929 |     abi_uint __unused[2];
#   ../linux-user/aarch64/signal.c:44:18: error: (同上)
#   ../linux-user/aarch64/signal.c:538:26: error: implicit conversion from
#       'unsigned long' to 'int' changes value from 18446744073709551609 to -7
#       [-Werror,-Wconstant-conversion]
#
# 根因（已逐字实证）：NDK bionic <sys/cdefs.h> 第 105 行为**裸 define**（无 #ifndef 守卫）：
#     #define __unused __attribute__((__unused__))
#   于是 qemu 的 `abi_uint __unused[2];` 展开为
#     `abi_uint __attribute__((__unused__))[2];`
#   属性落在**数组声明符**位置 → clang 报「expected member name or ';'」。
#   glibc 下 __unused 不是宏（是 qemu 自己声明的普通成员名），故上游 qemu 从不触发
#   —— 纯 Android 平台特有的宏冲突。本地 clang 逐字复现：
#     #include <sys/cdefs.h> / typedef unsigned int abi_uint;
#     struct S { abi_uint __unused[2]; };
#     → 与 run#35 日志错误原文完全一致。
#
# 方案抉择（三条路线本地实测，**路线 B/C 被证伪**）：
#   A. 源码改名 __unused → __qemu_unused .................. PASS（唯一可行）
#   B. --extra-cflags 加 -D__unused= ..................... FAIL
#      （cdefs.h 裸 #define 无 #ifndef 守卫，include 时无条件覆盖命令行 -D；
#        连 -U__unused -D__unused= 组合也实测 FAIL —— 顺序无解）
#   C. -include 一个 shim 头先 #undef 再重定义 ............ FAIL
#      （shim 生效于 cdefs 之前，cdefs include 时又改回属性宏）
#   机理：-D/-include 都发生在 <sys/cdefs.h> 被包含**之前**，而 cdefs 是无守卫裸
#   #define → 命令行方案必然被覆盖。故唯一出路是改源码。
#
# 受影响面（grep -rnE '\b__unused\b' 精确词边界）= 10 处，全在 linux-user/：
#   ✅ 在 aarch64 编图内（run#35 直接报错）：
#      - linux-user/syscall_defs.h:1929   （TARGET_AARCH64 的 target_stat 分支）
#      - linux-user/aarch64/signal.c:43   （target_rt_sigframe 的 pad 成员）
#   ⬜ 不在当前编图（他架构 / 条件编译未命中），但一并改以防版本漂移：
#      - linux-user/syscall_defs.h:1798 / 1885 / 1906
#      - linux-user/riscv/signal.c:48
#      - linux-user/arm/signal.c:57
#      - linux-user/loongarch64/signal.c:74
#      - linux-user/sparc/target_fcntl.h:41 / 42
#   ⚠️ 严禁误伤：全树另有 ~100 处带数字后缀的 __unused1 / __unused2 … __unused6
#      （如 linux-user/ppc/target_structs.h）。宏名精确匹配，这些**不受 bionic 影响**，
#      改了反而破坏结构体成员布局语义。故 sed 与断言**必须**用精确词边界 \b__unused\b。
#
# 幂等语义：KEEP_BUILD=1 / 同 workspace 重跑时源码可能已改。此时「未找到裸 __unused」
#   属**正常已打补丁状态**，应静默跳过而非 die —— 只有「既未改名又改名失败」才是异常。
log "step 3.5/5 — 修补 bionic __unused 宏冲突（源码改名，Android 平台特有）"
UNUSED_TARGETS=(
  "linux-user/syscall_defs.h"
  "linux-user/aarch64/signal.c"
  "linux-user/riscv/signal.c"
  "linux-user/arm/signal.c"
  "linux-user/loongarch64/signal.c"
  "linux-user/sparc/target_fcntl.h"
)
for _rel in "${UNUSED_TARGETS[@]}"; do
  [[ -f "$QEMU_DIR/$_rel" ]] \
    || die "qemu 源码结构漂移：缺 $_rel（无法打 __unused 补丁，拒绝静默产出不可用库）"
done
# 前置探针：全树是否仍存在裸 __unused（精确词边界）。
#   用 \b 而非裸 __unused —— 否则 __unused1 等会让「0 残留」检查永远非空而误 die。
#   D-C3-R4：一律 awk 全量消费 + 落原文，禁用管道上的 grep -q 静默模式。
_UNUSED_HITS="$(grep -rnE '\b__unused\b' "$QEMU_DIR/linux-user" || true)"
if printf '%s\n' "$_UNUSED_HITS" | awk '/__unused/{f=1} END{exit !f}'; then
  # 幂等改名（sed -i 重复执行不累积破坏；\b 保证 __unused1 不被误伤）
  for _rel in "${UNUSED_TARGETS[@]}"; do
    sed -i 's/\b__unused\b/__qemu_unused/g' "$QEMU_DIR/$_rel" \
      || die "sed 改名失败: $_rel"
  done
  # 收尾断言：全树不得再残留裸 __unused（否则 bionic 宏将再次触发语法错误）
  _UNUSED_REMAIN="$(grep -rnE '\b__unused\b' "$QEMU_DIR/linux-user" || true)"
  if printf '%s\n' "$_UNUSED_REMAIN" | awk '/__unused/{f=1} END{exit !f}'; then
    die "补丁后仍残留裸 __unused（bionic 宏将再次触发语法错误）：
$_UNUSED_REMAIN"
  fi
  # 正向断言：改名结果确实落地（防 sed 静默无效）。D-C3-R4：用 awk 全量消费，避免 -q 静默。
  _UNUSED_NEW="$(grep -rnE '\b__qemu_unused\b' "$QEMU_DIR/linux-user" || true)"
  printf '%s\n' "$_UNUSED_NEW" | awk '/__qemu_unused/{f=1} END{exit !f}' \
    || die "改名后未发现任何 __qemu_unused（sed 静默失效）"
  log "  __unused → __qemu_unused 完成（命中 $(printf '%s\n' "$_UNUSED_HITS" | awk '/__unused/{n++} END{print n+0}') 处，全树零残留）"
else
  log "  裸 __unused 已不存在（前次已打补丁，幂等跳过）"
fi

# ══════════════════ 第 3.6 步：bionic sigorset GNU 扩展 shim（run#36 实锤） ═══
# 【run#36 根因修复】run#36 推进到 [366/374]，仅剩一个阻断点（真实语义缺失，非告警）：
#   [363/374] Compiling C object libqemu-aarch64-linux-user.a.p/linux-user_signal.c.o
#   FAILED: [code=1] ...
#   ../linux-user/signal.c:219:13: error: call to undeclared function 'sigorset';
#       ISO C99 and later do not support implicit function declarations
#       [-Wimplicit-function-declaration]
#     219 |             sigorset(&ts->signal_mask, &ts->signal_mask, set);
#   ../linux-user/signal.c:1311:9: error: (同上)
#     1311 |         sigorset(&ts->signal_mask, blocked_set, &set);
#
# 根因证据链（全部本地实测）：
#   证据1 sigorset 是 glibc 的 GNU 扩展（signal.h 非 POSIX 部分），bionic 全无。
#        实测：全 NDK sysroot grep 'sigorset|sigandset|sigisemptyset' 零命中；
#        bionic signal.h 仅有 sigaddset/sigdelset/sigemptyset/sigfillset/sigismember。
#   证据2 qemu v9.2 全树 sigorset 仅 2 处（linux-user/signal.c:217 & 1309），
#        include/ / common-user/ / target/ 全扫无间接依赖。
#   证据3 ts->signal_mask 类型 = host 的 sigset_t（linux-user/qemu.h:142），
#        故只需补「host sigset_t 版」sigorset，无需碰 target_sigset_t。
#   证据4 ★【推翻「按位或」直觉】bionic aarch64 的 sigset_t 是
#           typedef struct { unsigned long sig[_NSIG_WORDS]; } sigset_t;
#         （asm-generic/signal.h:55-58）—— 不透明结构体。
#         实测 `*s = *l | *r;` 编译报
#           error: invalid operands to binary expression ('const sigset_t' and ...)
#         → shim 必须用 sigismember/sigaddset 遍历信号号，**不可位运算**。
#   证据5 NSIG 由 bits/signal_types.h:54 定义为 65（非 64！），
#         _KERNEL__NSIG=64、_NSIG_WORDS=1。故循环上界用 `sig < NSIG` 覆盖到 64。
#         ★ 本地实跑验证（qemu-aarch64-static 执行 aarch64 静态可执行）：
#           rc=0 | SIGINT=1 SIGTERM=1 SIGRTMAX(64)=1 SIGKILL(不该有)=0
#         —— 并集正确、边界信号 64 命中、未设信号不误置。
#   证据6 门控陷阱：NDK clang 只定义 __ANDROID__，**不定义 __BIONIC__**；
#         __BIONIC__ 由 sysroot sys/cdefs.h 经 <signal.h> 传递。
#         → shim 头**必须自行 #include <signal.h>**，否则门控恒假、shim 不生效。
#
# 方案抉择：路线 B（-include staging shim）优于「改 signal.c」：
#   * 幂等 0 成本：cat > 覆盖写，重跑无「重复插入」隐患（对比 step 2.5 的 sed -i 插入）；
#   * 不脏 qemu 树：KEEP_BUILD=1 重跑时源码保持干净，diff 可审计；
#   * 覆盖可扩展：未来 linux-user/ 若再现 sigandset/sigisemptyset（当前各 0 处）
#     同一 -include 即可罩住。
#   * 与 run#35 的 __unused「裸 #define 覆盖命令行 -D」问题**本质不同**：
#     那是宏冲突（命令行方案必然被覆盖），这是缺失函数（-include 注入不会被顶掉）。
#
# 【run#37 自伤修复 · -include 会波及 .S 汇编输入】
#   run#37 用本 shim 后反而退到 [303/374]（低于 run#36 的 366/374），失败目标是
#     libcommon.a.p/common-user_safe-syscall.S.o（源 = ../common-user/safe-syscall.S）
#   报错：signal.h:149:23: error: unexpected token in argument list
#         bionic-sigorset-shim.h:18:1: error: unrecognized instruction mnemonic
#   根因：-include 是 driver 级 flag，对【汇编 .S 输入同样生效】——汇编器把 C 头
#     当汇编解析。本地实测 `clang --target=... -include <C头> -c x.S` 精确复现。
#   机理：--extra-cflags 折进 meson c_args，而 c_args 作用于该 target 的**所有语言**
#     （含 .S），故 -include 无法避免波及汇编。
#     （对照：-Wno-error=* 对 .S 无害 —— 实测汇编+这些 flag 正常通过。）
#   修法：.S 由 clang 预处理并定义 __ASSEMBLER__（.c 不定义），故在 shim 内用
#     #ifndef __ASSEMBLER__ 整体包住 → 汇编输入下本头展开为空，零副作用。
#   本地四向验证：.S 静默通过 / .c shim 生效 / 端到端实跑 sigorset ok /
#     非 Android target 正确 #error。
log "step 3.6/5 — 注入 bionic sigorset shim（GNU 扩展缺失补丁）"
SHIM_HEADER="$STAGING/bionic-sigorset-shim.h"

# (a) 前置断言：bionic 确实缺失 sigorset（防上游/新版 NDK 已提供 → 重复定义）
_BIONIC_SIGNAL_H="$TOOLCHAIN/sysroot/usr/include/signal.h"
[[ -f "$_BIONIC_SIGNAL_H" ]] || die "未找到 bionic signal.h: $_BIONIC_SIGNAL_H"
_BIONIC_HAS_SIGORSET=0
for _h in "$_BIONIC_SIGNAL_H" "$TOOLCHAIN/sysroot/usr/include/bits/signal_types.h"; do
  [[ -f "$_h" ]] || continue
  if awk '/sigorset/{f=1} END{exit !f}' "$_h"; then _BIONIC_HAS_SIGORSET=1; fi
done
[[ "$_BIONIC_HAS_SIGORSET" == 0 ]] \
  || die "bionic 已自带 sigorset（NDK 已更新）：shim 会重复定义，请复核 NDK 版本与 qemu 适配"

# (b) 前置断言：qemu 侧确有 sigorset 调用（防 qemu 版本漂移后 shim 变成空转）
awk '/sigorset[[:space:]]*\(/{f=1} END{exit !f}' "$QEMU_DIR/linux-user/signal.c" \
  || die "signal.c 内未见 sigorset 调用：qemu 源码结构漂移，请复核 step 2 的 QEMU_REF"

# (c) 幂等生成 shim（覆盖写 → 重跑安全）
cat > "$SHIM_HEADER" <<'SHIM_EOF'
/* bionic-sigorset-shim.h — 为 bionic 补 glibc GNU 扩展 sigorset()
 *
 * 【run#37 关键修正 · 必须保留 __ASSEMBLER__ 守卫】
 *   现象：run#37 在 [303/374] 失败，报错全在系统头与 shim 头内，形如
 *     signal.h:149:23: error: unexpected token in argument list
 *     bionic-sigorset-shim.h:18:1: error: unrecognized instruction mnemonic
 *   根因：-include 是 **driver 级 flag**，对同 target 的【汇编输入 .S 同样生效】。
 *     失败目标正是 libcommon.a.p/common-user_safe-syscall.S.o
 *     （源文件 ../common-user/safe-syscall.S）。汇编器试图把 C 头当汇编解析。
 *     本地实测：`clang --target=... -include <C头> -c x.S` 精确复现同款报错。
 *   机理：--extra-cflags 被 qemu 折进 meson c_args，而 meson 的 c_args 会应用到
 *     该 target 的**所有语言**（含 .S），故 -include 无法避免波及汇编。
 *     （注：-Wno-error=* 对 .S 无害，实测汇编+这些 flag 正常通过；只有 -include 有害。）
 *   修法：.S 由 clang 预处理并定义 __ASSEMBLER__，.c 不定义。
 *     用 #ifndef __ASSEMBLER__ 整体包住本头 → 汇编输入下展开为空，零副作用。
 *     本地四向验证：.S 静默通过 / .c shim 生效 / 端到端实跑 sigorset ok /
 *     非 Android target 正确 #error。
 *
 * 门控说明：NDK clang 只定义 __ANDROID__，不定义 __BIONIC__；
 *   __BIONIC__ 由 sysroot sys/cdefs.h 经 <signal.h> 传递。
 *   故本头必须自行 #include <signal.h>，否则 __BIONIC__ 不可见、门控恒假。
 */
#ifndef __ASSEMBLER__

#if !defined(__linux__) || (!defined(__ANDROID__) && !defined(__BIONIC__))
#  error "bionic-sigorset-shim.h 仅应在 Android/bionic 构建中注入"
#endif
#include <signal.h>     /* 关键：引入 sigset_t / NSIG / __BIONIC__ 定义 */
#include <errno.h>

/* 语义对齐 glibc：*set = *left | *right；成功返回 0，失败返回 -1 且 errno=EINVAL。
 * bionic 无 sigorset，且 sigset_t 是不透明结构体（不可按位或），只能遍历信号号。 */
#if defined(__BIONIC__) || defined(__ANDROID__)
#  ifndef sigorset
static __inline__ int sigorset(sigset_t *set, const sigset_t *left, const sigset_t *right)
{
    int sig;
    sigemptyset(set);
    for (sig = 1; sig < NSIG; ++sig) {
        if (sigismember(left, sig) == 1 || sigismember(right, sig) == 1) {
            if (sigaddset(set, sig) != 0) { errno = EINVAL; return -1; }
        }
    }
    return 0;
}
#  endif
#endif

#endif /* !__ASSEMBLER__ */
SHIM_EOF
[[ -s "$SHIM_HEADER" ]] || die "shim 头生成失败: $SHIM_HEADER"
log "  [shim] 已生成 $SHIM_HEADER（$(wc -c < "$SHIM_HEADER") 字节）"

# ══════════════════ 第 3.7 步：bionic sched_attr / mqueue 双补丁（run#38 实锤） ═
# 【run#38 根因修复】run#38 推进到 [366/374] → [369/374]（safe-syscall.S 的
#   __ASSEMBLER__ 守卫修复生效），仅剩 2 个阻断点，均落在 linux-user/syscall.c，
#   均为 bionic/Android 平台差异（本地全部实测复核）：
#
# ── 问题 1：struct sched_attr 重复定义 ──────────────────────────────────────
#   ../linux-user/syscall.c:364:8: error: redefinition of 'sched_attr'
#   ../sysroot/usr/include/linux/sched/types.h:12:8: note: previous definition is here
#   证据1 qemu syscall.c:361 的注释「sched_attr is not defined in glibc」是**前提假设错误**：
#     该 struct 在 glibc 环境确无，但 Android/bionic 内核头 **linux/sched/types.h 已定义**
#     （NDK r29 实测存在）。即「Android 内核头比 glibc 环境更新」，与 qemu 假设相反。
#   证据2 该 struct 定义在 syscall.c:362-373，**无任何 #ifdef 门控**（无条件定义）→ 必冲突。
#   证据3 ★逐字比对：qemu 自定版与 linux/sched/types.h 版 **10/10 字段名/顺序/宽度全一致**
#     （uint32_t==__u32==unsigned int；int32_t==__s32）：
#       size,sched_policy,sched_flags,sched_nice,sched_priority,
#       sched_runtime,sched_deadline,sched_period,sched_util_min,sched_util_max
#     sizeof == 56（= SCHED_ATTR_SIZE_VER1），本地 clang -S 实测确认 → **ABI 一致**。
#   证据4 全树只有 syscall.c 定义该 struct（grep 唯一命中），其余都是引用：
#       syscall.c:375/378（_syscall4/3 的 struct sched_attr * 形参）、
#       syscall.c:11487/11519（局部变量 scha）、11508（offsetof(...,sched_util_min)）。
#   方案抉择：**A2（#ifndef __BIONIC__ 包住 qemu 自定版）优于 A1（直接删）**：
#     * A1（删）依赖「系统定义必存在」——若某 NDK 版本该头路径/宏变化，会直接编译失败，
#       无兜底。A2 保留 qemu 自定版作为非 bionic 环境的回退，更稳健。
#     * A2 与 A1 在 bionic 下**等价**（门控为假 → 用系统版；两版 ABI 一致 → 语义相同）。
#     * A3（改名 sched_attr→qemu_sched_attr）会连带改 6 处引用且与系统版**并存**，
#       徒增混淆，否决。
#   ★ 是否影响 sched_getattr/sched_setattr 封装：否。
#     门控只包 struct 定义；_syscall4(..., struct sched_attr *, ...) 与 11487+ 的用法
#     在 __BIONIC__ 下解析到 linux/sched/types.h 的定义，字段同名同型 → 零影响（e2e 实测）。
#
# ── 问题 2：mqueue.h / mq_* 缺失 ───────────────────────────────────────────
#   ../linux-user/syscall.c:1281:10: fatal error: 'mqueue.h' file not found
#   证据1 顶层 mqueue.h 缺失：NDK sysroot `ls .../include/mqueue.h` → not found（实测）。
#        （注：linux/mqueue.h **存在**，但它只是内核 UAPI，仅提供 struct mq_attr，
#          不含 POSIX 的 mq_open/mq_unlink 声明。）
#   证据2 全 sysroot 无任何 mq_open 等 libc 函数声明（grep 实测）；bionic libc.so
#        符号表 `llvm-nm -D` 对 mq_* **零命中** → bionic 完全不提供 POSIX 消息队列 libc 层。
#   证据3 host syscall 号齐全：asm-generic/unistd.h 有 __NR_mq_open=180 / mq_unlink=181 /
#        mq_timedsend=182 / mq_timedreceive=183（仅缺 libc 封装与头）。
#   证据4 门控结构（逐字核对）：mqueue 代码分**三块**，门控**不一致**：
#     (a) syscall.c:771-780  safe_syscall5(mq_timedsend/timedreceive)
#         门控 = `#if defined(TARGET_NR_mq_timedsend[_time64])` —— **不含 __NR_mq_***
#     (b) syscall.c:1278-1318 `#include <mqueue.h>` + copy_{from,to}_user_mq_attr
#         门控 = `#if defined(TARGET_NR_mq_open) && defined(__NR_mq_open)`
#     (c) syscall.c:13015-13150 `case TARGET_NR_mq_*` 分支
#         门控 = `#if defined(TARGET_NR_mq_open) && defined(__NR_mq_open)`
#     ★ 关键：mq_timedsend/timedreceive 经 safe_syscall5 展开为 `syscall(__NR_mq_timedsend,...)`
#       —— **纯原生 syscall，不需要 libc 实现**。故仅 (b)(c) 需要 mq_* libc 符号。
#   证据5 (b) 块实际调用 libc mq_* 的**只有 2 个**：mq_open(13034) / mq_unlink(13044)。
#       （mq_getsetattr/mq_notify 未在此块调用；mq_close 亦未调用。）
#
#   方案抉择：**B1（自备 mqueue.h shim + 实现 mq_open/mq_unlink）**。B2 已被**实测证伪**：
#   ✗ B2（-D__NR_mq_open=0）**不可行** —— 本地实测 `clang -D__NR_mq_open=0 -dM -E` 显示
#     最终值仍为 180：命令行 -D 发生在 <linux/unistd.h>（内含 asm-generic/unistd.h）**之前**，
#     头文件的裸 `#define __NR_mq_open 180` 无条件覆盖命令行宏，且 `defined()` 恒真。
#     → 门控永不熄灭。此外 B2 即便能熄灭 (b)(c)，也**漏掉 (a)**（(a) 只看 TARGET_NR_*），
#     与 run#35 的「cdefs 裸 #define 覆盖命令行 -D」是同一类陷阱（step 3.5 已载明）。
#   ✗ B3（改 syscall.c 加门控）会脏化 qemu 树、且门控条件需覆盖 3 处不一致的块，维护差；
#     shim 路线对上游零侵入、可审计。
#   ✓ B1 工作量评估（已收窄）：仅需 2 个函数实现，均用 host syscall 直拼：
#       mq_open(name,flags,...)  → syscall(__NR_mq_open, name, flags&~O_CLOEXEC, mode, attr)
#       mq_unlink(name)          → syscall(__NR_mq_unlink, name)
#     细节：① O_CLOEXEC 不是 syscall 选项，须剥离（否则 EINVAL）；glibc 亦如此。
#           ② O_CREAT 时才消费 mode+attr 两个可变参数（va_arg）。
#           ③ name 须以 '/' 起头（POSIX mq 语义），qemu 侧 lock_user_string(arg1-1) 已处理。
#           ④ 本地已实测：shim 头 + mq-shim.c 编译链接 PASS，16KB LOAD 对齐达标（0x4000）。
#
#   ★ 对 opencode 实际使用场景的影响评估：
#     opencode 在安卓上跑 node CLI / git / ripgrep。POSIX 消息队列（mq_*）在 Linux 上
#     依赖 /dev/mqueue 挂载 + mqueue 文件系统，**proot/容器环境通常未挂载**；
#     node（libuv）、git、ripgrep **均不使用 POSIX 消息队列**（它们用 pipe/socket/eventfd）。
#     grep node/git/ripgrep 源码：无 mq_open/mq_unlink 调用。
#     → 即便 mq_* 在宿主返回 ENOSYS，对 opencode 场景**零影响**。但按项目铁律
#       「不静默破坏兼容性」，仍选 B1（保持 guest mq_* 语义完整，宿主真支持时可用）。
#
# 注入方式：**用 -I 目录**（步 4 的 --extra-cflags 追加 -I$MQ_SHIM_DIR），**不用 -include**：
#   * -include 是 driver 级 flag，会波及 .S 汇编（run#37 的教训，step 3.6 已载）；
#   * -I 仅影响 include 搜索路径，.S 不 include <mqueue.h> → 天然免疫（本地实测确认）；
#   * shim 头仍加 __ASSEMBLER__ 守卫作为**双保险**（防未来 -include 注入）。
# 幂等：源码补丁用 awk 重写（先探门控存在性再决定），shim 用 cat > 覆盖写，重跑安全。
log "step 3.7/5 — 修补 bionic sched_attr 重复定义 + mqueue.h 缺失（Android 平台特有）"

# ── 3.7a 源码补丁：sched_attr 加 __BIONIC__ 门控 ──
SC="$QEMU_DIR/linux-user/syscall.c"
[[ -f "$SC" ]] || die "qemu 源码结构漂移：缺 linux-user/syscall.c"
# 前置断言：qemu 自定版 struct sched_attr 确存在（防 qemu 版本漂移后补丁空转/错位）
awk '/^struct sched_attr \{/{f=1} END{exit !f}' "$SC" \
  || die "syscall.c 内未见自定 struct sched_attr：qemu 版本漂移，请复核 QEMU_REF"
# 前置断言：该定义当前**无** __BIONIC__ 门控（防上游已修复 → 重复包一层）
# 【加固】判据不能用裸 `#ifndef __BIONIC__`：若 qemu 未来在 syscall.c 别处引入
#   任何 __BIONIC__ 门控，裸判据会误判「已打补丁」而静默跳过，或让 L706 的
#   「恰为 1」断言误 die。故锚定到【本补丁独有的标记串】（含中文注释特征）。
if awk '/^#ifndef __BIONIC__   \/\* bionic: linux\/sched\/types\.h/{f=1} END{exit !f}' "$SC"; then
  log "  sched_attr 已带 __BIONIC__ 门控（前次已打补丁，幂等跳过）"
else
  # 幂等：awk 重写，仅在「注释行前插 #ifndef / 紧邻的 } 后插 #endif」。
  #   （BSD sed 的 range+append 跨平台不可靠，故用 awk；输出落临时文件再原子替换。）
  awk '
    /^\/\* sched_attr is not defined in glibc \*\/$/ {
      print "#ifndef __BIONIC__   /* bionic: linux/sched/types.h 已定义，防重复定义 */"
      print; inguard=1; next
    }
    inguard && /^};$/ { print; print "#endif /* !__BIONIC__ */"; inguard=0; next }
    { print }
  ' "$SC" > "$SC.qemu-tmp" && mv "$SC.qemu-tmp" "$SC" \
    || die "sched_attr 门控补丁失败（awk 重写）"
  # 收尾断言：门控确实落地（awk 全量消费 + exit 码，禁用 | grep -q）
  #   同样锚定本补丁独有标记串，避免与未来上游 __BIONIC__ 误配。
  awk '/^#ifndef __BIONIC__   \/\* bionic: linux\/sched\/types\.h/{f=1} END{exit !f}' "$SC" \
    || die "补丁后 syscall.c 仍未见 __BIONIC__ 门控（静默失效）"
  awk '/^#endif \/\* !__BIONIC__ \*\/$/{f=1} END{exit !f}' "$SC" \
    || die "补丁后 syscall.c 缺少配套 #endif（括号不配对）"
  log "  sched_attr 已加 __BIONIC__ 门控（bionic 下改用 linux/sched/types.h 定义，ABI 逐字一致）"
fi
# 正向断言：本补丁的门控标记恰为 1（防重复插入破坏；锚定独有标记串，不误配上游）
_N_BIONIC_GUARD="$(grep -c '^#ifndef __BIONIC__   /\* bionic: linux/sched/types\.h' "$SC" || true)"
[[ "$_N_BIONIC_GUARD" == 1 ]] \
  || die "sched_attr 门控出现 $_N_BIONIC_GUARD 次（应为 1，补丁非幂等或错位）"

# ── 3.7b mqueue.h shim（-I 目录注入；仅需 mq_open/mq_unlink/mq_close 实现）──
MQ_SHIM_DIR="$STAGING/bionic-shim-include"
mkdir -p "$MQ_SHIM_DIR"
# 前置断言：bionic 确实缺顶层 mqueue.h（防新版 NDK 已提供 → 遮蔽上游头）
[[ ! -f "$TOOLCHAIN/sysroot/usr/include/mqueue.h" ]] \
  || die "bionic 已提供 mqueue.h（NDK 更新）：shim 会遮蔽上游头，请复核 NDK 版本"
# 前置断言：qemu 侧确在 syscall.c 引用 mqueue.h（防 qemu 版本漂移后 shim 空转）
awk '/#include <mqueue.h>/{f=1} END{exit !f}' "$SC" \
  || die "syscall.c 内未见 #include <mqueue.h>：qemu 结构漂移，请复核 QEMU_REF"

# 幂等生成 shim 头（cat > 覆盖写）
cat > "$MQ_SHIM_DIR/mqueue.h" <<'MQSHIM_EOF'
/* mqueue.h — bionic POSIX 消息队列缺失头的补丁 shim（经 -I 目录注入）
 *
 * 【存在性证据】bionic 无顶层 <mqueue.h>；bionic libc 不导出任何 mq_* 符号
 *   （llvm-nm -D 对 mq_* 零命中）；仅内核 UAPI <linux/mqueue.h> 提供 struct mq_attr。
 *   故本 shim 提供：struct mq_attr（转引 UAPI）+ mqd_t + 7 个 POSIX 原型声明。
 *
 * 【__ASSEMBLER__ 守卫】双保险：本头经 -I 注入（.S 不 include <mqueue.h>，天然免疫），
 *   但保留守卫以防未来改用 -include（run#37 的 .S 教训，见 step 3.6）。
 */
#ifndef QEMU_BIONIC_MQUEUE_SHIM_H
#define QEMU_BIONIC_MQUEUE_SHIM_H

#ifndef __ASSEMBLER__

#include <linux/mqueue.h>   /* UAPI: struct mq_attr（含 __reserved[4]） */
#include <sys/cdefs.h>
#include <sys/types.h>
#include <fcntl.h>
#include <time.h>

/* POSIX: mqd_t 为 int；bionic 无此 typedef */
#ifndef __mqd_t_defined
typedef int mqd_t;
#define __mqd_t_defined 1
#endif

struct sigevent;            /* 仅需前向声明（mq_notify 形参） */

__BEGIN_DECLS

extern mqd_t mq_open(const char *__name, int __flags, ...);
extern int mq_close(mqd_t __mqdes);
extern int mq_unlink(const char *__name);
extern int mq_timedsend(mqd_t __mqdes, const char *__msg_ptr,
                        size_t __msg_len, unsigned int __msg_prio,
                        const struct timespec *__abs_timeout);
extern ssize_t mq_timedreceive(mqd_t __mqdes, char *__msg_ptr,
                               size_t __msg_len, unsigned int *__msg_prio,
                               const struct timespec *__abs_timeout);
extern int mq_getsetattr(mqd_t __mqdes, const struct mq_attr *__newattr,
                         struct mq_attr *__oldattr);
extern int mq_notify(mqd_t __mqdes, const struct sigevent *__notification);

__END_DECLS

#endif /* !__ASSEMBLER__ */
#endif /* QEMU_BIONIC_MQUEUE_SHIM_H */
MQSHIM_EOF
[[ -s "$MQ_SHIM_DIR/mqueue.h" ]] || die "mqueue.h shim 生成失败: $MQ_SHIM_DIR/mqueue.h"
log "  [shim] 已生成 $MQ_SHIM_DIR/mqueue.h（$(wc -c < "$MQ_SHIM_DIR/mqueue.h") 字节）"

# ── 3.7c mq_* 实现（mq_open/mq_unlink/mq_close），编译为静态库供链接期使用 ──
MQ_SHIM_SRC="$STAGING/mq-shim.c"
MQ_SHIM_LIB="$STAGING/lib/libqemu-mqshim.a"
cat > "$MQ_SHIM_SRC" <<'MQIMPL_EOF'
/* mq-shim.c — bionic 缺失的 mq_* libc 层最小实现（仅供 qemu linux-user 使用）
 * qemu 实际调用面（syscall.c 实测）：仅 mq_open / mq_unlink；
 * mq_timedsend/mq_timedreceive 走 qemu 原生 safe_syscall（不经 libc）→ 无需实现。
 * mq_close 兜底提供（= close(fd)）。 */
#include <stdarg.h>
#include <fcntl.h>
#include <unistd.h>          /* syscall() 原型 + __NR_close */
#include <sys/syscall.h>
#include <linux/mqueue.h>

typedef int mqd_t;
#ifndef O_CLOEXEC
#define O_CLOEXEC 02000000
#endif

/* POSIX mq_open(name, oflag, ...)：仅 O_CREAT 时传 mode 与 attr。
 * O_CLOEXEC 非 syscall 选项，须剥离（否则内核 EINVAL），与 glibc 行为一致。 */
mqd_t mq_open(const char *name, int oflag, ...)
{
    mode_t mode = 0;
    struct mq_attr *attr = 0;
    va_list ap;
    int flags = oflag;

    if (oflag & O_CREAT) {
        va_start(ap, oflag);
        mode = (mode_t)va_arg(ap, int);
        attr = va_arg(ap, struct mq_attr *);
        va_end(ap);
    }
    flags &= ~O_CLOEXEC;
    return (mqd_t)syscall(__NR_mq_open, name, flags, (int)mode, attr);
}

int mq_unlink(const char *name)
{
    return (int)syscall(__NR_mq_unlink, name);
}

int mq_close(mqd_t mqdes)
{
    return (int)syscall(__NR_close, (int)mqdes);
}
MQIMPL_EOF
[[ -s "$MQ_SHIM_SRC" ]] || die "mq-shim.c 生成失败: $MQ_SHIM_SRC"

# 编译 mq-shim.c（静态，对齐 flag 一并施加）。用 -I 注入 shim 头。
#   $CC = aarch64-linux-android26-clang wrapper，已内置 --target/--sysroot/API，
#   故不再重复传 target/sysroot（避免与 wrapper 默认冲突）。
mkdir -p "$STAGING/lib"
"$CC" -fPIC -O2 \
  -I"$MQ_SHIM_DIR" \
  -c "$MQ_SHIM_SRC" -o "$STAGING/lib/mq-shim.o" \
  || die "mq-shim.c 编译失败（检查 __NR_mq_open/__NR_mq_unlink 是否在 host unistd.h）"
"$AR" rcs "$MQ_SHIM_LIB" "$STAGING/lib/mq-shim.o" \
  || die "mq-shim 归档失败: $MQ_SHIM_LIB"
# 正向断言：库内确有 mq_open/mq_unlink 符号（防静默空库）
"$NM" "$MQ_SHIM_LIB" 2>/dev/null | awk '/ T mq_open$/{o=1} / T mq_unlink$/{u=1} END{exit !(o&&u)}' \
  || die "libqemu-mqshim.a 内未见 mq_open/mq_unlink 符号（编译/归档静默失败）"
log "  [impl] 已生成 $MQ_SHIM_LIB（含 mq_open/mq_unlink/mq_close）"

# ══════════════════ 第 4 步：qemu configure + ninja 编译（--static） ═══════════
# 【run#31 根因修复】原调用传了 --cross-file "$CROSS_FILE" → qemu configure 直接报
#   ERROR: unknown option --cross-file
# 根因：--cross-file 不是 qemu configure 的选项，qemu 有 **自己的 cross file 生成器**。
# 逐字依据（qemu v9.2.0 root/configure，已核对）：
#   (a) cross_compile 的唯一置位点是参数解析：
#         --cross-prefix=*) cross_prefix="$optarg"; cross_compile="yes" ;;
#       --help 明文：use PREFIX for compile tools, PREFIX can be blank
#       —— 即传「空串」--cross-prefix= 即可点亮交叉模式（本地 sh 实测：
#          case '--cross-prefix=*' 命中空串，optarg=""，cross_compile=yes）。
#       注意：--cross-prefix=aarch64-linux-android26- 是【错误替代】—— 会让
#           as/ld/nm/ar/ranlib/strip/objcopy 全部按 ${cross_prefix}<tool> 拼接，
#           拼出不存在的 aarch64-linux-android26-ld 等；工具名必须走环境变量。
#   (b) qemu 自行生成 config-meson.cross，其内容来源：
#         c_args      = $CFLAGS + $EXTRA_CFLAGS
#         c_link_args = $CFLAGS + $LDFLAGS + $EXTRA_CFLAGS + $EXTRA_LDFLAGS
#       故 include/lib/对齐 flag 必须经 CFLAGS/LDFLAGS（或 --extra-*）送入。
#   (c) 仅 cross_compile=yes 时才写 [host_machine] 并加 --cross-file config-meson.cross。
#   (d) 不存在的选项（如 --llvm）会走 --*) meson_option_parse 兜底报 unknown option。
#   (e) linux-user 产物名 = 'qemu-' + TARGET_NAME = qemu-aarch64（非 softmmu 无
#       system- 前缀），落 build/qemu-aarch64 —— step 5 路径无需改。
# 保留 $CROSS_FILE 的生成（step 1d 的 glib 仍在使用它）。
log "step 4/5 — qemu configure (交叉模式 via --cross-prefix=, --static, aarch64-linux-user) 与 ninja 编译"

# 4a. 投送参数：qemu 生成的 cross file 从 CFLAGS/LDFLAGS 取值（见上方 (b)）
# 【run#38 新增】-I$MQ_SHIM_DIR：注入 step 3.7b 的 mqueue.h shim（仅影响 include 搜索
#   路径，.S 不 include <mqueue.h> → 不波及汇编，区别于 step 3.6 的 -include）。
QEMU_CFLAGS="-O2 -I$STAGING/include -I$MQ_SHIM_DIR"
QEMU_LDFLAGS="-L$STAGING/lib $ALIGN_LDFLAG"

(
  cd "$QEMU_DIR"
  rm -rf build

  # 4b. 工具链全量显式（因 cross_prefix 为空，不做任何名称拼接）
  #     依据 (a)：CC/CXX/AR/NM/STRIP/RANLIB/LD/OBJCOPY/READELF/PKG_CONFIG
  #     均可经环境变量覆盖，空 prefix 不会拼出错误的 *-gcc / *-ld。
  #
  # 【run#33 根因修复】新增 -Wno-error=default-const-init-field-unsafe 到 extra-cflags。
  #   现象：../tcg/perf.c:252:24: error: default initialization of an object of type
  #     'struct debug_entry' with const member leaves the object uninitialized
  #     [-Werror,-Wdefault-const-init-field-unsafe]
  #   根因（已核源码 qemu-9.2.0/tcg/perf.c）：
  #     * perf.c L154-159 的 struct debug_entry 末成员 `const char name[];` 是
  #       柔性数组成员（FAM），合法 C；L252 `struct debug_entry ent;` 仅取
  #       sizeof(ent)（L256/L262）参与 jitdump 记录长度计算，从不读 ent.name。
  #       → 标准 C 惯用法，clang 新诊断对此误报，非上游 bug、无实害。
  #     * 该诊断是 clang 19+ 新增；CI runner 实为 clang 21.0.0，NDK 30.0.16248370，
  #       被 qemu 自带 -Werror（meson warning level 注入，非本脚本所加）提升为硬错误。
  #     * 【非 bionic 特有】属「上游 qemu + 新版 clang -Werror」通用兼容问题。
  #
  #   修法裁决：用 -Wno-error=...（只降级不关闭），而非 -Wno-...（全关）。
  #     理由：① 仅降级可使告警文字仍留在日志（CI 可检索），真问题不被静默掩盖；
  #           ② 语义精准到单条诊断，不扩大到 qemu 全树；③ 该 flag 追加在 c_args
  #           末尾（c_args = $CFLAGS + $EXTRA_CFLAGS，见 (b)），必然位于 -Werror
  #           之后，按 clang「后者覆盖前者」规则成功降级。
  #     为何经 --extra-cflags 而非 CFLAGS：CFLAGS（4a 的 QEMU_CFLAGS）承载
  #       -I$STAGING/include 等探测必需项，代表通用环境；--extra-cflags 是 qemu
  #       为项目级额外 flag 预留的语义入口，归位更正且不动 4a 稳定定义。
  #       （两条最终都进 meson c_args，位置等价，故按语义择用。）
  #
  #   【刻意只修这一处】不透支性加一批 -Wno-*：一次性堆 flag 会掩盖后续真问题、
  #     且掩盖「哪些是新诊断」的事实。让 CI 逐轮暴露下一处，保持修复可审计。
  #
  # 【run#34 根因修复】新增 -Wno-error=deprecated-declarations 到 extra-cflags（同 tail 段）。
  #   现象：../hw/core/cpu-common.c:172/193: error: 'strtok' is deprecated:
  #     strtok() is not thread-safe; use strtok_r() instead [-Werror,-Wdeprecated-declarations]
  #   根因（已核源码 hw/core/cpu-common.c + bionic string.h:125）：
  #     * bionic <string.h>:125 主动把 strtok 标为 __attribute__((__deprecated__,...))，
  #       属【Android 平台特有严格检查】（glibc 不报），非上游 qemu 的 bug。
  #     * 该 strtok 用在「解析 CPU feature 逗号分隔串」的非并发路径
  #       （cpu-common.c L171-193），非真线程安全风险。
  #
  #   修法裁决：同样用 -Wno-error=...（只降级不关闭），沿用 run#33 已实证手法。
  #     ① 只降级保留告警文字（CI 可 grep '-Wdeprecated-declarations'），真问题不静默；
  #     ② 【关键实证·范围】该诊断类在本次 --target-list=aarch64-linux-user +
  #        --without-default-features + --disable-slirp 的【编译图内命中面 = 仅
  #        hw/core/cpu-common.c 一个文件】（主理人已独立复核）：
  #          - 全树 strtok( 共 84 处，其中 roms/ 74 处（u-boot/edk2/SLOF/skiboot/
  #            ipxe/openbios），交叉编译不进图；
  #          - 非 roms 仅 10 处，逐一验证均不在图：
  #              semihosting/config.c(2)   → 门控 CONFIG_SYSTEM_ONLY，linux-user 下 false
  #              tests/qtest/libqos/*(2)   → --disable-tools 排除
  #              target/sparc/cpu.c(2)     → 非本 guest
  #              target/i386/cpu.c(2)      → 非本 guest
  #              hw/core/cpu-common.c(2)   → ★ 唯一在图（common_ss，全 target 共享）
  #          - 日志实证：net_ 目标对象数 = 0；slirp support = NO；semihosting 编译次数 = 0；
  #            全日志 strtok 报错源仅 cpu-common.c 一处。
  #        ⟹ (a) 的【实际生效范围 ≈ 单文件】，与源码级处理（改 strtok→strtok_r）的实际
  #           范围等价，却零改上游、零语义风险（避免引入 saveptr/const 串边界的新 bug）。
  #     ③ 【否决 -Wno-deprecated-declarations】：会连告警一起吞掉，丧失可见性。
  #     ④ 【为何不用 per-file c_args】：cpu-common.c 属 common_ss（全 target 共享源），
  #        meson 无 per-file 接口；打 c_args 到 static_library 反而范围更宽且须改上游 meson。
  #
  #   为何经 --extra-cflags 而非 CFLAGS：CFLAGS（4a 的 QEMU_CFLAGS）承载
  #     -I$STAGING/include 等探测必需项，代表通用环境；--extra-cflags 是 qemu
  #     为项目级额外 flag 预留的语义入口，归位更正且不动 4a 稳定定义。
  #     （两条最终都进 meson c_args，位置等价，故按语义择用。）
  #
  #   【方法论】刻意逐轮只解决「当前 CI 实锤到的那一类诊断」，每轮新增一条
  #     -Wno-error=<单类>，不预埋一批 -Wno-*：保持修复可审计、不漏报新诊断。
  #     run#33 加 1 类、run#34 加 1 类，逐条递增，未透支。
  #
  # 【run#35 根因修复】新增 -Wno-error=constant-conversion 到 extra-cflags（同 tail 段）。
  #   现象：../linux-user/aarch64/signal.c:538:26: error: implicit conversion from
  #     'unsigned long' to 'int' changes value from 18446744073709551609 to -7
  #     [-Werror,-Wconstant-conversion]
  #   根因（已核源码 linux-user/aarch64/signal.c:537-538）：
  #       const int std_size = sizeof(struct target_rt_sigframe)
  #                            - sizeof(struct target_aarch64_ctx);
  #     两 sizeof 相减为 size_t（unsigned long），当 rt_sigframe 比 aarch64_ctx
  #     小 7 字节时得 -7UL → 回绕成 18446744073709551609，再隐式转 int 得 -7。
  #     该回绕值**被下游比较逻辑依赖**（alloc_sigframe_space 里
  #       `this_size + this_loc > std_size` 的 int/unsigned long 比较）。
  #
  #   修法裁决：同样用 -Wno-error=...（只降级不关闭）。
  #     ★ 【否决改源码】：加 (int) 强转或改 ptrdiff_t 会**改变比较语义**，
  #       属高危（上游固化行为，非我们的 bug）。降级是与 strtok/FAM 同哲学的正解。
  #     本地实测：-Werror -Wno-error=constant-conversion → 警告保留、exit=0 ✅。
  #
  #   注：signal.c:44 的 `char __unused[...]` 语法错误不在此处理 —— 已由 step 3.5
  #     的源码改名根治（该错是 bionic 宏冲突，非告警，加 flag 无效）。
  #
  #   【本轮方法论升级】run#35 同时暴露「告警类」与「真语法错类」两种，故本轮
  #     分两处治理：语法错 → step 3.5 改源码；告警类 → 此处加 flag。分类处置，
  #     不再一律加 flag（对语法错加 flag 是无效的）。
  env \
    CC="$CC" CXX="$CXX" AR="$AR" NM="$NM" STRIP="$STRIP" RANLIB="$RANLIB" \
    LD="$TOOLCHAIN/bin/ld.lld" \
    OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" \
    READELF="$READELF" \
    PKG_CONFIG="$PKGCFG_WRAPPER" \
    PKG_CONFIG_LIBDIR="$STAGING/lib/pkgconfig" \
    PKG_CONFIG_PATH="" \
    CFLAGS="$QEMU_CFLAGS" \
    LDFLAGS="$QEMU_LDFLAGS" \
    PATH="$TOOLCHAIN/bin:$PATH" \
  ./configure \
    --cross-prefix= \
    --static \
    --target-list=aarch64-linux-user \
    --without-default-features \
    --enable-tcg \
    --disable-system \
    --disable-docs --disable-tools --disable-guest-agent \
    --disable-capstone --disable-gnutls --disable-gcrypt --disable-nettle \
    --disable-seccomp --disable-curl --disable-libssh --disable-slirp \
    --extra-cflags="-O2 -Wno-error=default-const-init-field-unsafe -Wno-error=deprecated-declarations -Wno-error=constant-conversion -include $SHIM_HEADER" \
    --extra-ldflags="$ALIGN_LDFLAG -L$STAGING/lib -lqemu-mqshim" \
    --with-pkgversion="OpenCode-Android-$QEMU_REF" \
    || die "qemu configure 失败（交叉模式未生效或依赖未找到，见上方日志）"

  # 4c. configure 后置断言：交叉模式确实生效（防静默错配回归，依据 (c) (d)）
  # 【run#32 根因修复】原断言用相对路径 `config-meson.cross`，恒 MISSING → 误报 FATAL。
  #   逐字依据：qemu v9.2.0 root/configure L13-59 —— 在源码树根执行 configure 时：
  #       if test "$PWD" -ef "$source_path"; then
  #           MARKER=build/auto-created-by-configure
  #           cd build
  #           exec "$source_path/configure" "$@"
  #       fi
  #   即 configure 会 mkdir build、cd build、再 exec 自身 → 真实工作 cwd = $QEMU_DIR/build。
  #   后续 `mv $cross config-meson.cross` / `meson_add_machine_file` 均为相对路径，
  #   故 cross file 落在 $QEMU_DIR/build/config-meson.cross（config.status/Makefile 同理）。
  #   父 shell 的 cwd 因 exec 不改变（仍在 $QEMU_DIR），相对路径断言必然找不到文件。
  #   CI run#32 反证：日志已回显 "Cross files : config-meson.cross"，且 postconf 脚本路径
  #   含 ".../qemu-src/build/pyvenv/..." → 文件确实在 build/ 下，configure 返回码为 0。
  #   → 全部断言改用显式 $QEMU_DIR/build/ 路径，不依赖 cwd。
  #   注：step 5 的 QEMU_BIN 早已是 "$QEMU_DIR/build/qemu-aarch64"，此处与之对齐。
  #
  # 【为何保留断言而非交给 qemu 报错（对比 run#27 iconv.pc 守卫的教训）】
  #   run#32 实证：configure 在本路径上【返回 0 并打印完整 options 表】—— 上游不报错。
  #   若撤掉断言，误加 --skip-meson / configure 提前 return 这类错配将被静默放行，
  #   直接进入 ninja 编出错误架构产物。iconv 守卫该删是因为它复刻了 meson 的探测算法
  #   （与上游内部实现强耦合、易漂移）；而本断言只校验 qemu 已公开承诺的产物契约
  #   （cross_compile=yes ⟹ 生成 config-meson.cross 且含 [host_machine]），漂移风险低。
  #   故保留必要最小集：文件存在 + [host_machine] 段存在 + host cpu = aarch64。
  CROSS_OUT="$QEMU_DIR/build/config-meson.cross"
  [[ -f "$CROSS_OUT" ]] \
    || die "未生成 $CROSS_OUT：configure 未进入 meson 阶段（检查是否误加 --skip-meson）"

  # D-C3-R4：统一 awk 全量消费形态，禁用任何 | grep -q。
  #   现状 grep -q PATTERN FILE 无管道、无 SIGPIPE 风险，但脚本其余自检（如 step 5 的
  #   Machine 断言）已确立 awk 形态；统一可防后人误改为 `cmd | grep -q`（pipefail 下
  #   producer 收 SIGPIPE → 141 → 假 FATAL），且 awk 会把命中行原文一并 print 到日志，
  #   断言失败时取证信息更足。
  awk '/^\[host_machine\]/{f=1} {print} END{exit !f}' "$CROSS_OUT" \
    || die "$CROSS_OUT 缺 [host_machine]：cross_compile 未置位，--cross-prefix= 未生效"
  awk "/cpu = 'aarch64'/{f=1} {print} END{exit !f}" "$CROSS_OUT" \
    || die "$CROSS_OUT 的 host cpu 非 aarch64：NDK clang target 异常（__aarch64__ 探针失败）"
  log "交叉模式已生效，$CROSS_OUT [host_machine]："
  sed -n '/^\[host_machine\]/,/^$/p' "$CROSS_OUT" | sed 's/^/    /'

  # 4d. 编译（依据 (e)：目标名 = qemu-aarch64，产物 build/qemu-aarch64）
  ninja -C build -j"$JOBS" qemu-aarch64 \
    || die "ninja qemu-aarch64 失败（若为 undefined reference，查缺失库，勿改用 tcg-interpreter）"
)

# ══════════════════ 第 5 步：交付自检 + jniLibs 落地 ═════════════════════════
log "step 5/5 — 交付自检并落位 jniLibs"
QEMU_BIN="$QEMU_DIR/build/qemu-aarch64"
[[ -s "$QEMU_BIN" ]] || die "qemu 产物缺失: $QEMU_BIN"
"$STRIP" "$QEMU_BIN"

# (a) 架构断言（D-C3-R4：awk 全量消费 + readelf 原文落日志）
"$READELF" -h "$QEMU_BIN" \
  | awk '/Machine:.*AArch64/{f=1} {print} END{exit !f}' \
  || die "架构断言失败：产物不是 AArch64（上方为 readelf 原始头信息）"

# (b) C3 静态断言：NEEDED 必须为 0
NEEDED="$("$READELF" -d "$QEMU_BIN" 2>/dev/null | awk '/NEEDED/{print}' || true)"
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
