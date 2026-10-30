plugins { id("com.android.application") version "8.10.1" }

val input = providers.gradleProperty("rimeSources").map { file(it) }
val stagedAssets = layout.buildDirectory.dir("compiler-assets")
val stageSources by tasks.registering(Sync::class) {
    from(input) { into("rime"); include("manifest.json", "default.yaml", "essay.txt", "vime_pinyin.dict.yaml", "vime_pinyin.schema.yaml", "vime_nine.schema.yaml") }
    into(stagedAssets)
    doFirst { require(input.isPresent && input.get().isDirectory) { "Pass -PrimeSources=/absolute/path/to/prepared/rime" } }
}

android {
    namespace = "com.vime.compiler"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.vime.dictionary.compiler"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "x86_64" }
    }
    sourceSets.getByName("main") {
        assets.srcDir(stagedAssets)
        jniLibs.srcDir("../../app/src/main/jniLibs")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild").configure { dependsOn(stageSources) }
