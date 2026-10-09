/**
 * EngineClient.kt — REST + SSE 统一客户端（Ubuntu 本地 / None 远程共用，唯一差异是构造参数）
 *
 * 设计思路（契约出处见行内注释）:
 *   1. 双模式共用: baseUrl 由调用方给定 —— Ubuntu 模式 = EngineHandle.baseUrl(http://127.0.0.1:{port}),
 *      None 模式 = 用户配置的 http(s)://host[:port](P1 §3.4「二者共用同一客户端实现」)。
 *   2. Basic Auth 拦截器(P1 §3.4): Ubuntu 模式用户名固定 opencode、密码 = EngineHandle.password;
 *      None 模式取用户配置的用户名(缺省 opencode)/密码。密码仅内存传递, toString 全程脱敏, 不进日志。
 *   3. /doc 动态校验(P1 §3.4 版本漂移对策): 启动时拉取 OpenAPI 3.1, 校验端点存在性 → 缺失端点由
 *      Endpoints 标记 MISSING, UI 据此禁用对应功能; body 由 DynamicBody 按 /doc schema 组装,
 *      全程禁止硬编码 body schema。/doc 不可用 → degraded 模式: UI 顶部告警条 + seed 兜底发送。
 *   4. SSE /event(P1 §3.4): 最低事件集 = 消息片段更新 / 权限请求 / 会话错误; 未知事件类型一律
 *      忽略不崩溃; 全局同一时刻 ≤2 订阅(SseQuota: 终端桥 + 原生 UI); 断线指数退避 1s→30s;
 *      重连成功后回调 onResync, 由调用方重建会话状态。
 *
 * 依赖: OkHttp / Kotlinx-Coroutines / org.json(平台内置)。
 */
package dev.opencode.mobile.ui

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** REST 调用结果; Err.code==401 表示凭据过期(引擎重启密码轮换, 见 P4 文档 §2.2) */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T, val code: Int) : ApiResult<T>()
    data class Err(val code: Int, val message: String) : ApiResult<Nothing>()
    data class Network(val message: String) : ApiResult<Nothing>()
}

/**
 * SSE 事件模型。前三类 = P1 §3.4 冻结的最低事件集; Unknown = 未知事件兜底,
 * 一律忽略不崩溃(P1 §3.4「未知事件类型一律忽略不崩溃」)。字段宽松提取, 抗版本漂移。
 */
sealed class EngineEvent {
    /** 消息片段更新(流式渲染): 按 partId upsert */
    data class MessagePart(
        val sessionId: String?, val messageId: String?, val partId: String?,
        val text: String?, val raw: JSONObject,
    ) : EngineEvent()

    /** 权限请求 → 触发原生审批弹窗(once/always/reject, P3 §7.1 触发链) */
    data class PermissionAsked(
        val sessionId: String?, val permissionId: String?,
        val title: String?, val description: String?, val raw: JSONObject,
    ) : EngineEvent()

    /** 会话错误 */
    data class SessionError(val sessionId: String?, val message: String?, val raw: JSONObject) : EngineEvent()

    /** 未知事件: 解析后直接丢弃, 仅 debug 日志(P1 §3.4) */
    data class Unknown(val type: String, val raw: JSONObject) : EngineEvent()
}

/** SSE 连接状态(供 UI 状态条/日志观测; RETRYING 期间退避值见 logcat, M2-4 观测点) */
enum class SseState { CONNECTING, CONNECTED, RETRYING, CLOSED, QUOTA_DENIED }

