import app.lockin.buildlogic.lib
import app.lockin.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature module: one user-facing area (screens + ViewModels).
 * Features depend on :core:* modules and interfaces, never on other features or on :system:*.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("lockin.android.library")
        pluginManager.apply("lockin.android.compose")
        pluginManager.apply("lockin.hilt")
        dependencies {
            add("implementation", project(":core:designsystem"))
            add("implementation", libs.lib("androidx-compose-material3"))
            add("implementation", libs.lib("androidx-compose-material-icons-core"))
            add("implementation", libs.lib("androidx-activity-compose"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
        }
    }
}
