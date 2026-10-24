plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.blocker"
}

dependencies {
    implementation(project(":core:data"))
}
