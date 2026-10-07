# OpenCode Android 移动端 APP 全栈开发方案

> **声明**：本项目为社区驱动的第三方打包工程，"OpenCode" 名称仅用于标识所封装的上游工具。本项目与 OpenCode 官方团队无关、非官方出品。上游项目：https://github.com/anomalyco/opencode （Apache-2.0）；构建脚本所引用的 proot（GPL-2.0+）、qemu（GPL-2.0+）等组件许可见 `team-output/P2b-原生编译说明.md` 与 P5 发布运维说明的第三方许可条款。

> 本地引擎 = **None**（纯远程客户端）/ **Ubuntu**（本地 proot 快照引擎）· 快照预打包 · 免 root · 免 Termux 依赖
> 版本：v1.0（2026-10-07）· OpenCode 目标版本 ≥ 1.18 · 分发方式：GitHub Releases 侧载

---

## 0. 一句话结论

**做一个 Android 原生 App：UI 层用 Kotlin/Compose，引擎层把「Ubuntu rootfs + opencode 单文件二进制」打成快照预埋进 APK，首次启动解压到私有目录，用 proot（可选 qemu-user 降级）跑 `opencode serve` 绑定 `127.0.0.1:4096`，App UI 通过 HTTP/SSE 驱动它。** 用户装完即用，不需要 Termux、不需要在线装环境。`None` 模式下 App 不启动本地引擎，直接作为同一套 UI 连接远程服务器上的 `opencode serve`。

### 可行性依据（均已验证，非假设）

| # | 事实 | 来源/依据 |
|---|------|-----------|
| 1 | OpenCode（anomalyco/opencode，原 sst/opencode）是 Bun 编译的**单文件二进制**，`linux-arm64` 版在 proot Ubuntu 中正常运行 | 官方安装脚本 `curl -fsSL https://opencode.ai/install`；社区用户已在 Android proot Ubuntu 日用 5 周+（opencode 1.18.34） |
| 2 | `opencode serve` 提供无头 HTTP API：`/global/health`、`/doc`(OpenAPI 3.1)、`/session`、`/session/:id/message`、`/prompt_async`、SSE `/event`、HTTP Basic Auth（`OPENCODE_SERVER_PASSWORD`） | 官方文档 opencode.ai/docs/server |
| 3 | 官方二进制是 glibc 链接，**无法**在 Termux(bionic) 直接跑（launcher 误判 arch，issue #21043 未修） | GitHub issue + 社区实测 → proot Ubuntu 是唯一成熟本地路径 |
| 4 | targetSdk ≥ 29 后，App **不能执行私有目录里的文件**（SELinux W^X），只有 `nativeLibraryDir`（jniLibs 解压目录）可执行 | Android 10 行为变更官方文档 |
| 5 | Android 12+ 幻影进程杀手会杀后台子进程（>32 个或高 CPU），可用 adb 解除，Android 14+ 有开发者选项开关 | AOSP + Termux 文档 |
| 6 | 2025-11-01 起 Google Play 强制 16KB page size 支持；4KB 对齐 ELF 在 16KB 内核上加载失败 | Android 官方公告 |

---

## 1. 两种引擎模式定义

| 模式 | 引擎位置 | 快照 | 适用场景 | 依赖 |
|------|----------|------|----------|------|
| **None** | 远程 Ubuntu 服务器（用户自己的 VPS/家用机） | 不需要 | 手机性能弱、代码在服务器上、想要 24h 在线 agent | 远程已装 `opencode serve`；可选 Tailscale/SSH 隧道 |
| **Ubuntu** | 手机本地 proot 容器 | **预打包进 APK（full 变体）或首启下载（lite 变体）** | 离线可用、代码在手机/网盘、零服务器成本 | 设备 ≥ 6GB RAM（建议）、arm64 |

