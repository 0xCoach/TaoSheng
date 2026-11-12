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
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            api("io.ktor:ktor-client-core:3.3.3")
            api("io.ktor:ktor-client-websockets:3.3.3")
        }
        androidMain.dependencies {
            api("io.ktor:ktor-client-okhttp:3.3.3")
        }
        if (providers.gradleProperty("taosheng.ios").orNull == "true") {
            // 配置阶段 iosMain 还没由默认层级生成，直接给两个 iOS 目标各自的源码集加 Darwin 引擎
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            iosArm64Main.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
            iosSimulatorArm64Main.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
        }
        jvmTest.dependencies {
            implementation("io.ktor:ktor-client-mock:3.3.3")
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

// 卡 151：Ktor 引擎兼容现有 OkHttp；禁止传递依赖升级范围外网络组件。
configurations.configureEach {
    resolutionStrategy.force(
        "com.squareup.okhttp3:okhttp:4.12.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0",
        "org.jetbrains.kotlin:kotlin-stdlib:2.2.20",
    )
}
