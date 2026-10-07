# P2b — 原生编译说明（native-builder 交付文档）

- 负责人：native-builder（丁铸基）；对齐契约：P1-架构总纲 §2 D4/D5/D7、§3.3 C3、§5.2
- 交付脚本（已由主理人逐行核验，本文档只做说明，**不构成对脚本的任何修改**）：
  - `native/build-proot.sh` — proot 交叉编译与 jniLibs 落地
  - `native/build-qemu-user-static.sh` — qemu-user 静态交叉编译与 jniLibs 落地
  - `native/check-page-align.sh` — 16KB 对齐校验（C3 第 1 项门禁）
- 两脚本均以 `BASH_SOURCE` 自定位，不依赖 cwd；产物统一落
  `app/src/main/jniLibs/arm64-v8a/`（targetSdk≥29 下该目录是唯一可执行区，D4）。

---

## 1. 两库交付概述

### 1.1 libproot.so（DIRECT 模式主引擎）

| 项 | 值 | 落点 |
|----|----|------|
| 上游 | Termux Android 适配 fork `termux/proot`，tag **v5.1.107.96** | `PROOT_REF` |
| 二次 pin | commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`，clone 后 rev-parse 比对，不一致则 fetch+checkout，防 tag 漂移 | `PROOT_COMMIT` |
| 工具链 | NDK `aarch64-linux-android26-clang`（API 26 = minSdk），llvm-ar/ranlib/strip/readelf 同套 | 两脚本一致 |
| 链接 | `-static`（bionic libc 静态入包），C3「静态优先」 | LDFLAGS |
| 硬依赖 1 | **talloc 2.5.0** 静态 `.a`；waf 交叉配方逐字取自 termux-packages `libtalloc/build.sh`（`--cross-compile --cross-answers`，产物手动 `AR rcu talloc*.o` 打包） | step 2 |
| 硬依赖 2 | **libandroid-shmem 0.7** 静态 `.a`；bionic API<33 无 `shm_open`，以 ashmem 模拟层替代，经 `PROOT_WITH_LIBANDROID_SHMEM=true` 启用引用 | step 1 |
| 依赖校验 | talloc sha256 `912afa23…d0ebab007`、shmem sha256 `1e5ff845…b98e867`（termux-packages 权威值），fetch 强制校验 | `*_SHA256` |
| 交付断言 | ① `llvm-readelf -h` Machine=AArch64；② `llvm-readelf -d` **NEEDED 为零**（零额外 .so，C3 等效断言）；③ 复用 check-page-align.sh 对齐自检 | step 4 |
| 落地 | `install -m 755` → `jniLibs/arm64-v8a/libproot.so` | step 4(d) |

术语注：proot 本体是可执行文件，按 jniLibs 约束以 `libproot.so` 命名打包（D4）。

### 1.2 libqemu_aarch64.so（QEMU 模式兜底引擎）

| 项 | 值 | 落点 |
|----|----|------|
| 上游 | qemu 官方 tag **v9.2.0**（≥9.0，见 §2 TARGET_PAGE_BITS_VARY 前提） | `QEMU_REF` |
| 目标 | `--target-list=aarch64-linux-user`，**仅 aarch64 guest**，TCG 后端显式 `--enable-tcg`（防 `--without-default-features` 连带关闭） | configure |
| 链接 | `--static` 强制（C3）；`--without-default-features` 关闭全部可选依赖；`--with-pkgversion=OpenCode-Android-v9.2.0` 便于设备端日志归因 | configure |
| 静态依赖链 | **zlib 1.3.1 → libiconv 1.17 → pcre2 10.43 → glib 2.78.4**，全部预编 `.a` 装入 STAGING：bionic API 26 无 iconv；qemu-user 硬依赖 glib（≥2.56）；glib 依赖 pcre2/iconv/zlib。链序与 Termux/社区 Android 配方一致 | step 1a–1d |
| pkg-config | 脚本生成 `--static` wrapper 注入 meson cross file，保证 qemu 静态链 glib 时拉出 Requires.private（pcre2/iconv/zlib），否则静态链接期符号缺失 | step 1 前 |
| 交付断言 | 同 libproot.so：AArch64 架构断言 + **NEEDED=0** + 对齐自检，然后 `install` → `jniLibs/arm64-v8a/libqemu_aarch64.so` | step 5 |
| 时长预算 | ~20min（P1 §3.3），CI 预留超时余量 | — |

glib 经 meson 交叉构建（`-Diconv=native` 指向 GNU libiconv，selinux/libmount/nls 全关），zlib/libiconv/pcre2 走 autotools `--host=aarch64-linux-android`。

---

## 2. 16KB 页对齐：双层保障机制（缺一不可）

Google Play 自 2025-11 起强制 16KB page size 支持；4KB 对齐 ELF 在 16KB 内核上加载失败（D7）。本项目用**链接期 + 运行期**两层机制分别覆盖两套对齐语义：

### 2.1 第一层：链接 flag（host 侧 ELF LOAD 段对齐）

- `ALIGN_LDFLAG="-Wl,-z,max-page-size=16384"`，**两库的 CFLAGS 与 LDFLAGS 均同时包含**（proot 传给 `make -C src`；qemu 经 meson cross file `c_link_args`/`cpp_link_args` 注入）。
- 作用对象：产物 ELF 自身的 `PT_LOAD` 段 `p_align`，决定 Android 内核/dlopen 能否映射该文件。这是过 Play 审核与 16KB 新机型启动的硬约束。
- 该 flag **只解决 host 侧 ELF 布局**，不改变 qemu 内部对 guest 地址空间的映射粒度假设——因此必须叠加第二层。

### 2.2 第二层：TARGET_PAGE_BITS_VARY（qemu 运行时 guest 页宽自适应）

- qemu-user 默认 `TARGET_PAGE_BITS=12`（4KB），在 16KB host page 上 `MAP_FIXED`/mmap offset 对不上，启动即失败（D7）。
- 启用方式为 **qemu ≥9.0 源码内置机制，不是任何 meson option / configure 开关**：qemu 9.0 合入 Richard Henderson《linux-user: Improve host and guest page size handling》（patch 29/30），在 `target/arm/cpu-param.h` 的 `#ifdef CONFIG_USER_ONLY` + `#ifdef TARGET_AARCH64` 分支内定义 `TARGET_PAGE_BITS_VARY` 与 `TARGET_PAGE_BITS_MIN 12`，运行时按 host 真实 page size（4KB/16KB/64KB）自适应 guest 映射。
- 脚本在 **configure 之前做源码三重断言**（step 3，任一失败即 FATAL，不产出不可用库）：
  1. `cpu-param.h` 含 `TARGET_PAGE_BITS_VARY`（排除 qemu<9.0 或结构变更）；
  2. 该定义位于 `CONFIG_USER_ONLY` 分支且分支内含 `TARGET_AARCH64`（保证仅对 aarch64 linux-user 生效）；
  3. 分支内含 `TARGET_PAGE_BITS_MIN`（page-vary 机制完整）。

