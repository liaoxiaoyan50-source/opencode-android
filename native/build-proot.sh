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
[[ -x "$CC" ]] || die "NDK 编译器不存在: $CC"

# ── sysroot 平台库搜索路径（CI run#18 根因修复）───────────────────────
# run#18 报 `ld.lld: error: unable to find library -llog`（-landroid 同）：
# 原 LDFLAGS 只挂了 -L$STAGING/lib（自建库），从未把 NDK sysroot 库目录纳入
# 搜索路径。liblog/libandroid 是平台库，clang driver 在非 -static 下虽通常
# 自动带入，但显式 -L 更稳妥、且 r29 与 r30 的目录布局不同：
#   * NDK r29: sysroot/usr/lib/<abi>/<api>/   （带 API 子目录）
#   * NDK r30: sysroot/usr/lib/<abi>/         （扁平化，实测 runner 已升 r30）
# 两候选都挂 → 双版本通吃（目录不存在则跳过，不报错）。
NDK_SYSROOT_LIBDIR="$TOOLCHAIN/sysroot/usr/lib/aarch64-linux-android"
SYSROOT_LDFLAGS=""
for _d in "$NDK_SYSROOT_LIBDIR/${API_LEVEL}" "$NDK_SYSROOT_LIBDIR"; do
  [[ -d "$_d" ]] && SYSROOT_LDFLAGS="$SYSROOT_LDFLAGS -L$_d"
done
[[ -n "$SYSROOT_LDFLAGS" ]] || die "未定位到 NDK sysroot 库目录: $NDK_SYSROOT_LIBDIR"
log "sysroot -L:$SYSROOT_LDFLAGS"

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
  #
  # -D_PATH_TMP='"/tmp/"'（CI 实测 NDK 30 / bionic 必需）：shmem.c:352 经
  #   ASHV_KEY_SYMLINK_PATH 引用 _PATH_TMP 拼接 key 符号链接路径，而 AOSP
  #   bionic 的 paths.h 无此宏（glibc 独有）→ 编译期 undeclared identifier。
  #   termux 靠 ndk-patches/<n>/paths.h.patch 构建期给 NDK sysroot 追加
  #   _PATH_TMP（值 = $PREFIX/tmp/）；我们运行环境非 termux、无 $PREFIX，
  #   按 glibc 语义注入等价值：bash 单引号包双引号字面量，clang 实际收到
  #   -D_PATH_TMP="/tmp/"，宏值为字符串字面量，拼接后 = "/tmp/ashv_key_%d"。
  #   冲突自查：shmem.c 及其包含头均无 _PATH_TMP 的 #define（仅此一处引用），
  #   命令行 define 无重定义冲突。运行时影响面：仅 proot 的 sysvipc 可选
  #   扩展在 chroot 内客户程序使用 SysV IPC 时激活；opencode（Node.js）不用
  #   SysV shm，proot 核心 chroot/bind/exec 路径零依赖。
  for f in *.c; do
    "$CC" -O2 -D_GNU_SOURCE -D_PATH_TMP='"/tmp/"' -I. -c "$f" -o "${f%.c}.o"
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

# ══════════════════════ 第 3 步：proot 本体 ════════════════
log "step 3/4 — 编译 proot $PROOT_REF (Termux fork)"
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

