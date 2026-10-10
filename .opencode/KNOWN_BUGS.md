# KNOWN_BUGS — 审计底稿

> **用途**：本文件是专家团（`arch` / `engine` / `ui` / `native` / `snapshot` / `release` / `qa` / `security`）共享的**事实基线**。
> 任何角色开始工作前应先读本文件；发现行号漂移时以代码为准并就地更新。
>
> **审计方式**：静态代码审计 + kotlinc 独立编译验证 + JVM 单测实跑（环境缺 Android SDK `platform-35`，无法跑通完整 Android 构建）。
> **审计日期**：2026-10-09 · 审计对象：仓库默认分支 + 本次修复工作区
>
> **⚠ 首要结论**：本项目代码由外部 agent 产出后**从未真正编译通过**就提交。
> 首轮审计共找到 **6 个编译错误**（真实错误，非桩缺失误报），以及多个运行时/契约级缺陷。
> 且**流水线自身的门禁大量是坏的**（恒误报/配置非法），因此「CI 全绿」既可能是假绿、
> 「CI 红」也可能是假红。
>
> **✅ 进展**：业务缺陷已修 + 5 个 CI 门禁缺陷已修 → **run #58 首次真绿**
> （编译断言 + 13 单测 + 四道 gate + 16KB 冒烟全部通过）。详见文末「CI 门禁修复经验」。

---

# ✅ 修复进度

## 编译错误（6 个，全部已修）

| # | 位置 | 错误 | 验证 |
|---|---|---|---|
| 1 | `EngineService.kt:302` | 游离标识符 `n` | kotlinc: `302:1: unresolved reference 'n'` → 修复后 0 错 |
| 2 | `EngineService.kt:749` | `TimeUnit.toMillis(Int)` 不接受 Int（无隐式拓宽） | 探针复现 `Int vs Long` → 加 `.toLong()` 后通过 |
| 3 | `EngineService.kt:619` | `"$now()"` 未被当函数调用（缺花括号） | 探针复现 `function invocation 'now()' expected` → 改 `${now()}` |
| 4 | `SnapshotInstaller.kt:111` | `Long % Int == Int` 不合法 | 探针复现 → 改 `512L == 0L` |
| 5 | `SnapshotInstaller.kt:205-213` | `pn.OwnerRead` 枚举名错误（实为 `OWNER_READ`） | `javac` 证明枚举常量是大写下划线 → 全部改正 |
| 6 | （同 #4/#5 所在文件，随主代码编译一起暴露） | — | 全项目 kotlinc 复检结构性错误清零 |

> **教训**：第 5 个错误在前几轮一直被当作「缺 android 桩的误报」放过。甄别方法是**造最小探针**（不含任何 Android 符号）单独编译 —— 误报会消失，真错误会留下。

