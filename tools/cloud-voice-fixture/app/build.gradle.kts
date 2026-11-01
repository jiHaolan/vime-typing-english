plugins { id("com.android.application") }
android {
    namespace = "com.vime.cloudfixture"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "com.vime.cloudfixture"; minSdk = 24; targetSdk = 36; versionCode = 9; versionName = "0.1.9" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    val fixtures = providers.gradleProperty("vimeCloudFixturesDir").orNull ?: error("Supply the reviewed synthetic fixture directory")
    sourceSets.getByName("main").assets.srcDir(fixtures)
    androidResources { noCompress += listOf("gguf", "wav") }
}
