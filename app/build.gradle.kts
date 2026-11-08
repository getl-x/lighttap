import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val signingFile = rootProject.file("signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
fun signingValue(key: String, env: String): String? = System.getenv(env) ?: signing.getProperty(key)
val privateOptions = Properties().apply {
    val source = rootProject.file("private-build.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val accessSalt = System.getenv("BUILD_ACCESS_SALT") ?: privateOptions.getProperty("accessSalt")
val accessDigest = System.getenv("BUILD_ACCESS_DIGEST") ?: privateOptions.getProperty("accessDigest")
require(!accessSalt.isNullOrBlank() && !accessDigest.isNullOrBlank()) { "Required build inputs are not configured" }
require(accessSalt.matches(Regex("[A-Za-z0-9+/=]+")) && accessDigest.matches(Regex("[A-Za-z0-9+/=]+"))) { "Invalid build input encoding" }
val releaseStore = signingValue("storeFile", "SIGNING_STORE_FILE")
android {
    namespace = "cn.lighttap.app"
    compileSdk = 36
    defaultConfig { applicationId = "cn.lighttap.app"; minSdk = 26; targetSdk = 36; versionCode = 11; versionName = "1.2.7"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    signingConfigs {
        if (releaseStore != null) create("personal") {
            storeFile = rootProject.file(releaseStore)
            storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("personal")
        }
    }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig {
        buildConfigField("String", "ACCESS_SALT", "\"${accessSalt}\"")
        buildConfigField("String", "ACCESS_DIGEST", "\"${accessDigest}\"")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    testBuildType = "release"
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.opencv:opencv:4.13.0")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
}