两种模式**共用同一套 UI 与同一套 REST/SSE 客户端**，唯一区别是 baseUrl 指向 `http://127.0.0.1:{port}` 还是远程地址。引擎切换 = 换一个 baseUrl + 是否启动前台服务。

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        OpenCode Mobile (APK)                    │
│                                                                 │
│  ┌──────────────────────────── UI 层 (Kotlin + Compose) ──────┐ │
│  │  会话聊天UI │ 权限审批对话框 │ Provider/API Key 管理 │ 工作区 │ │
│  │  M1: 终端形态(内嵌 terminal emulator / xterm.js)            │ │
│  └──────────────┬──────────────────────────────▲───────────────┘ │
│                 │ REST + SSE                    │ BaseAuth        │
│                 ▼                               │                 │
│  ┌──────────────┴──────────── EngineClient ─────┴─────────────┐ │
│  │      baseUrl = http://127.0.0.1:{port}  或  https://remote │ │
│  └──────────────┬─────────────────────────────────────────────┘ │
│                 │                                                │
│  ┌──────────────▼──────── EngineService (Foreground Service) ─┐ │
│  │  EngineManager 状态机: STOPPED→INSTALLING→STARTING→READY   │ │
│  │  ExecCompat: noexec 探测 → proot 直跑 / proot+qemu 降级     │ │
│  │  健康检查 GET /global/health · 崩溃重启 · 幻影进程防护引导    │ │
│  └──────────────┬─────────────────────────────────────────────┘ │
│                 │ ProcessBuilder exec                            │
│  ┌──────────────▼─────────────────────────────────────────────┐ │
│  │ proot (nativeLibraryDir/libproot.so, 16KB 对齐)             │ │
│  │   └─ Ubuntu 24.04 arm64 rootfs 快照 (私有目录, 解压自 assets) │ │
│  │        ├─ /usr/local/bin/opencode  ← 官方单文件二进制        │ │
│  │        ├─ git / curl / ripgrep / fd / gh / tmux / openssh   │ │
│  │        └─ /workspace  ← bind 挂载手机工作区(外部私有目录)     │ │
│  │             └─ opencode serve --hostname 127.0.0.1 --port N │ │
│  └──────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
          │                                        │
          ▼                                        ▼
   LLM Provider API                        远程 opencode serve
   (走用户配置的代理)                       (None 模式, Tailscale/SSH)
