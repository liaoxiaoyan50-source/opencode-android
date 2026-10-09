/**
 * SseConnectionTest.kt — SSE 连接/重连全链路单测（G-3 残，MockWebServer）
 *
 * 覆盖此前零测试的 SseConnection.run 全链路:
 *   - 连上后解析 SSE 帧 → onEvent 回调
 *   - 服务端关流 → 退避后自动重连（requestCount 增加）
 *   - onResync 在连接成功时回调（首次也回调）
 *   - 状态序列含 CONNECTED / RETRYING
 */
package dev.opencode.mobile.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SseConnectionTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        SseQuota.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun sseResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)
        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END) // 关流, 触发客户端重连

    @Test
    fun `reconnects after stream closes and parses events`() = runBlocking {
        // 第一帧: server.connected(被忽略); 关流后重连
        val sse1 = "event: server.connected\ndata: {\"type\":\"server.connected\"}\n\n"
        // 第二帧: 一条 message.part.updated
        val sse2 = "event: message.part.updated\n" +
            "data: {\"type\":\"message.part.updated\",\"properties\":{\"part\":{\"id\":\"p1\",\"sessionID\":\"s1\",\"text\":\"hi\"}}}\n\n"
        server.enqueue(sseResponse(sse1))
        server.enqueue(sseResponse(sse2))

        val events = mutableListOf<EngineEvent>()
        val states = mutableListOf<SseState>()
        var resyncs = 0
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val conn = SseConnection(
            http = http,
            baseUrl = "http://127.0.0.1:${server.port}",
            onEvent = { events.add(it) },
            onState = { states.add(it) },
            onResync = { resyncs++ },
        )
        val job: Job = launch(Dispatchers.IO) { conn.run() }

        // 首连 + 断线退避(1s) + 重连, 实测等 2s
        withContext(Dispatchers.IO) { delay(2000) }
        conn.closed = true
        job.cancelAndJoin()

        assertTrue(server.requestCount >= 2, "服务端关流后必须自动重连(实际请求数=${server.requestCount})")
        assertTrue(states.contains(SseState.CONNECTED), "状态序列应含 CONNECTED: $states")
        assertTrue(states.contains(SseState.RETRYING), "状态序列应含 RETRYING: $states")
        assertTrue(resyncs >= 1, "连接成功须回调 onResync(重建会话状态)")
        assertTrue(events.any { it is EngineEvent.MessagePart }, "应解析出 MessagePart 事件, 实际=$events")
    }

    @Test
    fun `closing stops reconnect loop`() = runBlocking {
        server.enqueue(sseResponse("event: x\ndata: {\"type\":\"x\"}\n\n"))
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val conn = SseConnection(http, "http://127.0.0.1:${server.port}", {}, {}, {})
        val job = launch(Dispatchers.IO) { conn.run() }
        withContext(Dispatchers.IO) { delay(300) }
        conn.closed = true
        job.cancelAndJoin()
        assertTrue(conn.finished, "run() 退出须置 finished(P0-2 配额释放单点)")
    }
}
