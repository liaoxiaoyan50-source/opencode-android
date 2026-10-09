/**
 * SnapshotInstallerTest.kt — SnapshotInstaller 纯 JVM 单测（无需真机/模拟器/Android SDK）
 *
 * 覆盖（对齐 .opencode/KNOWN_BUGS.md）:
 *   1. 正常安装: 解压落位 + ready meta
 *   2. SHA-256 校验失败必须拒绝, 且绝不写 ready（防"假 ready"）
 *   3. tar 路径穿越（../../ 与绝对路径）必须 SecurityException
 *   4. 幂等: state != ready 的脏 meta 视为未安装 → 重装
 *   5. 升级: 版本比对触发重装, 且会话库(root/.local/share/opencode)保留（P0-4 / M3-2）
 *   6. P1-2 修复: 残留 rootfs.old 被清理, 不阻塞新安装; ready 只在 rootfs 真实存在后写入
 *   7. meta 解析: state 带空格/不带空格两种历史格式兼容
 *
 * 可测性前提: SnapshotInstaller(Dirs(...)) 主构造直接注入临时目录,
 * 不需要 mock android.content.Context（Dirs 是 PR7 引入的可测性重构）。
 */
package dev.opencode.mobile.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotInstallerTest {

    private lateinit var base: File
    private lateinit var installer: SnapshotInstaller

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("snapinst").toFile()
        installer = SnapshotInstaller(
            SnapshotInstaller.Dirs.fromFilesDir(base)
        )
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    // ── 工具 ──

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 内存构造最小 tar.gz; entryName→content, 目录条目以 / 结尾 */
    private fun makeTarGz(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bos).buffered()).use { tar ->
            for ((name, content) in entries) {
                val isDir = name.endsWith("/")
                val entry = TarArchiveEntry(name)
                if (!isDir) entry.size = content.toByteArray(Charsets.UTF_8).size.toLong()
                tar.putArchiveEntry(entry)
                if (!isDir) tar.write(content.toByteArray(Charsets.UTF_8))
                tar.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    private suspend fun install(openStream: () -> InputStream, manifest: SnapshotManifest?) =
        withContext(Dispatchers.IO) {
            installer.install(openStream = openStream, totalBytes = -1, manifest = manifest)
        }

    private fun manifestOf(bytes: ByteArray, version: String) = SnapshotManifest(
        snapshotVersion = version, ocVersion = "1.18.34",
        sha256 = sha256Of(bytes), size = bytes.size.toLong(),
    )

    // ── 1. 正常安装 ──

    @Test
    fun `fresh install extracts files and marks ready`() = runTest {
        val tar = makeTarGz(mapOf("usr/local/bin/opencode" to "OC-BIN", "root/.config/" to ""))
        install({ ByteArrayInputStream(tar) }, manifestOf(tar, "20261009-oc1.18.34"))

        assertEquals("20261009-oc1.18.34", installer.installedVersion())
        assertEquals("OC-BIN", File(installer.rootfsDir, "usr/local/bin/opencode").readText())
        assertTrue(installer.rootfsDir.isDirectory)
        assertTrue(!File(base, "rootfs.tmp").exists(), "解压暂存目录必须已消失")
        assertTrue(!File(base, "rootfs.old").exists(), "旧目录暂存必须已清理")
    }

    // ── 2. SHA-256 失败 → 拒绝且不写 ready ──

    @Test
    fun `sha mismatch rejects and never marks ready`() = runTest {
        val tar = makeTarGz(mapOf("f" to "data"))
        val bad = SnapshotManifest("v1", "1.18.34", "0".repeat(64), tar.size.toLong())
        var caught: IllegalStateException? = null
        try { install({ ByteArrayInputStream(tar) }, bad) }
        catch (e: IllegalStateException) { caught = e }
        assertTrue(caught != null, "SHA 不匹配必须抛 IllegalStateException")
        assertTrue(caught!!.message!!.contains("SHA-256"))
        assertNull(installer.installedVersion(), "失败后绝不能是 ready")
        assertTrue(!installer.dirs.metaFile.exists(), "meta 文件不能存在")
    }

    // ── 3. 路径穿越 ──

    @Test
    fun `dotdot traversal rejected`() = runTest {
        val tar = makeTarGz(mapOf("../../evil.txt" to "pwn"))
        var caught: SecurityException? = null
        try { install({ ByteArrayInputStream(tar) }, manifestOf(tar, "v-t")) }
        catch (e: SecurityException) { caught = e }
        assertTrue(caught != null, "路径穿越必须抛 SecurityException")
        assertTrue(caught!!.message!!.contains("非法路径"))
        assertNull(installer.installedVersion())
    }

    @Test
    fun `absolute path entry is normalized and contained`() = runTest {
        // commons-compress 会自动剥离前导 '/'(TarArchiveEntry("/etc/evil").name == "etc/evil");
        // 且 Java 的 File(parent, child) 对绝对 child 也按相对处理。故绝对路径条目会被
        // 安全地限制在 tmpDir 内, 不构成穿越 —— 这里断言"被容纳"而非"被拒绝"。
        // (真正的穿越威胁是 ../../, 已由上一个用例覆盖。)
        val tar = makeTarGz(mapOf("/etc/evil" to "pwn"))
        install({ ByteArrayInputStream(tar) }, manifestOf(tar, "v-abs"))
        assertEquals("v-abs", installer.installedVersion())
        // 落点必须在 rootfs 内(前导 / 被剥离), 绝不能逃到宿主 /etc
        assertTrue(File(installer.rootfsDir, "etc/evil").exists(), "绝对路径应被剥离并落在 rootfs 内")
    }

    // ── 4. 幂等: 脏 meta 触发重装 ──

    @Test
    fun `dirty meta means not installed and triggers reinstall`() = runTest {
        val tar1 = makeTarGz(mapOf("marker" to "v1"))
        install({ ByteArrayInputStream(tar1) }, manifestOf(tar1, "20261009-oc1.18.34"))

        // 模拟安装中断: state 停在非 ready
        installer.dirs.metaFile.writeText(
            """{"snapshotVersion":"20261009-oc1.18.34","state":"extracting"}""")
        assertNull(installer.installedVersion(), "state != ready 必须视为未安装")

        val tar2 = makeTarGz(mapOf("marker" to "v2"))
        install({ ByteArrayInputStream(tar2) }, manifestOf(tar2, "20261010-oc1.19.0"))
        assertEquals("20261010-oc1.19.0", installer.installedVersion())
        assertEquals("v2", File(installer.rootfsDir, "marker").readText())
    }

    // ── 5. 升级保留会话库（P0-4 配套 / M3-2 的 JVM 部分验收）──

    @Test
    fun `upgrade preserves opencode session db`() = runTest {
        val tar1 = makeTarGz(mapOf("bin/oc" to "old"))
        install({ ByteArrayInputStream(tar1) }, manifestOf(tar1, "20261009-oc1.18.34"))

        // 运行期数据落在旧 rootfs 内
        val db = File(installer.rootfsDir, "root/.local/share/opencode")
        db.mkdirs()
        File(db, "opencode.db").writeText("SESSION-DATA")

        val tar2 = makeTarGz(mapOf("bin/oc" to "new"))
        install({ ByteArrayInputStream(tar2) }, manifestOf(tar2, "20261010-oc1.19.0"))

        assertEquals("20261010-oc1.19.0", installer.installedVersion())
        assertEquals("new", File(installer.rootfsDir, "bin/oc").readText())
        assertEquals("SESSION-DATA",
            File(installer.rootfsDir, "root/.local/share/opencode/opencode.db").readText(),
            "升级后 opencode.db 必须原样保留(M3-2)")
    }

    // ── 6. 残留 rootfs.old 清理(P1-2 修复的新增逻辑) ──

    @Test
    fun `stale rootfs-old does not block install and gets cleaned`() = runTest {
        val tar = makeTarGz(mapOf("f" to "data"))
        File(base, "rootfs.old").mkdirs()
        File(base, "rootfs.old/stale").writeText("stale")

        install({ ByteArrayInputStream(tar) }, manifestOf(tar, "v-stale"))
        assertEquals("v-stale", installer.installedVersion())
        assertTrue(!File(base, "rootfs.old").exists(), "残留 rootfs.old 必须被清理")
    }

    // ── 7. meta state 解析兼容两种历史格式 ──

    @Test
    fun `installedVersion parses both state formats`() = runTest {
        val tar = makeTarGz(mapOf("f" to "data"))
        install({ ByteArrayInputStream(tar) }, manifestOf(tar, "v-parse"))

        // 带空格格式(历史兼容)
        installer.dirs.metaFile.writeText("""{"snapshotVersion":"v-space","state": "ready"}""")
        assertEquals("v-space", installer.installedVersion())

        // 非 ready 状态
        installer.dirs.metaFile.writeText("""{"snapshotVersion":"v-x","state":"failed"}""")
        assertNull(installer.installedVersion())
    }
}