## 运行时 / 契约级缺陷

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| P0-1f | `SnapshotInstaller.kt` [1] 段 | **SHA 口径与 CI 不符**：CI 对压缩包 `sha256sum`，安装器却对解压后内容算哈希 → 生产快照校验必然失败，full 变体永远装不上 | ✅ 已修（改两遍式：先对压缩包流校验，再解压） |
| P0-2 | `EngineClient.kt` SseConnection | SSE 配额永久泄漏（释放路径缺失） | ✅ 已修（`run()` 的 finally 单点释放） |
| P2-2 | `EngineClient.kt` SseSubscription | `cancel()` 非幂等，修 P0-2 时须防双释放 | ✅ 已修（`AtomicBoolean` CAS + `release()` 双边界） |
| P0-4 | `EngineService.kt` bootSequence | 从不比对快照 `snapshotVersion`，升级永不触发 | ✅ 已修 |
| P1-1 | `EngineService.kt` bootSequence | `allocatePort` 异常逃逸协程 → 僵尸前台服务 | ✅ 已修（顶层 try/catch 收口） |
| P1-2 | `SnapshotInstaller.kt` rename 段 | `renameTo` 返回值未检查 → 写「假 ready」 | ✅ 已修 |
| P1-4 | `EngineService.kt` fail() | 不调 `stopSelf()` → 失败后服务不回收 | ✅ 已修 |
| P1-5 | `MainActivity.kt` | Activity 重建时 SSE 订阅未显式解绑 | ✅ 已修（`DisposableEffect` → `dispose()`） |
| P1-6 | `ChatScreen.kt` | `send/abort/create/rename/delete` 丢弃 `ApiResult` → 静默失败 | ✅ 已修（失败写 errorBanner + 会话归属校验） |
| P2-1 | `ChatScreen.kt:439` | `remember` 缺 key → 二次重命名初值残留 | ✅ 已修 |
| P2-3 | `MainActivity.kt` | 深色模式从未实现（硬编码 lightColorScheme + Manifest 拦截 uiMode） | ✅ 已修（`isSystemInDarkTheme()`） |
| P0-3 | `EngineService.kt` / `LiteSnapshot.kt` | lite 变体下载链路未实现 | ✅ 已修（新增 `LiteSnapshotDownloader`，含 Range 续传 + SHA 校验；CI 注入 lite manifest；4 单测全绿） |
| P1-3 | `SnapshotInstaller.kt` 类注释 | workspace 迁移措辞与实际不符 | ✅ 已修（注释更正：workspace 在 external 目录不迁移） |
| P3-1 | `SettingsScreen.kt` | `KeyVault.seal/unseal` 冗余 `Context` 参数 | ✅ 已修（去掉参数，调用点同步） |

## 门禁补强

| 项 | 状态 |
|---|---|
| `gradlew` + `gradle/wrapper/*` 入仓，CI 硬断言缺失即失败 | ✅ |
| CI 新增 `Compile assertion` step（full/lite 编译），置于 native 编译之前 | ✅ |
| JVM 单测：`SnapshotInstallerTest` 8 用例全绿（首次实跑验证） | ✅ |
| JVM 单测：`LiteSnapshotDownloaderTest` 4 用例全绿（本地 HTTP 服务） | ✅ |
| `testImplementation`：kotlin-test + coroutines-test 已入 `app/build.gradle.kts` | ✅ |

---

# 🔧 CI 门禁修复经验（真实 GitHub Actions 运行暴露）

> **最大教训**：修好业务代码只是第一步。真正把 CI 跑起来后才发现 —— **流水线自身的门禁大量是坏的**：
> 有的恒误报（把好产物判成坏），有的配置非法（永远不可能通过）。这些坏门禁此前掩盖了一切，
> 「CI 全绿」既可能是假绿，「CI 红」也可能是门禁误报的**假红**。
> 共 5 个门禁/CI 配置缺陷，逐一生效后才迎来 run #58 的**全绿**。

## CI-1 · gradle wrapper jar 非官方 → setup-gradle 校验失败

- 现象：`job_android` 第 4 步 `gradle/actions/setup-gradle@v4` 报
  `At least one Gradle Wrapper Jar failed validation`。
- 根因：本地用系统 apt 的 Gradle **4.4.1** 生成 wrapper（`Implementation-Version: 4.4.1`），
  jar 非官方 → 被 setup-gradle 的防篡改校验拒绝。
- 修复：下载官方 Gradle 8.7，在空目录 `gradle wrapper` 生成官方 wrapper（43KB，
  `Implementation-Title: Gradle Wrapper`）后拷回。
- **经验**：wrapper jar 必须来自官方 Gradle 发行版，别用系统包管理器装的旧版本生成。

## CI-2 · Gate 4 恒误报：grep 格式与 aapt2 实际输出不符

- 现象：编译断言通过后卡在 Gate 4，两个 APK 都报 `extractNativeLibs != true`，
  但日志里明明打印 `...extractNativeLibs(0x010104ea)=true`。
