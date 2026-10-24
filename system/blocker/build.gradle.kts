plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin.system.blocker"
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.android)
}
