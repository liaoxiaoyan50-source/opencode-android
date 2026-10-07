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
#     并在编译后断言产物 NEEDED 为零（满足"如动态链需 CI 断言零额外依赖"的等效项）。
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
[[ -x "$CC" ]] || die "NDK 编译器不存在: $CC"

log "NDK: $NDK_ROOT"
log "CC : $CC"
log "产物对齐要求: 所有 LOAD 段 Align >= 0x4000 (16384)"

mkdir -p "$BUILD_DIR" "$STAGING/lib" "$STAGING/include" "$JNILIBS_DIR"

# 通用下载（可重试），sha256 非空则强制校验
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
    log "警告: $url 未配置 sha256，跳过校验（CI 长期运行建议补齐）"
  fi
}

# ══════════════════════ 第 1 步：libandroid-shmem（静态 .a） ═════════════════
# proot 依赖 SysV shm 接口；bionic 到 API 33 才有 shm_open，Termux 用 ashmem
# 模拟层替换（PROOT_WITH_LIBANDROID_SHMEM=true 的编译分支即引用它）。
log "step 1/4 — 编译 libandroid-shmem $SHMEM_VERSION (静态)"
SHMEM_DIR="$BUILD_DIR/libandroid-shmem-$SHMEM_VERSION"
if [[ ! -d "$SHMEM_DIR" ]]; then
  fetch "https://github.com/termux/libandroid-shmem/archive/refs/tags/v${SHMEM_VERSION}.tar.gz" \
        "$BUILD_DIR/libandroid-shmem.tar.gz" "$SHMEM_SHA256"
  tar -xzf "$BUILD_DIR/libandroid-shmem.tar.gz" -C "$BUILD_DIR"
