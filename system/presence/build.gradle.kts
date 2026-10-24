plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin.system.presence"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
}