```

**数据流**：UI 发 prompt → `POST /session/:id/message`（或 `prompt_async`）→ 引擎内 opencode 调 LLM（出网，走代理）→ 工具调用（bash/edit 作用于 rootfs 内 `/workspace`）→ SSE `/event` 推回 UI 渲染。权限请求（`permission.asked` 事件）弹原生对话框 → `POST /session/:id/permissions/:id` 回复。

**关键隔离**：引擎只绑定 `127.0.0.1`，永不暴露局域网；API Key 只存 Android Keystore 加密的 prefs，注入引擎时通过 bind 挂载的配置目录写入。

---

## 3. 关键技术决策

| # | 决策 | 选择 | 理由（一句话） |
|---|------|------|----------------|
| D1 | Linux 环境方案 | **proot**（非 chroot/AVF VM/Termux 依赖） | 免 root、单 APK 自包含、社区已验证跑 opencode；AVF VM 仅限 Pixel 8+ / 16GB 机型，chroot 需 root |
| D2 | opencode 运行形态 | **`opencode serve` 无头模式**，UI 走 HTTP/SSE | 官方一等公民 API（SDK/OpenAPI 均由此生成），TUI 只是它的客户端之一；无头模式不吃 TUI 渲染内存 |
| D3 | 快照分发 | **full/lite 双变体**：full 把 tar.gz 塞 assets（侧载）；lite 首启按 manifest 从 GitHub Releases 下载（可配镜像） | 快照压缩后约 150–250MB，超 Play 限制但侧载无限制；lite 照顾流量与增量更新 |
| D4 | 宿主侧二进制存放 | proot/qemu/busybox 全部以 `lib*.so` 命名打 jniLibs，运行时从 `nativeLibraryDir` 执行 | targetSdk≥29 只有该目录可执行（官方认可），且 NDK 打包天然处理解压与 ABI 过滤 |
| D5 | noexec 兜底 | **ExecCompat 运行时探测**：guest 内二进制不可 exec 时自动加 `-q libqemu_aarch64.so`（qemu-user 以只读数据方式加载 ELF，绕过 x 权限） | targetSdk 35 下私有目录 noexec 的实测行为随 ROM 有差异，探测+降级让两种情况都能跑，M0 一天实验收敛不确定性 |
| D6 | 终端形态实现 | M1 用 Apache-2.0 的 ConnectBot terminal 库 fork proot bash 直跑 `opencode` TUI；不引入 GPL 的 Termux 库 | TUI 是兜底全能界面（官方所有功能）；ConnectBot 库无协议传染 |
| D7 | 16KB page | 自编译 proot/qemu 均加 `-Wl,-z,max-page-size=16384`；CI 加 16KB 模拟器冒烟 job | 2025-11 起 Play 强制；不达标直接被 Play 拒审（侧载装机在新 flag 设备上段对齐崩溃） |
| D8 | 保活 | Foreground Service（`dataSync` 类型）+ WakeLock（可配）+ 幻影进程引导页 | opencode serve 是长驻进程；幻影杀手是 Android 12+ 最大死因，无法纯代码规避，必须引导用户 adb 一次性解除 |

---

## 4. 引擎层设计

### 4.1 快照内容与体积预算

| 内容 | 版本策略 | 解压后 | 压缩后(打包) |
|------|----------|--------|--------------|
| Ubuntu Base 24.04 arm64（minbase） | 锁定小版本 | ~90MB | ~30MB |
| opencode linux-arm64 单文件二进制 | 跟随官方 release，manifest 记录 | ~110MB | ~55MB |
| git / curl / ripgrep / fd / openssh-client / tmux / gh / ca-certificates / locales | apt（构建时锁版本） | ~80MB | ~30MB |
| 预配置（`LANG=C.UTF-8`、国内 apt 镜像可选、`opencode.json` 模板、欢迎脚本） | — | ~1MB | ~1MB |
| **合计** | | **~280MB** | **~120–160MB（tar.gz）** |

> 首次解压后磁盘占用 ≈ 解压后体积 + 压缩包（full 变体解压后可删包省 160MB；lite 变体下载到 cache 后同理）。运行期新增：opencode SQLite 会话库 + npm 缓存 + 用户代码，引导用户留 **2GB** 余量。

### 4.2 快照生命周期（幂等，可逐项核对）

```
构建(CI)          分发                安装(App 内)                  启动
─────────         ─────              ─────────────                ─────
ubuntu-base  ─►  GitHub Release ─►  ①读 assets 或下载     ─►  EngineService
+opencode二进制   附 manifest.json   ②SHA-256 流式校验         exec proot…
+工具链           (version/sha256/   ③解压到 .tmp             健康检查 /global/health
+预配置            size/ocVersion)   ④写 meta.json 幂等标记
                                    ⑤原子 rename 到 rootfs/