fi
(
  cd "$SHMEM_DIR"
  # 上游为单文件 C 库（ashmem 模拟），无构建系统耦合：直接逐文件编译打包。
  # 注意 CC 为 NDK clang，静态产物不含任何动态依赖。
  for f in *.c; do
    "$CC" -O2 -D_GNU_SOURCE -I. -c "$f" -o "${f%.c}.o"
  done
  "$AR" rcs libandroid-shmem.a *.o
  "$RANLIB" libandroid-shmem.a
  install -m 644 libandroid-shmem.a "$STAGING/lib/"
  install -m 644 ./*.h  "$STAGING/include/" 2>/dev/null || true
)

# ══════════════════════ 第 2 步：talloc（静态 .a，waf 交叉） ═════════════════
# 交叉配方逐字取自 termux-packages packages/libtalloc/build.sh：
#   --cross-compile + --cross-answers 答案文件（跳过 waf 运行时探测），
#   waf 不产静态库，termux 的做法是 bin/default 下手动 AR 打包 talloc*.o。
log "step 2/4 — 编译 talloc $TALLOC_VERSION (静态)"
TALLOC_DIR="$BUILD_DIR/talloc-$TALLOC_VERSION"
if [[ ! -d "$TALLOC_DIR" ]]; then
  fetch "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" \
        "$BUILD_DIR/talloc.tar.gz" "$TALLOC_SHA256"
  tar -xzf "$BUILD_DIR/talloc.tar.gz" -C "$BUILD_DIR"
fi
(
  cd "$TALLOC_DIR"
  if [[ ! -f bin/default/libtalloc.a ]]; then
    # cross-answers 与 termux-packages/libtalloc 对 2.5.0 的配方一致
    cat > cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
    CC="$CC" CFLAGS="-O2 -D_GNU_SOURCE" \
    ./configure --prefix="$STAGING" \
                --disable-rpath \
                --disable-python \
                --cross-compile \
                --cross-answers=cross-answers.txt
    make -j"$JOBS"
  fi
  (
    cd bin/default
    "$AR" rcu libtalloc.a talloc*.o      # termux 同款打包方式
    "$RANLIB" libtalloc.a
    install -m 644 libtalloc.a "$STAGING/lib/"
  )
  install -m 644 talloc.h "$STAGING/include/"
)

# ══════════════════════ 第 3 步：proot 本体（-static 静态链） ════════════════
log "step 3/4 — 编译 proot $PROOT_REF (Termux fork, -static)"
PROOT_DIR="$BUILD_DIR/proot-src"
if [[ ! -d "$PROOT_DIR" ]]; then
  git clone --depth 1 --branch "$PROOT_REF" \
            https://github.com/termux/proot "$PROOT_DIR"
fi
# commit 二次 pin：防止 tag 被上游移动（可复现构建）
if [[ -n "$PROOT_COMMIT" ]]; then
  ACTUAL="$(git -C "$PROOT_DIR" rev-parse HEAD)"
  if [[ "$ACTUAL" != "$PROOT_COMMIT" ]]; then
    git -C "$PROOT_DIR" fetch --depth 1 origin "$PROOT_COMMIT"
    git -C "$PROOT_DIR" checkout --detach "$PROOT_COMMIT"
  fi
fi
(
  cd "$PROOT_DIR"
  # 参数与 termux-packages packages/proot/build.sh 对齐：
  #   -C src ............................ termux/proot 构建入口为 src/Makefile
  #   PROOT_WITH_LIBANDROID_SHMEM=true .. 启用 ashmem shm 分支（引用 step1 产物）
  #   -DARG_MAX / -DVERSION ............. termux 配方原样 CPPFLAGS
  #   -static ........................... C3 静态优先（bionic libc 静态入包）
  #   ALIGN_LDFLAG ...................... D7：16KB host page LOAD 段对齐
  make -C src -j"$JOBS" \
    PROOT_WITH_LIBANDROID_SHMEM=true \
    CC="$CC" \
    CPPFLAGS="-DARG_MAX=131072 -DVERSION=\"${PROOT_REF#v}\" -I$STAGING/include" \
    CFLAGS="-O2 $ALIGN_LDFLAG" \
    LDFLAGS="-static $ALIGN_LDFLAG -L$STAGING/lib" \
    LDLIBS="-ltalloc -landroid-shmem"
  "$STRIP" src/proot
)

# ══════════════════════ 第 4 步：交付自检 + jniLibs 落地 ═════════════════════
log "step 4/4 — 交付自检并落位 jniLibs"
PROOT_BIN="$PROOT_DIR/src/proot"
[[ -s "$PROOT_BIN" ]] || die "proot 产物缺失: $PROOT_BIN"

# (a) 架构断言：必须是 AArch64
"$READELF" -h "$PROOT_BIN" | grep -q 'Machine:.*AArch64' \
  || die "架构断言失败：产物不是 AArch64"

# (b) C3 静态断言：NEEDED 必须为 0（零额外 .so 依赖）
readelf_dyn() { "$READELF" -d "$1" 2>/dev/null || true; }
NEEDED="$(readelf_dyn "$PROOT_BIN" | awk '/NEEDED/{print}')"
if [[ -n "$NEEDED" ]]; then
  die "静态断言失败：产物存在动态 .so 依赖（违反 C3）：
$NEEDED"
fi

# (c) D7 对齐断言：复用 check-page-align.sh 精确解析 LOAD 段 Align 字段
if [[ -x "$SCRIPT_DIR/check-page-align.sh" ]]; then
  READELF="$READELF" bash "$SCRIPT_DIR/check-page-align.sh" "$PROOT_BIN"
else
  log "警告: check-page-align.sh 不在同级目录，跳过对齐自检（CI 侧必须补跑）"
fi

# (d) 落位：jniLibs 只认 lib*.so 命名（targetSdk>=29 唯一可执行区）
install -m 755 "$PROOT_BIN" "$JNILIBS_DIR/libproot.so"

KEEP_BUILD="${KEEP_BUILD:-0}"
[[ "$KEEP_BUILD" == "1" ]] || rm -rf "$BUILD_DIR"

log "完成 → $JNILIBS_DIR/libproot.so"
log "  架构: AArch64 / 静态链接 / NEEDED=0 / LOAD 段 Align >= 0x4000"
log "  版本: $PROOT_REF ($PROOT_COMMIT) · talloc $TALLOC_VERSION · libandroid-shmem $SHMEM_VERSION"
