plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.android.compose)
}

android {
    namespace = "app.lockin.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
}
