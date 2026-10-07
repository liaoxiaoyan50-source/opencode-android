/**
 * SnapshotInstaller.kt — 引擎快照的校验、解压与幂等落盘
 *
 * 设计思路:
 *   1. 快照来源二选一: full 变体从 assets 读; lite 变体从 cacheDir 的下载产物读。
 *      本类只接收一个 InputStream 工厂, 两种来源统一处理。
 *   2. 边流式读取边做两件事: SHA-256 校验 + tar.gz 解压到 .tmp 目录
 *      (GZIP + TarArchiveInputStream 纯 Java 零原生依赖; 后续优化可换 zstd-jni)。
 *   3. 幂等: {rootfs}/.snapshot-meta.json 记录 {snapshotVersion, state}。
 *      任何一步中断(进程被杀/断电)后, state 永远到不了 "ready",
 *      下次 install() 检测到即整目录删除重装 — 保证"装一半"永不残留。
 *   4. 原子性: 全部解压到 rootfs.tmp, 成功后 rename 为 rootfs (同分区 rename 原子)。
 *   5. 升级保留数据: workspace 与会话库(~/.local/share/opencode)不在快照包内
 *      (构建脚本已剔除), 且位于旧 rootfs 内 — 换代前先搬移到新目录。
 *
 * 依赖: org.apache.commons:commons-compress:1.26+
 * 变量说明见各字段注释。
 */
package dev.opencode.mobile.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** 快照元数据, 与 CI 产出的 manifest.json 字段一致 */
data class SnapshotManifest(
    val snapshotVersion: String,
    val ocVersion: String,
    val sha256: String,
    val size: Long,
)

/** 安装进度: phase 用于 UI 分段展示(校验/解压/收尾) */
sealed class InstallProgress {
    data class Verifying(val readBytes: Long, val totalBytes: Long) : InstallProgress()
    data class Extracting(val fileCount: Int) : InstallProgress()
    data object Finishing : InstallProgress()
    data class Done(val version: String) : InstallProgress()
}

class SnapshotInstaller(private val context: Context) {

    /** rootfs 最终落位目录: /data/data/<pkg>/files/rootfs */
    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val tmpDir: File get() = File(context.filesDir, "rootfs.tmp")
    private val metaFile: File get() = File(rootfsDir, ".snapshot-meta.json")

    /** 当前已装快照版本; 无快照或 state!=ready 返回 null(触发重装) */
    fun installedVersion(): String? {
        if (!metaFile.exists()) return null
        val meta = metaFile.readText()
        if (!meta.contains("\"state\":\"ready\"") && !meta.contains("\"state\": \"ready\"")) return null
        // 骨架编译错误修复: groupValues 为 List<String>, takeIf 后是 List<String>?,
        // 与返回类型 String? 不匹配; 取第 1 捕获组(snapshotVersion 值)即为作者本意
        return Regex("\"snapshotVersion\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.getOrNull(1)
    }

    /**
     * 安装/重装快照。
     * @param openStream      快照包输入流工厂(assets 或下载文件), 每次调用返回新流
     * @param totalBytes      包大小(UI 进度条分母), 未知传 -1
     * @param manifest        CI manifest, 校验 SHA-256 用; null 则跳过哈希校验(不推荐)
     * @param onProgress      进度回调(主线程外回调, UI 层自行切线程)
     */
    suspend fun install(
        openStream: () -> InputStream,
        totalBytes: Long,
        manifest: SnapshotManifest?,
        onProgress: (InstallProgress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        // [0] 清理上一次的半成品: state!=ready 视为脏数据
        if (rootfsDir.exists() && installedVersion() == null) rootfsDir.deleteRecursively()
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        // [1] 流式校验 + 解压(一遍 IO 同时完成, 不落第二份盘)
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0L
        openStream().use { raw ->
            val gz = GZIPInputStream(raw.buffered(1 shl 16))
            // GZIP 无长度信息, 哈希只能对解压流同步计算; 进度用条目计数近似
            TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val target = File(tmpDir, entry.name).canonicalFile
                    // 安全: 拒绝路径穿越(../../)与绝对路径条目
                    if (!target.path.startsWith(tmpDir.canonicalPath + File.separator) && target != tmpDir) {
                        throw SecurityException("快照包含非法路径: ${entry.name}")
                    }
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    // 逐块写入并同步喂哈希
                    java.io.FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                        val n = tar.read(buf); if (n < 0) break
                        // P1 §7 缺陷 1: 骨架笔误 md.update → digest.update(md 未定义, 编译错误)
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        }
                    }
                    // 恢复权限位: proot 不强制要求 x 权限(ptrace 模拟 execve),
                    // 但 qemu 降级模式和用户手动进入容器时需要, 全部按 tar 原样恢复
                    chmod(target, entry.mode)
                    if (++files % 512 == 0) onProgress(InstallProgress.Extracting(files.toInt()))
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (manifest != null && !actual.equals(manifest.sha256, ignoreCase = true)) {
                throw IllegalStateException("SHA-256 不匹配: 期望 ${manifest.sha256}, 实际 $actual")
            }
        }
        onProgress(InstallProgress.Finishing)

        // [2] 升级场景: 把旧 rootfs 里的运行期数据搬进新 rootfs(会话库/用户工作区)
        if (rootfsDir.exists()) migrateUserDirs(rootfsDir, tmpDir)

        // [3] 原子替换: 同分区 rename
        if (rootfsDir.exists()) rootfsDir.renameTo(File(context.filesDir, "rootfs.old"))
        tmpDir.renameTo(rootfsDir)
        File(context.filesDir, "rootfs.old")?.deleteRecursively()

        // [4] 幂等标记: state=ready 是"安装完成"的唯一事实来源
        metaFile.writeText(
            """{"snapshotVersion":"${manifest?.snapshotVersion ?: "unknown"}",""" +
            """"ocVersion":"${manifest?.ocVersion ?: "unknown"}","state":"ready"}"""
        )
        onProgress(InstallProgress.Done(manifest?.snapshotVersion ?: "unknown"))
    }

