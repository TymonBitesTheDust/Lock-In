plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.subjects"
}

dependencies {
    implementation(project(":core:data"))
}
