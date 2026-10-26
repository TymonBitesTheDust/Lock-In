plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.android.compose)
}

android {
    namespace = "app.lockin.core.designsystem"
}

dependencies {
    // AccentColor and ThemeMode live in core:model so the plain-View lock screen can use them too.
    api(project(":core:model"))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
}
