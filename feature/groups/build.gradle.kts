plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.groups"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
}