# ── CI run#10 根因修复：隐式声明防御补丁（NDK r29 / clang 19 硬错误）─────────
# 实锤（CI run#10）：src/extension/ashmem_memfd/ashmem_memfd.c 调用 strcmp/
#   memset 但未 #include <string.h>（其 include 表仅 stdlib/signal/unistd/
#   sys/syscall/linux/ashmem/linux/memfd/talloc 与项目内头），靠 C89 时代的
#   隐式函数声明。NDK r29（clang 19，runner 实测 29.0.14206865；clang 16 起
#   已把 C99+ 模式下的 -Wimplicit-function-declaration 从警告升级为默认硬
#   错误）直接编译失败：
#     error: call to undeclared library function 'strcmp' ...
#            [-Wimplicit-function-declaration]
#   属 proot 老源码 × 新工具链问题，与构建配方无关。明确不采用全局
#   -Wno-error=implicit-function-declaration 降级：会掩盖真问题，且 64 位
#   ABI 上隐式声明假定 int 返回值、返回指针的函数被截断属实险——宁可
#   include 补齐。
# 方案：编译前源码自愈。grep -E（≡egrep）扫描 proot-src 全部 .c（含 src/
#   及其 extension/ 等子目录；CI 日志里的 ./extension/... 即相对 src/），
#   凡用到下列函数但文件内缺对应 #include 的，在第 1 行前插入（合法 C，
#   位于最顶注释之前亦可）：
#     * string.h 族：strcmp/strncmp/strcpy/strncpy/strcat/strncat/strlen/
#       strnlen/strchr/strrchr/strstr/strdup/strndup/strspn/strcspn/strpbrk/
#       strtok/strtok_r/strerror/strerror_r/strcasecmp/strncasecmp/strsignal/
#       memset/memcpy/memmove/memcmp/memchr/memmem
#     * stdio.h 族：snprintf/sprintf/sscanf/vsnprintf/fprintf/printf/puts/
#       fputs —— 注意 snprintf 声明在 stdio.h 而非 string.h，必须分族判定
#       补齐，否则"只插 string.h"的防御对 snprintf 类缺失根本无效。
#     * 项目内函数族（CI run#11 实锤 + 项目内函数族）：标准库扫描覆盖不到
#       项目内部符号。extension/sysvipc/sysvipc_shm.c L912/L918 调用
#       libandroid_shmat_fd / libandroid_shmdt_fd（位于 #if
#       WITH_LIBANDROID_SHMEM 分支内 = 本构建必编译路径，GNUmakefile L20-22:
#       PROOT_WITH_LIBANDROID_SHMEM=true → CFLAGS += -DWITH_LIBANDROID_SHMEM），
#       其 include 表（sysvipc.h/sysvipc_internal.h/tracee/*/sys/shm.h 等）
#       无 libandroid-shmem 的 shm.h → 隐式声明，clang 19 硬错误：
#         ./extension/sysvipc/sysvipc_shm.c:913:18: error: call to undeclared
#           function 'libandroid_shmat_fd' [-Wimplicit-function-declaration]
#       （CI 行号 913/919 = 上游 912/918 + 本补丁第 1 行插入偏移 1 行，实锤
#        同一构建内前两族补丁已生效、新符号族暴露。）
#       取证链（v5.1.107.96 × libandroid-shmem v0.7 源码核验）：
#         a) 声明 = libandroid-shmem 的 shm.h L34-35：
#              extern int libandroid_shmat_fd(int shmid, size_t* out_size);
#              extern int libandroid_shmdt_fd(int fd);
#            定义 = 同库 shmem.c L508/L538（step1 已编入 libandroid-shmem.a，
#            链接期由 GNUmakefile ifdef 分支追加的 -landroid-shmem 解析）。
#            → 声明真实存在且头文件已随 step1 `install -m 644 ./*.h` 落入
#            $STAGING/include/shm.h，走方案 A（include 补齐），方案 B
#            （-Wno-error=implicit-function-declaration）否决：有正确头可用
#            无需降级掩盖，且隐式声明下 size_t* 参数传递无原型保护。
#         b) include 形式取 <shm.h>（外部依赖头，与 proot 对外部库一律 <>
#            的惯例一致；项目内头才用 ""）。命中路径：env CPPFLAGS 的
#            -I$STAGING/include 居 -I 序列首位（GNUmakefile 的 CPPFLAGS +=
#            追加在其后，run#5 已验证），<> 先搜 $STAGING/include/shm.h；
#            bionic sysroot 无顶层 shm.h（仅 sys/shm.h），proot src/ 树内
#            亦无同名头（已 find 核验），无遮蔽风险。
#         c) guard 短路排除：bionic <sys/shm.h> 用 #pragma once（无传统
#            include guard），与 libandroid-shmem shm.h 的 #ifndef
#            _SYS_SHM_H 不互斥——两头均展开，原型必然可见（shm.h 插在
#            第 1 行、先于 sysvipc_shm.c 原有的 #include <sys/shm.h>）。
#         d) 宏重定向副作用为零：shm.h 同时 #define shmget→libandroid_shmget
#            等（ashmem 模拟重定向）；而 shmem.c 自身 include "shm.h"、
#            #undef 后以 __attribute__((alias)) 同时导出 libandroid_shm* 与
#            POSIX shm* 两套符号（指向同一 ashmem 实现）——sysvipc_shm.c 的
#            shmget/shmctl 调用重定向与否都绑定同一实现，-landroid-shmem
#            全覆盖，链接零漂移。
#         e) 标识符污染排除：sysvipc_shm.c 内 shm* 仅出现于调用语句与注释
#            （已 grep 核验），无变量/字段名冲突。
#       全树风险面已用「调用符号 × 声明头 × include 传递闭包」扫描器复核
#       （解析引号+尖括号 include、剥离 #define 宏体、剔除"定义在调用者
#       自身文件"类误报）：项目内部符号的隐式声明风险有且仅有本两处调用；
#       sysvipc.c/sysvipc_sem.c/sysvipc_msg.c 无 libandroid_*/ashv_* 调用，
#       不命中本族。后续若再现新项目内符号族，按同模式扩展映射即可。
#   插第 1 行安全性（已核验上游 src/GNUmakefile）：-D_GNU_SOURCE 经
#   CPPFLAGS 放在编译命令行、先于一切头文件解析，include 顺序不影响特性宏
#   展开；loader/loader.c 为 -ffreestanding 的 NO_LIBC_HEADER 独立代码，
#   不调用任何标准库字符串/内存函数（自带 clear/basename），扫描不会命中、
#   freestanding 语义零干扰。
# 幂等：已显式包含对应头文件的文件自动跳过，KEEP_BUILD=1 重跑不重复插入。
# sed 用 POSIX 标准 'i\' + 换行形式（GNU/BSD 通用），-i.bak 后删备份以兼容
# macOS sed 的 -i 必须带后缀。若 CI 再现其他头文件族（stdlib.h/errno.h 等）
# 同类错误，按同模式扩展即可。
log "隐式声明防御补丁（clang 19 硬错误 → 编译前源码补齐 include）"
_PATCH_COUNT=0
while IFS= read -r -d '' f; do
  if grep -Eq '(^|[^A-Za-z0-9_])(strcmp|strncmp|strcpy|strncpy|strcat|strncat|strlen|strnlen|strchr|strrchr|strstr|strdup|strndup|strspn|strcspn|strpbrk|strtok|strtok_r|strerror|strerror_r|strcasecmp|strncasecmp|strsignal|memset|memcpy|memmove|memcmp|memchr|memmem)[[:space:]]*\(' "$f" \
     && ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]*[<"]string\.h[>"]' "$f"; then
    sed -i.bak '1i\
#include <string.h>' "$f" || die "隐式声明补丁失败: $f"
    rm -f "$f.bak"
    log "  [patch] + #include <string.h> ← ${f#"$PROOT_DIR"/}"
    _PATCH_COUNT=$((_PATCH_COUNT + 1))
  fi
  if grep -Eq '(^|[^A-Za-z0-9_])(snprintf|sprintf|sscanf|vsnprintf|fprintf|printf|puts|fputs)[[:space:]]*\(' "$f" \
     && ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]*[<"]stdio\.h[>"]' "$f"; then
    sed -i.bak '1i\
#include <stdio.h>' "$f" || die "隐式声明补丁失败: $f"
    rm -f "$f.bak"
    log "  [patch] + #include <stdio.h> ← ${f#"$PROOT_DIR"/}"
    _PATCH_COUNT=$((_PATCH_COUNT + 1))
  fi
  # 第三族：项目内部函数（CI run#11 实锤 + 项目内函数族）。
  # libandroid_shmat_fd / libandroid_shmdt_fd 的声明在 libandroid-shmem 的
  # shm.h（step1 已随 `install -m 644 ./*.h` 装入 $STAGING/include/shm.h），
  # 定义在 libandroid-shmem.a（-landroid-shmem 解析）。<> 形式按 -I 顺序
  # （env CPPFLAGS 的 -I$STAGING/include 居首）必命中该头；bionic sysroot
  # 无顶层 shm.h、src/ 树内无同名头，无遮蔽。详见上方注释块取证链 a-e。
  # 幂等同前两族：已含 shm.h 的文件自动跳过。
  if grep -Eq '(^|[^A-Za-z0-9_])libandroid_(shmat_fd|shmdt_fd)[[:space:]]*\(' "$f" \
     && ! grep -Eq '^[[:space:]]*#[[:space:]]*include[[:space:]]*[<"]shm\.h[>"]' "$f"; then
    sed -i.bak '1i\
#include <shm.h> /* libandroid-shmem: libandroid_shmat_fd/shmdt_fd 声明（CI run#11 项目内函数族） */' "$f" || die "隐式声明补丁失败: $f"
    rm -f "$f.bak"
    log "  [patch] + #include <shm.h> ← ${f#"$PROOT_DIR"/}"
    _PATCH_COUNT=$((_PATCH_COUNT + 1))
  fi
