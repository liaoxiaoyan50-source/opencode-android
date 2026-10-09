/**
 * EngineService.kt — 本地 Ubuntu 引擎(proot + opencode serve)前台服务编排
 *
 * 设计思路(P1 §5.3 输入摘要 → 逐项落地, 契约出处见行内注释):
 *   1. 引擎生命周期 = Foreground Service 生命周期。UI 通过 EngineService.start()/stop()
 *      控制, 状态经 state(EngineState) 广播给 Compose 层; EngineHandle{baseUrl, password,
 *      ocVersion} 是 UI 层唯一对接句柄(P1 §3.4/§5.3)。
 *   2. ExecCompat 探测(P1 §3.2 C2):
 *        L1  — 对 rootfs 内 /usr/bin/env 直接 execve 一次: 0→DIRECT; EACCES/Permission
 *              denied→QEMU; 其他异常→QEMU+诊断; 结果落盘 {filesDir}/.exec-mode, 设置页可清缓存重探测。
 *        L2  — 启动自愈: 健康检查 60s 超时且本轮从未 READY → 读 engine.log 尾部 4KB →
 *              命中特征串(P1 §3.2 四条 + P2b §6 正面 7 条; 负面样例 "can't sanitize binding"
 *              强制排除防误判) → 删 .exec-mode + 清 PROOT_TMP_DIR → 强制 QEMU 重试一次 →
 *              仍失败进 FAILED 终态(诊断报告 + None 远程模式引导文案)。
 *   3. 启动参数(P1 §3.6 C6): 端口 4096..4160 探测首个空闲口; env -i 全量注入 guest 环境
 *      变量; 健康检查 GET /global/health 指数退避 300ms 起上限 2s, 总超时 60s。
 *   4. 保活(P1 D8): Foreground Service(dataSync) + PARTIAL WakeLock(上限 6h 可配) +
 *      崩溃守护退避 30s→1m→2m、10 分钟窗内 3 连败进 FAILED 终态(冻结参数)。
 *   5. 安全红线: 引擎仅绑 127.0.0.1; Basic Auth 口令 = SecureRandom 32 位小写 hex(P1 §3.4),
 *      仅经 EngineHandle 内存传递, 不落盘不进日志, 重启引擎即轮换; API Key 由 ui-layer 的
 *      Keystore 解密后经 Settings.authJsonProvider 内存回调提供(引擎层不落明文)。
 *
 * 依赖: OkHttp / Kotlinx-Coroutines / SnapshotInstaller(同包)。
 * 变量说明: 字段级注释见各定义; 常量级冻结参数见 companion object。
 */
package dev.opencode.mobile.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.opencode.mobile.ui.EngineBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

// ─────────────────────────── 状态与模式(P1 §5.3 状态机) ───────────────────────────

/** STOPPED → INSTALLING → STARTING → READY / FAILED(状态机图见 P3 文档 §2) */
sealed class EngineState {
    data object Stopped : EngineState()
    data class Installing(val progress: InstallProgress) : EngineState()
    data class Starting(val mode: ExecMode, val port: Int) : EngineState()
    data class Ready(val port: Int, val ocVersion: String) : EngineState()
    data class Failed(val reason: String, val diagnostics: String) : EngineState()
}

/** ExecCompat 探测结论(P1 §3.2 C2 枚举, 逐字定稿) */
enum class ExecMode { DIRECT, QEMU }

/**
 * 引擎运行句柄: UI 层据此构造 EngineClient(baseUrl + Basic Auth 用户名固定 opencode)。
 * password 生命周期 = 引擎进程生命周期, 重启引擎即轮换, 不落盘(P1 §3.4)。
 */
data class EngineHandle(val baseUrl: String, val password: String, val ocVersion: String)

// ─────────────────────────── ExecCompat(P1 §3.2 C2) ───────────────────────────

object ExecCompat {

    private const val CACHE_FILE = ".exec-mode"      // P1 §3.2: 探测缓存落盘文件名(定稿)
    private const val DIAG_FILE = ".exec-mode.diag"  // 最近一次 L1 判定依据(诊断报告引用)

    /**
     * L1 探测(带缓存)。缓存命中 → 启动零探测开销; 缓存损坏 → 视为无缓存重探。
     * 探测与 proot 内行为等价的依据: SELinux 对 execve 的拦截先于 ptrace(P1 §3.2)。
     */
    fun detect(rootfs: File, nativeDir: String, filesDir: File): ExecMode {
        val cache = File(filesDir, CACHE_FILE)
        if (cache.exists()) {
            val cached = runCatching { ExecMode.valueOf(cache.readText().trim()) }.getOrNull()
            if (cached != null) return cached
            cache.delete() // 缓存内容非法 → 视为无缓存
        }
        val r = runProbe(rootfs)
        writeDiag(filesDir, r.diagnostic)
        runCatching { cache.writeText(r.mode.name) } // 落盘失败不阻断启动
        return r.mode
    }

    /** 清缓存重探测(P1 §3.2: 设置页「重新探测」入口, ROM 升级后用户可自愈) */
    fun reprobe(rootfs: File, nativeDir: String, filesDir: File): ExecMode {
        clearCache(filesDir)
        return detect(rootfs, nativeDir, filesDir)
    }

    /** 清除探测缓存(L2 自愈处置第 1 步也复用本方法) */
    fun clearCache(filesDir: File) { File(filesDir, CACHE_FILE).delete() }

