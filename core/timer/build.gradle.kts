// Pure Kotlin. No Android dependency may be added here: the engine's tests must run on a plain JVM.
plugins {
    alias(libs.plugins.lockin.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