done < <(find "$PROOT_DIR" -type f -name '*.c' -print0)
log "  [patch] 完成：共插入 $_PATCH_COUNT 处 include"

(
  cd "$PROOT_DIR"
  # 参数与 termux-packages packages/proot/build.sh 对齐：
  #   -C src ............................ termux/proot 构建入口为 src/GNUmakefile
  #   PROOT_WITH_LIBANDROID_SHMEM=true .. 启用 ashmem shm 分支（引用 step1 产物）
  #   -DARG_MAX / -DVERSION ............. termux 配方原样 CPPFLAGS
  #   半静态（ADR-C3-R2）................ 自建库静态吸入，系统库动态 NEEDED
  #   ALIGN_LDFLAG ...................... D7：16KB host page LOAD 段对齐
  #
  # ── CI run#5 根因修复（step#11 链接期 undefined reference）──────────────────
  # CC/CPPFLAGS/CFLAGS/LDFLAGS 必须以【环境变量】前缀传给 make，不得写成 make
  # 命令行参数：GNU make 优先级 = 命令行变量 > makefile 内赋值（含 +=），
  # 命令行传 flags 会把 GNUmakefile 的这些追加全部压掉——
  #     CPPFLAGS += -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$(VPATH)
  #     CFLAGS   += -Wall -Wextra -O2 -DWITH_LIBANDROID_SHMEM
  #     LDFLAGS  += -ltalloc -Wl,-z,noexecstack（ifdef 分支另 += -landroid-shmem）
  # → -ltalloc / -landroid-shmem 根本进不了链接行 → undefined reference。
  # 改为环境变量后（优先级低于 makefile 赋值），+= 正常追加，与 termux 官方
  # 构建一致。已用 pinned GNUmakefile + make -n 干跑验证最终装配：
  #   proot 链接 = <objs> -Wl,-z,max-page-size=16384 -L<staging>/lib
  #                -L<sysroot> -llog -landroid -ltalloc -Wl,-z,noexecstack
  #                -landroid-shmem
  #
  # -llog / -landroid = 链 libandroid-shmem v0.7 的硬依赖（源码实测）：
  #   shmem.c 无条件 #include <android/log.h>，DBG(...)=__android_log_print；
  #   android26（API>=26）分支改走 ASharedMemory_create/getSize（属 libandroid）。
  #   【ADR-C3-R2 纠正】早期注释断言"NDK sysroot 自带 liblog.a / libandroid.a，
  #   按需抽成员、不产生 NEEDED"——**该论断经 NDK r29 实测为假**：
  #   find $NDK -name liblog.a / libandroid.a 均零结果，sysroot 下仅有
  #   liblog.so(12KB)/libandroid.so(48KB)（位于 <abi>/<api>/ 内）。故二者必然
  #   产生动态 NEEDED，C3 已由"全静态"修订为"半静态"（系统库动态许可），
  #   断言改为 C3-N 白名单（见 step4 (b)）。二者置于 GNUmakefile 追加的
  #   -landroid-shmem 之前即可：lld 惰性解析、顺序无关（本机实测：
  #   GNU ld 同序报 undefined reference，lld 同序链接通过）。
  #
  # 其他兼容点（均已核验，无需改动）：
  #   * loader 用独立 LOADER_LDFLAGS（-static -nostdlib -Wl,-Ttext=...）链接，
  #     不消费 $(LDFLAGS) → env 传法不污染 freestanding loader（干跑已验证）；
  #   * loader.exe 的工具变量（CI run#15-17 根因修复）──────────────
  #     GNUmakefile 的 STRIP ?= $(CROSS_COMPILE)strip，未显式传入时取【宿主
  #     x86_64 strip】——L240 规则 `$(STRIP) $@` 处理 arm64 的 loader.exe 时
  #     直接报错：strip: Unable to recognise the format of the input file
  #     `loader.exe' → make Error 1。OBJCOPY/OBJDUMP 同理（OBJIFY 链）。
  #     修复：把 NDK 三件套以环境变量传入（llvm-strip/llvm-objcopy/llvm-objdump
  #     均为 multi-target、能正确处理 aarch64 目标）。
  #     【教训】早期注释曾断言"OBJIFY 走 host 默认即可、无需干预"——那是基于
  #     run#5 能走到主链接的假象（当时从未跑到 loader.exe 规则），属误判，勿再
  #     依赖该结论。CI run#15/#16/#17 三轮全挂于此。
  #   * build.h 的 git describe --tags --dirty --abbrev=8 --always 在 --depth 1
  #     下由 --always 兜底（tag 在手则描述为 v5.1.107.96），无需干预。
  CC="$CC" \
  STRIP="$STRIP" \
  OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" \
  OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
  CPPFLAGS="-DARG_MAX=131072 -DVERSION=\"${PROOT_REF#v}\" -I$STAGING/include" \
  CFLAGS="-O2" \
  LDFLAGS="$ALIGN_LDFLAG -L$STAGING/lib $SYSROOT_LDFLAGS -llog -landroid" \
  make -C src -j"$JOBS" PROOT_WITH_LIBANDROID_SHMEM=true
  "$STRIP" src/proot
)

