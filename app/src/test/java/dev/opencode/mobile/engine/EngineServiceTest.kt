/**
 * EngineServiceTest.kt — EngineService 的 Robolectric 单测（G-3，JVM 上驱动 Android 组件）
 *
 * 覆盖 proot 命令构造契约（P1 §3.6 C6）——这是引擎能跑起来的最核心字符串契约，
 * 此前零测试。用 Robolectric 实例化 Service（不触发 bootSequence，仅 create()）。
 */
package dev.opencode.mobile.engine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineServiceTest {

    private fun service(): EngineService =
        Robolectric.buildService(EngineService::class.java).create().get()

    @Test
    fun `direct mode command follows C6 contract`() {
        val cmd = service().buildProotCommand(ExecMode.DIRECT, 4096)
        // 宿主侧 proot 以 lib*.so 命名（targetSdk>=29 唯一可执行区）
        assertTrue(cmd.first().endsWith("libproot.so"), "首元素须为 libproot.so: ${cmd.first()}")
        // 根文件系统 -r 指向 rootfs
        assertTrue(cmd.contains("-r"), "缺 -r rootfs")
        // guest 尾部: env -i ... opencode serve --hostname 127.0.0.1 --port 4096
        assertTrue(cmd.contains("serve"), "缺 serve")
        assertTrue(cmd.contains("--hostname"), "缺 --hostname")
        assertTrue(cmd.contains("127.0.0.1"), "缺回环地址(安全红线)")
        assertTrue(cmd.contains("4096"), "缺端口")
        // 安全: 绝不出现 0.0.0.0
        assertTrue(cmd.none { it == "0.0.0.0" }, "不得绑 0.0.0.0")
    }

    @Test
    fun `qemu mode inserts -q right after -w per contract`() {
        val cmd = service().buildProotCommand(ExecMode.QEMU, 4100)
        val iw = cmd.indexOf("-w")
        val iq = cmd.indexOf("-q")
        assertTrue(iw >= 0 && iq >= 0, "QEMU 模式须含 -w 与 -q")
        assertTrue(iq > iw, "-q 必须插在 -w 之后(P1 §3.6)")
        assertTrue(cmd[iq + 1].endsWith("libqemu_aarch64.so"), "-q 后须为 libqemu 绝对路径")
    }
}
