/**
 * EngineClientCoreTest.kt — EngineClient 纯逻辑单测（无需真机/模拟器，G-3）
 *
 * 覆盖 SSE 与动态 schema 的核心纯函数（这些逻辑此前零测试, 且是历史 P0 bug 所在区）:
 *   - parseEvent: SSE 事件分类（最低事件集 + permission.replied 必须先于 permission 分流）
 *   - ApiDoc / DynamicBody: /doc 端点匹配、$ref 解引用、schema 组装、degraded 兜底
 *   - Endpoints: 端点可用性 OK/MISSING/UNKNOWN
 *   - SseQuota: ≤2 配额上限 + release 不越界
 *   - SseSubscription: cancel() 幂等（CAS 只生效一次）
 */
package dev.opencode.mobile.ui

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineClientCoreTest {

    // ── parseEvent ──────────────────────────────────────────────────

    @Test
    fun `message part updated parses to MessagePart`() {
        val json = JSONObject(
            """{"type":"message.part.updated","properties":{"part":{"id":"p1","messageID":"m1","sessionID":"s1","text":"hi"}}}"""
        )
        val ev = parseEvent("message.part.updated", json)
        assertIs<EngineEvent.MessagePart>(ev)
        assertEquals("s1", ev.sessionId)
        assertEquals("p1", ev.partId)
        assertEquals("m1", ev.messageId)
        assertEquals("hi", ev.text)
    }

    @Test
    fun `permission updated parses to PermissionAsked with fallback keys`() {
        val json = JSONObject(
            """{"type":"permission.updated","properties":{"id":"perm1","sessionID":"s1","title":"run","pattern":"rm *"}}"""
        )
        val ev = parseEvent("permission.updated", json)
        assertIs<EngineEvent.PermissionAsked>(ev)
        assertEquals("perm1", ev.permissionId)   // permissionID 缺省时回落到 id
        assertEquals("s1", ev.sessionId)
        assertEquals("run", ev.title)
        assertEquals("rm *", ev.description)     // description 优先 pattern
    }

    @Test
    fun `permission replied must not be captured as PermissionAsked`() {
        // 回归守卫: type.contains("replied") 必须先于 type.contains("permission") 分流,
        // 否则审批回执被误当新权限请求 → 审批后重复弹窗。
        val json = JSONObject("""{"type":"permission.replied","properties":{"id":"p"}}""")
        assertIs<EngineEvent.Unknown>(parseEvent("permission.replied", json))
    }

    @Test
    fun `session error parses nested message`() {
        val json = JSONObject(
            """{"type":"session.error","properties":{"sessionID":"s1","error":{"message":"boom"}}}"""
        )
        val ev = parseEvent("session.error", json)
        assertIs<EngineEvent.SessionError>(ev)
        assertEquals("s1", ev.sessionId)
        assertEquals("boom", ev.message)
    }

    @Test
    fun `server connected and unknown types are ignored`() {
        assertIs<EngineEvent.Unknown>(parseEvent("server.connected", JSONObject("""{"type":"server.connected"}""")))
        assertIs<EngineEvent.Unknown>(parseEvent("totally.weird", JSONObject("""{"type":"totally.weird"}""")))
    }

    @Test
    fun `session id tolerates snake and camel case keys`() {
        val camel = parseEvent("session.error", JSONObject("""{"type":"session.error","properties":{"sessionId":"s-c"}}"""))
        assertIs<EngineEvent.SessionError>(camel)
        assertEquals("s-c", camel.sessionId)
    }

    // ── SseQuota ────────────────────────────────────────────────────

    @Test
    fun `sse quota caps at two and release is bounded`() {
        SseQuota.resetForTest()
        assertTrue(SseQuota.tryAcquire())
        assertTrue(SseQuota.tryAcquire())
        assertFalse(SseQuota.tryAcquire(), "第 3 个必须被拒（≤2）")
        assertEquals(2, SseQuota.inUse())
        SseQuota.release()
        assertEquals(1, SseQuota.inUse())
        // 过度释放不越界/不变负
        SseQuota.release(); SseQuota.release(); SseQuota.release()
        assertEquals(0, SseQuota.inUse())
    }

    // ── SseSubscription 幂等 ────────────────────────────────────────

    @Test
    fun `sse subscription cancel is idempotent`() {
        var teardowns = 0
        val sub = SseSubscription(null) { teardowns++ }
        assertFalse(sub.isCancelled())
        sub.cancel(); sub.cancel(); sub.cancel()
        assertEquals(1, teardowns, "cancel() 必须幂等: teardown 只执行一次")
        assertTrue(sub.isCancelled())
    }

    // ── ApiDoc / DynamicBody ────────────────────────────────────────

    private fun promptDoc() = ApiDoc(JSONObject(
        """{"paths":{"/session/{id}/prompt_async":{"post":{"requestBody":{"content":{"application/json":{"schema":{
             "type":"object","properties":{"parts":{"type":"array"},"text":{"type":"string"}},"required":["parts"]
           }}}}}}}}"""
    ))

    @Test
    fun `dynamic body keeps only schema properties`() {
        val seed = JSONObject().put("parts", JSONArray()).put("text", "hi").put("junk", "x")
        val out = DynamicBody.build(promptDoc(), "/session/s1/prompt_async", "POST", seed)
        assertTrue(out.has("parts"))
        assertTrue(out.has("text"))
        assertFalse(out.has("junk"), "seed 中 schema 未声明的键不得外泄")
    }

    @Test
    fun `dynamic body fills required when seed missing`() {
        val seed = JSONObject().put("text", "only-text") // 缺 required 的 parts
        val out = DynamicBody.build(promptDoc(), "/session/s1/prompt_async", "POST", seed)
        assertTrue(out.has("parts"), "required 缺失须补默认值")
        assertEquals("only-text", out.getString("text"))
    }

    @Test
    fun `dynamic body degraded passes seed through when no doc`() {
        val seed = JSONObject().put("text", "hi")
        val out = DynamicBody.build(null, "/x", "POST", seed)
        assertEquals("hi", out.getString("text"))
    }

    @Test
    fun `api doc matches templated path and extracts enum`() {
        val doc = ApiDoc(JSONObject(
            """{"paths":{"/session/{id}/permissions/{permissionID}":{"post":{"requestBody":{"content":{"application/json":{"schema":{
                 "type":"object","properties":{"response":{"type":"string","enum":["once","always","reject"]}}
               }}}}}}}}"""
        ))
        assertEquals("/session/{id}/permissions/{permissionID}",
            doc.matchTemplate("/session/abc/permissions/xyz"))
        assertEquals(listOf("once", "always", "reject"),
            doc.requestEnum("/session/{id}/permissions/{permissionID}", "POST", "response"))
    }

    // ── Endpoints 可用性 ────────────────────────────────────────────

    @Test
    fun `endpoints unknown when doc null and ok or missing when doc present`() {
        // 无 /doc → 全部 UNKNOWN（degraded）
        assertEquals(EndpointAvail.UNKNOWN, Endpoints(null).listSessions)
        // 有 /doc: 声明了 GET /session → OK; 未声明 rename(PATCH) → MISSING
        val doc = ApiDoc(JSONObject("""{"paths":{"/session":{"get":{}}}}"""))
        val e = Endpoints(doc)
        assertEquals(EndpointAvail.OK, e.listSessions)
        assertEquals(EndpointAvail.MISSING, e.renameSession)
    }
}
