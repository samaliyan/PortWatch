import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.samaliyan.portwatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.samaliyan.portwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    // Every release must be signed with the same key, or updates will not install over
    // the old version. The key comes from GitHub Secrets (see README), never from the repo.
    val keystoreB64 = System.getenv("SIGNING_KEYSTORE_BASE64")
    val keystoreFile = layout.buildDirectory.file("signing/release.keystore").get().asFile
    val hasReleaseKey = !keystoreB64.isNullOrBlank()
    if (hasReleaseKey) {
        keystoreFile.parentFile.mkdirs()
        keystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreB64))
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "portwatch"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
