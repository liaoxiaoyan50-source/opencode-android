// =============================================================================
// app/build.gradle.kts — OpenCode Android 应用模块构建配置 [P5, release-ops]
//
// 契约落点:
//   * P1 D4 【发布阻断级】packaging.jniLibs.useLegacyPackaging = true:
//     minSdk>=23 时 AGP 默认 false（库不解压进 nativeLibraryDir），而 targetSdk>=29
//     下只有 nativeLibraryDir 是可执行区 —— 漏配 = ProcessBuilder 无法 exec
//     libproot.so = 全链路不可用。等效 APK manifest android:extractNativeLibs="true"，
//     release.yml "Gate 4" 对最终 APK 做断言，双保险，禁止为省体积回退。
//   * P1 §3.3 C3: abiFilters = ["arm64-v8a"]（唯一 ABI，与 native 两脚本交付一致）。
//   * P4 §6: minSdk 26 / targetSdk 35 / compileSdk 35；依赖仅限 Compose BOM(>=2024.05)
//     + activity-compose + kotlinx-coroutines + OkHttp；不引 navigation-compose /
//     security-crypto（UI 已自实现等价能力，引入反而增面）。
//   * full/lite（dimension "dist"）: 差异仅快照分发方式 —— full 内嵌 assets
//     （src/full/assets/snapshot/，由 release.yml 从 job_snapshot artifact 注入，
//     引擎层按 assets/snapshot/manifest.json 读取）；lite 不内嵌，首启按 manifest 下载。
//     业务逻辑一致，不加 applicationIdSuffix（如需 full/lite 并行安装，请主理人终裁后加）。
//
// ⚠ 需同时存在的最简 settings.gradle.kts（置于仓库根目录，gradle wrapper 8.7+）:
// ──────────────────────────── 8< ────────────────────────────
// pluginManagement {
//     repositories { google(); mavenCentral(); gradlePluginPortal() }
// }
// plugins {
//     id("com.android.application") version "8.5.2"
//     id("org.jetbrains.kotlin.android") version "2.0.20"
//     id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"   // Kotlin 2.0 Compose 编译器
// }
// dependencyResolutionManagement {
//     repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
//     repositories { google(); mavenCentral() }
// }
// rootProject.name = "OpenCodeMobile"
// include(":app")
// ─────────────────────────────────────────────────────────────
// 源码落位（入仓映射，入仓后 gradle 直接可编）:
//   code/EngineService.kt + code/SnapshotInstaller.kt → app/src/main/java/dev/opencode/mobile/engine/
//   code/ui/*.kt                                      → app/src/main/java/dev/opencode/mobile/ui/
//   code/release.yml 由 CI 注入                        → app/src/full/assets/snapshot/{tar.gz, manifest.json}
// =============================================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── APK 版本: CI 从 tag 推导注入 (release.yml "Derive app version from tag");
//    本地构建回落 1.0.0/1，仅作开发冒烟口径 ──
val appVersionName: String = System.getenv("APP_VERSION_NAME") ?: "1.0.0"
val appVersionCode: Int = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 1

// ── 签名: CI 经环境变量注入（release.yml 由 Secrets KEYSTORE_BASE64 还原 keystore 后
//    以 CI_KEYSTORE_FILE / CI_KEYSTORE_PASSWORD / CI_KEY_ALIAS / CI_KEY_PASSWORD 传入）。
//    四个变量全有 → CI 签名；全无 → release 包回落 debug 签名兜底（仅供本地侧载冒烟，
//    release.yml 已确保 tag 发布路径绝不走到该分支）；部分缺失 → 配置期直接报错防呆。──
val ciSigning: List<String>? = listOf(
    System.getenv("CI_KEYSTORE_FILE"),
    System.getenv("CI_KEYSTORE_PASSWORD"),
    System.getenv("CI_KEY_ALIAS"),
    System.getenv("CI_KEY_PASSWORD"),
).let { v ->
    when {
        v.all { !it.isNullOrBlank() } -> v.map { it!! }
        v.all { it.isNullOrBlank() } -> null
        else -> error(
            "CI 签名环境变量不完整: 需同时提供 CI_KEYSTORE_FILE / CI_KEYSTORE_PASSWORD / " +
                "CI_KEY_ALIAS / CI_KEY_PASSWORD（release.yml 由 Secrets 注入，勿手工部分设置）"
        )
    }
}

