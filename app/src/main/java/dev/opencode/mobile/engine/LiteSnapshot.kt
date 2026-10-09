/**
 * LiteSnapshot.kt — lite 变体的快照下载链路(P0-3 修复)
 *
 * 背景: full 变体把 tar.gz 内嵌 assets; lite 变体不内嵌, 首启按 manifest 下载。
 * 此前 `SnapshotInstaller.fromDownload` 已定义但**无任何调用者**, lite 变体必然
 * 走 fail("无可用快照") —— 本文件补上缺失的下载/续传/校验链路。
 *
 * 口径: SHA-256 对【压缩包全文】校验, 与 CI `sha256sum <tar.gz>` 一致。
 * 续传: 支持 HTTP Range(206)。服务器不支持则从头下载。
 */
package dev.opencode.mobile.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** 快照下载源地址组装。base 可被用户设置的镜像前缀覆盖(Settings.snapshotMirror)。 */
object SnapshotSource {
    /**
     * 默认下载基址 = 本项目自己的 GitHub Release(四件套与 APK 同 Release 发布)。
     * 标签取 App 的 versionName(发布时 tag 去 v 前缀), 故 URL = {base}/v{versionName}/{file}。
     */
    const val DEFAULT_BASE = "https://github.com/liaoxiaoyan50-source/opencode-android/releases/download"

    fun urlFor(base: String, appVersionName: String, fileName: String): String =
        "${base.trimEnd('/')}/v$appVersionName/$fileName"
}

class LiteSnapshotDownloader(
    private val http: OkHttpClient,
) {
    /**
     * 下载快照包到 destDir 并校验 SHA-256。
     * @param url        完整下载地址
     * @param manifest   CI manifest(提供 file 名/sha256/size)
     * @param destDir    下载目录(通常 cacheDir)
     * @param onProgress (readBytes, totalBytes); totalBytes 未知为 -1
     * @return 已通过 SHA-256 校验的本地文件
     */
    suspend fun download(
        url: String,
        manifest: SnapshotManifest,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val target = File(destDir, manifest.file)
        val part = File(destDir, "${manifest.file}.part")

        // 已完整下载但上轮未校验/改名成功 → 直接校验(避免 Range 越界 416)
        if (part.exists() && manifest.size > 0 && part.length() >= manifest.size) {
            return@withContext verifyAndFinalize(part, target, manifest)
        }

        val existing = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("快照下载失败: HTTP ${r.code} (url=$url)")
            val body = r.body ?: throw java.io.IOException("快照下载失败: 空响应体")
            // 仅当服务端确认分段(206)才续写; 否则从头覆盖
            val append = existing > 0 && r.code == 206
            val total = if (manifest.size > 0) manifest.size
                        else (if (append) existing else 0L) + body.contentLength()
            var read = if (append) existing else 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        verifyAndFinalize(part, target, manifest)
    }

    /** 校验 .part 的 SHA-256(压缩包全文), 通过则改名为最终文件; 失败删除并抛异常 */
    private fun verifyAndFinalize(part: File, target: File, manifest: SnapshotManifest): File {
        val actual = sha256OfFile(part)
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            part.delete()
            throw IllegalStateException("下载快照 SHA-256 不匹配: 期望 ${manifest.sha256}, 实际 $actual")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            // rename 失败: 退化为复制
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    private fun sha256OfFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