/** 事件解析: type 精确匹配优先(官方事件名), 关键词兜底抗漂移; 都不中 → Unknown */
internal fun parseEvent(eventName: String, json: JSONObject): EngineEvent {
    val type = json.optString("type", eventName).ifBlank { eventName }
    val props = json.optJSONObject("properties") ?: json
    val sessionId = props.optText("sessionID") ?: props.optText("sessionId") ?: props.optText("session_id")
    return when {
        // 最低事件集 1: 消息片段更新(P1 §3.4)。opencode 载荷: properties.part.{id,messageID,text,sessionID}
        type == "message.part.updated" || (type.contains("part") && type.contains("message")) -> {
            val part = props.optJSONObject("part")
            EngineEvent.MessagePart(
                sessionId = sessionId ?: part?.optText("sessionID"),
                messageId = part?.optText("messageID") ?: props.optText("messageID"),
                partId = part?.optText("id"),
                text = part?.optText("text"),
                raw = json,
            )
        }
        // 最低事件集 2: 权限请求(P1 §3.4 / P3 §7.1)。实核 v1.18.34(sdk types.gen.ts): 事件名 = permission.updated
        // (非 asked), 载荷 properties = Permission{id,type,title,pattern,sessionID,...}, 宽松提取多候选键保持兼容;
        // permission.replied(审批回执广播)须先于本分支分流, 否则被 contains("permission") 兜底误捕获 → 审批后重复弹窗。
        type.contains("replied") -> EngineEvent.Unknown(type, json) // 回执事件: 忽略不弹窗(事件化清理待授权后启用)
        type == "permission.updated" || type.contains("permission") -> EngineEvent.PermissionAsked(
            sessionId = sessionId,
            permissionId = props.optText("permissionID") ?: props.optText("id"),
            title = props.optText("title") ?: props.optText("type"),
            description = props.optText("pattern") ?: props.optText("description") ?: props.optText("message"),
            raw = json,
        )
        // 最低事件集 3: 会话错误(P1 §3.4)
        type == "session.error" || type.contains("error") -> EngineEvent.SessionError(
            sessionId = sessionId,
            message = props.optJSONObject("error")?.optText("message") ?: props.optText("message"),
            raw = json,
        )
        // 实核 v1.18.34: server.connected = SSE 流首事件, 已知无害 → 显式落 Unknown 忽略(与既有兜底同效, 不崩溃)
        type == "server.connected" -> EngineEvent.Unknown(type, json)
        else -> EngineEvent.Unknown(type, json) // 未知事件 → 上层忽略(P1 §3.4)
    }
}

/** JSONObject 安全取字符串(缺失/空串 → null) */
internal fun JSONObject.optText(key: String): String? = optString(key, "").ifBlank { null }

// ─────────────────────────── /doc 动态 schema(P1 §3.4 版本漂移对策) ───────────────────────────

/**
 * OpenAPI 3.1 文档封装。仅用于: ① 端点存在性校验; ② requestBody schema 提取(组装动态 body /
 * 枚举值)。支持 "#/components/..." 的 $ref 解引用(最多 8 跳防环)。
 */
class ApiDoc internal constructor(val root: JSONObject) {
    private val paths: JSONObject? = root.optJSONObject("paths")

    private fun methodNode(template: String, method: String): JSONObject? =
        paths?.optJSONObject(template)?.optJSONObject(method.lowercase())

    fun hasEndpoint(template: String, method: String): Boolean = methodNode(template, method) != null

    /** 把实际请求路径(/session/abc)匹配到 /doc 模板(/session/{id}); 未命中返回 null */
    fun matchTemplate(actual: String): String? {
        val p = paths ?: return null
        if (p.has(actual)) return actual
        val segs = actual.trim('/').split('/')
        for (key in p.keys()) {
            val ksegs = key.trim('/').split('/')
            if (ksegs.size != segs.size) continue
            var ok = true
            for (i in ksegs.indices) {
                if (ksegs[i].startsWith("{")) continue // 模板参数段通配
                if (ksegs[i] != segs[i]) { ok = false; break }
            }
            if (ok) return key
        }
        return null
    }

    private fun deref(node: JSONObject?): JSONObject? {
        var cur = node ?: return null
        var hops = 0
        while (cur.has("\$ref") && hops++ < 8) {
            val ref = cur.optString("\$ref")
            var obj: JSONObject? = root
            for (seg in ref.removePrefix("#/").split('/')) obj = obj?.optJSONObject(seg) ?: return null
            cur = obj ?: return null
        }
        return cur
    }

    /** requestBody 的 application/json schema(经 $ref 解引用); 无 body 端点返回 null */
    fun requestSchema(template: String, method: String): JSONObject? {
        val node = deref(methodNode(template, method)) ?: return null
        val rb = deref(node.optJSONObject("requestBody")) ?: return null
        val json = rb.optJSONObject("content")?.optJSONObject("application/json") ?: return null
        return deref(json.optJSONObject("schema"))
    }

