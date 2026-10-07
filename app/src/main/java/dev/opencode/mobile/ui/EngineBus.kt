/**
 * EngineBus.kt — 引擎流静态桥 + 客户端编排(AppController)
 *
 * 设计思路(契约出处见行内注释):
 *   1. EngineService.state/handle 是服务实例字段(P3 EngineService.kt L223/L225), 前台服务实例
 *      无法由 UI 直接引用 —— 本桥提供静态订阅面, P3 需在 EngineService.onCreate 里补一行
 *      EngineBus.attach(this)(状态流转的镜像转发, ≤6 行; 已列 P4 §8 请终裁)。
 *   2. AppController 负责模式编排: Ubuntu 本地 = 订阅 EngineHandle(replay=1, P3 §7.1)构造
 *      EngineClient(baseUrl=handle.baseUrl, 用户名固定 opencode, 密码=handle.password);
 *      None 远程 = 读用户配置构造同款客户端(P1 §3.4 双模式共用客户端)。
 *   3. 引擎重启 → handle 重发(replay=1) → combine 重新发射 → client 重建:
 *      旧 password 随之失效, 任何代码路径都拿不到旧句柄(P3 §7.1「勿跨引擎重启缓存旧值」)。
 */
package dev.opencode.mobile.ui

import android.content.Context
import dev.opencode.mobile.engine.EngineHandle
import dev.opencode.mobile.engine.EngineService
import dev.opencode.mobile.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 引擎流静态桥: P3 集成点 = EngineService.onCreate 调用 attach(this) */
object EngineBus {
    /** 镜像 EngineService.state(P3 L223): STOPPED/INSTALLING/STARTING/READY/FAILED 状态卡渲染 */
    val state = MutableStateFlow<EngineState>(EngineState.Stopped)

    /** 镜像 EngineService.handle(P3 L225, replay=1): UI 任意时刻订阅可得最近句柄(P3 §7.1) */
    val handle = MutableSharedFlow<EngineHandle>(replay = 1, extraBufferCapacity = 1)

    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mirrorJob: Job? = null

    /** P3 需在 EngineService.onCreate 调用; 重复 attach 以最新服务实例为准(服务重建自愈) */
    fun attach(service: EngineService) {
        mirrorJob?.cancel()
        mirrorJob = mirrorScope.launch {
            launch { service.state.collect { state.value = it } }
            launch { service.handle.collect { handle.tryEmit(it) } }
        }
    }
}

/** 引擎状态卡文案(P1 §5.3 状态机, 五态逐字) */
internal fun engineStateText(s: EngineState): String = when (s) {
    is EngineState.Stopped -> "引擎已停止"
    is EngineState.Installing -> "快照安装中: ${s.progress}"
    is EngineState.Starting -> "引擎启动中: ${s.mode} 模式 · 端口 ${s.port}"
    is EngineState.Ready -> "引擎就绪 · opencode ${s.ocVersion} @ 127.0.0.1:${s.port}"
    is EngineState.Failed -> "引擎失败: ${s.reason}(诊断与 None 远程引导见设置页)"
}

/**
 * 客户端编排器。client 流 = combine(引擎模式, 远程配置, EngineHandle):
 *   - MODE_LOCAL: handle 就绪(READY)才产出 client; 引擎重启 password 轮换 → 自动重建(P3 §7.1)
 *   - MODE_NONE : 按用户配置产出 client, 与本地引擎状态解耦(M2-3)
 * client 实例变化即"凭据变化"信号, 下游(ChatController)据此重建 SSE 订阅与数据。
 */
class AppController(context: Context, val scope: CoroutineScope) {
    private val appCtx = context.applicationContext
    private val uiPrefs = appCtx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    val engineMode = MutableStateFlow(uiPrefs.getString(KEY_MODE, MODE_LOCAL) ?: MODE_LOCAL)
    private val remote = MutableStateFlow(loadRemote())

    /** None 模式远程配置; 密码不进本类(仅 KeyVault 密文槽, P1 §3.4/M2-5) */
    data class RemoteCfg(val url: String, val user: String, val hasPassword: Boolean)

    private fun loadRemote(): RemoteCfg = RemoteCfg(
        url = uiPrefs.getString(KEY_REMOTE_URL, "").orEmpty(),
        user = uiPrefs.getString(KEY_REMOTE_USER, "").orEmpty(),
        hasPassword = KeyVault.get(appCtx, KeyVault.REMOTE_ID) != null,
    )

    /** handle 流转 nullable: 引擎未 READY 时为 null(SharedFlow 无首发值时 combine 不发射的对策) */
    private val handleOrNull: Flow<EngineHandle?> =
        EngineBus.handle.map { it as EngineHandle? }.onStart { emit(null) }

    val client: StateFlow<EngineClient?> = combine(engineMode, remote, handleOrNull) { mode, cfg, handle ->
        when (mode) {
            MODE_NONE -> {
                if (cfg.url.isBlank()) null
                else EngineClient(
                    baseUrl = cfg.url,                                    // P1 §3.4: 用户配置 http(s)://host[:port]
                    username = cfg.user.ifBlank { "opencode" },           // 可选用户名, 缺省 opencode(P1 §3.4)
                    password = KeyVault.get(appCtx, KeyVault.REMOTE_ID) ?: "", // Keystore 密文解密, 仅内存
                    scope = scope,
                )
            }
            else -> handle?.let {
                // P1 §3.4/P3 §7.1: 用户名固定 opencode, 密码 = 本轮 EngineHandle.password
                EngineClient(baseUrl = it.baseUrl, username = "opencode", password = it.password, scope = scope)
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** 保存 None 远程配置(密码走 KeyVault 密文, P2-5); 保存即触发 client 重建 */
    fun saveRemote(url: String, user: String, password: String?) {
        uiPrefs.edit().putString(KEY_REMOTE_URL, url.trim()).putString(KEY_REMOTE_USER, user.trim()).apply()
        if (password != null && password.isNotBlank()) {
            KeyVault.put(appCtx, KeyVault.REMOTE_ID, password) // Keystore AES-256-GCM 密文(见 SettingsScreen.kt)
        }
        remote.value = loadRemote()
    }

    /** 切换引擎模式(P1: Ubuntu 本地 / None 远程) */
    fun setEngineMode(mode: String) {
        uiPrefs.edit().putString(KEY_MODE, mode).apply()
        engineMode.value = mode
    }

    companion object {
        const val UI_PREFS = "ui_settings"
        const val KEY_MODE = "engine_mode"       // "local" | "none"
        const val KEY_REMOTE_URL = "remote_url"  // None 模式地址(非机密, 明文可存)
        const val KEY_REMOTE_USER = "remote_user"
        const val MODE_LOCAL = "local"           // P1: Ubuntu 本地引擎(proot + opencode serve)
        const val MODE_NONE = "none"             // P1: None 远程模式(用户配置 http(s)://host[:port])
    }
}
