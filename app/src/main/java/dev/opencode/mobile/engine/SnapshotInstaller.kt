/**
 * SnapshotInstaller.kt — 引擎快照的校验、解压与幂等落盘
 *
 * 设计思路:
 *   1. 快照来源二选一: full 变体从 assets 读; lite 变体从下载产物读。
 *      本类只接收一个 InputStream 工厂, 两种来源统一处理。
 *      ⚠ lite 变体的下载/缓存链路尚未实现(KNOWN_BUGS P0-3), 现仅 full(assets) 可用。
 *   2. 两遍式: 第一遍对【压缩包字节流】计算 SHA-256(口径与 CI `sha256sum <tar.gz>` 一致);
 *      第二遍解压到 .tmp 目录(GZIP + TarArchiveInputStream, 纯 Java 零原生依赖)。
 *      ⚠ SHA 口径必须与 CI 一致 —— 曾因对解压后内容算哈希导致生产校验必然失败(P0-1f)。
 *   3. 幂等: {rootfs}/.snapshot-meta.json 记录 {snapshotVersion, state}。
 *      任何一步中断(进程被杀/断电)后, state 永远到不了 "ready",
 *      下次 install() 检测到即整目录删除重装 — 保证"装一半"永不残留。
 *   4. 原子性: 全部解压到 rootfs.tmp, 成功后 rename 为 rootfs (同分区 rename 原子);
 *      rename 返回值必须检查, 失败即抛异常, 绝不写"假 ready"(P1-2)。
 *   5. 升级保留数据: 用户工作区在**外部私有目录**(getExternalFilesDir/workspace,
 *      非本类管理), 不需要迁移; 会话库(~/.local/share/opencode)在旧 rootfs 内,
 *      换代前由 migrateUserDirs 搬移到新 rootfs。二者不在快照包内(构建脚本已剔除)。
 *
 * 依赖: org.apache.commons:commons-compress:1.26+
 * 可测性: 主构造接收 Dirs(目录锚点), 测试可注入临时目录, 无需 mock Android Context。
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
    /** 快照文件名(C1 manifest 的 `file` 字段); lite 下载与落盘用。默认与 CI 产出一致 */
    val file: String = "oc-ubuntu-arm64.tar.gz",
)

/** 安装进度: phase 用于 UI 分段展示(校验/解压/收尾) */
sealed class InstallProgress {
    data class Verifying(val readBytes: Long, val totalBytes: Long) : InstallProgress()
    data class Extracting(val fileCount: Int) : InstallProgress()
    data object Finishing : InstallProgress()
    data class Done(val version: String) : InstallProgress()
}

