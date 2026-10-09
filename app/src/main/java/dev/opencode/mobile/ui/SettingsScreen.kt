/**
 * SettingsScreen.kt — 设置页 + KeyVault(Keystore 安全存储)
 *
 * 设计思路(契约出处见行内注释):
 *   1. 引擎模式切换: Ubuntu 本地 / None 远程(P1 §5.4; prefs ui_settings/engine_mode)。
 *   2. None 模式表单(P1 §3.4): 地址 http(s)://host[:port] + 可选用户名/密码 + 连通测试按钮
 *      (GET /global/health); 明文 http 显示警示; 密码仅 Keystore 密文持久化(M2-5)。
 *   3. Provider Key 管理(P1 §5.4/M2-5): KeyVault 用 AndroidKeyStore AES-256-GCM 加密,
 *      prefs 只存密文(iv|ciphertext base64); auth.json 文本经 Settings.authJsonProvider
 *      内存回调提供给引擎层, UI 层自身不持久化明文(P3 §7.1 注入点)。
 *   4. 代理设置与 WakeLock 时长: 写 SharedPreferences("engine_settings") 的 "proxy" /
 *      "wake_lock_hours" 键 —— 键名为 P3 引擎层契约(P3 §7.1: L203/L208)。
 *   5. ExecCompat「重新探测」: ExecCompat.reprobe 清缓存重跑 L1(P1 §3.2 / P3 §7.1)。
 *   6. 引擎启停: EngineService.start/stop(P3 §7.1 companion)。
 */
package dev.opencode.mobile.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.engine.EngineService
import dev.opencode.mobile.engine.EngineState
import dev.opencode.mobile.engine.ExecCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeyVault — AndroidKeyStore AES-256-GCM 封装存储(P1 §5.4 / M2-5 验收对象)。
 *
 * 方案:
 *   - 主密钥: 别名 opencode_master_aes, AES-256/GCM/NoPadding, AndroidKeyStore 内生成且
 *     不可导出(setUserAuthenticationRequired=false, 进程内免交互解密)。
 *   - 密文格式: "1|base64(iv 12B)|base64(ciphertext)" 存普通 prefs(keystore_sealed) ——
 *     文件系统/dumpsys 均无明文(M2-5); 版本前缀 "1" 为向后兼容密钥轮换预留。
 *   - 解密结果仅内存使用(拼 auth.json 文本/注入 None 密码), 不写任何文件、不进日志。
 */
object KeyVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val ALIAS = "opencode_master_aes" // Key 别名(见 P4 文档 §4)
    private const val PREFS = "keystore_sealed"
    const val REMOTE_ID = "__remote__"      // None 模式远程密码专用槽位

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)          // AES-GCM(P1 §3.4 安全红线落点)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * 加密: 每次随机 IV(GCM 语义要求), 输出版本前缀 + base64(iv|ct)。
     * [P3-1] 去掉未使用的 context 参数 —— 密钥来自 AndroidKeyStore(secretKey()),
     * 密文由调用方 put() 落 prefs, seal 本身不需要上下文。
     */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return "1|${b64.encodeToString(iv)}|${b64.encodeToString(ct)}"
    }

    /** 解密: 失败(密文损坏/密钥重置)返回 null, 调用方按未配置处理, 不抛明文相关异常。[P3-1] 同上去掉 context */
    fun unseal(sealed: String): String? = runCatching {
        val (v, ivB64, ctB64) = sealed.split('|')
        check(v == "1")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.getDecoder().decode(ivB64)))
        String(cipher.doFinal(Base64.getDecoder().decode(ctB64)), Charsets.UTF_8)
    }.getOrNull()

    fun put(context: Context, id: String, plain: String) {
        prefs(context).edit().putString(id, seal(plain)).apply()
    }

    fun get(context: Context, id: String): String? =
        prefs(context).getString(id, null)?.let { unseal(it) }

    fun remove(context: Context, id: String) = prefs(context).edit().remove(id).apply()

    /** 已存 Provider id 列表(不含远程密码槽; 仅 id 可展示, 密钥值永不回显) */
    fun providerIds(context: Context): List<String> =
        prefs(context).all.keys.filter { it != REMOTE_ID }

    /**
     * 组装 auth.json 文本(opencode 上游格式: {"<provider>":{"type":"api","key":"..."}})。
     * 经 Settings.authJsonProvider 内存回调交给引擎层写 oc-auth(P3 §7.1; UI 不持久化明文)。
     * 注: auth.json 内部 schema 未在 P1 冻结(P1 §3.5 仅冻结文件路径与权限 600), 按上游当前
     *     格式组装, 版本漂移对策见 P4 §8 请终裁项。
     */
    fun buildAuthJson(context: Context): String {
        val root = JSONObject()
        for (id in providerIds(context)) {
            val key = get(context, id) ?: continue
            root.put(id, JSONObject().put("type", "api").put("key", key))
        }
        return root.toString() // 全空 → "{}"(引擎层默认同值, P3 L205-206)
    }
}

