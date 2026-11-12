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
    // 默认不配置 Native 目标；Xcode 就绪后由负责人显式启用。
    if (providers.gradleProperty("taosheng.ios").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

android {
    namespace = "app.resound.shared"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 在只有 Android/JVM 目标时默认跳过 metadata 编译；显式启用，
// 让 commonMain 真正经过无 JDK 的共用编译器，而非仅靠目录和文本检查。
gradle.projectsEvaluated {
    tasks.named("compileCommonMainKotlinMetadata") { enabled = true }
}
tasks.matching { it.name in setOf("compileKotlinJvm", "compileDebugKotlinAndroid", "compileReleaseKotlinAndroid", "check") }
    .configureEach { dependsOn("compileCommonMainKotlinMetadata") }
