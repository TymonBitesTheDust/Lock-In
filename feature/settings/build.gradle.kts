plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.settings"
}

dependencies {
    implementation(project(":core:data"))
}
