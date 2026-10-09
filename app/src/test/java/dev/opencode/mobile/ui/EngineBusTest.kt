/**
 * EngineBusTest.kt — engineStateText 状态文案单测（G-3，纯函数）
 *
 * 引擎五态的状态卡文案此前零测试。engineStateText 为纯函数, 不触碰 Android。
 */
package dev.opencode.mobile.ui

import dev.opencode.mobile.engine.EngineState
import dev.opencode.mobile.engine.ExecMode
import dev.opencode.mobile.engine.InstallProgress
import kotlin.test.Test
import kotlin.test.assertTrue

class EngineBusTest {

    @Test
    fun `engineStateText covers all five states`() {
        val stopped = engineStateText(EngineState.Stopped)
        assertTrue(stopped.contains("停止"), stopped)

        val installing = engineStateText(EngineState.Installing(InstallProgress.Done("v1")))
        assertTrue(installing.contains("安装"), installing)

        val starting = engineStateText(EngineState.Starting(ExecMode.DIRECT, 4096))
        assertTrue(starting.contains("启动") && starting.contains("4096"), starting)

        val ready = engineStateText(EngineState.Ready(4096, "1.18.34"))
        assertTrue(ready.contains("就绪") && ready.contains("1.18.34") && ready.contains("127.0.0.1"), ready)

        val failed = engineStateText(EngineState.Failed("链接失败", "diag"))
        assertTrue(failed.contains("失败") && failed.contains("链接失败"), failed)
    }
}
