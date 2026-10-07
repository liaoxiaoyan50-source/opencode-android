/**
 * ChatScreen.kt — 会话列表(SessionsPane) + 聊天界面(ChatPane)
 *
 * 设计思路(契约出处见行内注释):
 *   1. 会话管理: GET/POST /session(列表/创建)、PATCH/DELETE /session/:id(更名/删除)
 *      (P1 §3.4); 对应端点被 /doc 判 MISSING 时按钮禁用(Endpoints, P1 §3.4 版本漂移对策)。
 *   2. 发送走 POST /session/:id/prompt_async(P1 §3.4 推荐路径, UI 不阻塞), 结果经 SSE 回流。
 *   3. SSE 流式渲染: MessagePart 事件按 partId upsert 到消息流(P1 §3.4 最低事件集 1);
 *      PermissionAsked 入审批队列(弹窗见 PermissionDialog.kt); SessionError 显示错误条。
 *   4. 订阅生命周期: ChatController 占用 1 个全局 SSE 名额(≤2: 原生 UI 1 + 终端桥预留 1,
 *      P1 §3.4); client 实例变化(引擎重启 password 轮换 / None 配置变更)即重建订阅。
 *   5. 重连状态重建: onResync 回调里重拉会话列表 + 当前会话全量消息(P1 §3.4)。
 */
package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SessionSummary(val id: String, val title: String)

data class ChatMessage(val key: String, val role: String, val text: String, val streaming: Boolean = false)

/** 审批队列条目(来自 SSE PermissionAsked 事件, P1 §3.4 最低事件集 2) */
data class PermissionRequest(
    val sessionId: String?, val permissionId: String?,
    val title: String?, val description: String?, val raw: JSONObject,
)

/**
 * 聊天域控制器: 持有唯一一个 SSE 订阅(/event 全局广播, 无互斥, P1 §3.4), 按会话 id 分桶渲染。
 * client 变化(bind)时: 取消旧订阅 → 重建新凭据订阅 → 全量重载(引擎重启后旧 password 即弃)。
 */
class ChatController(private val scope: CoroutineScope) {
    private var client: EngineClient? = null
    private var sse: SseSubscription? = null

    val currentClient = MutableStateFlow<EngineClient?>(null)
    val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val activeSessionId = MutableStateFlow<String?>(null)
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val permissionQueue = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val errorBanner = MutableStateFlow<String?>(null)
    val sending = MutableStateFlow(false)

    /** client 重建入口(引擎 handle 轮换 / None 配置变更); null = 引擎不可用(清空展示) */
    fun bind(newClient: EngineClient?) {
        sse?.cancel(); sse = null
        client = newClient
        currentClient.value = newClient
        sessions.value = emptyList()
        messages.value = emptyList()
        if (newClient == null) return
        // 1 个原生 UI 订阅(全局 ≤2, P1 §3.4); onResync = 重连成功重建状态(P1 §3.4)
        sse = newClient.subscribeEvents(
            onEvent = ::onEvent,
            onState = { st ->
                if (st == SseState.QUOTA_DENIED) errorBanner.value = "SSE 订阅名额已满(≤2, 终端桥占用中)"
            },
            onResync = ::resync,
        )
        scope.launch { loadSessions() }
        activeSessionId.value?.let { id -> scope.launch { loadMessages(id) } }
    }

    private suspend fun resync() { // P1 §3.4: 重连成功后重建会话状态
        loadSessions()
        activeSessionId.value?.let { loadMessages(it) }
    }

    suspend fun loadSessions() {
        val c = client ?: return
        when (val r = c.listSessions()) { // GET /session(P1 §3.4)
            is ApiResult.Ok -> sessions.value = parseSessions(r.value)
            is ApiResult.Err -> if (r.code == 401) errorBanner.value = "凭据已过期(引擎可能已重启), 等待新句柄…"
            is ApiResult.Network -> errorBanner.value = "网络错误: ${r.message}"
        }
    }

