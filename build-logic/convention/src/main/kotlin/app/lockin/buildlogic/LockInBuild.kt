package app.lockin.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

object LockInBuild {
    /** Android 17. Stable since June 2026; Hilt's Compose artifacts already require it. */
    const val COMPILE_SDK = 37

    /**
     * Play has required API 36 for new apps and updates since 31 Aug 2026. We target 37 so the
     * Android 17 changes (background-audio hardening, large-screen orientation rules) are handled
     * now rather than in a rush before the 2027 deadline.
     */
    const val TARGET_SDK = 37

    /** Android 8.0: notification channels everywhere; WorkManager 2.12 and Room 2.8 need 24+ anyway. */
    const val MIN_SDK = 26

    val JAVA_VERSION = JavaVersion.VERSION_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalArgumentException("No library '$alias' in libs.versions.toml") }