- 根因：断言 `grep -q '"true"'`（带双引号），而 aapt2 `dump xmltree` 输出是 `=true`（布尔无引号）。
- 修复：改为 `grep -qiE 'extractNativeLibs.*=(true|0xffffffff)'`。
- **经验**：门禁断言必须对齐工具**真实输出格式**；写断言前先捞一条真实样本。

## CI-3 · Gate 1b 恒误报：unzip 交互提示

- 现象：Gate 1b 循环校验两个 APK 的 .so 对齐，full 通过后 lite 报
  `APK 内缺 libproot.so/libqemu_aarch64.so`。
- 根因：`unzip -j ... -d "${TMP}/pkg"` 缺 `-o`；第二轮时 `${TMP}/pkg` 还留着上一轮的 .so，
  unzip 弹 `replace? [y/n]` 交互提示，CI 非交互读 stdin 得 NULL → 非零退出 → 误判缺库。
- 修复：每轮 `rm -rf pkg` + `unzip -o`。日志已实证两个 APK 的 .so 对齐均 PASS。
- **经验**：CI 里所有会读 stdin / 弹交互的命令必须显式非交互（`unzip -o`、`apt -y` 等）。

## CI-4 · 16KB 冒烟 job 配置非法 → 永远不可能通过

- 现象：`reactivecircus/android-emulator-runner@v2` 报
  `Value for input.arch 'x86_64-16k' is unknown. Supported options: x86,x86_64,arm64-v8a`。
- 根因：把 `16k` 塞进了 `arch`；该 action 的 arch 不接受它，16KB 镜像应通过 `target` 选。
- 修复：`target: google_apis_ps16k`（ps16k = page size 16KB 镜像）+ `arch: x86_64`，
  并补 `Enable KVM` 步骤（ubuntu-latest 跑模拟器所需）。
- **经验**：第三方 action 的输入取值范围以官方 README 为准；别臆造枚举值。

## CI-5 · 冒烟 script 被逐行执行 → 多行 for 循环语法错误

- 现象：模拟器已 booted，但 `sh: Syntax error: end of file unexpected (expecting "done")`。
- 根因：该 action 把 `script` **按行拆成多条 `sh -c`** 执行（日志可见
  `sh -c 'for i in $(seq 1 60); do'` 单独成条）→ 多行 `for/do/done` 被拆散。
- 修复：`script` 改 YAML **折叠标量 `>-`**（解析后为单行，不被拆行）；去掉 boot 等待
  （action 自身已等 `Emulator booted.`）；逻辑用 `;`/`if..fi` 压成一行（dash/bash 双语法校验过）。
- 附加：给 job 加 `continue-on-error: ${{ github.ref_type != 'tag' }}` —— 非 tag 推送不因
  模拟器基础设施抖动阻断主流水线；tag 发布仍严格。真正的产品门禁是 `job_android`。
- **经验**：传给 CI action 的 `script` 优先用单行或折叠标量；多行 shell 控制结构在逐行执行模型下必崩。

## CI-6 · 编译断言首次生效即抓出 5 个被本地过滤规则掩盖的真错误

新增 `Compile assertion` step 后，第一次运行就在**真实 Android 构建环境**抓出：

| 位置 | 错误 | 为何被漏 |
|---|---|---|
| `EngineService.kt` | 缺 `import kotlinx.coroutines.cancel`（`scope.cancel()` 无法解析） | 本地 kotlinc 过滤掉了 unresolved |
| `EngineService.kt` | suspend 函数内裸用 `isActive`（非 CoroutineScope 接收者）→ 改 `currentCoroutineContext().isActive` | 同上 |
| `ChatScreen.kt` | `onEvent` 表达式体 when 的分支块以无 else 的 `if` 结尾 | 同上 |
| `EngineClient.kt` | `Endpoints` 构造参数 `doc` 非 `val`，成员函数访问不到 | 同上 |
| `EngineClient.kt` | `TAG` 仅在 companion，同文件顶层类 `SseConnection` 访问不到 → 提为文件级 | 同上 |

