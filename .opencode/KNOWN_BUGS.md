# KNOWN_BUGS — 审计底稿

> **用途**：本文件是专家团（`arch` / `engine` / `ui` / `native` / `snapshot` / `release` / `qa` / `security`）共享的**事实基线**。
> 任何角色开始工作前应先读本文件；发现行号漂移时以代码为准并就地更新。
>
> **审计方式**：静态代码审计 + kotlinc 独立编译验证 + JVM 单测实跑（环境缺 Android SDK `platform-35`，无法跑通完整 Android 构建）。
> **审计日期**：2026-10-09 · 审计对象：仓库默认分支 + 本次修复工作区
>
> **⚠ 首要结论**：本项目代码由外部 agent 产出后**从未真正编译通过**就提交。
> 这次审计共找到 **6 个编译错误**（全部真实，非桩缺失误报），以及多个运行时/契约级缺陷。
> 「CI 全绿」在当前流水线下**不能作为代码可用的证据**。

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

## 未修复 / 待办

| # | 位置 | 问题 | 级别 |
|---|---|---|---|
| — | 全仓库 | UI 层（Compose）仍无测试（需 Robolectric 或 Compose 测试基建） | — |
| — | `SettingsScreen.kt` | 未加 `snapshot_mirror` 的设置项 UI（引擎层已读该 prefs 键，用户暂需手动设置） | P3 |
| — | 构建验证 | 完整 Android 构建未跑通（本环境缺 `platform-35`），首次真机/CI 构建需预留调试余量 | — |


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
