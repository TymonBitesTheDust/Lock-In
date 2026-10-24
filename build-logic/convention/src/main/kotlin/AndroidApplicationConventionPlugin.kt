import app.lockin.buildlogic.LockInBuild
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no org.jetbrains.kotlin.android
 * plugin here. jvmTarget follows compileOptions.targetCompatibility by default.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = LockInBuild.COMPILE_SDK
            defaultConfig {
                minSdk = LockInBuild.MIN_SDK
                targetSdk = LockInBuild.TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = LockInBuild.JAVA_VERSION
                targetCompatibility = LockInBuild.JAVA_VERSION
            }
        }
    }
}
