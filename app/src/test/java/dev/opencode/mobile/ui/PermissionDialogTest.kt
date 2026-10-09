/**
 * PermissionDialogTest.kt — 权限审批弹窗交互单测（G-4，Robolectric + Compose）
 *
 * 断言弹窗渲染 + 点击某个回复值 → onRespond 收到该值(once/always/reject 逐字传回)。
 */
package dev.opencode.mobile.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `clicking a choice invokes onRespond with that exact value`() {
        var responded: String? = null
        val req = PermissionRequest("s1", "p1", "执行 rm -rf", "pattern: rm *", JSONObject())
        compose.setContent {
            OpenCodeTheme {
                PermissionDialog(
                    req = req,
                    doc = null,
                    choices = listOf("once", "always", "reject"),
                    onRespond = { responded = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("仅本次允许").performClick()
        assertEquals("once", responded, "点击「仅本次允许」须回传逐字值 once")
    }
}
