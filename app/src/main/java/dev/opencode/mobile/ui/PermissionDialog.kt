/**
 * PermissionDialog.kt — 权限审批原生弹窗(once / always / reject)
 *
 * 触发链(P1 §3.4 最低事件集 2 / P3 §7.1):
 *   SSE /event 权限请求事件(PermissionAsked) → 本弹窗 → 用户选择 →
 *   POST /session/:id/permissions/:permissionID(ChatController.respondPermission)。
 *
 * 契约要点:
 *   - 回复值语义以 /doc 为准(P1 §3.4): choices 由 EngineClient.permissionResponseChoices()
 *     从 /doc requestBody enum 提取, 逐字渲染; /doc 不可用时退回兜底三值并显示降级提示。
 *   - 「稍后」仅关闭弹窗不回复(不占权限决策; 事件会留在引擎侧, 重新问询由服务端语义决定)。
 */
package dev.opencode.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 回复值 → 中文标签(未知枚举值原样展示, 以 /doc 语义为准, P1 §3.4) */
private fun labelFor(choice: String): String = when (choice.lowercase()) {
    "once" -> "仅本次允许"
    "always" -> "始终允许"
    "reject", "deny" -> "拒绝"
    else -> choice
}

@Composable
fun PermissionDialog(
    req: PermissionRequest,
    doc: ApiDoc?,
    choices: List<String>,
    onRespond: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限请求") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(req.title ?: "Agent 请求执行需要授权的操作", style = MaterialTheme.typography.titleSmall)
                req.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                req.permissionId?.let {
                    Text("permissionID: $it", style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)
                }
                req.sessionId?.let {
                    Text("session: ${it.take(12)}…", style = MaterialTheme.typography.labelSmall)
                }
                if (doc == null) {
                    // /doc 不可用 → 回复值未经校验(降级提示, P1 §3.4 语义以 /doc 为准)
                    Text(DynamicBody.DEGRADED_NOTE, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        // 回复按钮: choices 来自 /doc enum(once/always/reject 逐字), 三种回复 → POST 回执(P1 §3.4)
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                choices.forEach { choice ->
                    TextButton(onClick = { onRespond(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Text(labelFor(choice))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}
