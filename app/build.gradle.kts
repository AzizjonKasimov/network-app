import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.azizjon.network"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.azizjon.network"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.ui.tooling)
}

val rootDebugApkName = "NetworkApp-debug.apk"
val rootReleaseApkName = "NetworkApp-latest.apk"

val copyDebugApkToRoot by tasks.registering {
    val sourceApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val rootApk = rootProject.layout.projectDirectory.file(rootDebugApkName)
    inputs.file(sourceApk)
    outputs.file(rootApk)
    doLast {
        val source = sourceApk.get().asFile
        check(source.exists()) { "Debug APK was not produced at ${source.absolutePath}" }
        source.copyTo(rootApk.asFile, overwrite = true)
    }
}

val copyReleaseApkToRoot by tasks.registering {
    val sourceApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val rootApk = rootProject.layout.projectDirectory.file(rootReleaseApkName)
    inputs.file(sourceApk)
    outputs.file(rootApk)
    doLast {
        val source = sourceApk.get().asFile
        check(source.exists()) { "Release APK was not produced at ${source.absolutePath}" }
        source.copyTo(rootApk.asFile, overwrite = true)
    }
}

afterEvaluate {
    tasks.named("assembleDebug") { finalizedBy(copyDebugApkToRoot) }
    tasks.named("assembleRelease") { finalizedBy(copyReleaseApkToRoot) }
}
