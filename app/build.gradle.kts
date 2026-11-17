plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode=<run number>; local builds default to 1.
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.kitchenui.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kitchenui.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "1.0.$ciVersionCode"
        buildConfigField("String", "UPDATE_REPO", "\"ProfoundBandit86/KitchenUI\"")
        // Bliss OS on the OptiPlex is x86_64. One ABI keeps VLC's native code (and each update) small.
        ndk { abiFilters += listOf("x86_64") }
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
            // R8 optimizes Compose and the libraries (smoother scrolling, faster start);
            // our own code and VLC are kept as-is in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Installs Compose's precompiled startup and scrolling profiles on sideloaded installs (no Play Store)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Streaming (ported from Flux TV)
    // Built-in VLC: plays nearly every codec and container, with its own software decoders
    implementation("org.videolan.android:libvlc-all:3.7.6")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