    /** requestBody 中某属性的 enum(权限审批回复值语义以 /doc 为准, P1 §3.4) */
    fun requestEnum(template: String, method: String, property: String): List<String>? {
        val schema = requestSchema(template, method) ?: return null
        val prop = deref(schema.optJSONObject("properties")?.optJSONObject(property)) ?: return null
        val arr = prop.optJSONArray("enum") ?: return null
        return (0 until arr.length()).map { arr.optString(it) }
    }
}

/** 端点可用性(禁硬编码 body schema 的 UI 侧对策: MISSING → 禁用对应功能, P1 §3.4) */
enum class EndpointAvail { OK, MISSING, UNKNOWN }

/** 端点注册表: /doc 拉取成功 → 逐端点判 OK/MISSING; /doc 不可用 → 全 UNKNOWN(degraded, UI 告警) */
class Endpoints(doc: ApiDoc?) {
    val docAvailable: Boolean = doc != null

    private fun avail(template: String, method: String): EndpointAvail = when {
        doc == null -> EndpointAvail.UNKNOWN
        doc.hasEndpoint(template, method) -> EndpointAvail.OK
        else -> EndpointAvail.MISSING
    }

    // 端点路径为 P1 §3.4 冻结清单(路径稳定契约; body schema 一律动态)
    val listSessions by lazy { avail("/session", "GET") }
    val createSession by lazy { avail("/session", "POST") }
    val sessionDetail by lazy { avail("/session/{id}", "GET") }
    val renameSession by lazy { avail("/session/{id}", "PATCH") }
    val deleteSession by lazy { avail("/session/{id}", "DELETE") }
    val messages by lazy { avail("/session/{id}/message", "GET") }
    val promptAsync by lazy { avail("/session/{id}/prompt_async", "POST") }
    val abort by lazy { avail("/session/{id}/abort", "POST") }
    val respondPermission by lazy { avail("/session/{id}/permissions/{permissionID}", "POST") }
}

/**
 * 动态 body 组装器(P1 §3.4: 禁止硬编码 body schema)。
 *   - /doc schema 存在 → 仅保留 schema properties 中实际存在的 seed 键; required 但 seed 缺失
 *     的键填类型默认值(防必填缺失 422)。seed 中的多余键不会外泄。
 *   - /doc 或 schema 缺失 → seed 原样兜底发送, 调用方 UI 显示 [DEGRADED_NOTE] 告警。
 * seed 携带候选键(如 prompt_async 同时备 parts/text), 由 schema 裁决用哪个 —— 版本漂移时
 * 客户端零改动。
 */
object DynamicBody {
    const val DEGRADED_NOTE = "⚠ /doc 不可用, 请求体未经 schema 校验(降级模式)"

    fun build(doc: ApiDoc?, actualPath: String, method: String, seed: JSONObject): JSONObject {
        val d = doc ?: return seed // degraded: 无法校验, seed 兜底
        val template = d.matchTemplate(actualPath) ?: return seed
        val schema = d.requestSchema(template, method) ?: return seed // 无 body schema → seed 兜底
        val props = schema.optJSONObject("properties") ?: return seed
        val out = JSONObject()
        val keys = props.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (seed.has(k)) out.put(k, seed.get(k))
        }
        schema.optJSONArray("required")?.let { req ->
            for (i in 0 until req.length()) {
                val k = req.optString(i)
                if (!out.has(k)) out.put(k, defaultFor(props.optJSONObject(k)))
            }
        }
        return out
    }

    private fun defaultFor(s: JSONObject?): Any = when (s?.optString("type")) {
        "number", "integer" -> 0
        "boolean" -> false
        "array" -> JSONArray()
        "object" -> JSONObject()
        else -> ""
    }
}

// ─────────────────────────── SSE 订阅配额(P1 §3.4: 同一时刻 ≤2 订阅) ───────────────────────────