    /** 最近一次 L1 探测诊断(FAILED 诊断报告引用) */
    fun readDiag(filesDir: File): String =
        runCatching { File(filesDir, DIAG_FILE).readText() }.getOrDefault("(无探测诊断)")

    private fun writeDiag(filesDir: File, text: String) {
        runCatching {
            File(filesDir, DIAG_FILE).writeText(
                "[${java.time.LocalDateTime.now().withNano(0)}] $text\n")
        }
    }

    /** L1 探测结果: 模式 + 判定依据(诊断) */
    private data class ProbeResult(val mode: ExecMode, val diagnostic: String)

    /**
     * L1 探测本体 — 逐步骤判定契约: P1 §3.2 L1 表(决策表可视化见 P3 文档 §3.1)。
     * 步骤1: {rootfs}/usr/bin/env 不存在 → QEMU + 诊断
     * 步骤2: File.setExecutable(true)
     * 步骤3: ProcessBuilder(env, --version), 5 秒超时
     * 步骤4a: 退出码 0 → DIRECT
     * 步骤4b: 异常链含 EACCES / "Permission denied" → QEMU(SELinux execute 拦截)
     * 步骤4c: 其他异常(超时/崩溃/非 0 退出) → QEMU(安全兜底) + 诊断记录
     */
    private fun runProbe(rootfs: File): ProbeResult {
        // 步骤 1
        val env = File(rootfs, "usr/bin/env")
        if (!env.exists()) {
            return ProbeResult(ExecMode.QEMU,
                "L1 步骤1: ${env.absolutePath} 不存在 → QEMU(快照异常, 直接走兜底模式)")
        }
        // 步骤 2
        runCatching { env.setExecutable(true) }
            .onFailure {
                return ProbeResult(ExecMode.QEMU,
                    "L1 步骤2: setExecutable 失败: ${it.message} → QEMU")
            }
        // 步骤 3
        val proc = try {
            ProcessBuilder(env.absolutePath, "--version")
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            return if (isPermissionDenied(e)) {
                // 步骤 4b
                ProbeResult(ExecMode.QEMU,
                    "L1 步骤4b: exec 被拒(${e.message}) → QEMU(EACCES/SELinux execute 拦截)")
            } else {
                // 步骤 4c
                ProbeResult(ExecMode.QEMU,
                    "L1 步骤4c: exec 异常: $e → QEMU(安全兜底)")
            }
        }
        try {
            return when {
                !proc.waitFor(5, TimeUnit.SECONDS) ->          // 步骤 4c: 超时
                    ProbeResult(ExecMode.QEMU, "L1 步骤4c: 执行超时(>5s) → QEMU(安全兜底)")
                proc.exitValue() == 0 ->                        // 步骤 4a
                    ProbeResult(ExecMode.DIRECT, "L1 步骤4a: 退出码 0 → DIRECT")
                else ->                                          // 步骤 4c: 非 0 退出
                    ProbeResult(ExecMode.QEMU,
                        "L1 步骤4c: 退出码 ${proc.exitValue()} → QEMU(安全兜底)")
            }
        } finally {
            proc.destroyForcibly()
        }
    }

    /** 异常链(含 cause, 最深 5 层防环)中是否含 EACCES / "Permission denied"(P1 §3.2 步骤4b) */
    private fun isPermissionDenied(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(5)
            .mapNotNull { it.message }
            .any { m -> m.contains("EACCES", ignoreCase = true) ||
                      m.contains("Permission denied", ignoreCase = true) }
}

// ─────────────────────────── 设置桥(骨架引用点, 最小实现) ───────────────────────────

/**
 * 设置桥: 骨架 buildProotCommand()/writeAuthStub() 引用的 Settings 在 code/ 中无源文件,
 * 为满足「完整可编译」在此补最小实现; ui-layer 可整体替换或按需改写来源。
 *
 *  - proxy()        : 用户代理地址(非机密), 读 SharedPreferences, 设置页写 "proxy" 键。
 *  - authJson()     : auth.json 注入内容 —— 机密(M2-5: API Key 仅存 Keystore 加密 prefs,
 *                     dumpsys/文件系统无明文), 引擎层不落盘; ui-layer 在 startEngine 前
 *                     设置 [authJsonProvider] 内存回调, 未配置 Provider 时默认 "{}"。
 *  - wakeLockHours(): 保活时长(小时), 默认 6 = P1 D8 上限, 设置页可配(1..24 收敛)。
 */
object Settings {
    /** 由 ui-layer 注入; 返回 auth.json 文件文本(仅内存, 不落盘不进日志) */
    var authJsonProvider: (Context) -> String = { "{}" }

    fun proxy(context: Context): String = prefs(context).getString("proxy", "").orEmpty()

    fun authJson(context: Context): String =
        runCatching { authJsonProvider(context) }.getOrDefault("{}")

    fun wakeLockHours(context: Context): Int = prefs(context).getInt("wake_lock_hours", 6)

