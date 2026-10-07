/**
 * TerminalScreen.kt — M1 终端形态壳(P1 D6: ConnectBot terminal 库 fork 直跑 proot bash → opencode TUI)
 *
 * 集成设计(本文件预留全部接口与挂点, 真实库替换点见 [ProotSessionFactory] 注释):
 *   1. 依赖: settings.gradle 引入 ConnectBot fork 的 terminal-emulator + terminal-view
 *      (Apache-2.0, P1 D6 定稿; 明确禁用 GPL Termux terminal 库)。
 *   2. 进程: proot bash 直跑(无 serve), 命令复用 EngineService.buildProotCommand 骨架、
 *      尾部 `opencode serve …` 换 `bash` → 用户在 TUI 里敲 `opencode`; PROOT_TMP_DIR=
 *      {cacheDir}/proot(P3 §3 宿主侧环境变量契约)。TUI 与 serve 并存: 共享只读 rootfs,
 *      会话库同在 XDG_DATA_HOME(/root/.local/share), 是 M1-1「90 秒可对话」的最短链路。
 *   3. 渲染: [TerminalHost] 用 AndroidView 包裹 ConnectBot TerminalView; Stub 实现仅回显,
 *      保证无三方依赖时可编译运行、接口面与真实库一致。
 *   4. 快捷键条(P1 §5.4 / M4-5): Esc / Tab / Ctrl(sticky) / Ctrl+D —— 外接键盘场景由
 *      TerminalView 自带键处理, 软键条覆盖无键盘场景。
 *   5. 事件桥预留: [TerminalEventBridge] 占用第 2 个 SSE 订阅名额(P1 §3.4 ≤2: 终端桥+原生 UI)。
 */
package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 终端会话抽象: 对应 ConnectBot terminal-emulator 的 TerminalSession(pty 宿主) */
interface TerminalSession {
    /** 用户键入 → 进程 stdin(真实库: TerminalView.onEmulatorData 已含本方向, 此处供软键条) */
    fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun kill()
    fun detach()
}

/** 输出/退出回调(真实库: TerminalSession.SessionChangedCallback 承接) */
interface TerminalSessionListener {
    fun onText(buffer: CharSequence)
    fun onExit()
}

/**
 * 会话工厂。真实实现(集成 ConnectBot fork 后替换 Stub):
 *   org.connectbot.terminal.TerminalSession.startSession(
 *       command = [{nativeLibraryDir}/libproot.so, --kill-on-exit, -0, -w, /root,
 *                  -r, {filesDir}/rootfs, -b, /dev, -b, /proc, -b, /sys,
 *                  -b, {workspace}:/workspace, -b, {authDir}:/root/.config/opencode,
 *                  /usr/bin/env, -i, HOME=/root, LANG=C.UTF-8, LC_ALL=C.UTF-8,
 *                  PATH=/usr/local/bin:/usr/bin:/bin, XDG_DATA_HOME=/root/.local/share,
 *                  NO_PROXY=127.0.0.1,localhost, bash],   // P1 §3.6 env 集, serve→bash 直跑
 *       env = ["PROOT_TMP_DIR={cacheDir}/proot"],          // P3 §3: 宿主侧, 不进 guest
 *       cwd = {filesDir}/rootfs, …)
 * 环境变量集与 bind 清单必须与 EngineService.buildProotCommand 保持逐项一致(P1 §3.6/§3.5),
 * 差异仅一处: 进程尾 = bash(直跑 TUI 形态, P1 D2/D6)。
 */
interface TerminalSessionFactory {
    fun create(command: List<String>, env: Map<String, String>, listener: TerminalSessionListener): TerminalSession
}

/** Stub 实现: 无三方依赖时保证可编译可运行; 界面回显集成说明与按键回执 */
class StubTerminalSessionFactory(private val banner: List<String>) : TerminalSessionFactory {
    override fun create(command: List<String>, env: Map<String, String>, listener: TerminalSessionListener): TerminalSession =
        StubTerminalSession(banner, listener)
}

