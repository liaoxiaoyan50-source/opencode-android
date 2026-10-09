/**
 * LiteSnapshotDownloaderTest.kt — lite 变体下载链路的纯 JVM 单测(无需真机)
 *
 * 用 OkHttp MockWebServer 起本地服务(不能用 com.sun.net.httpserver —— Android
 * 单测编译类路径不含 JDK 的 com.sun.* 模块), 验证:
 *   1. 正常下载 + SHA-256 校验通过
 *   2. SHA-256 不匹配 → 抛异常且清理 .part
 *   3. 断点续传(206): 已有 .part 时续写拼接
 *   4. HTTP 非 2xx → IOException
 */
package dev.opencode.mobile.engine

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
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

    private lateinit var server: MockWebServer
    private lateinit var dest: File
    private val payload = ByteArray(200_000) { (it % 251).toByte() } // 非平凡内容

    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @BeforeTest
    fun setUp() {
        dest = Files.createTempDirectory("litedl").toFile()
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
        dest.deleteRecursively()
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
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val url = server.url("/snap.tar.gz").toString()
        val f = dl().download(url, manifest(sha(payload)), dest)
        assertTrue(f.exists())
        assertEquals(payload.size.toLong(), f.length())
        assertTrue(f.readBytes().contentEquals(payload))
        assertFalse(File(dest, "snap.tar.gz.part").exists(), ".part 应已改名")
    }

    @Test
    fun `sha mismatch throws and cleans part`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val url = server.url("/snap.tar.gz").toString()
        assertFailsWith<IllegalStateException> {
            dl().download(url, manifest("0".repeat(64)), dest)
        }
        assertFalse(File(dest, "snap.tar.gz.part").exists(), "校验失败必须清理 .part")
        assertFalse(File(dest, "snap.tar.gz").exists(), "校验失败不得产出最终文件")
    }

    @Test
    fun `resume from partial uses range`() = runTest {
        // 预置前 50_000 字节; 服务端返回 206 + 剩余内容
        File(dest, "snap.tar.gz.part").writeBytes(payload.copyOfRange(0, 50_000))
        val remaining = payload.copyOfRange(50_000, payload.size)
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 50000-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(remaining))
        )
        val url = server.url("/snap.tar.gz").toString()
        val f = dl().download(url, manifest(sha(payload)), dest)
        assertTrue(f.readBytes().contentEquals(payload), "续传拼接后内容必须完整")
        assertFalse(File(dest, "snap.tar.gz.part").exists())
    }

    @Test
    fun `http error throws IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val url = server.url("/missing").toString()
        assertFailsWith<java.io.IOException> {
            dl().download(url, manifest(sha(payload)), dest)
        }
    }
}