    /**
     * lite 变体快照下载的镜像前缀(P0-3)。留空则用 [SnapshotSource.DEFAULT_BASE]。
     * 国内网络可设置为 ghproxy 类镜像以加速; 非机密, 明文存 prefs。
     */
    fun snapshotMirror(context: Context): String = prefs(context).getString("snapshot_mirror", "").orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
}

// ─────────────────────────── 服务本体 ───────────────────────────

class EngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    // 引擎状态流: Compose 层订阅渲染(会话页顶部状态卡)
    val state = MutableStateFlow<EngineState>(EngineState.Stopped)
    // 引擎就绪句柄流: replay=1 保证 UI 任意时刻订阅都能拿到最近一次句柄(EngineHandle 为唯一对接句柄)
    val handle = MutableSharedFlow<EngineHandle>(replay = 1, extraBufferCapacity = 1)

    // ——— 核心字段 ———
    private var engineProcess: Process? = null      // proot 子进程句柄
    private var engineJob: Job? = null              // 守护协程(bootSequence → superviseEngine)
    private var currentPort = 0                     // 本轮端口(4096..4160 内探测)
    private var currentMode = ExecMode.DIRECT       // 当前生效的执行模式(诊断引用)
    private var enginePassword = ""                 // 本轮 Basic Auth 口令(随机, 仅内存)
    private var wakeLock: PowerManager.WakeLock? = null
    private val attemptHistory = mutableListOf<String>() // ExecMode 尝试历史(诊断报告字段, P1 §3.2)

    private val installer by lazy { SnapshotInstaller(this) }
    private val rootfs get() = installer.rootfsDir                       // P1 §3.5: files/rootfs
    private val workspace: File get() = File(getExternalFilesDir(null), "workspace") // P1 §3.5: 外部私有工作区
    private val nativeDir: String get() = applicationInfo.nativeLibraryDir  // P1 §3.5: 唯一可执行区
    private val authDir: File get() = File(filesDir, "oc-auth")          // P1 §3.5: 配置注入目录
    // P2b §5-3: proot loader 内嵌, 运行期提取到 PROOT_TMP_DIR。落点取 cache 的 proot 子目录
    // 而非 cache 根(防 L2 清缓存误删 lite 变体下载产物; 语义同「app cache」, 偏差已列 P3 §10 请终裁)
    private val prootTmpDir: File get() = File(cacheDir, "proot")

    // ── Service 入口 ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        EngineBus.attach(this) // P4 §8 静态桥: state/handle 镜像转发至 ui 层(重复 attach 以最新实例自愈)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundAndEngine()
            ACTION_STOP   -> stopEngine("用户停止")
            // START_STICKY 被系统回收重建时 intent 为 null: 必须先 startForeground
            // (否则 ~5s 内 ForegroundServiceDidNotStartInTimeException 崩溃),
            // 并自动拉起引擎(快照已就绪则秒级进 Starting, 兼顾 M1-3 恢复语义)
            null          -> startForegroundAndEngine()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 先杀引擎进程再取消协程: superviseEngine 的 proc.waitFor() 依赖进程退出解除阻塞
        killQuietly(engineProcess)
        engineProcess = null
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    // ── 启动编排 ─────────────────────────────────────────────────

    private fun startForegroundAndEngine() {
        startAsForeground()
        if (engineJob?.isActive == true) return // 防重复启动(服务重建/重复点击)
        engineJob = scope.launch { bootSequence() }
    }

    /** 启动编排: 快照就绪 → oc-auth 注入 → PROOT_TMP_DIR 就绪 → L1 选模式 → 守护循环 */
    private suspend fun bootSequence() {
        // [P1-1 修复] 顶层异常收口。此前 allocatePort() 抛出的 IllegalStateException 会
        // 逃逸出 bootSequence → scope.launch → 被 SupervisorJob 默认处理器吞掉 →
        // state 永久停在 Starting, 不进 FAILED, 无诊断报告, 前台服务成为僵尸。
        try {
            bootSequenceInner()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t // 协作式取消不吞
            appendEngineNote("[${now()}] [FATAL] bootSequence 未预期异常: ${t.javaClass.simpleName}: ${t.message}")
            fail("引擎启动异常: ${t.javaClass.simpleName}", buildDiagnostics(currentMode))
        }
    }

    private suspend fun bootSequenceInner() {
        // 步骤1: 快照就绪。判定含「是否需要升级」(P0-4 修复): 此前只判 installedVersion()==null,
        //        从不比对 bundled manifest 的 snapshotVersion, App 升级带来新快照时旧 rootfs 永不替换,
        //        P6 M3-2 验收项不可能通过。
        val bundled = readBundledManifest()
        // [P1 minAppVersion] 快照要求的最低 App 版本校验(P1 §3.1 / M3-6): 旧 App 不得
        // 安装/下载不兼容的新快照, 直接阻断并引导升级。判定抽到 EngineLogic(可纯测)。
        if (bundled != null && isBlockedByMinAppVersion(appVersionName(), bundled)) {
            return fail("需升级 App",
                "快照(oc ${bundled.ocVersion})要求 App ≥ ${bundled.minAppVersion}, " +
                    "当前 App ${appVersionName()}。请升级 App 后再启动本地引擎(P1 §3.1)。")
        }
        val installed = installer.installedVersion()
        val needsInstall = needsSnapshotInstall(installed, bundled)
        if (needsInstall) {
            if (installed != null && bundled != null) {
                appendEngineNote(
                    "[${now()}] 快照版本更新: 已装 $installed → 目标 ${bundled.snapshotVersion}, 执行升级(保留会话库)"
                )
            }
            val manifest = bundled
                ?: return fail("无可用快照",
                    "assets/snapshot/manifest.json 缺失或 schemaVersion!=1(P1 §3.1: 需升级 App)")
            runCatching { installSnapshot(manifest) }
                .onFailure { return fail("快照安装失败", it.stackTraceToString()) }
        }

        // 步骤2: oc-auth 注入目录写入(P1 §3.5 配置唯一写入者原则: App 是 auth.json 与
        //        opencode.json 的唯一写入者; 该目录 bind 到 guest /root/.config/opencode)
        runCatching { writeOcAuth() }
            .onFailure { return fail("oc-auth 注入失败", it.stackTraceToString()) }

        // 步骤3: PROOT_TMP_DIR 就绪(P2b §5-3: 启动前确保存在且可写, loader 运行期提取于此)
        runCatching { prootTmpDir.mkdirs() }
            .onFailure { return fail("缓存目录不可写", it.stackTraceToString()) }

        // 步骤4: ExecCompat L1 选模式(结果落盘 {filesDir}/.exec-mode, P1 §3.2)
        currentMode = ExecCompat.detect(rootfs, nativeDir, filesDir)

        // 步骤5: 本轮 Basic Auth 口令(P1 §3.4: SecureRandom 32 位小写 hex, 仅内存, 重启即轮换)
        enginePassword = randomToken(32)

        // 步骤6: 拉起守护循环(L2 自愈 + D8 崩溃退避)
        superviseEngine(currentMode)
    }