    /** 运行期数据搬移: 会话库(SQLite)与配置不在快照包内, 升级时从旧 rootfs 平移 */
    private fun migrateUserDirs(oldRootfs: File, newRootfs: File) {
        val keep = listOf(
            "root/.local/share/opencode",   // opencode.db 会话库, 重启/升级不丢会话
            "root/.config/opencode",        // auth.json(API Key 注入点) — 每次由 App 重写, 保留亦可
        )
        keep.forEach { rel ->
            val src = File(oldRootfs, rel)
            val dst = File(newRootfs, rel)
            if (src.exists()) { dst.parentFile?.mkdirs(); src.copyRecursively(dst, overwrite = true) }
        }
    }

    /** 纯 Java chmod: targetSdk>=29 下不可 exec /system/bin/chmod 时同样可用 */
    private fun chmod(file: File, mode: Int) {
        if (file.isDirectory) return
        // 骨架编译错误修复: 原稿 "val p = java.nio.file.attribute.PosixFilePermissions" 把类当表达式(非法),
        // 且 OwnerRead 等 9 个常量属于 PosixFilePermission 枚举(= 底部 typealias pn), 并非 PosixFilePermissions 成员
        val perms = mutableSetOf<pn>()
        if (mode and 0b100_000_000 != 0) perms.add(pn.OwnerRead)
        if (mode and 0b010_000_000 != 0) perms.add(pn.OwnerWrite)
        if (mode and 0b001_000_000 != 0) perms.add(pn.OwnerExecute)
        if (mode and 0b000_100_000 != 0) perms.add(pn.GroupRead)
        if (mode and 0b000_010_000 != 0) perms.add(pn.GroupWrite)
        if (mode and 0b000_001_000 != 0) perms.add(pn.GroupExecute)
        if (mode and 0b000_000_100 != 0) perms.add(pn.OthersRead)
        if (mode and 0b000_000_010 != 0) perms.add(pn.OthersWrite)
        if (mode and 0b000_000_001 != 0) perms.add(pn.OthersExecute)
        try {
            java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: Exception) { /* 某些 FS 不支持权限位, proot 场景可容忍 */ }
    }

    companion object {
        /** full 变体: 从 assets 打开快照流 */
        fun fromAssets(context: Context, assetPath: String): () -> InputStream =
            { context.assets.open(assetPath) }

        /** lite 变体: 从下载缓存打开快照流 */
        fun fromDownload(file: File): () -> InputStream = { file.inputStream() }
    }
}

// 上面 chmod 里用到的简写别名, 避免 import 冲突
private typealias pn = java.nio.file.attribute.PosixFilePermission