```

**安装幂等规则**：`{dataDir}/rootfs/.snapshot-meta.json` 记录 `{snapshotVersion, ocVersion, state}`；任何一步中断（进程被杀/断电）→ 下次启动检测到 `state != "ready"` 即整目录删除重装。**升级**：新快照版本号 > 本地 → 下载新包解压到 `.tmp` → 原子替换 rootfs，`/workspace` 与用户 `~/.local/share/opencode`（会话库）**不在替换范围**（快照构建时排除这两个路径，安装时保留旧目录）。

### 4.3 ExecCompat：noexec 探测与 qemu 降级（本方案安卓底层核心）

**问题**：targetSdk ≥ 29 时 SELinux 禁止 app 域对 `app_data_file` 执行 `execute`，而 rootfs 正是 app 私有目录。proot 自己在 `nativeLibraryDir` 没问题，但它最终要 exec rootfs 里的 `/bin/bash`、`/usr/local/bin/opencode`。

**三层策略（自动选择，无需用户干预）**：

1. **探测**：启动时对 `{rootfs}/usr/bin/env` 做 `setExecutable` 后直接 `ProcessBuilder` 执行 `--version`。
   - 返回 0 → **模式 A（直跑）**：`libproot.so -r rootfs ...`
2. 失败(EACCES) → **模式 B（proot + qemu-user 降级）**：
   ```
   libproot.so -q libqemu_aarch64.so -r rootfs ...
   ```
   qemu-user 的 ELF 加载器把 guest 二进制当**普通数据文件**读入内存模拟执行（同 box64/FEX 在 Android 绕 noexec 的通用做法），不经过内核 `execve` 的 x 权限检查。性能损失约 +15%（arm64→arm64 直通翻译），叠加 proot 的 ptrace 开销（~20%），总损耗可接受（LLM 等待占绝对大头）。
3. 模式 B 仍失败 → 引导页给出诊断报告（SELinux 域、page size、内核版本），提示社区反馈。

**qemu 从哪来**：NDK 交叉编译 `qemu-user-static`（meson，`--static`，arm64-host/aarch64-guest），或从 Termux packages 的 `qemu-user-static` 构建配方移植。proot 用 Termux 维护的 Android 适配 fork（proot 官方 + Termux 补丁，MIT）。

### 4.4 opencode serve 编排

**启动命令（在 App 内构造，经由 proot）**：

```bash
# 端口：从 4096 起向上找空闲口（App 侧 ServerSocket(0) 先探测再传入）
opencode serve --hostname 127.0.0.1 --port {port}
```

**注入环境变量**（proot `--env` 或 `env -i` 构造）：

| 变量 | 值 | 用途 |
|------|----|------|
| `HOME` | `/root` | opencode 数据目录基准 |
| `LANG` / `LC_ALL` | `C.UTF-8` | 修复中文乱码（社区实测必须） |
| `PATH` | `/usr/local/bin:/usr/bin:/bin` | 保证 opencode/git 优先 |
| `HTTP_PROXY`/`HTTPS_PROXY` | 用户在 App 设置的代理（如 `socks5://192.168.x.x:7890`） | **国内访问 Anthropic/OpenAI 必需**，透传给引擎出网 |
| `XDG_DATA_HOME` | `/root/.local/share` | SQLite 会话库位置（升级快照时保留） |
| `OPENCODE_SERVER_PASSWORD` | App 随机生成 32 位串 | localhost 也上 Basic Auth，防其他 app（同 uid 除外）扫本地端口 |

**健康检查与守护**：
- 就绪判定：`GET /global/health` 返回 `{"healthy":true,"version":"..."}`（指数退避轮询，超时 60s）。
- 崩溃重启：进程退出码 ≠ 0 且用户未手动停止 → 自动重启，10 分钟内连续 3 次失败 → 停止并弹诊断。
- 会话库持久：`~/.local/share/opencode/opencode.db` 在快照升级中保留，重启引擎会话不丢。

### 4.5 保活与幻影进程防护

| 层 | 手段 | 说明 |
|----|------|------|
| App 层 | Foreground Service + 常驻通知（显示端口/内存/活跃会话） | `dataSync` 类型，Android 14+ 声明对应权限 |
| 系统层 | 引导页检测 `settings_enable_monitor_phantom_procs` 状态 | 图文引导 adb 三连（见附录 A），Android 14+ 指路开发者选项「停用子进程限制」开关 |
| 资源层 | 引擎空闲超时（可配，默认不休眠）| 防高 CPU 触发 excessive-kill：限制引擎内并发工具数 `--concurrency` 思路 |
| 电池 | 引导用户关闭对该 App 的「电池优化」 | Intent `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

---

## 5. UI 层设计（三阶段演进）

| 阶段 | 形态 | 内容 | 工作量 |
|------|------|------|--------|
| **M1 终端形态** | 内嵌终端（ConnectBot terminal 库，Apache-2.0）fork proot bash 直跑 `opencode` TUI | 验证引擎链路的最短路径；TUI 全功能兜底 | 小 |
| **M2 原生主界面** | Compose 聊天 UI：会话列表/消息流（Markdown+代码高亮）、权限审批弹窗、Provider/API Key 管理、引擎状态卡 | REST + SSE 对接（复用 M1 的 EngineClient），移动端体验主体 | 中 |
| **M3 工作区** | 文件浏览器（`/file` `/find` API + 本地 SAF）、diff 查看（`/session/:id/diff`）、git 操作（引擎内 gh）、远程模式管理器 | 对齐桌面端工作流 | 大 |

**None 模式 UI**：设置页填远程地址 + 密码（或导入 `opencode://` 分享串）。远程连通性测试按钮直接打 `/global/health`。建议配 Tailscale；App 不内置 VPN，但文档给一键跳转。

