plugins {
    alias(libs.plugins.lockin.android.library)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin.core.database"
}

dependencies {
    // api: the Hilt graph in :app must see RoomDatabase as the supertype of LockInDatabase.
    api(libs.androidx.room.runtime)
    add("ksp", libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
}
