plugins {
    alias(libs.plugins.lockin.android.feature)
}

android {
    namespace = "app.lockin.feature.timer"
}

dependencies {
    // The TimerController interface only. Its implementation is bound by :system:timer in the app graph.
    implementation(project(":core:timer"))
    implementation(project(":core:data"))
}