**横竖屏**：会话流竖屏优先；终端形态支持外接键盘 + 悬浮快捷键条（Esc/Tab/Ctrl/Ctrl+D）——TUI 操作刚需。

---

## 6. 网络与安全

| 项 | 设计 |
|----|------|
| 引擎监听 | 仅 `127.0.0.1`，端口随机化 + Basic Auth 双保险 |
| API Key 存储 | Android Keystore（AES-GCM）加密后存 EncryptedSharedPreferences；注入引擎时写入 bind 挂载的 `/root/.config/opencode/auth.json`，文件权限 600 |
| 出网代理 | App 设置 HTTP/SOCKS 代理 → 环境变量透传引擎（`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY=127.0.0.1`）；App 自身请求远程引擎可走独立代理配置 |
| 快照下载 | 官方 GitHub Releases + 可配镜像前缀（ghproxy 类），SHA-256 必校验 |
| 工作区 bind | 引擎内 `/workspace` ↔ `/storage/emulated/0/Android/data/<pkg>/files/workspace`（外部私有目录，无需存储权限，用户可用系统文件管理器/其他 app 访问，方便与网盘同步） |
|隐私| 不上传任何遥测；崩溃日志仅本地留存，用户手动导出 |

---

## 7. 代码骨架

> 4 个完整文件在 `code/` 目录，可直接入仓。此处说明设计要点。

| 文件 | 职责 | 关键变量/设计 |
|------|------|---------------|
| `code/build-snapshot.sh` | CI 内构建快照（在 **arm64 runner** 上原生 chroot，无需 qemu） | `OC_VERSION` 锁版本；`ROOTFS` 解包目录；预装工具链清单；`EXCLUDE` 打包时剔除 `/workspace`、会话库；产出 `manifest.json`（version/sha256/ocVersion） |
| `code/SnapshotInstaller.kt` | 校验→解压→幂等落盘 | `TarArchiveInputStream(GZIPInputStream)` 流式（零额外原生依赖）；`.tmp` 解压 + 原子 rename；`meta.json` 幂等标记；SHA-256 边下边算；`onProgress` 回调供 UI |
| `code/EngineService.kt` | 前台服务 + proot 编排 + 健康检查 | `ExecCompat.detect()` 选 proot/`-q` 模式；`buildProotCommand()` 构造 bind 挂载；`awaitHealthy()` 轮询 `/global/health`；`restartGuard` 崩溃重启退避 |
| `code/release.yml` | GitHub Actions 双 job | `snapshot`(arm64 runner) 产快照+manifest；`android`(x86 runner) 编 proot/qemu(jniLibs) + gradle 出 full/lite 双 APK，产物传 Release |

### 7.1 proot 命令构造（EngineService 核心逻辑预览）

