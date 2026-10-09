/**
 * ChatPaneRenderTest.kt — 聊天业务屏渲染单测（G-4，Robolectric + Compose）
 *
 * 驱动真实 ChatController + ChatPane, 断言消息渲染出来。UI 层业务交互测试的开始。
 */
package dev.opencode.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPaneRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders messages provided by controller`() {
        val chat = ChatController(CoroutineScope(Dispatchers.Unconfined))
        chat.messages.value = listOf(
            ChatMessage("k1", "user", "你好世界", streaming = false),
            ChatMessage("k2", "assistant", "收到回复", streaming = false),
        )
        compose.setContent {
            OpenCodeTheme { ChatPane(chat, onBack = {}) }
        }
        compose.onNodeWithText("你好世界").assertIsDisplayed()
        compose.onNodeWithText("收到回复").assertIsDisplayed()
    }
}
