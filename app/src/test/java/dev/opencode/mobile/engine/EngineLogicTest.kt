/**
 * EngineLogicTest.kt — EngineService 抽出逻辑的纯 JVM 单测（G-3 测试护栏，无需真机）
 *
 * 覆盖 EngineLogic.kt：
 *   - compareSemver：快照 minAppVersion 版本比对
 *   - SelfHeal.match：L2 自愈日志特征串匹配（含负面样例必须排除、ptrace 共现规则）
 */
package dev.opencode.mobile.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineLogicTest {

    // ── compareSemver ───────────────────────────────────────────────

    @Test
    fun `compareSemver orders by numeric segments`() {
        assertEquals(-1, compareSemver("1.0.0", "1.0.1").coerceIn(-1, 1))
        assertEquals(0, compareSemver("1.2.3", "1.2.3"))
        assertEquals(1, compareSemver("1.3.0", "1.2.9").coerceIn(-1, 1))
        assertEquals(-1, compareSemver("1.9.0", "2.0.0").coerceIn(-1, 1))
    }

    @Test
    fun `compareSemver ignores pre-release suffix and pads missing segments`() {
        assertEquals(0, compareSemver("1.0.0-local", "1.0.0"))
        assertEquals(0, compareSemver("1.0", "1.0.0"))
        // 非数字段按 0
        assertEquals(0, compareSemver("1.x.0", "1.0.0"))
    }

    @Test
    fun `compareSemver drives minAppVersion gate`() {
        // 场景: 快照要求 App >= 2.0.0, 当前 1.9.9 → 应阻断(负)
        assertEquals(true, compareSemver("1.9.9", "2.0.0") < 0)
        // 当前 2.0.0, 要求 2.0.0 → 放行(0)
        assertEquals(0, compareSemver("2.0.0", "2.0.0"))
    }

    // ── SelfHeal.match ──────────────────────────────────────────────

    @Test
    fun `selfheal matches positive signatures case-insensitively`() {
        assertEquals("permission denied", SelfHeal.match("some log\nPermission Denied\nmore"))
        assertEquals("exec format error", SelfHeal.match("sh: ./oc: Exec format error"))
        assertEquals("error while loading shared libraries", SelfHeal.match("error while loading shared libraries: libc.so"))
    }

    @Test
    fun `selfheal excludes negative sample to avoid false L2 trigger`() {
        // P2b §6-8: proot 正常启动即输出该警告; 命中必须被剔除返回 null
        assertNull(SelfHeal.match("proot warning: can't sanitize binding .../proc"))
    }

    @Test
    fun `selfheal negative line wins even if it also contains positive text`() {
        // 同一行同时含负面串与正面串 → 整行剔除, 不得误判
        val line = "proot warning: can't sanitize binding (mmap failed?)"
        assertNull(SelfHeal.match(line))
    }

    @Test
    fun `selfheal detects ptrace co-occurrence rule`() {
        assertEquals("ptrace ... Operation not permitted",
            SelfHeal.match("ptrace(2) error: ptrace ... Operation not permitted"))
        // 仅含其一不触发
        assertNull(SelfHeal.match("ptrace attached to process"))
        assertNull(SelfHeal.match("operation not permitted"))
    }

    @Test
    fun `selfheal returns null on benign output`() {
        assertNull(SelfHeal.match("opencode serve listening on 127.0.0.1:4096\nhealthy"))
    }

    // ── bootSequence 决策函数 ───────────────────────────────────────

    @Test
    fun `needsSnapshotInstall logic`() {
        val m = SnapshotManifest("20261010-oc1.19.0", "1.19.0", "x", 1L)
        assertTrue(needsSnapshotInstall(null, m), "未安装 → 需安装")
        assertTrue(needsSnapshotInstall("20261009-oc1.18.34", m), "版本不同 → 需安装(P0-4)")
        assertFalse(needsSnapshotInstall("20261010-oc1.19.0", m), "同版本 → 不重装")
        assertFalse(needsSnapshotInstall("20261010-oc1.19.0", null), "已装且无 bundled → 不重装")
        assertTrue(needsSnapshotInstall(null, null), "未装无 bundled → true(后续 fail 无可用快照)")
    }

    @Test
    fun `isBlockedByMinAppVersion logic`() {
        val blocking = SnapshotManifest("v", "oc", "x", 1L, minAppVersion = "2.0.0")
        val nonBlocking = SnapshotManifest("v", "oc", "x", 1L, minAppVersion = "1.0.0")
        val empty = SnapshotManifest("v", "oc", "x", 1L, minAppVersion = "")
        assertTrue(isBlockedByMinAppVersion("1.9.9", blocking), "当前 < 要求 → 阻断")
        assertFalse(isBlockedByMinAppVersion("2.0.0", blocking), "等于要求 → 放行")
        assertFalse(isBlockedByMinAppVersion("1.5.0", nonBlocking), "已满足 → 放行")
        assertFalse(isBlockedByMinAppVersion("1.5.0", empty), "minAppVersion 空 → 不阻断")
        assertFalse(isBlockedByMinAppVersion("1.5.0", null), "无 manifest → 不阻断")
    }
}