// ─────────────────────────── 设置页 UI ───────────────────────────

@Composable
fun SettingsScreen(app: AppController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engineState by EngineBus.state.collectAsState()
    val mode by app.engineMode.collectAsState()

    // None 模式表单本地态(保存前不落任何存储)
    val savedUrl = context.getSharedPreferences(AppController.UI_PREFS, Context.MODE_PRIVATE)
        .getString(AppController.KEY_REMOTE_URL, "").orEmpty()
    val savedUser = context.getSharedPreferences(AppController.UI_PREFS, Context.MODE_PRIVATE)
        .getString(AppController.KEY_REMOTE_USER, "").orEmpty()
    var url by remember { mutableStateOf(savedUrl) }
    var user by remember { mutableStateOf(savedUser) }
    var pass by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    var proxy by remember { mutableStateOf(context.getSharedPreferences("engine_settings", Context.MODE_PRIVATE).getString("proxy", "").orEmpty()) }
    var wakeHours by remember { mutableStateOf(context.getSharedPreferences("engine_settings", Context.MODE_PRIVATE).getInt("wake_lock_hours", 6).toFloat()) }
    var probeResult by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SimpleTopBar(title = "设置", onBack = onBack)

        // ── 卡 1: 引擎模式(P1 §5.4) ──
        SectionCard("引擎模式") {
            ModeRow("Ubuntu 本地(proot + opencode serve)", mode == AppController.MODE_LOCAL) {
                app.setEngineMode(AppController.MODE_LOCAL)
            }
            ModeRow("None 远程(用户配置的 opencode 服务)", mode == AppController.MODE_NONE) {
                app.setEngineMode(AppController.MODE_NONE)
            }
            Text(engineStateText(engineState), style = MaterialTheme.typography.labelMedium)
            if (engineState is EngineState.Failed) {
                val d = (engineState as EngineState.Failed).diagnostics
                TextButton(onClick = { probeResult = d.take(600) }) { Text("查看诊断报告") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { EngineService.start(context) }) { Text("启动本地引擎") } // P3 §7.1
                TextButton(onClick = { EngineService.stop(context) }) { Text("停止引擎") }   // P3 §7.1
            }
        }

        // ── 卡 2: None 远程模式(P1 §3.4 / M2-3) ──
        SectionCard("None 远程模式") {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("地址 http(s)://host[:port]") }, singleLine = true)
            if (url.trim().startsWith("http://", ignoreCase = true)) {
                // P1 §3.4: 明文 http 地址需 UI 警示
                Text("⚠ 明文 http 将以未加密传输暴露密码与会话内容, 仅限可信内网, 建议 https",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("用户名(可选, 默认 opencode)") }, singleLine = true)
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(),
                label = { Text(if (KeyVault.get(context, KeyVault.REMOTE_ID) != null) "密码(已加密存储, 留空=保留)" else "密码") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Text("密码经 AndroidKeyStore AES-256-GCM 加密存储, 文件系统与 dumpsys 无明文(M2-5)",
                style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 连通测试: GET /global/health + Basic Auth(P1 §3.4; M2-3 验收点)
                Button(onClick = {
                    testing = true; testResult = null
                    scope.launch {
                        val u = url.trim()
                        if (u.isBlank()) testResult = "✗ 请先填地址"
                        else {
                            val tmp = EngineClient(u, user.ifBlank { "opencode" },
                                pass.ifBlank { KeyVault.get(context, KeyVault.REMOTE_ID) ?: "" }, scope)
                            testResult = when (val r = tmp.health()) {
                                is ApiResult.Ok -> {
                                    val v = r.value.optText("version")?.let { " · opencode $it" } ?: ""
                                    "✓ 连通成功$v"
                                }
                                is ApiResult.Err -> "✗ HTTP ${r.code} ${r.message.take(80)}"
                                is ApiResult.Network -> "✗ 无法连接: ${r.message.take(80)}"
                            }
                        }
                        testing = false
                    }
                }, enabled = !testing) { Text(if (testing) "测试中…" else "连通测试") }
                Button(onClick = { app.saveRemote(url, user, pass.takeIf { it.isNotBlank() }) }) { Text("保存") }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }

        // ── 卡 3: Provider Key 管理(P1 §5.4 / M2-5) ──
        SectionCard("Provider API Key(Keystore 加密)") {
            var providerId by remember { mutableStateOf("") }
            var apiKey by remember { mutableStateOf("") }
            OutlinedTextField(providerId, { providerId = it }, Modifier.fillMaxWidth(),
                label = { Text("Provider ID(如 anthropic / openai)") }, singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(),
                label = { Text("API Key(仅 Keystore 密文落盘)") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (providerId.isNotBlank() && apiKey.isNotBlank()) {
                        KeyVault.put(context, providerId.trim(), apiKey) // 加密落盘, 内存值立即清弃
                        apiKey = ""; providerId = ""
                    }
                }) { Text("保存 Key") }
                TextButton(onClick = {
                    // 自检: 预览注入引擎的 auth.json 结构(不含 key 值), 验证 authJsonProvider 链路
                    val ids = KeyVault.providerIds(context)
                    probeResult = "已存 Provider: ${if (ids.isEmpty()) "(无)" else ids.joinToString()}" +
                        "\n注入时 auth.json = {\"<provider>\":{\"type\":\"api\",\"key\":\"…\"}}"
                }) { Text("查看已存") }
            }
            Text("注入时序: 引擎 startEngine 前 Settings.authJsonProvider 被回调, 解密文本仅经内存写入 oc-auth/auth.json(600), UI 不持久化明文(P3 §7.1)",
                style = MaterialTheme.typography.labelSmall)
        }

        // ── 卡 4: 引擎运行参数(P3 §7.1 键名契约: proxy / wake_lock_hours) ──
        SectionCard("引擎运行参数") {
            OutlinedTextField(proxy, { proxy = it }, Modifier.fillMaxWidth(),
                label = { Text("HTTP(S) 代理(空=直连; 写入 engine_settings/proxy)") }, singleLine = true)
            Text("WakeLock 保活时长: ${wakeHours.toInt()} 小时(1..24, 写入 engine_settings/wake_lock_hours)",
                style = MaterialTheme.typography.labelSmall)
            Slider(value = wakeHours, onValueChange = { wakeHours = it }, valueRange = 1f..24f, steps = 22)
            Button(onClick = {
                // P3 §7.1: 引擎层从同名键读取(P3 L203 代理 / L208 WakeLock)
                context.getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
                    .edit().putString("proxy", proxy.trim()).putInt("wake_lock_hours", wakeHours.toInt()).apply()
                probeResult = "已保存(下次引擎启动生效: 代理注入 guest 环境变量, WakeLock 重挂)"
            }) { Text("保存参数") }
        }

        // ── 卡 5: ExecCompat 重新探测(P1 §3.2 / P3 §7.1) ──
        SectionCard("执行模式探测") {
            Text("L1 探测决定 DIRECT/QEMU; ROM 升级后可清缓存重探测(P1 §3.2)", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = {
                // P3 §7.1: ExecCompat.reprobe(P3 L105-108); 路径契约 P1 §3.5
                val m = ExecCompat.reprobe(
                    rootfs = File(context.filesDir, "rootfs"),
                    nativeDir = context.applicationInfo.nativeLibraryDir,
                    filesDir = context.filesDir,
                )
                probeResult = "重新探测结论: $m(已更新 .exec-mode 缓存)"
            }) { Text("重新探测") }
        }

        // ── 卡 6: 发布装配提示(引用 P2b §4) ──
        SectionCard("关于 / 排障") {
            Text("若引擎启动失败且诊断含 exec/加载类错误, 请核对 APK 已配置 useLegacyPackaging=true" +
                    "(=extractNativeLibs=true, 库解压进 nativeLibraryDir 才可被 ProcessBuilder 执行; P2b §4)",
                style = MaterialTheme.typography.labelSmall)
            Text("None 远程模式是本地引擎不可用时的完整替代路径(P1 §3.2 FAILED 引导文案同源)",
                style = MaterialTheme.typography.labelSmall)
        }

        if (probeResult != null) {
            AlertDialog(
                onDismissRequest = { probeResult = null },
                title = { Text("信息") },
                text = { Text(probeResult.orEmpty(), fontFamily = FontFamily.Monospace) },
                confirmButton = { TextButton(onClick = { probeResult = null }) { Text("关闭") } },
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
