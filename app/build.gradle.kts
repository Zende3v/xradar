import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Stadia Maps key for the basemap styles. Put "stadia.apiKey=..." in local.properties
// (git-ignored) or set STADIA_API_KEY in the environment. Empty = fall back to IGN.
val stadiaApiKey: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("stadia.apiKey") ?: System.getenv("STADIA_API_KEY") ?: ""
}

plugins {
    // Kotlin support is built into AGP 9+ (do NOT apply org.jetbrains.kotlin.android).
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.eona.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.eona.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "STADIA_API_KEY", "\"$stadiaApiKey\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Live backend (the VPS through Cloudflare Tunnel). Works on a real phone too.
            // For local dev instead, use "http://10.0.2.2:8090/" (emulator → host).
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api.lrda-mercuriale.uk/\"")
        }
        release {
            // Shrinking/obfuscation are wired up in a later hardening step.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so the release APK is installable now.
            // TODO: generate a dedicated release keystore before any store upload.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BACKEND_BASE_URL", "\"https://api.lrda-mercuriale.uk/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.ui.tooling)
}