    /**
     * 安装快照(P0-3: 补全 lite 链路)。
     *   - full 变体: tar 内嵌 assets → 直接读 assets 安装。
     *   - lite 变体: 无 tar → 按 manifest 从 GitHub Release 下载 → SHA-256 校验 → 安装。
     * 两路最终都汇聚到 SnapshotInstaller.install(SHA-256 → 解压 → 原子 rename)。
     */
    private suspend fun installSnapshot(manifest: SnapshotManifest) {
        val hasBundledTar = runCatching { assets.open(SNAPSHOT_ASSET).close(); true }.getOrDefault(false)
        if (hasBundledTar) {
            state.value = EngineState.Installing(InstallProgress.Finishing)
            installer.install(
                openStream = SnapshotInstaller.fromAssets(this@EngineService, SNAPSHOT_ASSET),
                totalBytes = -1, manifest = manifest,
            ) { state.value = EngineState.Installing(it) }
            return
        }
        // lite: 下载 → 校验 → 安装
        state.value = EngineState.Installing(InstallProgress.Verifying(0, -1))
        val base = Settings.snapshotMirror(this).ifBlank { SnapshotSource.DEFAULT_BASE }
        val url = SnapshotSource.urlFor(base, appVersionName(), manifest.file)
        appendEngineNote("[${now()}] [lite] 下载快照: $url (snapshot=${manifest.snapshotVersion})")
        val dlClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // 大文件下载: 不设读超时
            .build()
        val file = LiteSnapshotDownloader(dlClient).download(
            url = url,
            manifest = manifest,
            destDir = File(cacheDir, "snapshot-dl"),
        ) { rb, tb -> state.value = EngineState.Installing(InstallProgress.Verifying(rb, tb)) }
        installer.install(
            openStream = SnapshotInstaller.fromDownload(file),
            totalBytes = file.length(), manifest = manifest,
        ) { state.value = EngineState.Installing(it) }
        runCatching { file.delete() } // 解压完成后压缩包无保留价值
    }