private class StubTerminalSession(
    banner: List<String>,
    private val listener: TerminalSessionListener,
) : TerminalSession {
    init {
        listener.onText(banner.joinToString("\n"))
    }

    override fun write(data: ByteArray) {
        // 回显可打印字符, 控制字符(Esc/Tab/Ctrl 组合)以 ^X 记法展示
        val echo = data.joinToString("") { b ->
            val c = b.toInt() and 0xff
            when {
                c == 0x1b -> "^["
                c == 0x09 -> "^I"
                c < 0x20 -> "^" + ('A' + c - 1)
                else -> c.toChar().toString()
            }
        }
        listener.onText(echo)
    }

    override fun resize(cols: Int, rows: Int) = Unit
    override fun kill() = listener.onExit()
    override fun detach() = Unit
}

/**
 * M2/M3 预留: 终端事件桥。若终端形态需要消费引擎事件(如会话列表同步), 通过本接口持有
 * EngineClient 并再开一路 SSE —— 全局第 2 个订阅名额(P1 §3.4 ≤2 订阅: 终端桥 + 原生 UI)。
 * 当前原生 UI 独占 1 个名额, 配额由 SseQuota 强制。
 */
interface TerminalEventBridge {
    fun attach(client: EngineClient)
    fun detach()
}

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    // 集成说明横幅: Stub 形态的初始输出(真实库替换后由 TUI 接管渲染)
    val banner = listOf(
        "══ OpenCode 终端形态(M1) ══",
        "本壳预留 ConnectBot terminal 库 fork(Apache-2.0, P1 D6)集成接口。",
        "真实会话命令(复用 EngineService.buildProotCommand 骨架, serve→bash):",
        "  {nativeLibraryDir}/libproot.so --kill-on-exit -0 -w /root -r {filesDir}/rootfs \\",
        "    -b /dev -b /proc -b /sys -b {workspace}:/workspace \\",
        "    -b {authDir}:/root/.config/opencode /usr/bin/env -i … bash",
        "PROOT_TMP_DIR={cacheDir}/proot(宿主侧, P3 §3)。",
        "进入 TUI 后执行 opencode 即可直跑对话(M1-1 最短验证路径)。",
        "──────────────────────────────",
    )
    var output by remember { mutableStateOf(listOf<String>()) }
    var ctrlArmed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val session = remember {
        // 换桩点: 真实集成改为 ProotSessionFactory().create(prootBashCommand(context), env, listener)
        StubTerminalSessionFactory(banner).create(
            command = emptyList(), env = emptyMap(),
            listener = object : TerminalSessionListener {
                override fun onText(buffer: CharSequence) { output = output + buffer.toString() }
                override fun onExit() { output = output + "[会话结束]" }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(title = "终端形态", onBack = {
            session.detach()
            onBack()
        })
        Text(
            "Stub 渲染(无 pty)。真实集成: AndroidView 包裹 ConnectBot TerminalView attach 本会话。",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(output) { line ->
                Text(line, Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        LaunchedEffect(output.size) { if (output.isNotEmpty()) listState.animateScrollToItem(output.size - 1) }

        // 快捷键条(P1 §5.4 / M4-5): Esc / Tab / Ctrl(sticky) / Ctrl+D
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalKey("Esc") { session.write(byteArrayOf(0x1b)) }              // ESC → 0x1B
            TerminalKey("Tab") { session.write(byteArrayOf(0x09)) }              // TAB → 0x09
            OutlinedButton(onClick = { ctrlArmed = !ctrlArmed }) {               // sticky 修饰键
                Text(if (ctrlArmed) "Ctrl*" else "Ctrl")
            }
            TerminalKey("Ctrl+D") {                                              // EOT → 0x04(结束 TUI 输入)
                session.write(byteArrayOf(0x04)); ctrlArmed = false
            }
        }
    }
    LaunchedEffect(ctrlArmed) {
        if (ctrlArmed) output = output + "[Ctrl 已挂起: 修饰下一字符键(真实集成由 TerminalView 软键盘处理)]"
    }
}

@Composable
private fun TerminalKey(label: String, onClick: () -> Unit) {
    Button(onClick = onClick) { Text(label, fontFamily = FontFamily.Monospace) }
}
