plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.stats"
}

dependencies {
    implementation(project(":core:data"))
}
