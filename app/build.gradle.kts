import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Private signing material is supplied by the operator/CI, never by checked-in files.
val releaseStore = providers.environmentVariable("VIME_RELEASE_STORE_FILE").orNull
val releaseAlias = providers.environmentVariable("VIME_RELEASE_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("VIME_RELEASE_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("VIME_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(releaseStore,releaseAlias,releaseStorePassword,releaseKeyPassword).all { !it.isNullOrBlank() }
val windowChecks = providers.gradleProperty("vimeWindowChecks").orNull
require(windowChecks == null || windowChecks in listOf("true", "false")) { "vimeWindowChecks must be true or false" }
val nativeChecks = providers.gradleProperty("vimeNativeChecks").orNull
require(nativeChecks == null || nativeChecks in listOf("true", "false")) { "vimeNativeChecks must be true or false" }
val inputDataChecks = providers.gradleProperty("vimeInputDataChecks").orNull
require(inputDataChecks == null || inputDataChecks in listOf("true", "false")) { "vimeInputDataChecks must be true or false" }
val noticeChecks = providers.gradleProperty("vimeNoticeChecks").orNull
require(noticeChecks == null || noticeChecks in listOf("true", "false")) { "vimeNoticeChecks must be true or false" }
val keyboardChecks = providers.gradleProperty("vimeKeyboardChecks").orNull
require(keyboardChecks == null || keyboardChecks in listOf("true", "false")) { "vimeKeyboardChecks must be true or false" }
require(listOf(windowChecks, nativeChecks, inputDataChecks, noticeChecks, keyboardChecks).count { it == "true" } <= 1) { "Select one instrumentation suite at a time" }

android {
    namespace = "com.vime.ime"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.vime.ime"
        minSdk = 24
        targetSdk = 36
        versionCode = 38
        versionName = "0.24.1"
        testInstrumentationRunner = when {
            keyboardChecks == "true" -> "com.vime.ime.KeyboardUsabilityInstrumentation"
            windowChecks == "true" -> "com.vime.ime.WindowInteractionInstrumentation"
            nativeChecks == "true" -> "com.vime.ime.NativeRuntimeInstrumentation"
            inputDataChecks == "true" -> "com.vime.ime.InputDataInstrumentation"
            noticeChecks == "true" -> "com.vime.ime.NoticesInstrumentation"
            else -> "com.vime.ime.data.LearningStorageInstrumentation"
        }
    }

    signingConfigs {
        if (releaseSigningReady) create("operatorRelease") {
            storeFile = file(releaseStore!!)
            keyAlias = releaseAlias
            storePassword = releaseStorePassword
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("operatorRelease")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // 候选栏/单词卡布局通过 ViewBinding 绑定（CandidateBarBinding 等）
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 注：compileDebugJavaWithJavac 每次会打印两行「注: XXX 使用了过时的 API」，
// 指向的是 ViewBinding 为 KeyboardView（API 29 起 deprecated）生成的 ImeInputBinding.java，
// 不是本项目源码。试过 -Xlint:-deprecation（确认已进 javac 命令行），该 note 不归它管，
// 只有 -nowarn 能吞掉——但那会连真正的 javac 警告一起吞，不值得。所以保留这两行「注」。
// 本项目 0 个 .java 源文件，Kotlin 侧的 deprecation 已用 @file:Suppress("DEPRECATION") 处理。

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

// Resolved production runtime only: build plugins and test libraries are excluded.
tasks.register("writeRuntimeInventory") {
    val report = layout.buildDirectory.file("reports/runtime-components.json")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val runtime = configurations.getByName("releaseRuntimeClasspath")
        val modules = runtime.incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { id ->
                "${id.group}:${id.module}:${id.version}"
            }
        }.sorted()
        val artifacts = runtime.resolvedConfiguration.resolvedArtifacts.map { artifact ->
            mapOf("coordinate" to artifact.moduleVersion.id.toString(),
                  "file" to artifact.file.absolutePath,
                  "sha256" to MessageDigest.getInstance("SHA-256")
                      .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) })
        }.sortedBy { it.getValue("coordinate") }
        val target = report.get().asFile
        target.parentFile.mkdirs()
        target.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(
            mapOf("format" to "vime-runtime-inventory-1", "modules" to modules, "artifacts" to artifacts))))
    }
}