- **经验**：本地 kotlinc 缺桩时的「过滤规则」会连真错误一起吞掉 —— **真实编译器/CI 才是唯一可信判据**。
  这也是新增 CI 编译断言的根本理由。

## CI-7 · 单测 step 抓出 `com.sun.net.httpserver` 在 Android 单测不可用

- 现象：`LiteSnapshotDownloaderTest` 在 CI 编不过（`Unresolved reference 'HttpServer'`），本地却过。
- 根因：Android 单测编译类路径**不含 JDK 的 `com.sun.*` 模块**（本地用完整 JDK 才通过）。
- 修复：改用 OkHttp `MockWebServer`（新增 `testImplementation mockwebserver:4.12.0`），行为等价。
- **经验**：Android JVM 单测 ≠ 纯 JVM；JDK 内部 API（`com.sun.*`）不可用，起本地服务用 MockWebServer。

---

# ✅ CI 修复后状态

- **run #58 首次全绿**：`job_snapshot` ✅ / `job_android` ✅（编译断言 + 单测 + Gate1/1b/4 + 四件套）/
  `job_smoke_16kb` ✅ / `job_release` ⊘（非 tag 按设计跳过）。
- 这是本项目**第一个「真绿」**——此前的绿灯建立在坏门禁（误报/恒失败）与从未编译通过之上。

---

## 未修复 / 待办

> 以下为 `/gate` 复验（专家团第二轮）确认的**可发布性缺口**——不影响当前 `main` 真绿，
> 但在打正式 tag 发布前应逐项处理。

| G-1 | 发布路径 | ~~tag 路径零验证~~ → ✅ **已验证**：4 个签名 Secrets 经 GitHub API 写入；修掉「tag 冒烟对 ABI 不可能断言硬失败卡死发布」的设计缺陷（安装/库校验恒降级 warning，PAGESIZE 仍硬）；打 `v0.0.1` 预发 tag → 全绿并**成功发布 Release**（四件套：full APK 135MB / lite APK 10MB / manifest.json / tar 125MB）。**注意**：签名密钥在服务器 `/root/keystore/`（upload.jks + store_pw.txt），请下载备份 | 高 |
| G-2 | `app/build.gradle.kts` | ~~lint 缺席~~ → ✅ 已修：`checkReleaseBuilds = true`，恢复 lintVitalRelease 出包门禁（只查 fatal） | 中 |
| G-3 | `EngineClient.kt` / `EngineService.kt` | ~~测试护栏缺口~~ → ✅ 大部分已修（累计 47 单测全绿）：纯 JVM `EngineClientCoreTest`(13)+`EngineLogicTest`(10, 含 bootSequence 决策)；Robolectric `EngineServiceTest`(2, proot 命令契约 C6)+`ExecCompatTest`(3)+`EngineBusTest`(1)；`SseConnectionTest`(2, MockWebServer 断流重连全链路)。**仍缺**：`bootSequence` 完整状态机端到端（CI 单测阶段快照已注入会真解压 120MB, 不宜做, 已用决策函数覆盖核心判断） | 中 |
| G-4 | 全仓库 | ~~UI 零测试~~ → ✅ 基建+多业务屏已建：Robolectric + Compose；`ComposeUiSmokeTest`(1) + `ChatPaneRenderTest`(1) + `PermissionDialogTest`(1) + `SessionsPaneTest`(1, 点击会话回调)。**仍缺**：SettingsScreen/TerminalScreen 等 | 中 |
| G-5 | `release.yml` / `app/build.gradle.kts` / wrapper | ~~NDK 未锁~~ → ✅ NDK 锁定(env.NDK_VERSION + Ensure pinned NDK + gradle ndkVersion)；✅ wrapper `distributionSha256Sum`(Gradle 分发包完整性)。**仍缺**：Gradle 依赖校验和(`verification-metadata.xml`, 需在 CI 生成) | 中 |
| G-6 | `release.yml` | ~~Release blocker grep 脆弱~~ → ✅ 已修：改为解析 `${VAR:-<default>}` 默认值（空/缺行均判缺失），并覆盖 proot 脚本 talloc/shmem | 中 |
| G-7 | `snapshot/build-snapshot.sh` | ~~快照下载无 sha256~~ → ✅ 已修：ubuntu-base 对齐上游同目录 `SHA256SUMS` 动态校验；opencode 资产用 `OPENCODE_SHA256`（v1.18.34 实测）校验。**仍缺**：apt 安装的 payload 本身无法位复现（固有） | 低 |
| G-8 | `EngineService.kt` / `app/build.gradle.kts` / `release.yml` | **AAPT2 改写 `.gz` 资产名**（真机运行才暴露）：CI 注入 `oc-ubuntu-arm64.tar.gz` 被 AAPT2 自动解压去后缀成 `assets/snapshot/oc-ubuntu-arm64.tar`（402MB 未压缩）→ app 按原名 `assets.open` 找不到 → 误回退 lite 网络下载 → 「快照安装失败」。修：内嵌资产改用 `.tgz`（AAPT 不改动）+ 新增 `Gate 5` 断言 APK 内资产名完好 | 高 |
| — | 运行时 | 真机首启仍待验证（G-8 修复后重发 v1.0.1 再测）；proot 实际执行 `opencode serve` 尚未在真机跑通 | 高 |
| — | `SettingsScreen.kt` | 未加 `snapshot_mirror` 的设置项 UI（引擎层已读该 prefs 键，用户暂需手动设置） | P3 |
| — | 构建验证 | 本环境缺 `platform-35`，无法本地完整构建；以真实 CI（run #58/#59/#60 全绿）为准 | — |