# ══════════════════════ 第 4 步：交付自检 + jniLibs 落地 ═════════════════════
log "step 4/4 — 交付自检并落位 jniLibs"
PROOT_BIN="$PROOT_DIR/src/proot"
[[ -s "$PROOT_BIN" ]] || die "proot 产物缺失: $PROOT_BIN"

# (a) 架构断言：必须是 AArch64
"$READELF" -h "$PROOT_BIN" | grep -q 'Machine:.*AArch64' \
  || die "架构断言失败：产物不是 AArch64"

# (b) C3-N 半静态断言：NEEDED 仅允许 Android 系统库；任何自建库(.so)出现即失败
#   【ADR-C3-R2 修订】原契约要求 NEEDED=0（全静态），但 NDK r29 实测：
#     * find $NDK -name liblog.a / libandroid.a → 零结果（NDK 从未提供二者的
#       静态库，只随 sysroot 提供 .so），而 lld 拒绝 `-static` 链接动态对象
#       （attempted static link of dynamic object .../liblog.so）；
#     * llvm-ar 无法从 .so 抽成员重建 .a（file too small to be an archive）。
#     → 全静态对本两库物理不可行，无任何 tunable 可绕过（架构总师终裁）。
#   修订为「半静态」：自建依赖（libtalloc/libandroid-shmem/proot 自身 .o）
#   必须静态吸入（.a）；仅允许 liblog/libandroid/libc/libdl/libm 等
#   /system/lib64 内建稳定 ABI 的系统库产生动态 NEEDED。
#   断言方向随之反转：从「NEEDED 必须为空」改为「NEEDED 每项必须命中白名单」——
#   这才是安全核心：libtalloc.so 等自建库一旦出现在 NEEDED 即被拒绝
#   （它们不在白名单，且运行期会真实加载失败）。
ALLOWED_RE='^(liblog\.so|libandroid\.so|libc\.so|libdl\.so|libm\.so|libstdc\+\+\.so|libc\+\+_shared\.so)$'
NEEDED_LIST="$("$READELF" -d "$PROOT_BIN" 2>/dev/null | awk '/NEEDED/{gsub(/[\[\]]/,"",$NF);print $NF}' || true)"
BAD=""
while IFS= read -r _n; do
  [[ -z "$_n" ]] && continue
  printf '%s' "$_n" | grep -qE "$ALLOWED_RE" || BAD="$BAD $_n"
done <<< "$NEEDED_LIST"
if [[ -n "$BAD" ]]; then
  die "半静态断言失败：出现非系统库动态依赖（违反 C3-N）：$BAD"
fi
log "半静态断言通过：NEEDED 全为系统库：$(printf '%s' "$NEEDED_LIST" | tr '\n' ' ')"

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
log "  架构: AArch64 / 半静态(自建库静态,系统库动态) / LOAD 段 Align >= 0x4000"
log "  版本: $PROOT_REF ($PROOT_COMMIT) · talloc $TALLOC_VERSION · libandroid-shmem $SHMEM_VERSION"
