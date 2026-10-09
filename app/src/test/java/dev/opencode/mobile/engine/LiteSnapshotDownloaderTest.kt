/**
 * LiteSnapshotDownloaderTest.kt — lite 变体下载链路的纯 JVM 单测(P0-3)
 *
 * 用 JDK 内置 com.sun.net.httpserver 起本地 HTTP 服务, 验证:
 *   1. 正常下载 + SHA-256 校验通过
 *   2. SHA-256 不匹配 → 抛异常且清理 .part
 *   3. 断点续传(Range/206): 已有 .part 时续写拼接
 *   4. HTTP 非 2xx → IOException
 */
package dev.opencode.mobile.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteSnapshotDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var dest: File
    private val payload = ByteArray(200_000) { (it % 251).toByte() } // 非平凡内容

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }

    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        dest = Files.createTempDirectory("litedl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        dest.deleteRecursively()
    }

    private fun serve(path: String, body: ByteArray, supportRange: Boolean = true) {
        server.createContext(path) { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (supportRange && range != null && range.startsWith("bytes=")) {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                val slice = body.copyOfRange(from, body.size)
                ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
    }

    private fun dl() = LiteSnapshotDownloader(
        OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
    )

    private fun manifest(sha256: String) = SnapshotManifest(
        snapshotVersion = "20261009-oc1.18.34", ocVersion = "1.18.34",
        sha256 = sha256, size = payload.size.toLong(), file = "snap.tar.gz",
    )

    @Test
    fun `download success verifies sha and finalizes`() = runTest {
        serve("/snap.tar.gz", payload)
        val f = dl().download("$baseUrl/snap.tar.gz", manifest(sha(payload)), dest)
        assertTrue(f.exists())
        assertEquals(payload.size.toLong(), f.length())
        assertTrue(f.readBytes().contentEquals(payload))
        assertFalse(File(dest, "snap.tar.gz.part").exists(), ".part 应已改名")
    }

    @Test
    fun `sha mismatch throws and cleans part`() = runTest {
        serve("/snap.tar.gz", payload)
        assertFailsWith<IllegalStateException> {
            dl().download("$baseUrl/snap.tar.gz", manifest("0".repeat(64)), dest)
        }
        assertFalse(File(dest, "snap.tar.gz.part").exists(), "校验失败必须清理 .part")
        assertFalse(File(dest, "snap.tar.gz").exists(), "校验失败不得产出最终文件")
    }

    @Test
    fun `resume from partial uses range`() = runTest {
        serve("/snap.tar.gz", payload)
        // 预置前 50_000 字节
        val part = File(dest, "snap.tar.gz.part")
        part.writeBytes(payload.copyOfRange(0, 50_000))

        val f = dl().download("$baseUrl/snap.tar.gz", manifest(sha(payload)), dest)
        assertTrue(f.exists())
        assertTrue(f.readBytes().contentEquals(payload), "续传拼接后内容必须完整")
        assertFalse(part.exists())
    }

    @Test
    fun `http error throws IOException`() = runTest {
        serve("/ok", payload)
        assertFailsWith<java.io.IOException> {
            dl().download("$baseUrl/missing", manifest(sha(payload)), dest)
        }
    }
}
