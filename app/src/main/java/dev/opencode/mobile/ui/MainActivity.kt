/**
 * MainActivity.kt — 应用入口与导航(会话 ↔ 聊天 ↔ 设置 ↔ 终端)
 *
 * 设计思路(契约出处见行内注释):
 *   1. 包名/类名 = dev.opencode.mobile.ui.MainActivity —— P3 EngineService companion
 *      MAIN_ACTIVITY 常量(L798)经 Class.forName 定位通知点击落点, 重构类名必须同步 P3(P3 §7.1)。
 *   2. 注入时序(P3 §7.1 关键): onCreate 第一时间设置 Settings.authJsonProvider ——
 *      早于任何 EngineService.start 调用; 回调在引擎 bootSequence 写 oc-auth/auth.json 时
 *      触发(P3 L535-541), 文本仅经内存传递, UI 层不持久化明文(P1 §3.4 安全红线)。
 *   3. 自动拉起: Ubuntu 本地模式且引擎处于 Stopped 时 EngineService.start(M1-1: 开 App
 *      90 秒内可对话; Failed 终态不自动重启, 由用户在设置页处置或转 None 模式)。
 *   4. 导航为轻量 sealed 状态机(不引 navigation-compose, 减少 UI 层依赖面);
 *      client 由 AppController 流式供给, 其变化(引擎重启 password 轮换/None 配置变更)
 *      经 LaunchedEffect 传给 ChatController 重建订阅(P3 §7.1)。
 */
package dev.opencode.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.engine.EngineService
import dev.opencode.mobile.engine.EngineState
import dev.opencode.mobile.engine.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() { // P3 MAIN_ACTIVITY(L798) 通知点击落点: 类名勿改

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: AppController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // P3 §7.1 注入点①: startEngine 前设置 authJsonProvider(onCreate 早于下方 start 调用);
        // 回调返回 Keystore 解密的 auth.json 文本, 引擎层写 oc-auth(P1 §3.5 唯一写入者原则)
        Settings.authJsonProvider = { ctx -> KeyVault.buildAuthJson(ctx) }

        app = AppController(applicationContext, appScope)

        // [安全/功能] API 33+ 运行时申请通知权限。Manifest 声明了 POST_NOTIFICATIONS,
        // 但此前从不申请 → 引擎前台服务通知与 FAILED 诊断通知在真机上不可见。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS)
            }
        }

        // M1-1: 本地模式开 App 自动拉引擎(Failed 终态不自动重启, 避免 D8 10 分钟窗内空转)
        if (app.engineMode.value == AppController.MODE_LOCAL &&
            EngineBus.state.value is EngineState.Stopped
        ) {
            EngineService.start(this) // P3 §7.1: companion start/stop
        }

        setContent { OpenCodeTheme { AppRoot(app) } }
    }

    override fun onDestroy() {
        appScope.cancel() // 级联取消 client SSE/REST 协程; 引擎服务不受影响(独立生命周期)
        super.onDestroy()
    }

    private companion object {
        const val REQ_POST_NOTIFICATIONS = 100
    }
}

// ─────────────────────────── 导航 ───────────────────────────

private sealed class Nav {
    data object Sessions : Nav()               // 会话列表(SessionsPane)
    data class Chat(val sessionId: String) : Nav() // 聊天(ChatPane)
    data object Settings : Nav()               // 设置(SettingsScreen)
    data object Terminal : Nav()               // 终端形态(TerminalScreen)
}

@Composable
fun AppRoot(app: AppController) {
    val client by app.client.collectAsState()
    var nav by remember { mutableStateOf<Nav>(Nav.Sessions) }
    val chat = remember { ChatController(app.scope) }

    // client 变化 = 凭据变化: 引擎重启(password 轮换, P3 §7.1「勿跨重启缓存旧值」)/
    // None 配置变更/模式切换 → ChatController 取消旧订阅、以新凭据重建并全量重载
    LaunchedEffect(client) { chat.bind(client) }

    // [P1-5 修复] 组合离开时(Activity 销毁/转屏/进程重建)显式解绑 ChatController。
    // 此前只依赖 scope.cancel 的时序, 订阅句柄从未被显式 cancel。
    // 配额归还由 SseConnection.run 的 finally 单点负责(P0-2), dispose 保证语义明确。
    DisposableEffect(Unit) {
        onDispose { chat.dispose() }
    }

    BackHandler(enabled = nav != Nav.Sessions) { nav = Nav.Sessions }

    when (val n = nav) {
        Nav.Sessions -> SessionsPane(
            chat = chat, app = app,
            onOpenSession = { id -> chat.open(id); nav = Nav.Chat(id) },
            onOpenSettings = { nav = Nav.Settings },
            onOpenTerminal = { nav = Nav.Terminal },
        )
        is Nav.Chat -> ChatPane(chat = chat, onBack = { chat.open(null); nav = Nav.Sessions })
        Nav.Settings -> SettingsScreen(app = app, onBack = { nav = Nav.Sessions })
        Nav.Terminal -> TerminalScreen(onBack = { nav = Nav.Sessions })
    }
}

// ─────────────────────────── 通用小组件 / 主题 ───────────────────────────

/** 轻量顶栏(actions 用文本按钮, 零 icon 依赖) */
@Composable
internal fun SimpleTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable (() -> Unit)? = null) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ 返回") }
            Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            actions?.invoke()
        }
        androidx.compose.material3.HorizontalDivider()
    }
}

/**
 * 主题: 跟随系统浅色/深色。
 * [P2-3 修复] 此前硬编码 lightColorScheme 从未构造 darkColorScheme —— 而 Manifest 声明
 * configChanges 含 uiMode, 拦截了系统深色切换(不重建 Activity), 于是深色模式永远不生效。
 * 现依据 isSystemInDarkTheme() 选择配色, 由 Compose recomposition 驱动(uiMode 变化仍会触发)。
 */
@Composable
internal fun OpenCodeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Color(0xFFB2C5FF),
                errorContainer = Color(0xFF93000A),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF2D5BFF),
                errorContainer = Color(0xFFFFDAD6),
            )
        },
        content = content,
    )
}