/** 终端桥 + 原生 UI 共享的全局名额; /event 为全局广播、无互斥, 仅限并发连接数 */
object SseQuota {
    const val MAX_SUBSCRIBERS = 2 // P1 §3.4 定稿: ≤2 订阅
    private val used = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val u = used.get()
            if (u >= MAX_SUBSCRIBERS) return false
            if (used.compareAndSet(u, u + 1)) return true
        }
    }

    // 下界 0 防重复释放变负; 上界 MAX 防「重复释放」把名额加到上限之上而使 ≤2 约束失效。
    // 幂等的唯一保证在 SseSubscription 的 CAS 标志位, 这里是纵深防御。
    fun release() = used.updateAndGet { (it - 1).coerceIn(0, MAX_SUBSCRIBERS) }

    /** 当前已用名额(诊断用: 排查 QUOTA_DENIED 时观察是否泄漏) */
    fun inUse(): Int = used.get()
}

/**
 * 订阅句柄: cancel() 停止重连循环。**配额归还不在此处理** ——
 * 名额由 [SseConnection.run] 的 finally 单点释放(P0-2 修复)。
 *
 * 之所以不让 cancel() 释放: 协程可能尚未真正结束(run() 未跑到 finally),
 * cancel() 释放 + run() 释放 = 双释放, 会破坏「同一时刻 ≤2 订阅」契约(P2-2)。
 * cancel() 本身以 CAS 保证幂等。
 */
class SseSubscription internal constructor(private val job: Job?, private val teardown: () -> Unit) {
    private val cancelClosed = AtomicBoolean(false)

    /** 是否已 cancel(诊断用) */
    fun isCancelled(): Boolean = cancelClosed.get()

    fun cancel() {
        if (!cancelClosed.compareAndSet(false, true)) return // 幂等
        job?.cancel()
        teardown()
    }
}

// ─────────────────────────── EngineClient 主体 ───────────────────────────

/**
 * 引擎客户端。每个实例对应一份凭据(baseUrl + Basic Auth):
 *   - Ubuntu 模式: 每次 EngineHandle 变化(引擎重启 password 轮换)由上层重建实例 —— 勿跨引擎
 *     重启缓存旧 password(P3 §7.1)。
 *   - None 模式: 用户配置变更时重建。
 */