    /** App versionName(拼 lite 下载 tag 用; 发布时 tag = v{versionName}) */
    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    /**
     * 引擎守护循环:
     *   拉起 → 健康检查(P1 §3.6) → READY(常驻) → 进程意外退出 → 失败处理;
     *   失败处理优先级:
     *     ① L2 启动自愈(P1 §3.2, 全生命周期仅一次): 本轮从未 READY + 当前 DIRECT +
     *        日志尾部 4KB 命中特征串 → 清缓存强制 QEMU 立即重试一次 → 仍失败 → FAILED 终态;
     *     ② D8 崩溃退避(P1 D8 冻结参数): 首败立即重启(M1-3: kill -9 后 10s 内恢复 READY,
     *        调和方案已列 P3 §10 请终裁), 次败起 30s→1m→2m 退避, 10 分钟窗内 3 连败 → FAILED 终态。
     */
    private suspend fun superviseEngine(initialMode: ExecMode) {
        var mode = initialMode
        var l2 = L2State.NONE
        var backoffMs = BACKOFF_INITIAL_MS
        val failureTimes = ArrayDeque<Long>()   // D8: 10 分钟滑动窗失败时间戳

        while (currentCoroutineContext().isActive) {
            // 每轮重新探测端口: 探测-占用 TOCTOU 竞态 P1 §7-5 已定稿接受(绑定失败由健康检查超时兜底)
            currentPort = allocatePort()
            currentMode = mode
            recordAttempt(mode)

            state.value = EngineState.Starting(mode, currentPort)
            val proc = startEngineProcess(mode, currentPort)
            engineProcess = proc
            pumpEngineLog(proc, mode, currentPort)

            var wasReady = false
            val version = awaitHealthy()
            if (version != null) {
                wasReady = true
                warnVersionDrift(version)
                failureTimes.clear()                 // 成功即重置 D8 计数与退避
                backoffMs = BACKOFF_INITIAL_MS
                if (l2 == L2State.RETRYING) l2 = L2State.DONE
                acquireWakeLock()
                state.value = EngineState.Ready(currentPort, version)
                handle.tryEmit(EngineHandle("http://127.0.0.1:$currentPort", enginePassword, version))
                proc.waitFor()                       // 常驻运行: 进程意外退出后落回失败处理
                releaseWakeLock()
                if (!currentCoroutineContext().isActive) return
                state.value = EngineState.Starting(mode, currentPort)
                appendEngineNote("[${now()}] 引擎进程意外退出(exit=${runCatching { proc.exitValue() }.getOrDefault("?")}) → 守护重启")
            }
            if (!wasReady) killQuietly(proc)

            // —— 失败处理 ①: L2 启动自愈(P1 §3.2) ——
            // 触发条件: 健康检查 60s 超时(进程存活或退出, wasReady=false 覆盖两者)、
            // 当前 L1 结论为 DIRECT、尚未自愈过; 曾 READY 的运行期崩溃不走 L2(属 D8 范畴)
            if (!wasReady && l2 == L2State.NONE && mode == ExecMode.DIRECT) {
                val hit = SelfHeal.match(readLogTail(L2_LOG_TAIL_BYTES))
                if (hit != null) {
                    l2 = L2State.RETRYING
                    ExecCompat.clearCache(filesDir)  // 处置 1: 删除 .exec-mode 缓存
                    clearProotTmpDir()               // 处置 1b: 清 PROOT_TMP_DIR loader 残留(P2b §5-3)
                    mode = ExecMode.QEMU             // 处置 2: 强制 QEMU 模式
                    appendEngineNote("[${now()}] [L2] 健康检查失败且命中自愈特征串($hit) → 判 L1 误判, 清缓存后强制 QEMU 重试一次")
                    continue                         // 立即重试, 不走退避
                }
            }
            // L2 自愈重试轮仍失败 → FAILED 终态(P1 §3.2: 「强制以 QEMU 模式重试一次 → 仍失败进 FAILED 终态」)
            if (!wasReady && l2 == L2State.RETRYING) {
                fail("L2 自愈(QEMU 降级)重试仍失败", buildDiagnostics(mode))
                return
            }

            // —— 失败处理 ②: D8 崩溃退避(冻结参数) ——
            val t = System.currentTimeMillis()
            failureTimes.addLast(t)
            while (failureTimes.isNotEmpty() && t - failureTimes.first() > FAILURE_WINDOW_MS) {
                failureTimes.removeFirst()
            }
            if (failureTimes.size >= MAX_FAILURES_IN_WINDOW) {
                fail("崩溃守护: ${FAILURE_WINDOW_MS / 60_000} 分钟窗口内 $MAX_FAILURES_IN_WINDOW 连败",
                    buildDiagnostics(mode))
                return
            }
            // 首败立即重启(M1-3: kill -9 后 10s 内恢复 READY); 次败起按 30s→1m→2m 退避
            if (failureTimes.size >= 2) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
        }
    }

    /** L2 自愈状态机: NONE(未触发) → RETRYING(强制 QEMU 重试轮) → DONE(终局, 不再触发) */
    private enum class L2State { NONE, RETRYING, DONE }

    // ── 引擎进程与健康检查 ───────────────────────────────────────

    /** 拉起 proot 子进程; PROOT_TMP_DIR 为宿主侧环境变量(P2b §5-3), 不进 guest */
    private fun startEngineProcess(mode: ExecMode, port: Int): Process =
        ProcessBuilder(buildProotCommand(mode, port))
            .redirectErrorStream(true)
            .apply { environment()["PROOT_TMP_DIR"] = prootTmpDir.absolutePath }
            .start()

