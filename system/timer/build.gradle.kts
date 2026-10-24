plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin.system.timer"
}

dependencies {
    api(project(":core:timer"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
