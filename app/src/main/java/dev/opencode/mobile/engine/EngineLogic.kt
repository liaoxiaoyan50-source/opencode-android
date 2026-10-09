/**
 * EngineLogic.kt — EngineService 中可纯 JVM 测试的逻辑（G-3 测试护栏）
 *
 * 从 EngineService 抽出、不依赖 Android Service 生命周期的纯函数/常量，便于单测：
 *   - compareSemver：快照 minAppVersion 版本比对
 *   - SelfHeal：L2 启动自愈的日志特征串匹配（P1 §3.2 / P2b §6）
 */
package dev.opencode.mobile.engine

/** 语义化版本比较(X.Y.Z); 预发布后缀(如 -local)忽略, 非数字段按 0。返回 <0/0/>0 */
internal fun compareSemver(a: String, b: String): Int {
    fun parts(s: String) = s.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val pa = parts(a); val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/**
 * bootSequence 决策(P0-4): 是否需要安装/重装快照。
 * 未安装(installed==null) 或 bundled 的 snapshotVersion 与已装不同 → 需要。
 * 从 EngineService.bootSequenceInner 抽出以便纯 JVM 单测。
 */
internal fun needsSnapshotInstall(installedVersion: String?, bundled: SnapshotManifest?): Boolean =
    installedVersion == null || (bundled != null && bundled.snapshotVersion != installedVersion)

/**
 * bootSequence 决策(minAppVersion, P1 §3.1 / M3-6): 当前 App 是否被快照要求的最低版本阻断。
 * 仅当 manifest 存在、minAppVersion 非空、且 当前版本 < 要求版本 时为 true。
 */
internal fun isBlockedByMinAppVersion(appVersion: String, bundled: SnapshotManifest?): Boolean =
    bundled != null && bundled.minAppVersion.isNotBlank() &&
        compareSemver(appVersion, bundled.minAppVersion) < 0

/**
 * L2 启动自愈特征串匹配（P1 §3.2 四条 + P2b §6 正面样例；负面样例强制排除防误判）。
 *
 * 正面串 1-4 = P1 §3.2 四条；其余 = P2b §6 登记正面样例（§6 第 8 条为负面样例）。
 * 行级匹配：含负面串的行先剔除；「ptrace … Operation not permitted」按同行共现判定。
 * 最终清单以 M0 真机实测修订为准（P1 §2 冻结规则允许参数级修正）。
 */
internal object SelfHeal {
    val POSITIVE = listOf(
        "permission denied",                    // P1 §3.2 / P2b §6-1: noexec 或 SELinux 拒绝 exec
        "exec format error",                    // P1 §3.2 / P2b §6-2: ENOEXEC, 架构/对齐问题
        "failed to load",                       // P1 §3.2: 加载失败
        "error while loading shared libraries", // P1 §3.2 / P2b §6-3: 库文件损坏或缺库
        "mmap failed",                          // P2b §6-4: 映射失败(16KB page 遇 4KB 假设等)
        "mmap: cannot allocate memory",         // P2b §6-4: 同上
        "failed to map",                        // P2b §6-5: qemu linux-user 映射阶段
        "map_fixed",                            // P2b §6-5: MAP_FIXED 相关报错(小写化匹配)
        "qemu: uncaught target signal",         // P2b §6-6: guest 异常信号
        // P2b §6-7 「ptrace … Operation not permitted」为共现规则, 见 match()
    )

    /** 负面样例(P2b §6-8): proot 非致命警告, 正常启动即输出, 必须排除防 L2 误触发清缓存循环 */
    val NEGATIVE = listOf("can't sanitize binding")

    /** @return 命中的特征串描述(进诊断报告/引擎日志), 未命中返回 null */
    fun match(tail: String): String? {
        for (rawLine in tail.lineSequence()) {
            val line = rawLine.lowercase()
            if (NEGATIVE.any { line.contains(it) }) continue // 负面行剔除(防误判)
            for (sig in POSITIVE) if (line.contains(sig)) return sig
            if (line.contains("ptrace") && line.contains("operation not permitted")) {
                return "ptrace ... Operation not permitted" // P2b §6-7: 内核禁 ptrace, 环境级失败
            }
        }
        return null
    }
}