android {
    namespace = "dev.opencode.mobile"               // UI 包名 dev.opencode.mobile.ui 之下的 app 层
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.opencode.mobile"
        minSdk = 26                                  // = P1 §3.3 NDK android26-clang 口径
        targetSdk = 35                               // P4 §6-1
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += "arm64-v8a"                // C3: 唯一 ABI
        }
    }

    // D4【发布阻断级】库解压进 nativeLibraryDir（详见文件头注释；Gate 4 断言等效 manifest 位）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (ciSigning != null) {
            create("ci") {
                storeFile = File(ciSigning[0])
                storePassword = ciSigning[1]
                keyAlias = ciSigning[2]
                keyPassword = ciSigning[3]
            }
        }
    }

    buildTypes {
        release {
            // v1 不开 R8: 引擎层经 Class.forName 定位 dev.opencode.mobile.ui.MainActivity
            // 等反射点（P3 §7.1），混淆需先行补 keep 规则，属后续工程化任务
            isMinifyEnabled = false
            signingConfig = if (ciSigning != null) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")     // 本地 debug 签名兜底（不得作为发布物）
            }
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        create("full") {
            dimension = "dist"
            // 快照内嵌于 src/full/assets/snapshot/（flavor sourceSet 默认自动并入 assets）
        }
        create("lite") {
            dimension = "dist"
            // 不内嵌快照; 首启按 manifest 下载。minAppVersion 下载前校验由 UI/引擎层处理
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // lintVital 不拦 assembleRelease（质量门禁另行接入，勿让 lint 阻断出包链路）
        checkReleaseBuilds = false
    }
}

// ── full 变体防呆: 快照资产缺失即 fail-fast（防 CI 忘注入 / 本地误打出空壳 full 包）──
val validateFullSnapshotAssets = tasks.register("validateFullSnapshotAssets") {
    doLast {
        val dir = file("src/full/assets/snapshot")
        val tar = file("src/full/assets/snapshot/oc-ubuntu-arm64.tar.gz")
        val manifest = file("src/full/assets/snapshot/manifest.json")
        if (!tar.isFile || !manifest.isFile) {
            throw GradleException(
                "full 变体快照资产缺失（期望 ${tar.name} 与 ${manifest.name} 位于 ${dir}）。\n" +
                    "CI 侧由 release.yml 'Inject snapshot into full variant assets' 注入；" +
                    "本地构建请先放置产物，或改用 lite 变体冒烟（无需快照）。"
            )
        }
        // 内容一致性（sha256/size/C1 schema）由 release.yml gate ③ 负责，此处仅防缺失
    }
}
tasks.matching { it.name == "mergeFullAssets" }.configureEach {
    dependsOn(validateFullSnapshotAssets)
}

dependencies {
    // Compose BOM >= 2024.05（P4 §6-2: HorizontalDivider 需 material3 >= 1.2，随 BOM 管理）
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // P3 SnapshotInstaller 依赖声明（GZIP+TarArchiveInputStream 纯 Java 解包，零原生依赖）
    implementation("org.apache.commons:commons-compress:1.26.2")

    // [PR7] JVM 单测: SnapshotInstaller 的校验/解压/幂等/路径穿越防护均为纯 JVM 逻辑,
    // 无需真机即可回归。kotlin-test 提供断言, kotlinx-coroutines-test 提供 runTest。
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ⚠ 依赖红线（P4 §6-2）: 不引 androidx.navigation:navigation-compose 与
    //   androidx.security:security-crypto —— UI 层已自实现等价能力，引入反而增依赖面。
}