```kotlin
// 变量说明:
// nativeDir = applicationInfo.nativeLibraryDir   // jniLibs 解压目录, 唯一可执行区
// rootfs    = File(filesDir, "rootfs")           // 快照解压目的地
// workspace = File(getExternalFilesDir(null), "workspace") // 手机可见工作区
// port      = 空闲端口(ServePort.allocate())      // 默认从 4096 起
// proxy     = 用户配置代理, 形如 "http://192.168.1.9:7890"

fun buildProotCommand(mode: ExecMode, port: Int): List<String> {
    val proot = "$nativeDir/libproot.so"
    val qemu  = "$nativeDir/libqemu_aarch64.so"
    val env   = listOf(
        "HOME=/root", "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
        "PATH=/usr/local/bin:/usr/bin:/bin",
        "XDG_DATA_HOME=/root/.local/share",
        "HTTP_PROXY=$proxy", "HTTPS_PROXY=$proxy",
        "NO_PROXY=127.0.0.1,localhost",
        "OPENCODE_SERVER_PASSWORD=$enginePassword",
    )
    val binds = listOf(
        "-b", "/dev", "-b", "/proc", "-b", "/sys",
        "-b", "${workspace.absolutePath}:/workspace",
        "-b", "${authDir.absolutePath}:/root/.config/opencode",  // Key 注入点, 权限600
    )
    val guest = listOf(
        "/usr/bin/env", "-i", *env.toTypedArray(),
        "opencode", "serve", "--hostname", "127.0.0.1", "--port", port.toString(),
    )
    return when (mode) {
        ExecMode.DIRECT -> listOf(proot, "--kill-on-exit", "-0", "-w", "/root",
            "-r", rootfs.absolutePath, *binds.toTypedArray(), *guest.toTypedArray())
        ExecMode.QEMU   -> listOf(proot, "--kill-on-exit", "-0", "-w", "/root",
            "-q", qemu, "-r", rootfs.absolutePath, *binds.toTypedArray(), *guest.toTypedArray())
    }
}
```

### 7.2 幻影进程解除命令（引导页内嵌复制按钮）

```bash
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell "settings put global settings_enable_monitor_phantom_procs false"
adb reboot
```

---

## 8. 工程目录结构

```
opencode-mobile/
├── app/                          # Android 主工程 (Kotlin + Compose, minSdk 26, targetSdk 35)
│   ├── src/main/
│   │   ├── java/dev/opencode/mobile/
│   │   │   ├── engine/           # EngineService, EngineManager, ExecCompat, SnapshotInstaller
│   │   │   ├── client/           # EngineClient (REST+SSE, OkHttp), 按 /doc OpenAPI 生成器补充类型
│   │   │   ├── ui/               # Compose 会话/设置/终端桥接
│   │   │   └── terminal/         # M1 终端形态 (ConnectBot terminal 库封装)
│   │   ├── assets/snapshot/      # full 变体: oc-ubuntu-arm64.tar.gz (CI 注入)
│   │   └── jniLibs/arm64-v8a/    # libproot.so / libqemu_aarch64.so (CI 编译注入, 16KB 对齐)
│   └── build.gradle.kts          # productFlavors: full(assets内嵌) / lite(首启下载)
├── snapshot/build-snapshot.sh    # 快照构建
├── native/                       # proot / qemu-user 的 NDK 构建脚本与补丁
├── .github/workflows/release.yml # CI/CD
└── docs/                         # 用户手册(含 adb 保活图文引导)
```

---

## 9. 里程碑与验收标准

| 里程碑 | 内容 | 验收标准（可逐项核对） |
|--------|------|------------------------|
| **M0 验证周**（1周） | ① 3 台真机（含 16KB flag 模拟器）跑通 ExecCompat 探测 demo；② Termux 复现社区路径跑 `opencode serve`；③ 快照首版构建 | [ ] 探测输出 DIRECT/QEMU 结论；[ ] `curl /global/health` 返回 healthy；[ ] 快照 sha256 与 manifest 一致 |
| **M1 引擎可用**（2周） | SnapshotInstaller + EngineService + 终端形态 UI + full APK | [ ] 安装 APK → 开 App → 90 秒内 TUI 可对话；[ ] 杀 App 重开，快照不重装（幂等）；[ ] 引擎崩溃 10s 内自动拉起 |
| **M2 原生 UI**（3周） | Compose 会话流 + SSE 渲染 + 权限弹窗 + Key 管理 + 代理设置 + None 远程模式 | [ ] 手机上完成一次"建会话→改文件→批准权限→git 提交"全流程；[ ] None 模式连远程 health 通过；[ ] 断网恢复后 SSE 自动重连 |
| **M3 工程化**（2周） | lite 变体 + OTA 快照升级 + 保活引导页 + 16KB CI 冒烟 + 双 APK 签名发布 | [ ] 快照升级保留旧会话库；[ ] 幻影进程引导页 adb 步骤逐条可复制执行；[ ] Release 双 APK + manifest 全部产出 |
| **M4 打磨**（持续） | 文件工作区/diff/gh 集成、性能调优（qemu 模式基准）、外接键盘 | [ ] 冷启动→READY ≤ 45s（中端机 8GB）；[ ] 空载引擎内存 ≤ 300MB；[ ] 崩溃率 < 0.5%/会话 |