    private fun parseSessions(arr: JSONArray): List<SessionSummary> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optText("id") ?: return@mapNotNull null
        SessionSummary(id, o.optText("title") ?: o.optText("name") ?: id.take(8))
    }

    fun open(id: String?) {
        activeSessionId.value = id
        messages.value = emptyList()
        id?.let { scope.launch { loadMessages(it) } }
    }

    suspend fun loadMessages(id: String) {
        val c = client ?: return
        when (val r = c.messages(id)) { // GET /session/:id/message(P1 §3.4)
            is ApiResult.Ok -> if (activeSessionId.value == id) messages.value = parseHistory(r.value)
            is ApiResult.Err -> if (r.code != 401) errorBanner.value = "消息历史加载失败: HTTP ${r.code}"
            is ApiResult.Network -> errorBanner.value = "网络错误: ${r.message}"
        }
    }

    /** 历史条目宽松解析(opencode {info,parts} 结构或平铺 {role,parts}, 抗版本漂移) */
    private fun parseHistory(arr: JSONArray): List<ChatMessage> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val info = o.optJSONObject("info")
        val role = o.optText("role") ?: info?.optText("role") ?: "assistant"
        val key = o.optText("id") ?: info?.optText("id") ?: "h$i"
        val parts = o.optJSONArray("parts") ?: info?.optJSONArray("parts")
        val text = if (parts != null) (0 until parts.length())
            .mapNotNull { p -> parts.optJSONObject(p)?.optText("text") }.joinToString("")
        else o.optText("text") ?: ""
        ChatMessage(key = key, role = role, text = text)
    }

    fun create(title: String) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            c.createSession(title.ifBlank { "新会话" }) // POST /session(P1 §3.4)
            loadSessions()
        }
    }

    fun rename(id: String, title: String) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) { c.renameSession(id, title); loadSessions() } // PATCH(P1 §3.4)
    }

    fun delete(id: String) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            c.deleteSession(id) // DELETE /session/:id(P1 §3.4)
            if (activeSessionId.value == id) open(null)
            loadSessions()
        }
    }

    /** 发送: prompt_async 不阻塞(P1 §3.4), 消息渲染等 SSE MessagePart 回流 upsert */
    fun send(text: String) {
        val id = activeSessionId.value ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        sending.value = true
        scope.launch(Dispatchers.IO) {
            client?.promptAsync(id, body)
            sending.value = false
        }
    }

    fun abort() {
        val id = activeSessionId.value ?: return
        scope.launch(Dispatchers.IO) { client?.abort(id) } // POST /session/:id/abort(P1 §3.4)
    }

    /** 权限审批回执: POST /session/:id/permissions/:permissionID(P1 §3.4 / P3 §7.1) */
    fun respondPermission(req: PermissionRequest, response: String) {
        val sid = req.sessionId
        val pid = req.permissionId
        permissionQueue.update { it - req }
        if (sid == null || pid == null) return
        scope.launch(Dispatchers.IO) { client?.respondPermission(sid, pid, response) }
    }

    fun dismissError() { errorBanner.value = null }

    private fun onEvent(ev: EngineEvent) = when (ev) {
        is EngineEvent.MessagePart -> {
            val sid = ev.sessionId
            val text = ev.text
            if (sid == activeSessionId.value && text != null) {
                val key = ev.partId ?: (ev.messageId ?: "stream")
                // part 快照 upsert(opencode message.part.updated 携带完整 part)
                messages.update { list ->
                    val idx = list.indexOfFirst { it.key == key }
                    if (idx >= 0) list.toMutableList().also { it[idx] = it[idx].copy(text = text, streaming = true) }
                    else list + ChatMessage(key = key, role = "assistant", text = text, streaming = true)
                }
            }
        }
        is EngineEvent.PermissionAsked -> permissionQueue.update { it + PermissionRequest(ev.sessionId, ev.permissionId, ev.title, ev.description, ev.raw) }
        is EngineEvent.SessionError -> errorBanner.value = ev.message ?: "会话错误(见引擎日志)"
        is EngineEvent.Unknown -> Unit // P1 §3.4: 未知事件忽略(连接层已过滤, 双保险)
    }
}

// ─────────────────────────── 会话列表 ───────────────────────────

@Composable
fun SessionsPane(
    chat: ChatController,
    app: AppController,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val sessions by chat.sessions.collectAsState()
    val engineState by EngineBus.state.collectAsState()
    val client by chat.currentClient.collectAsState()
    val mode by app.engineMode.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SessionSummary?>(null) }
    val canRename = client?.endpoints?.renameSession == EndpointAvail.OK    // PATCH /session/{id}(P1 §3.4)
    val canDelete = client?.endpoints?.deleteSession == EndpointAvail.OK    // DELETE /session/{id}(P1 §3.4)

    Column(Modifier.fillMaxSize()) {
        // 顶栏: 标题 + 终端入口 + 设置入口
        SimpleTopBar(title = "OpenCode 会话", onBack = null,
            actions = { TextButton(onClick = onOpenTerminal) { Text("终端") }
                       TextButton(onClick = onOpenSettings) { Text("设置") } })

        // 引擎状态卡(P3 §7.1: 订阅 state 渲染; degraded 告警对应 /doc 不可用)
        Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(engineStateText(engineState), style = MaterialTheme.typography.bodyMedium)
                if (mode == AppController.MODE_NONE) Text("None 远程模式(${client?.baseUrl ?: "未配置"})",
                    style = MaterialTheme.typography.labelSmall)
                if (client?.degraded == true) Text(DynamicBody.DEGRADED_NOTE,
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }

        // 会话列表(GET /session; /doc 判 MISSING → 禁用创建, P1 §3.4)
        val canCreate = client?.endpoints?.createSession != EndpointAvail.MISSING && client != null
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("会话(${sessions.size})", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { showCreate = true }, enabled = canCreate) { Text("新建会话") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(sessions, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { onOpenSession(s.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(s.id, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋯") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("重命名") }, enabled = canRename,
                                onClick = { menu = false; editing = s },  // PATCH /session/:id(P1 §3.4)
                            )
                            DropdownMenuItem(
                                text = { Text("删除") }, enabled = canDelete,
                                onClick = { menu = false; chat.delete(s.id) }, // DELETE /session/:id(P1 §3.4)
                            )
                        }
                    }
                }
            }
        }
    }
    if (showCreate) NewSessionDialog(onDismiss = { showCreate = false }, onCreate = { t -> chat.create(t); showCreate = false })
    editing?.let { s ->
        RenameDialog(initial = s.title, onDismiss = { editing = null },
            onRename = { t -> chat.rename(s.id, t); editing = null }) // PATCH /session/:id(P1 §3.4)
    }
}

