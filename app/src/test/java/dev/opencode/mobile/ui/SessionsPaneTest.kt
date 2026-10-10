/**
 * SessionsPaneTest.kt — 会话列表业务屏交互单测（G-4，Robolectric + Compose）
 *
 * 驱动真实 AppController + ChatController + SessionsPane, 断言会话列表渲染,
 * 点击某会话触发 onOpenSession(id)。
 */
package dev.opencode.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionsPaneTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders session list and opens on click`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val app = AppController(RuntimeEnvironment.getApplication(), scope)
        val chat = ChatController(scope)
        chat.sessions.value = listOf(
            SessionSummary("id-1", "会话甲"),
            SessionSummary("id-2", "会话乙"),
        )
        var opened: String? = null
        compose.setContent {
            OpenCodeTheme {
                SessionsPane(
                    chat = chat,
                    app = app,
                    onOpenSession = { opened = it },
                    onOpenSettings = {},
                    onOpenTerminal = {},
                )
            }
        }
        compose.onNodeWithText("会话(2)").assertIsDisplayed()
        compose.onNodeWithText("会话甲").assertIsDisplayed()
        compose.onNodeWithText("会话乙").performClick()
        assertEquals("id-2", opened, "点击会话行须回调 onOpenSession(id)")
    }
}
