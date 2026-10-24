plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin.core.network"
}

dependencies {
    api(project(":core:model"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
}
