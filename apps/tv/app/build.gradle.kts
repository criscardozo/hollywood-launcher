plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.cardozo.hollywood.launcher"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.cardozo.hollywood.launcher"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded only: the debug key is enough until there is a reason for a real one.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jellyfin.core)
    // The Jellyfin SDK logs through SLF4J but ships no binding; without one it crashes on first use.
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // adb to the TV itself, for the Reiniciar tile: an app cannot reboot the TV any other way.
    implementation(libs.dadb)
    implementation(libs.kotlinx.serialization.json)
    // Settings page and API on the LAN (docs/launcher.md), so no secret ever lives in the code.
    implementation(libs.nanohttpd)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