class SnapshotInstaller(
    /** 目录锚点集合; 生产经 Context 次构造派生, 测试直接注入临时目录(无需 mock Android)。
     *  [public] 供测试读取锚点路径(如 metaFile); 生产代码不应使用。 */
    internal val dirs: Dirs,
) {
    /**
     * [可测性重构 PR7] 目录锚点集中一处。
     * 生产路径: 所有目录派生自 context.filesDir, 行为零变化。
     * 测试路径: 直接构造 Dirs 注入临时目录 —— Context.filesDir 在 JVM 不可用, 此为可测前提。
     */
    data class Dirs(
        val base: File,        // 所有目录的父目录(= filesDir)
        val rootfs: File,      // 快照最终落位: {base}/rootfs
        val tmp: File,         // 解压暂存: {base}/rootfs.tmp
        val old: File,         // 升级时旧快照暂存: {base}/rootfs.old
    ) {
        companion object {
            fun fromFilesDir(filesDir: File) = Dirs(
                base = filesDir,
                rootfs = File(filesDir, "rootfs"),
                tmp = File(filesDir, "rootfs.tmp"),
                old = File(filesDir, "rootfs.old"),
            )
        }
        val metaFile: File get() = File(rootfs, ".snapshot-meta.json")
    }

    constructor(context: Context) : this(Dirs.fromFilesDir(context.filesDir))

    val rootfsDir: File get() = dirs.rootfs
    private val tmpDir: File get() = dirs.tmp
    private val metaFile: File get() = dirs.metaFile

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

        // [1a] 第一遍: 对【压缩包字节流】计算 SHA-256 —— 口径必须与 CI 一致:
        // build-snapshot.sh:266 用 `sha256sum <tar.gz>` 对压缩文件计算, manifest.sha256 即压缩包哈希。
        // [P0-1f 契约级修复] 原实现对【解压后内容】计算哈希(注释自述"哈希只能对解压流同步计算"),
        // 与 CI 口径完全不符 → 生产快照的校验必然失败, full 变体永远装不上快照。
        // 本 bug 由 PR7 单测暴露(测试按 CI 口径造数据, 安装器按解压流校验, 8 用例全红)。
        // 代价: 需两遍流(openStream 工厂支持重开); 收益: 口径正确 + Verifying 进度真实可用。
        val digest = MessageDigest.getInstance("SHA-256")
        openStream().use { raw ->
            val buf = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                val n = raw.read(buf); if (n < 0) break
                digest.update(buf, 0, n)
                read += n
                if (totalBytes > 0) onProgress(InstallProgress.Verifying(read, totalBytes))
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (manifest != null && !actual.equals(manifest.sha256, ignoreCase = true)) {
            throw IllegalStateException("SHA-256 不匹配: 期望 ${manifest.sha256}, 实际 $actual")
        }

        // [1b] 第二遍: 解压(哈希已在第一遍确认, 此处纯解压)
        var files = 0L
        openStream().use { raw ->
            val gz = GZIPInputStream(raw.buffered(1 shl 16))
            TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val target = File(tmpDir, entry.name).canonicalFile
                    // 安全: 拒绝路径穿越(../../)与绝对路径条目
                    if (!target.path.startsWith(tmpDir.canonicalPath + File.separator) && target != tmpDir) {
                        throw SecurityException("快照包含非法路径: ${entry.name}")
                    }
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    // [P0-symlink 修复] tar 保留符号链接(build-snapshot.sh 的 tar 未加
                    // --dereference), Ubuntu rootfs 含大量 symlink(usrmerge 的 /bin->usr/bin、
                    // /lib->usr/lib、/etc/alternatives/* 等)。原实现把非目录条目一律按普通
                    // 文件写入, symlink 条目 size=0 → 被写成【空文件】→ 装出的 rootfs 损坏
                    // (shell/二进制路径失效)。此处按条目类型分别还原。
                    when {
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists()) target.delete()
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(), java.nio.file.Paths.get(entry.linkName))
                            continue
                        }
                        entry.isLink -> { // 硬链接(LF_LINK)
                            val src = File(tmpDir, entry.linkName).canonicalFile
                            target.parentFile?.mkdirs()
                            if (src.exists()) {
                                if (target.exists()) target.delete()
                                runCatching { java.nio.file.Files.createLink(target.toPath(), src.toPath()) }
                                    .onFailure { src.copyTo(target, overwrite = true) }
                            }
                            continue
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO ->
                            continue // 设备节点/FIFO 不应出现在 rootfs; 跳过而非误写成普通文件
                    }
                    target.parentFile?.mkdirs()
                    java.io.FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = tar.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    // 恢复权限位: proot 不强制要求 x 权限(ptrace 模拟 execve),
                    // 但 qemu 降级模式和用户手动进入容器时需要, 全部按 tar 原样恢复
                    chmod(target, entry.mode)
                    if (++files % 512L == 0L) onProgress(InstallProgress.Extracting(files.toInt()))
                }
            }
        }
        onProgress(InstallProgress.Finishing)

        // [2] 升级场景: 把旧 rootfs 里的运行期数据搬进新 rootfs(会话库/用户工作区)
        if (rootfsDir.exists()) migrateUserDirs(rootfsDir, tmpDir)

        // [3] 原子替换: 同分区 rename
        //
        // [P1-2 修复] File.renameTo() 失败时返回 false 而不抛异常。原实现不检查返回值,
        // 无条件写 state=ready → rename 失败时会写出指向空/残缺目录的"假 ready",
        // 下次启动跳过安装, 引擎永远起不来且日志无线索。现逐步校验, 失败即抛异常。
        val oldDir = dirs.old
        if (oldDir.exists()) oldDir.deleteRecursively()   // 清上次残留, 避免 rename 目标已存在
        if (rootfsDir.exists()) {
            if (!rootfsDir.renameTo(oldDir)) {
                throw IllegalStateException(
                    "旧 rootfs 重命名失败: ${rootfsDir.absolutePath} -> ${oldDir.absolutePath}。" +
                        "已中止安装以避免丢失会话库。")
            }
        }
        if (!tmpDir.renameTo(rootfsDir)) {
            val restored = oldDir.exists() && oldDir.renameTo(rootfsDir)
            throw IllegalStateException(
                "快照原子落位失败: ${tmpDir.absolutePath} -> ${rootfsDir.absolutePath}。" +
                    if (restored) "已回滚到旧快照。" else "且旧快照回滚失败, 需清空后重试。")
        }
        oldDir.deleteRecursively()

        // [4] 幂等标记: state=ready 是"安装完成"的唯一事实来源
        // 只有确认 rootfs 真实存在后才允许写 ready(否则 meta 指向不存在的目录)
        check(rootfsDir.isDirectory) {
            "落位后 rootfs 不存在或非目录: ${rootfsDir.absolutePath} — 拒绝写入 ready 标记"
        }
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
        // [P0-1e 修复] PosixFilePermission 枚举常量名为大写下划线(OWNER_READ), 原代码
        // pn.OwnerRead 等 9 处全部 unresolved = 编译错误(此前被误判为"缺桩误报")。
        // 八进制位→枚举的映射: 0b100_000_000=OwnerRead(0400), 以此类推。
        if (mode and 0b100_000_000 != 0) perms.add(pn.OWNER_READ)
        if (mode and 0b010_000_000 != 0) perms.add(pn.OWNER_WRITE)
        if (mode and 0b001_000_000 != 0) perms.add(pn.OWNER_EXECUTE)
        if (mode and 0b000_100_000 != 0) perms.add(pn.GROUP_READ)
        if (mode and 0b000_010_000 != 0) perms.add(pn.GROUP_WRITE)
        if (mode and 0b000_001_000 != 0) perms.add(pn.GROUP_EXECUTE)
        if (mode and 0b000_000_100 != 0) perms.add(pn.OTHERS_READ)
        if (mode and 0b000_000_010 != 0) perms.add(pn.OTHERS_WRITE)
        if (mode and 0b000_000_001 != 0) perms.add(pn.OTHERS_EXECUTE)
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