// ─────────────────────────── 聊天界面 ───────────────────────────

@Composable
fun ChatPane(chat: ChatController, onBack: () -> Unit) {
    val messages by chat.messages.collectAsState()
    val sending by chat.sending.collectAsState()
    val error by chat.errorBanner.collectAsState()
    val permissions by chat.permissionQueue.collectAsState()
    val client by chat.currentClient.collectAsState()
    var input by remember { mutableStateOf("") }

    val endpoints = client?.endpoints
    val canPrompt = endpoints?.promptAsync != EndpointAvail.MISSING && client != null
    val canAbort = endpoints?.abort == EndpointAvail.OK

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(
            title = "会话对话",
            onBack = onBack,
            actions = { if (canAbort) TextButton(onClick = { chat.abort() }) { Text("中断") } },
        )
        if (client?.degraded == true) ErrorBanner(DynamicBody.DEGRADED_NOTE) { chat.dismissError() }
        error?.let { ErrorBanner(it) { chat.dismissError() } }

        // 消息流(SSE MessagePart upsert 渲染; 自动滚底)
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(messages, key = { it.key }) { m -> MessageBubble(m) }
            if (messages.isEmpty()) item {
                Text("发送第一条消息开始对话(prompt_async, 结果经 SSE 流式回流)",
                    Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        // 权限审批弹窗(P1 §3.4 最低事件集 2 → 原生弹窗 → POST 回执)
        permissions.firstOrNull()?.let { req ->
            PermissionDialog(
                req = req,
                doc = client?.doc,
                choices = client?.permissionResponseChoices() ?: listOf("once", "always", "reject"),
                onRespond = { r -> chat.respondPermission(req, r) },
                onDismiss = { chat.permissionQueue.update { it - req } },
            )
        }

        // 输入行(prompt_async 被 /doc 判 MISSING → 禁用, P1 §3.4)
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), placeholder = { Text("输入消息…") },
                maxLines = 4,
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { chat.send(input); input = "" }, enabled = canPrompt && !sending && input.isNotBlank()) {
                Text(if (sending) "…" else "发送")
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Column(
            Modifier
                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(10.dp),
                )
                .padding(10.dp)
                .widthIn(max = 320.dp),
        ) {
            Text(if (isUser) "你" else "assistant", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            MessageBody(m.text) // 极简 Markdown-lite(围栏代码块), 完整引擎列 M3
            if (m.streaming) Text("▍", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 极简渲染: ``` 围栏代码块等宽深底, 其余常规文本; 不做完整 Markdown(M3 范围) */
@Composable
private fun MessageBody(text: String) {
    // 先在 remember 内把文本切分成 (isCode, content) 块, 再逐块渲染(块切分非 Composable 计算)
    val blocks = remember(text) {
        val out = mutableListOf<Pair<Boolean, String>>()
        var inCode = false
        val buf = StringBuilder()
        fun flush() { val t = buf.toString().trim(); buf.clear(); if (t.isNotEmpty()) out.add(inCode to t) }
        text.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) { flush(); inCode = !inCode }
            else { if (buf.isNotEmpty()) buf.append('\n'); buf.append(line) }
        }
        flush()
        out.toList()
    }
    Column {
        blocks.forEach { (isCode, t) ->
            Text(
                t,
                Modifier
                    .let { m -> if (isCode) m.background(MaterialTheme.colorScheme.scrim, RoundedCornerShape(6.dp)) else m }
                    .padding(if (isCode) 6.dp else 0.dp),
                fontFamily = if (isCode) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onDismiss) { Text("×") }
    }
}

@Composable
private fun NewSessionDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, placeholder = { Text("标题(可空)") }) },
        confirmButton = { TextButton(onClick = { onCreate(title); onDismiss() }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }) },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onRename(title) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
