/*
 * [INPUT]: 固定工具链与固定提交与 SHA-256 校验的 Sonicloud AAR、厂商声明依赖。
 * [OUTPUT]: arm64 Android 应用与定向测试构建。
 * [POS]: 模块构建边界；保留现有 SDK 级别与 OkHttp 版本。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
import java.util.Properties
import java.security.MessageDigest
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.resound"
    sourceSets.getByName("main").java.srcDir("vendor/tinypinyin/java")
    compileSdk = 36

    defaultConfig {
        applicationId = if (project.hasProperty("localAsrValidation")) "app.resound.validation" else "app.resound"
        minSdk = 29
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 57
        versionName = "0.2.19" + (providers.gradleProperty("previewNumber").orNull?.let {
            require(it.matches(Regex("[1-9][0-9]*"))) { "previewNumber 必须为正整数" }
            "-preview.$it"
        } ?: "")
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

    // 升级前后核对数据的真机检查要与正式包同签名：-PtestBuildType=release
    testBuildType = (project.findProperty("testBuildType") as String?) ?: "debug"

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


val sonicloudAar = layout.buildDirectory.file("sonicloud/pnote_20260827161403.aar")
val prepareSonicloudSdk = tasks.register("prepareSonicloudSdk") {
    val sha256 = "1fb794ac2631aa2278eca54ea5c6c3afec8efd7818a679398c4bbc4501f8f49e"
    outputs.file(sonicloudAar)
    fun valid(bytes: ByteArray) = bytes.size == 193310 &&
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == sha256
    outputs.upToDateWhen {
        val file = sonicloudAar.get().asFile
        file.isFile && valid(file.readBytes())
    }
    doLast {
        val connection = URI("https://raw.githubusercontent.com/SonicloudTech/sonicloud_opensdk/fa63976f3fe37c889fd084acc75bd66b87e2c83a/pnote-android-sdk/pnote_20260827161403.aar").toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        val bytes = connection.getInputStream().use { it.readNBytes(193311) }
        check(valid(bytes)) { "Sonicloud SDK 大小或 SHA-256 不匹配，请检查厂商固定版本" }
        sonicloudAar.get().asFile.apply { parentFile.mkdirs(); writeBytes(bytes) }
    }
}

dependencies {
    implementation(files(sonicloudAar).builtBy(prepareSonicloudSdk))
    implementation("com.polidea.rxandroidble2:rxandroidble:1.17.2")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("io.reactivex.rxjava3:rxjava:3.1.8")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("org.greenrobot:greendao:3.2.2")
    implementation("com.koushikdutta.async:androidasync:3.1.0")
    implementation("com.squareup.retrofit2:retrofit:2.5.0")
    implementation("com.squareup.retrofit2:adapter-rxjava2:2.5.0")
    implementation("com.squareup.retrofit2:converter-gson:2.5.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.5.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.xutils:xutils:3.3.40")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("org.ahocorasick:ahocorasick:0.4.0") // 随包 TinyPinyin 的依赖
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.25.1")
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
    // 屏幕适配截图检查（Robolectric 在电脑上画界面，不需要模拟器）
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