    /**
     * 轮询 /global/health(opencode 官方就绪端点, P1 §3.4/§3.6):
     * 指数退避 300ms 起、上限 2s, 总超时 60s; 超时返回 null 触发 L2/D8 失败处理。
     * 请求携带 Basic Auth(用户名固定 opencode, P1 §3.4)。
     */
    private suspend fun awaitHealthy(): String? {
        var gap = HEALTH_INTERVAL_MIN_MS
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                http.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$currentPort/global/health")
                        .header("Authorization", Credentials.basic("opencode", enginePassword))
                        .build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                            ?.let { return it }
                    }
                }
            }
            delay(gap); gap = (gap * 2).coerceAtMost(HEALTH_INTERVAL_MAX_MS)
        }
        return null
    }

    // ── proot 命令构造(权威参考: P1 §3.6 C6 模板; P2b §5 调用约定) ──

    /**
     * DIRECT: {nativeDir}/libproot.so --kill-on-exit -0 -w /root -r {rootfs} \
     *           -b /dev -b /proc -b /sys \
     *           -b {workspace}:/workspace -b {authDir}:/root/.config/opencode \
     *           /usr/bin/env -i {env...} opencode serve --hostname 127.0.0.1 --port {port}
     * QEMU  : 同上, 在 -w /root 之后插入 -q {nativeDir}/libqemu_aarch64.so
     *         (P1 §3.3/P2b §5-2: -q 必须传宿主侧绝对路径, proot 不做 guest 路径翻译;
     *          两库均 NEEDED=0 静态产物, 无需 LD_LIBRARY_PATH)
     * 安全: serve 仅绑 127.0.0.1, 绝不暴露局域网(P1 §3.4/注意事项)。
     */
    fun buildProotCommand(mode: ExecMode, port: Int): List<String> {
        val proot = "$nativeDir/libproot.so"
        val qemu = "$nativeDir/libqemu_aarch64.so"
        // guest 环境变量集: env -i 全量注入, 缺一不可(P1 §3.6 表)
        val env = listOf(
            "HOME=/root",                                // P1 §3.6
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8",            // P1 §3.6: 中文乱码修复(社区实测必须)
            "PATH=/usr/local/bin:/usr/bin:/bin",         // P1 §3.6
            "XDG_DATA_HOME=/root/.local/share",          // P1 §3.6: 会话库目录锚点
            "HTTP_PROXY=${Settings.proxy(this)}",        // P1 §3.6: 用户配置代理(可为空)
            "HTTPS_PROXY=${Settings.proxy(this)}",
            "NO_PROXY=127.0.0.1,localhost",              // P1 §3.6: 本机回调不走代理
            "OPENCODE_SERVER_PASSWORD=$enginePassword",  // P1 §3.4/§3.6: 本轮随机口令(仅进程环境, 不落盘)
        )
        // bind 挂载清单: 顺序无关但内容定死(P1 §3.5 C5)
        val binds = listOf(
            "-b", "/dev", "-b", "/proc", "-b", "/sys",                                  // 原样
            "-b", "${workspace.apply { mkdirs() }.absolutePath}:/workspace",             // 工作区
            "-b", "${authDir.apply { mkdirs() }.absolutePath}:/root/.config/opencode",   // oc-auth 注入点
        )
        val guest = listOf(
            "/usr/bin/env", "-i", *env.toTypedArray(),
            // P1 §3.6: opencode serve 无头模式, 绑定 loopback
            "opencode", "serve", "--hostname", "127.0.0.1", "--port", port.toString(),
        )
        return when (mode) {
            ExecMode.DIRECT -> listOf(proot, "--kill-on-exit", "-0", "-w", "/root",
                "-r", rootfs.absolutePath, *binds.toTypedArray(), *guest.toTypedArray())
            // -q 位置契约: 插在 -w /root 之后(P1 §3.6 模板原样)
            ExecMode.QEMU -> listOf(proot, "--kill-on-exit", "-0", "-w", "/root", "-q", qemu,
                "-r", rootfs.absolutePath, *binds.toTypedArray(), *guest.toTypedArray())
        }
    }

    // ── oc-auth 注入目录(P1 §3.5 配置唯一写入者原则) ──────────────

    /**
     * App 启动引擎前写入(P1 §3.5 C5):
     *   auth.json     — Provider Key 注入文件, 权限 600; 内容经 Settings.authJsonProvider
     *                   内存提供(ui-layer Keystore 解密), 引擎层不落明文存储。
     *   opencode.json — 引擎配置: autoupdate=false(快照锁版, 禁止 guest 内自更新) +
     *                   share=disabled(禁分享)。
     * 该目录整体 bind 到 guest /root/.config/opencode, 是运行期配置唯一生效来源;
     * 快照内模板文件仅离线参考(P1 §3.5 定稿)。
     */
    private fun writeOcAuth() {
        authDir.mkdirs()
        val auth = File(authDir, "auth.json")
        auth.writeText(Settings.authJson(this))
        File(authDir, "opencode.json").writeText("""{"autoupdate": false, "share": "disabled"}""")
        restrictOwner600(auth) // P1 §3.5: auth.json 权限 600
    }

    /** 纯 Kotlin 权限收紧: owner rw-, group/other 无(等效 600); POSIX 不可用时退化 File API */
    private fun restrictOwner600(f: File) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
        }.onFailure {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
            f.setExecutable(false, false)
        }
    }

    // ── 引擎日志(L2 读尾部判定; FAILED 诊断引用; 口令永不落日志) ──

    /** 引擎 stdout/stderr → engine.log(追加模式, 各轮以分隔行隔开; 上限 5MB 截断保留尾部) */
    private fun pumpEngineLog(proc: Process, mode: ExecMode, port: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val log = File(filesDir, "engine.log")
                rotateIfNeeded(log)
                appendEngineNote("──── [${now()}] attempt mode=$mode port=$port (Basic Auth 口令不记录) ────")
                proc.inputStream.buffered().use { src ->
                    FileOutputStream(log, true).buffered().use { dst -> src.copyTo(dst) }
                }
            } catch (_: Exception) { /* 流随进程退出关闭, 正常路径 */ }
        }
    }

    private fun appendEngineNote(line: String) {
        runCatching { File(filesDir, "engine.log").appendText(line + "\n") }
    }

    /** 日志滚动: 超过 5MB 截断保留尾部一半(UTF-8 边界可能截半字符, 对诊断无碍) */
    private fun rotateIfNeeded(log: File) {
        runCatching {
            val len = log.length()
            if (len <= LOG_CAP_BYTES) return
            val keep = (LOG_CAP_BYTES / 2).toInt()
            RandomAccessFile(log, "r").use { raf ->
                raf.seek(len - keep)
                val tail = ByteArray(keep); raf.readFully(tail)
                log.writeText(String(tail, Charsets.UTF_8))
            }
        }
    }

    /** 读 engine.log 尾部 n 字节(L2 自愈读 4KB, P1 §3.2) */
    private fun readLogTail(bytes: Int): String {
        val log = File(filesDir, "engine.log")
        if (!log.exists()) return ""
        return runCatching {
            val len = log.length()
            if (len <= bytes) return@runCatching log.readText()
            RandomAccessFile(log, "r").use { raf ->
                raf.seek(len - bytes)
                val buf = ByteArray(bytes); raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    // ── FAILED 终态诊断报告(P1 §3.2 定稿字段) ────────────────────

    /**
     * FAILED 诊断报告: SELinux 上下文 /proc/self/attr/current、内核版本、宿主 page size
     * (getconf PAGESIZE)、ExecMode 尝试历史、engine.log 尾部; 末尾固定附 None(远程)模式引导文案。
     */
    private fun buildDiagnostics(mode: ExecMode): String = buildString {
        appendLine("══ OpenCode 引擎诊断报告 ══")
        appendLine("生成时间 : ${now()}")
        appendLine("最终模式 : $mode (全部尝试见 ExecMode 尝试历史)")
        appendLine("SELinux  : ${procText("/proc/self/attr/current")}")
        appendLine("内核     : ${System.getProperty("os.version") ?: "unknown"}")
        appendLine("设备     : ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        appendLine("PageSize : ${hostPageSize()} 字节 (宿主侧, 等效 getconf PAGESIZE)")
        appendLine("最近 L1 探测: ${ExecCompat.readDiag(filesDir).trim().lineSequence().lastOrNull().orEmpty()}")
        appendLine(".exec-mode 缓存存在: ${File(filesDir, ".exec-mode").exists()}")
        appendLine("ExecMode 尝试历史:")
        attemptHistory.forEach { appendLine("  · $it") }
        appendLine("──── engine.log 尾部 ${L2_LOG_TAIL_BYTES}B ────")
        appendLine(readLogTail(L2_LOG_TAIL_BYTES).ifBlank { "(空)" })
        appendLine()
        appendLine("本地引擎暂不可用? 可改用 None(远程)模式: 设置 → 引擎模式 → 填入远程 opencode 服务地址")
        appendLine("(http/https, 明文 http 将有警示), 连通测试通过后即可继续使用。")
    }

    private fun procText(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("(不可读)")

    /** 宿主 page size: 优先 getconf PAGESIZE(P1 §3.2 原文), 不可用回退 sysconf */
    private fun hostPageSize(): String {
        runCatching {
            val p = ProcessBuilder("getconf", "PAGESIZE").redirectErrorStream(true).start()
            val ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
            val out = if (ok) p.inputStream.bufferedReader().readText().trim() else ""
            p.destroyForcibly()
            if (out.isNotEmpty()) return out
        }
        return runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toString()
        }.getOrDefault("unknown")
    }

    private fun recordAttempt(mode: ExecMode) {
        attemptHistory.add("[${now()}] mode=$mode port=$currentPort")
        if (attemptHistory.size > 16) attemptHistory.removeAt(0)
    }

    /** 版本漂移观测(P1 §3.1: health version 需与快照 ocVersion 前缀一致; 偏差仅告警不阻断) */
    private fun warnVersionDrift(healthVersion: String) {
        val expected = runCatching {
            assets.open("snapshot/manifest.json").bufferedReader().readText().let { raw ->
                Regex("\"ocVersion\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            }
        }.getOrNull() ?: return
        if (!healthVersion.startsWith(expected)) {
            appendEngineNote("[${now()}] [warn] /global/health version=$healthVersion 与快照 ocVersion=$expected 前缀不一致(P1 §3.1)")
        }
    }

    // ── 停止/前台/保活/工具函数 ──────────────────────────────────

    private fun stopEngine(reason: String) {
        appendEngineNote("[${now()}] [stop] $reason")
        killQuietly(engineProcess)
        engineProcess = null
        engineJob?.cancel()
        engineJob = null
        releaseWakeLock()
        state.value = EngineState.Stopped
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    /** FAILED 终态: 状态广播 + 诊断报告落盘(engine-diagnostics.txt) + 通知栏入口(P1 §3.2) */
    private fun fail(title: String, detail: String) {
        appendEngineNote("[${now()}] [FAILED] $title")
        runCatching { File(filesDir, "engine-diagnostics.txt").writeText(detail) }
        state.value = EngineState.Failed(title, detail)
        notifyFailed(title)
        // [P1-4 修复] 原实现只 stopForeground 不 stopSelf, 服务降级为常驻 started service
        // 不被回收(scope 与 OkHttpClient 一直存活)。对比 stopEngine() 是有 stopSelf 的。
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startAsForeground() {
        ensureChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ENGINE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("OpenCode 引擎运行中")
            .setContentText(if (currentPort > 0) "127.0.0.1:$currentPort · Ubuntu 快照引擎"
                            else "Ubuntu 快照引擎启动中")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, mainActivityIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            // P1 D8: Foreground Service(dataSync)
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            // API 26–28: 3 参 startForeground 为 API 29+ 才存在, 必须走 2 参版本
            startForeground(NOTIFY_ID, n)
        }
    }

    /** API 26+ 必须先建 NotificationChannel, 否则通知不可见/部分 ROM 异常 */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ENGINE) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ENGINE, "OpenCode 引擎",
                        NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun notifyFailed(title: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ENGINE)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("OpenCode 引擎启动失败")
            .setContentText("$title · 点按查看诊断报告")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, mainActivityIntent(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFY_ID_FAILED, n) }
    }

    /** 通知点击落点: ui-layer MainActivity(重构类名需同步 [MAIN_ACTIVITY]) */
    private fun mainActivityIntent(): Intent = runCatching {
        Intent(this, Class.forName(MAIN_ACTIVITY))
    }.getOrElse {
        // 兜底: 打开本包启动器入口
        Intent(Intent.ACTION_MAIN).apply { setPackage(packageName); addCategory(Intent.CATEGORY_LAUNCHER) }
    }

    /** P1 D8: PARTIAL WakeLock, 上限 6h(Settings.wakeLockHours 可配 1..24), READY 后持有 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .also { it.acquire(TimeUnit.HOURS.toMillis(Settings.wakeLockHours(this).coerceIn(1, 24).toLong())) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /**
     * 端口分配: 4096..4160 内以 ServerSocket 探测首个空闲口(P1 §3.6 定稿范围)。
     * 探测与占用存在 TOCTOU 竞态 — P1 §7-5 已定稿接受(绑定失败由健康检查超时兜底), 仅记录不修。
     */
    private fun allocatePort(): Int =
        (PORT_MIN..PORT_MAX).firstOrNull { p ->
            runCatching {
                ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", p)) }.close(); true
            }.getOrDefault(false)
        } ?: throw IllegalStateException("$PORT_MIN..$PORT_MAX 无空闲端口")

    /** Basic Auth 口令: SecureRandom 32 位小写 hex(P1 §3.4); 仅经 EngineHandle 内存传递 */
    private fun randomToken(len: Int): String =
        SecureRandom().let { r -> CharArray(len) { HEX[r.nextInt(16)] } }.concatToString()

    /** 清 PROOT_TMP_DIR(P2b §5-3: 破除 loader 提取残留导致的异常态), L2 自愈处置 1b */
    private fun clearProotTmpDir() {
        runCatching { prootTmpDir.deleteRecursively(); prootTmpDir.mkdirs() }
    }

    private fun killQuietly(p: Process?) {
        p ?: return
        runCatching { p.destroy() }
        runCatching { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
    }

    private fun now(): String = java.time.LocalDateTime.now().withNano(0).toString()

    /** full/lite 内置 manifest 读取; schemaVersion!=1 按 P1 §3.1 拒绝(提示升级 App) */
    private fun readBundledManifest(): SnapshotManifest? = runCatching {
        val raw = assets.open("snapshot/manifest.json").bufferedReader().readText()
        fun s(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]+)\"").find(raw)!!.groupValues[1]
        val schema = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)").find(raw)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (schema != 1) throw IllegalStateException("manifest schemaVersion=$schema, 需要升级 App")
        // file 字段(C1 定稿)用于 lite 下载; 缺失时回落默认名(向后兼容旧 manifest)
        val fileName = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            ?: "oc-ubuntu-arm64.tar.gz"
        // minAppVersion(C1 定稿): 快照要求的最低 App 版本; 缺失回落空串(不校验)
        val minApp = Regex("\"minAppVersion\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: ""
        SnapshotManifest(s("snapshotVersion"), s("ocVersion"), s("sha256"),
            Regex("\"size\"\\s*:\\s*(\\d+)").find(raw)!!.groupValues[1].toLong(), fileName, minApp)
    }.getOrNull()

    // ── 冻结参数与常量(出处见行内注释) ──────────────────────────

    companion object {
        const val CHANNEL_ENGINE = "engine"
        const val NOTIFY_ID = 1001
        private const val NOTIFY_ID_FAILED = 1002
        const val ACTION_START = "dev.opencode.mobile.engine.START"
        const val ACTION_STOP = "dev.opencode.mobile.engine.STOP"

        private const val MAIN_ACTIVITY = "dev.opencode.mobile.ui.MainActivity"   // ui-layer 落点
        private const val SNAPSHOT_ASSET = "snapshot/oc-ubuntu-arm64.tar.gz"      // full 变体资产路径
        private const val WAKELOCK_TAG = "opencode:engine"
        private const val HEX = "0123456789abcdef"                                // 小写 hex 字符池

        // P1 §3.6: 端口探测范围(定稿)
        private const val PORT_MIN = 4096
        private const val PORT_MAX = 4160

        // P1 §3.6: 健康检查参数(定稿)
        private const val HEALTH_TIMEOUT_MS = 60_000L
        private const val HEALTH_INTERVAL_MIN_MS = 300L
        private const val HEALTH_INTERVAL_MAX_MS = 2_000L

        // P1 D8: 崩溃守护退避与失败窗口(冻结参数)
        private const val BACKOFF_INITIAL_MS = 30_000L
        private const val BACKOFF_MAX_MS = 120_000L
        private const val FAILURE_WINDOW_MS = 10 * 60_000L
        private const val MAX_FAILURES_IN_WINDOW = 3

        // 日志与 L2 自愈参数
        private const val LOG_CAP_BYTES = 5L * 1024 * 1024   // engine.log 上限(滚动)
        private const val L2_LOG_TAIL_BYTES = 4096           // P1 §3.2: L2 读尾部 4KB

        fun start(c: Context) = c.startForegroundService(
            Intent(c, EngineService::class.java).setAction(ACTION_START))
        fun stop(c: Context) = c.startService(
            Intent(c, EngineService::class.java).setAction(ACTION_STOP))
    }
}
