plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vime.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vime.ime"
        minSdk = 24
        targetSdk = 34
        versionCode = 22
        versionName = "0.17.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 候选栏/单词卡布局通过 ViewBinding 绑定（CandidateBarBinding 等）
        viewBinding = true
        buildConfig = true
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