---

## 10. 风险清单与对策

| 风险 | 等级 | 对策 |
|------|------|------|
| targetSdk 35 下私有目录 noexec（proot 无法 exec guest 二进制） | **高** | ExecCompat 三层策略（D5），M0 首日实验定案；qemu-user 降级为最终兜底 |
| 16KB page 设备上 guest glibc 二进制加载失败（Bun 段对齐 4KB） | **高** | 自编译产物全部 `-Wl,-z,max-page-size=16384`；qemu 模式下 qemu 自管 guest 映射（对齐要求为"至少"，多数情况可过）；16KB 模拟器进 CI 冒烟；无法挽救时该机型提示用 None 远程模式 |
| 幻影进程杀手杀引擎（Android 12+） | 高 | 引导页 adb 三连 + 开发者选项开关 + 崩溃自动重启；文档置顶 |
| 休眠时 guest 时钟停摆（suspend 冻结 sleep）→ agent 内 `sleep` 定时不准 | 中 | 产品定位为"人机交互中运行"，文档声明；关键等待改用 App 侧 alarm 唤醒 |
| 引擎内存峰值（Bun ~250–600MB）压垮 4GB 机型 | 中 | 设置页显示内存水位；< 4GB 机型建议 None 模式；`onTrimMemory` 预警 |
| opencode 版本漂移（CLI/API 变动，历史上 launcher 有过误判 bug） | 中 | 快照锁版本（manifest 记 ocVersion）；客户端按 `/doc` OpenAPI 动态兼容；升级走快照通道 |
| APK 体积（full ~200MB）劝退 | 低 | lite 变体首启下载 + 镜像加速；快照升级增量（后续可切 zstd+bsdiff） |
| GitHub 国内下载慢 | 低 | 镜像前缀配置项 + jsdelivr 备源（小文件） |

---

## 附录 A：保活 adb 命令与开关（用户引导用）

```bash
# 1. 解除幻影进程限制（Android 12–14，免 root，重启保留）
adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell "settings put global settings_enable_monitor_phantom_procs false"
adb reboot

# 2. Android 14+：开发者选项 → 「停用子进程限制」开关（App 引导页直接指路）

# 3. 验证
adb shell "device_config get activity_manager max_phantom_processes"   # 期望 2147483647
```

## 附录 B：版本与链接清单（2026-10 时点）

| 组件 | 版本 | 链接 |
|------|------|------|
| OpenCode | ≥ 1.18.x（快照锁定） | https://github.com/anomalyco/opencode · https://opencode.ai |
| Server API 文档 | OpenAPI 3.1 | https://opencode.ai/docs/server （`/doc` 端点实时获取） |
| Ubuntu Base | 24.04 arm64 | https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ |
| proot（Android 适配） | 跟随 Termux fork | https://github.com/termux/proot |
| qemu-user-static | 8.x/9.x | Termux packages 构建配方 / Debian 源码 |
| ConnectBot terminal 库 | Apache-2.0 | https://github.com/connectbot/connectbot |
| 社区实证（Android proot 跑 opencode） | — | dev.to/theagentloop（5 周日用实测）、Termux 安装方案总结 |

---

*配套文件：`code/build-snapshot.sh` · `code/SnapshotInstaller.kt` · `code/EngineService.kt` · `code/release.yml` · `assets/architecture.svg`*
