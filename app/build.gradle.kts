import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.resound"
    compileSdk = 36

    defaultConfig {
        applicationId = if (project.hasProperty("localAsrValidation")) "app.resound.validation" else "app.resound"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 15
        versionName = "0.2.1"
        // 只给小米 14 Pro 用：只打包 64 位 ARM 的原生库
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    // 正式签名只放在负责人电脑上（~/.config/taosheng/signing.properties），不进仓库；
    // 其他人编译时找不到就退回调试签名，不影响编译
    val releaseSigning = File(System.getProperty("user.home"), ".config/taosheng/signing.properties")
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = File(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.documentfile:documentfile:1.0.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("commons-io:commons-io:2.16.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.work:work-testing:2.10.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// ---------------------------------------------------------------------------
// 骁龙 8 Elite / 8 Gen 5 (Oryon) 兼容性补丁：禁用 ONNX Runtime 1.23.2 的 SME 派发
//
// 背景：
// 高通 Oryon 架构在 Linux AT_HWCAP2 中置位了 HWCAP2_SME (bit 23)，但未实现（或内核未放行）
// KleidiAI _sme2_mopa 算子使用的 SME2 指令（如 `ptrue pn9.b`）。
// ONNX Runtime 1.23.2 的 MLAS_CPUIDINFO 仅检查 `has_arm_sme_` 即派发至 SME2 算子，
// 导致 CaptureService 初始化 SileroVad / CamPlusPlus 时立即触发：
//   Fatal signal 4 (SIGILL), code 1 (ILL_ILLOPC)
//
// 方案：
// 在打包原生库阶段，将 arm64-v8a/libonnxruntime.so (1.23.2) 中两处 MLAS_CPUIDINFO
// 写入 `has_arm_sme_ = 1` 的指令：
//   - 0x0075ddac: 68 16 00 39 (`strb w8, [x19, #5]`)   -> 7f 16 00 39 (`strb wzr, [x19, #5]`)
//   - 0x011e4a0c: 68 16 01 39 (`strb w8, [x19, #0x45]`) -> 7f 16 01 39 (`strb wzr, [x19, #0x45]`)
// 改写为零寄存器写入（`has_arm_sme_ = 0`），使 ORT 安全回退至 ARM64 NEON / FP16 / DotProd 算子。
// ---------------------------------------------------------------------------
fun patchOnnxRuntimeOryonSme(soFile: File) {
    val expectedSize = 19_347_616L
    if (!soFile.isFile || soFile.length() != expectedSize) return

    val patches = listOf(
        // (offset, pre8Bytes, origInsn4Bytes, patchedInsn4Bytes, post4Bytes)
        Triple(
            0x0075ddac,
            byteArrayOf(0xc1.toByte(), 0xfb.toByte(), 0xbf.toByte(), 0x36, 0x28, 0x00, 0x80.toByte(), 0x52),
            byteArrayOf(0x81.toByte(), 0xfb.toByte(), 0x2f, 0xb6.toByte())
        ) to Pair(
            byteArrayOf(0x68, 0x16, 0x00, 0x39),
            byteArrayOf(0x7f, 0x16, 0x00, 0x39)
        ),
        Triple(
            0x011e4a0c,
            byteArrayOf(0x69, 0x0e, 0x01, 0x39, 0x6a, 0x12, 0x01, 0x39),
            byteArrayOf(0x33, 0xc8.toByte(), 0xd5.toByte(), 0x97.toByte())
        ) to Pair(
            byteArrayOf(0x68, 0x16, 0x01, 0x39),
            byteArrayOf(0x7f, 0x16, 0x01, 0x39)
        )
    )

    val bytes = soFile.readBytes()
    var modified = false

    for ((ctx, insns) in patches) {
        val (offset, pre, post) = ctx
        val (origInsn, patchedInsn) = insns

        val preMatch = bytes.sliceArray((offset - pre.size) until offset).contentEquals(pre)
        val postMatch = bytes.sliceArray((offset + 4) until (offset + 4 + post.size)).contentEquals(post)
        if (!preMatch || !postMatch) {
            logger.warn("[OryonSMEPatch] Unexpected context at 0x${offset.toString(16)} in ${soFile.path}; skipping.")
            return
        }

        val currentInsn = bytes.sliceArray(offset until (offset + 4))
        when {
            currentInsn.contentEquals(patchedInsn) -> Unit // 已经打过补丁（幂等）
            currentInsn.contentEquals(origInsn) -> {
                System.arraycopy(patchedInsn, 0, bytes, offset, 4)
                modified = true
            }
            else -> {
                logger.warn("[OryonSMEPatch] Unexpected instruction at 0x${offset.toString(16)} in ${soFile.path}; skipping.")
                return
            }
        }
    }

    if (modified) {
        soFile.writeBytes(bytes)
        logger.lifecycle("[OryonSMEPatch] Patched MLAS_CPUIDINFO.has_arm_sme_ in ${soFile.path}")
    }
}

tasks.matching { task ->
    (task.name.startsWith("merge") && task.name.endsWith("NativeLibs")) ||
        (task.name.startsWith("strip") && task.name.endsWith("DebugSymbols"))
}.configureEach {
    doLast {
        outputs.files.asFileTree
            .matching { include("**/arm64-v8a/libonnxruntime.so") }
            .forEach { soFile -> patchOnnxRuntimeOryonSme(soFile) }
    }
}
