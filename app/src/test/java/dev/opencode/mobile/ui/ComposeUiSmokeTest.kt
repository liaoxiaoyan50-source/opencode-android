/**
 * ComposeUiSmokeTest.kt — Compose 界面 Robolectric 冒烟（G-4 测试基建解锁）
 *
 * 在 JVM 上用 Robolectric 承载 Compose, 验证主题 + 内容可渲染与断言。
 * 这是 UI 层测试基建的起点; 后续可扩展到 ChatScreen/SessionsPane 等交互断言。
 */
package dev.opencode.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `theme renders content text`() {
        compose.setContent {
            OpenCodeTheme { Text("hello-opencode") }
        }
        compose.onNodeWithText("hello-opencode").assertIsDisplayed()
    }
}
