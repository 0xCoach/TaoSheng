plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvmToolchain(21)
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        jvmTest.dependencies { implementation("junit:junit:4.13.2") }
    }
}

android {
    namespace = "app.resound.devices"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 单测读取 App 的真实说明卡，不复制一套可能漂移的测试数据。
tasks.named<Test>("jvmTest") {
    systemProperty("deviceCardsDir", rootProject.file("app/src/main/assets/devices").absolutePath)
}

// KGP 在只有 Android/JVM 目标时默认跳过 metadata 编译；显式启用，
// 让 commonMain 真正经过无 JDK 的共用编译器，而非仅靠目录和文本检查。
gradle.projectsEvaluated {
    tasks.named("compileCommonMainKotlinMetadata") { enabled = true }
}
tasks.matching { it.name in setOf("compileKotlinJvm", "compileDebugKotlinAndroid", "compileReleaseKotlinAndroid", "check") }
    .configureEach { dependsOn("compileCommonMainKotlinMetadata") }
