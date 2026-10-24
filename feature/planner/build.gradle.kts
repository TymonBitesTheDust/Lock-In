plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.planner"
}

dependencies {
    implementation(project(":core:data"))
}