### 2.3 为什么两层缺一不可

- 只有第一层：libqemu_aarch64.so 自身能被 16KB 内核加载，但 qemu 运行期仍按 4KB 粒度做 guest MAP_FIXED，QEMU 模式必挂——16KB 设备上 D7 指定的降级主路径失效。
- 只有第二层：产物 ELF 以默认 4KB 对齐落 jniLibs，16KB 内核/Play 校验直接拒绝加载，两层机制根本没机会运行。
- 结论：第一层是「能装上」，第二层是「装上后 QEMU 模式能跑」，二者分别由 check-page-align.sh 与源码三重断言把守，任何一层校验失败均不得合入。

---

## 3. C3 契约四项 CI 校验落地

> P1 §3.3 规定四项校验由 native-builder 与 release-ops **双方都要执行**，任一失败即阻断发布。

### 3.1 第 1 项：16KB 对齐校验 — `check-page-align.sh`

```bash
# CI 用法（显式指定 NDK 的 llvm-readelf 最稳）
READELF=$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf \
  bash native/check-page-align.sh app/src/main/jniLibs/arm64-v8a/*.so
```

- 原理：`llvm-readelf -lW` → 仅在 `Program Headers:` 与 `Section to Segment mapping:` 之间扫描 → 逐条 `LOAD` 行取末字段（Align 列）→ 每段与 `0x4000` 做数值比较，**全部达标才通过**；解析不到任何 LOAD 段（非 ELF/格式异常）同样 FAIL。任一文件失败 exit 1。
- **修复了 release.yml 原 grep 校验的三重脆弱性**（P1 §7 缺陷 3）：① 原写法匹配整行任意位置，Align 列无列定位；② `Section to Segment mapping` 区域同样含 `LOAD` 字样被误判；③ 任一行含 `0x4000` 即整体放行，其余不达标段漏网。
- 选项：`-m/--min-align`（默认 0x4000）、`-r/--readelf`、`--no-name-check`。build-proot.sh / build-qemu-user-static.sh 在 step 4/5 内已自动复用本脚本（以 `READELF` 环境变量传入 NDK readelf）。

