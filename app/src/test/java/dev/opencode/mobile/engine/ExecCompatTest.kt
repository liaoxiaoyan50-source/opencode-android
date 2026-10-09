/**
 * ExecCompatTest.kt — ExecCompat 探测逻辑单测（G-3，纯 File 逻辑，无需 Robolectric）
 *
 * detect() 的「快照异常」分支(env 缺失 → QEMU)与缓存读写是纯文件逻辑,
 * 不触碰 Android API, 可在普通 JVM 单测下验证。
 */
package dev.opencode.mobile.engine

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecCompatTest {

    @Test
    fun `detect returns QEMU when rootfs env missing`() {
        val base = Files.createTempDirectory("execcompat").toFile()
        try {
            val mode = ExecCompat.detect(File(base, "no-such-rootfs"), "unused-native", base)
            assertEquals(ExecMode.QEMU, mode, "rootfs env 不存在时须回落 QEMU(快照异常兜底)")
            assertTrue(File(base, ".exec-mode").exists(), "探测结论须落盘缓存")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `detect uses cached mode when present`() {
        val base = Files.createTempDirectory("execcompat-cache").toFile()
        try {
            // 预写缓存 DIRECT; 即便 rootfs 不存在也应直接返回缓存值(零探测开销)
            File(base, ".exec-mode").writeText("DIRECT")
            val mode = ExecCompat.detect(File(base, "no-such-rootfs"), "unused", base)
            assertEquals(ExecMode.DIRECT, mode, "缓存命中须直接返回, 不重探测")
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `detect re-probes when cache content invalid`() {
        val base = Files.createTempDirectory("execcompat-badcache").toFile()
        try {
            File(base, ".exec-mode").writeText("NOT_A_MODE")   // 非法内容
            val mode = ExecCompat.detect(File(base, "no-such-rootfs"), "unused", base)
            assertEquals(ExecMode.QEMU, mode, "非法缓存须视为无缓存重探")
            assertEquals("QEMU", File(base, ".exec-mode").readText(), "重探结论须回写缓存")
        } finally {
            base.deleteRecursively()
        }
    }
}
