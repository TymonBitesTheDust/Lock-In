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
    implementation(project(":feature:timer"))

    implementation(libs.androidx.activity.compose)
}