class EngineClient(
    val baseUrl: String,
    val username: String,
    val password: String, // P1 §3.4 安全红线: 仅内存; 不落盘、不进日志(toString 已脱敏)
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    override fun toString(): String = "EngineClient(baseUrl=$baseUrl, user=$username, password=***)"

    var doc: ApiDoc? = null; private set
    var endpoints: Endpoints = Endpoints(null); private set
    var degraded: Boolean = true; private set // /doc 不可用 → true(UI 顶部告警条)

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Basic Auth 拦截器(P1 §3.4): 用户名固定 opencode(Ubuntu)/用户配置(None, 缺省 opencode) */
    private val auth = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", Credentials.basic(username, password))
                .build()
        )
    }

    private val http = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sseHttp = OkHttpClient.Builder()
        .addInterceptor(auth)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接: 不设读超时
        .retryOnConnectionFailure(false)  // 重连策略由 SseConnection 退避循环接管(P1 §3.4)
        .build()

    /** 拉取 /doc 并重建端点注册表(P1 §3.4); 返回 false → degraded 模式 */
    suspend fun refreshDoc(): Boolean = withContext(Dispatchers.IO) {
        val fetched = runCatching {
            http.newCall(Request.Builder().url("$baseUrl/doc").build()).execute().use { r ->
                check(r.isSuccessful) { "/doc HTTP ${r.code}" }
                ApiDoc(JSONObject(r.body?.string().orEmpty()))
            }
        }.getOrElse {
            Log.w(TAG, "/doc 拉取失败: ${it.message}") // 日志不含凭据(P1 §3.4)
            null
        }
        if (fetched != null) {
            doc = fetched; endpoints = Endpoints(fetched); degraded = false; true
        } else {
            doc = null; endpoints = Endpoints(null); degraded = true; false
        }
    }

    // ── REST 基础 ──

    private suspend fun call(method: String, path: String, body: JSONObject?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(baseUrl + path)
            when (method) {
                "GET" -> rb.get()
                "DELETE" -> rb.method("DELETE", null)
                else -> rb.method(method, (body?.toString() ?: "{}").toRequestBody(jsonType))
            }
            http.newCall(rb.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
        }

    private suspend fun callJson(method: String, path: String, seed: JSONObject?): ApiResult<JSONObject> =
        try {
            // body 按 /doc schema 动态组装(P1 §3.4 禁止硬编码); seed 为空则发 "{}"
            val payload = seed?.let { DynamicBody.build(doc, path, method, it) } ?: JSONObject()
            val (code, text) = call(method, path, payload)
            if (code in 200..299) ApiResult.Ok(JSONObject(text.ifBlank { "{}" }), code)
            else ApiResult.Err(code, text.ifBlank { "HTTP $code" }) // code==401 → 凭据过期(P3 §7.1)
        } catch (e: IOException) {
            ApiResult.Network(e.message ?: "网络错误")
        } catch (e: Exception) {
            ApiResult.Err(-1, e.message ?: "解析错误")
        }

    private suspend fun callArray(method: String, path: String): ApiResult<JSONArray> =
        try {
            val (code, text) = call(method, path, null)
            if (code in 200..299) ApiResult.Ok(JSONArray(text), code)
            else ApiResult.Err(code, text.ifBlank { "HTTP $code" })
        } catch (e: IOException) {
            ApiResult.Network(e.message ?: "网络错误")
        } catch (e: Exception) {
            ApiResult.Err(-1, e.message ?: "解析错误")
        }

    // ── REST 端点(路径 = P1 §3.4 冻结清单) ──

    /** /global/health: 状态卡自检 + None 模式连通测试(P1 §3.4 / M2-3) */
    suspend fun health(): ApiResult<JSONObject> = callJson("GET", "/global/health", null)

    suspend fun listSessions(): ApiResult<JSONArray> = callArray("GET", "/session")

    /** POST /session 创建会话; title 为候选键, 由 /doc schema 裁决 */
    suspend fun createSession(title: String): ApiResult<JSONObject> =
        callJson("POST", "/session", JSONObject().put("title", title))

    /** PATCH /session/:id 更名 */
    suspend fun renameSession(id: String, title: String): ApiResult<JSONObject> =
        callJson("PATCH", "/session/$id", JSONObject().put("title", title))

    suspend fun deleteSession(id: String): ApiResult<JSONObject> = callJson("DELETE", "/session/$id", null)

    /** GET /session/:id/message 消息历史(渲染起点; 流式增量经 SSE) */
    suspend fun messages(id: String): ApiResult<JSONArray> = callArray("GET", "/session/$id/message")

    /**
     * POST /session/:id/prompt_async(P1 §3.4 推荐路径, UI 不阻塞), 结果经 SSE 回流。
     * seed 备 parts(opencode 当前格式)与 text 两个候选键, 以 /doc schema 实际属性为准。
     */
    suspend fun promptAsync(id: String, text: String): ApiResult<JSONObject> {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        val seed = JSONObject().put("parts", parts).put("text", text)
        return callJson("POST", "/session/$id/prompt_async", seed)
    }

    /** POST /session/:id/abort 中断当前 agent 运行 */
    suspend fun abort(id: String): ApiResult<JSONObject> =
        callJson("POST", "/session/$id/abort", JSONObject().put("reason", ""))

    /** POST /session/:id/permissions/:permissionID(once/always/reject, P1 §3.4 / P3 §7.1) */
    suspend fun respondPermission(sessionId: String, permissionId: String, response: String): ApiResult<JSONObject> =
        callJson(
            "POST", "/session/$sessionId/permissions/$permissionId",
            JSONObject().put("response", response),
        )

    /**
     * 权限审批回复值: P1 §3.4「once / always / reject, 语义以 /doc 为准」——
     * /doc 提供 enum 则原样返回(逐字为准); 否则返回兜底三值(调用方应提示未经校验)。
     */
    fun permissionResponseChoices(): List<String> =
        doc?.requestEnum("/session/{id}/permissions/{permissionID}", "POST", "response")
            ?: listOf("once", "always", "reject")

    // ── SSE /event(P1 §3.4) ──

    /**
     * 订阅全局事件流。占用 1 个全局配额(≤2, P1 §3.4); 名额满 → onState(QUOTA_DENIED)。
     * @param onEvent   已分类事件(Unknown 已在连接层过滤, 不回调)
     * @param onState   连接状态变化(供 UI 状态条; RETRYING 期间 logcat 可观测退避, M2-4)
     * @param onResync  重连成功后重建会话状态(P1 §3.4); 首次连接也会调用一次(全量加载, 幂等)
     */
    fun subscribeEvents(
        onEvent: (EngineEvent) -> Unit,
        onState: (SseState) -> Unit = {},
        onResync: suspend () -> Unit,
    ): SseSubscription {
        if (!SseQuota.tryAcquire()) {
            Log.w(TAG, "SSE 订阅名额已满(≤${SseQuota.MAX_SUBSCRIBERS}, P1 §3.4)")
            onState(SseState.QUOTA_DENIED)
            return SseSubscription(null) {}
        }
        val conn = SseConnection(sseHttp, baseUrl, onEvent, onState, onResync)
        val job = scope.launch(Dispatchers.IO) { conn.run() }
        // 配额归还由 SseConnection.run() 的 finally 单点负责(P0-2); 此处只置 closed 让循环退出
        return SseSubscription(job) { conn.closed = true }
    }

    companion object {
        const val TAG = "OpenCodeUI"
    }
}