### 3.2 第 2 项：lib*.so 命名断言

check-page-align.sh 默认对每个受检文件执行正则 `^lib[A-Za-z0-9._-]+\.so$`，不合规即 FAIL——jniLibs 打包只认 `lib*.so`，裸名二进制（如 `proot`、`qemu-aarch64`）会被 AGP 静默丢弃、`nativeLibraryDir` 里根本没有它。单测/临时二进制校验对齐值时可加 `--no-name-check` 跳过。

### 3.3 第 3 项：16KB 模拟器冒烟

- CI 侧启动 **16KB page size 模拟器**（AVD 系统镜像 16KB 变体），安装测试 APK 后执行：
  `adb shell "/data/app/*/lib/arm64/libproot.so --version"`，要求**直接可执行且退出码 0**（P1 §3.3 原文）。
- 该项同时验证三件事：D4 解包正确（见 §4）、16KB 内核能加载我们的 ELF（第一层）、proot 静态产物自足可运行（NEEDED=0 的运行时等价验证）。
- 若冒烟报 §6 特征串（尤其 `Exec format error`），按 §6 处置矩阵归因。

### 3.4 第 4 项：extractNativeLibs 断言（与 D4 的关系）

- C3 第 4 项 = APK manifest 断言 `android:extractNativeLibs="true"`，其 gradle 等效写法即 D4 要求的 `packaging { jniLibs { useLegacyPackaging = true } }`（详见 §4）。两者是同一配置在 manifest 层与 build.gradle 层的表达，CI 对 manifest 做断言防 gradle 配置被意外覆盖/回退。

---

## 4. D4 发布阻断级提醒：useLegacyPackaging

**这是本项目最高优先级的隐性配置项：漏配 = 库不解压 = ProcessBuilder 无法 exec = 全链路不可用。**

