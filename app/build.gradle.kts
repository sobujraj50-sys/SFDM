plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sfdm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sfdm.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Stable signing key from CI secrets (SFDM_KEYSTORE_PATH / SFDM_KEYSTORE_PASSWORD).
    // Without it, builds fall back to the per-machine debug key.
    val keystorePath = System.getenv("SFDM_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SFDM_KEYSTORE_PASSWORD")
    val hasStableKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && file(keystorePath).exists()
    signingConfigs {
        if (hasStableKey) {
            create("sfdm") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "sfdm"
                keyPassword = keystorePassword
            }
        }
    }

    // Two editions: "full" is distributed as an APK by the developer,
    // "play" is the Google Play edition with Play-restricted features turned off.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "PLAY_EDITION", "false")
        }
        create("play") {
            dimension = "edition"
            versionNameSuffix = "-play"
            buildConfigField("boolean", "PLAY_EDITION", "true")
        }
    }

    buildTypes {
        debug {
            if (hasStableKey) signingConfig = signingConfigs.getByName("sfdm")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasStableKey) "sfdm" else "debug")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
