plugins {
    alias(libs.plugins.lockin.android.application)
    alias(libs.plugins.lockin.android.compose)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin"

    defaultConfig {
        applicationId = "app.lockin"
        versionCode = 1
        versionName = "0.1.0"
    }

    // One shared debug key (keystore/debug.keystore), so every CI and local build signs the same
    // way and a new debug APK installs over the previous one. Never use this key for release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":system:timer"))
    implementation(project(":core:data"))
    implementation(project(":system:blocker"))
    implementation(project(":feature:timer"))
    implementation(project(":feature:planner"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:subjects"))
    implementation(project(":feature:blocker"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
}