## `/gate` 复验结论（专家团 · qa + release-ops）

- **CI 修复 6/6 PASS**（wrapper 官方 8.7 · Gate4 regex 对齐 · Gate1b `-o`+清目录 ·
  单测 step 接入双通道 · smoke 单行脚本 · `continue-on-error` 仅限非 tag），
  **无 gate 被架空、无把失败降级为 warning 架空产品门禁**。
- **run #58 的 smoke 为真绿**（非 `continue-on-error` 掩盖）：日志实证 `PAGESIZE=16384` +
  `smoke 完成 (HARD=0)` + 步骤 conclusion=success；仅 arm64 APK 装入 x86_64 镜像的
  安装步骤按设计降级 warning（已知 ABI 边界）。
- 结论：**门禁本身已可信**；距「可安全发布」的差距在 **被测代码面过窄（G-3/G-4）与
  发布路径从未运行（G-1）**，非门禁有洞。


---

# 验证工具链

本轮建立的**独立编译验证能力**（位于 `/tmp/kotlinc`，会话间可能丢失）：

- `kotlin-compiler-2.0.20.jar` + stdlib/coroutines/okhttp/okio/commons-compress/json/junit/kotlin-test 等依赖
- `android-stub.jar`：最小 `android.content.Context` / `AssetManager` / `android.os.Build` 桩
- 用法：`java -cp <CP> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -cp <CP> -d <out> <files>`
- 跑测试：`java -cp <out:CP> org.junit.runner.JUnitCore dev.opencode.mobile.engine.SnapshotInstallerTest`

**局限**：缺 Compose 桩时 UI 文件会大量 `unresolved reference`（误报，需探针甄别）。
**正确姿势**：遇到可疑报错先造最小探针，不要在完整文件里猜。

---

# 契约基线（勿违反）

- D1–D8 技术决策冻结（P1 §2），变更需主理人终裁
- 快照 SHA-256 = **压缩包**哈希（与 CI `sha256sum` 一致）
- 引擎仅绑 `127.0.0.1`；Basic Auth 口令仅内存传递
- `.snapshot-meta.json` 的 `state=ready` 是「安装完成」唯一事实来源
- 快照解压→`.tmp`→原子 rename；`rootfs` 不真实存在则拒绝写 ready
- SSE 配额 ≤2，释放单点在 `SseConnection.run()` 的 finally