- 事实：minSdk≥23（本项目 26）时，**AGP 默认 `useLegacyPackaging=false`**——native 库以未压缩形式留在 APK 内、按页映射加载，**不释放到 `nativeLibraryDir`**。而 targetSdk≥29 下只有 `nativeLibraryDir` 是可执行区，我们的 proot/qemu 恰恰要从那里 exec。
- 必须显式配置（app/build.gradle.kts）：

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true   // = extractNativeLibs=true，库解压进 nativeLibraryDir
        }
    }
}
```

- 双重检查（P1 D4 修订条款）：native-builder 在本文档与 CI 配方中登记该要求；release-ops 在 release.yml 落地 CI gate——解包 APK 核对 manifest `extractNativeLibs="true"`（C3 第 4 项），任何一方发现缺失即阻断发布。
- 副作用知晓：开启后 APK 体积增大（.so 参与压缩）、安装后占用磁盘，属必要代价，不得为省体积回退。

---

## 5. 给 engine-layer 的调用约定

以下为 §3.2 ExecCompat / §3.6 启动参数契约在原生库侧的落地口径：

1. **DIRECT 模式**：直接 `ProcessBuilder.exec({nativeLibraryDir}/libproot.so ...)`，参数即 proot 原生命令行（`-r` rootfs、`-b` binds、`-w` cwd 等按 §3.5/§3.6）。依赖 NEEDED=0 静态产物，无需任何 LD_LIBRARY_PATH。
2. **QEMU 模式**：`{nativeLibraryDir}/libproot.so -q {nativeLibraryDir}/libqemu_aarch64.so -r <rootfs> ...`
   - **`-q` 的 qemu 路径必须是宿主侧绝对路径**（P1 §3.3 明文）：proot 不对 `-q` 参数做 guest 路径翻译，传 guest 内路径（如 `/usr/bin/qemu-aarch64`）会 exec 失败。rootfs 内**无需**放置 qemu 二进制，也不要为此做 bind。
   - 两库同处 `nativeLibraryDir`，路径拼接统一用该目录，避免自行推导。
3. **proot loader**：未设 `PROOT_UNBUNDLE_LOADER`（Termux 用于分离 loader 的优化），loader **内嵌在 libproot.so 内**，运行期自动提取到 **`PROOT_TMP_DIR` = app cache 目录**（engine-layer 契约）。engine-layer 需保证该目录在引擎启动前存在且可写，并纳入缓存清理范围（L2 自愈「清缓存」时一并清理，可破除 loader 提取残留导致的异常态）。
4. qemu 版本标识：`libqemu_aarch64.so --version` 输出含 `OpenCode-Android-v9.2.0`（`--with-pkgversion`），可作设备端诊断报告的版本归因字段。

---

## 6. D5 自愈日志特征串登记（L2 特征串库扩充素材）

> 供 engine-layer 按 P1 §3.2 扩充 L2「启动自愈」特征串库。建议：大小写不敏感子串匹配；匹配即判 DIRECT 探测误判 → 清缓存（含 PROOT_TMP_DIR）→ 强制 QEMU 模式重试一次；本表仅为样例，最终以 M0 真机/模拟器实测日志为准修订。

| 特征串（stderr 子串） | 典型来源 | 含义与处置 |
|----|----|----|
| `Permission denied` | 内核/SELinux 拒绝 exec | 库所在分区 noexec 或 SELinux 策略拦截 → DIRECT 不可用，降级 QEMU |
| `Exec format error`（ENOEXEC） | 内核加载 ELF 失败 | ABI/架构不符或 ELF 损坏（16KB 机器上若伴随加载失败也可能是对齐问题）→ 降级 QEMU 重试 |
| `error while loading shared libraries` | 动态链接器 | 出现即异常信号：宿主侧产物 NEEDED=0，该报错多半指向 guest 内二进制缺库或库文件损坏 → 降级 QEMU（qemu 自管 guest 映射）并核对产物完整性 |
| `mmap failed` / `mmap: Cannot allocate memory` | proot/qemu 映射失败 | 地址空间/映射粒度冲突（典型：16KB host page 遇 4KB 假设）→ 降级 QEMU |
| `Failed to map` / `MAP_FIXED` 相关报错 | qemu linux-user 映射阶段 | 16KB host page 上 guest 页宽不匹配的标志性报错 → 确认 QEMU 模式仍复现时回报 native-builder（对照 §2.2 三重断言） |
| `qemu: uncaught target signal` | qemu 运行期 | guest 进程异常信号，QEMU 模式内部失败 → 记入诊断报告，属引擎层重试/兜底范围 |
| `ptrace ... Operation not permitted` | proot 工作机制 | 内核禁 ptrace / SELinux 限制 → proot 无法工作，转 QEMU 同样受制于此，属环境级失败 → FAILED 诊断 |
| `proot warning: can't sanitize binding` | proot 路径规整 | **非致命警告**，不要匹配为失败特征；L2 库应排除该串防误判 |

登记规则（沿用 §5.3）：特征串可扩充但需登记；负面样例（第 8 条）与正面样例同等重要，防 L2 误触发清缓存循环。

---

## 7. 已知待办与契约修订

1. **【CI 首跑前必办】补齐四个 SHA256**：`build-qemu-user-static.sh` 中 `ZLIB_SHA256` / `LIBICONV_SHA256` / `PCRE2_SHA256` / `GLIB_SHA256` 默认为空（脚本会打「未配置 sha256」警告并跳过校验）。CI 首跑前必须按各官方发布页核对后填入（zlib.net fossils / ftp.gnu.org / PCRE2 GitHub Releases / download.gnome.org），或经 CI 环境变量注入。talloc/libandroid-shmem 的哈希已锁定 termux 权威值，无需处理。哈希为空期间 job_android 首步即硬拦拒绝构建（release.yml 实码为准，P5 §6），故四个 SHA256 为 CI 可出包的硬前置。
2. **【契约修订】proot 许可证更正**：P1 §3.3 与 D1 所写「MIT」有误，termux/proot 沿袭上游 proot-me/PRoot，实为 **GPL-2.0+**。对项目的影响评估：proot 以独立二进制（libproot.so）交付、不经链接器与 App 代码合成单一作品，且 D6 已因同类考量弃用 GPL Termux 库——此修订**不改变 D1 决策方向**，但请架构总师在 P1 做勘误并归档法务口径。本条为参数级修正，符合 P1 §2 决策冻结规则的例外通道。
3. 其余无待办：两库交付物、对齐机制、CI 四项均已按 P1 §3.3/§5.2 落地到脚本，无骨架缺口。

---

*文档结束。本文档为说明性产出，未改动 `native/build-proot.sh`、`native/build-qemu-user-static.sh`、`native/check-page-align.sh` 任何内容。*
