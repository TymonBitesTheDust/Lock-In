plugins {
    alias(libs.plugins.lockin.android.application)
    alias(libs.plugins.lockin.android.compose)
    alias(libs.plugins.lockin.hilt)
}

android {
    namespace = "app.lockin"

    defaultConfig {
        applicationId = "app.lockin"
        versionCode = 3
        versionName = "0.3.0"
    }

    // One shared debug key (keystore/debug.keystore), so every CI and local build signs the same
    // way and a new debug APK installs over the previous one. Never use this key for release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        resValues = true
    }

    // Firebase without the google-services plugin: if app/google-services.json exists (git-ignored;
    // CI writes it from the GOOGLE_SERVICES_JSON secret), expose the values FirebaseApp reads at
    // startup. Without the file the app builds and runs fully offline; Groups explains it isn't set up.
    val googleServices = file("google-services.json")
    if (googleServices.exists()) {
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(googleServices) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val project = json["project_info"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val client = (json["client"] as List<Map<String, Any?>>).first { c ->
            val info = c["client_info"] as Map<String, Any?>
            (info["android_client_info"] as Map<String, Any?>)["package_name"] == "app.lockin"
        }
        @Suppress("UNCHECKED_CAST")
        val appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String
        @Suppress("UNCHECKED_CAST")
        val apiKey = (client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String
        defaultConfig {
            resValue("string", "google_app_id", appId)
            resValue("string", "google_api_key", apiKey)
            resValue("string", "project_id", project["project_id"] as String)
            resValue("string", "gcm_defaultSenderId", project["project_number"].toString())
            (project["storage_bucket"] as String?)?.let { resValue("string", "google_storage_bucket", it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":system:timer"))
    implementation(project(":core:data"))
    implementation(project(":system:blocker"))
    implementation(project(":feature:timer"))
    implementation(project(":feature:planner"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:subjects"))
    implementation(project(":feature:blocker"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:groups"))
    implementation(project(":system:presence"))
    implementation(project(":core:network"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
}
