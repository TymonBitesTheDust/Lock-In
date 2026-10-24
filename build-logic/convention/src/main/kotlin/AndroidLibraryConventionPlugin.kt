import app.lockin.buildlogic.LockInBuild
import app.lockin.buildlogic.lib
import app.lockin.buildlogic.libs
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = LockInBuild.COMPILE_SDK
            defaultConfig {
                minSdk = LockInBuild.MIN_SDK
            }
            compileOptions {
                sourceCompatibility = LockInBuild.JAVA_VERSION
                targetCompatibility = LockInBuild.JAVA_VERSION
            }
            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }
        dependencies {
            add("testImplementation", libs.lib("junit4"))
            add("testImplementation", libs.lib("kotlin-test-junit"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        }
    }
}