/**
 * SSE 连接循环: GET /event → 逐行解析 event:/data: → 空行 dispatch。
 * 断线(读失败/服务端关流/HTTP 非 200)按指数退避重连: 1s 起、×2、30s 封顶, 连接成功即复位
 * (P1 §3.4 冻结参数); 退避过程打 INFO 日志(M2-4 要求可从日志观测)。
 */
internal class SseConnection(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val onEvent: (EngineEvent) -> Unit,
    private val onState: (SseState) -> Unit,
    private val onResync: suspend () -> Unit,
) {
    @Volatile var closed = false

    /** run() 已退出。配额释放以 finally 为单点(见 run), 此标志仅供诊断 */
    @Volatile var finished = false
        private set

    private var backoffMs = BACKOFF_MIN_MS

    suspend fun run() {
        try {
            onState(SseState.CONNECTING)
            while (currentCoroutineContext().isActive && !closed) {
                try {
                    connectAndPump() // 正常返回 = 服务端关流, 同样按断线处理
                } catch (e: Exception) {
                    if (closed) break
                    Log.i(TAG, "SSE 连接异常: ${e.message}") // 仅状态与 baseUrl, 无凭据(P1 §3.4)
                }
                if (closed) break
                onState(SseState.RETRYING)
                Log.i(TAG, "SSE 断线, ${backoffMs}ms 后重连(指数退避 1s→30s, P1 §3.4)") // M2-4 观测点
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
            }
            onState(SseState.CLOSED)
        } finally {
            // [P0-2 修复核心] 配额归还单点。必须放 finally —— 实测协程在 delay() 处被
            // cancel 时, finally 之后的普通语句不会执行, 只有 finally 路径必经。
            // 此前无任何释放路径, 名额只增不减 → 数次引擎重启后 SSE 永久 QUOTA_DENIED。
            finished = true
            SseQuota.release()
        }
    }

    private suspend fun connectAndPump() {
        http.newCall(
            Request.Builder().url("$baseUrl/event").header("Accept", "text/event-stream").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SSE HTTP ${resp.code}")
            onState(SseState.CONNECTED)
            backoffMs = BACKOFF_MIN_MS // 连接成功 → 退避复位
            onResync()                 // P1 §3.4: 重连成功后重建会话状态(首连也全量加载, 幂等)
            val reader = resp.body?.charStream()?.buffered() ?: throw IOException("SSE 空响应体")
            var eventName = ""
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> { dispatch(eventName, data.toString()); eventName = ""; data.setLength(0) }
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                    line.startsWith(":") -> Unit // SSE 注释/心跳行
                }
            }
            throw IOException("服务端关闭事件流")
        }
    }

    private fun dispatch(eventName: String, data: String) {
        if (data.isBlank()) return
        val json = runCatching { JSONObject(data) }.getOrElse { return } // 非 JSON 帧 → 忽略(P1 §3.4)
        when (val ev = parseEvent(eventName, json)) {
            is EngineEvent.Unknown ->
                Log.d(TAG, "忽略未知 SSE 事件 type=${ev.type}(P1 §3.4: 忽略不崩溃)") // M2-6
            else -> onEvent(ev)
        }
    }

    companion object {
        const val BACKOFF_MIN_MS = 1_000L  // P1 §3.4: 1s 起
        const val BACKOFF_MAX_MS = 30_000L // P1 §3.4: 30s 封顶
    }
}
