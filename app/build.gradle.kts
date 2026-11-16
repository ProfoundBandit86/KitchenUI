plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode=<run number>; local builds default to 1.
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.kitchenui.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kitchenui.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
        buildConfigField("String", "UPDATE_REPO", "\"ProfoundBandit86/KitchenUI\"")
    }

    signingConfigs {
        create("release") {
            // Uses GitHub secrets when set, otherwise the keystore committed in /signing.
            val ksFile = System.getenv("KEYSTORE_FILE")
            storeFile = if (ksFile != null) file(ksFile) else rootProject.file("signing/kitchenui.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "kitchenui"
            keyAlias = System.getenv("KEY_ALIAS") ?: "kitchenui"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "kitchenui"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
