package app.lockin.core.data

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import app.lockin.core.database.AllowedAppDao
import app.lockin.core.database.AllowedAppEntity
import app.lockin.core.model.AllowedApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The apps the user allows during focus. Everything else is blocked while focus runs. */
@Singleton
class AllowedAppsRepository @Inject constructor(
    private val dao: AllowedAppDao,
) {
    val allowed: Flow<List<AllowedApp>> = dao.observeAll().map { list -> list.map { AllowedApp(it.packageName, it.label) } }

    val allowedPackages: Flow<Set<String>> = allowed.map { list -> list.mapTo(HashSet()) { it.packageName } }

    suspend fun setAllowed(app: AllowedApp, allowed: Boolean) {
        if (allowed) dao.upsert(AllowedAppEntity(app.packageName, app.label)) else dao.delete(app.packageName)
    }
}

data class LaunchableApp(
    val packageName: String,
    val label: String,
)

@Singleton
class InstalledApps @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm: PackageManager get() = context.packageManager

    /** Apps with a launcher icon, except Lock In itself, sorted by name. */
    suspend fun launchable(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Packages that are never blocked, whatever the user picks: Lock In, the phone dialer
     * (emergency calls), Settings (so permissions can be granted), the system UI and the permission
     * controller. The home screen is not on this list: during focus it is covered by the lock
     * screen, which shows the allowed apps as shortcuts instead (like Yeolpumta).
     */
    fun alwaysAllowed(): Set<String> {
        val result = HashSet<String>()
        result += context.packageName
        result += FIXED_SYSTEM_PACKAGES
        result += resolvers(Intent(Intent.ACTION_DIAL))
        result += resolvers(Intent(Settings.ACTION_SETTINGS))
        return result
    }

    fun icon(packageName: String, sizePx: Int): Bitmap? = runCatching {
        val drawable = pm.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()

    fun label(packageName: String): String = runCatching {
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun resolvers(intent: Intent): List<String> {
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
    }

    private companion object {
        val FIXED_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.emergency",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
        )
    }
}

/** The two special permissions app blocking needs, and where to grant them. */
@Singleton
class BlockerPermissions @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Usage access: lets Lock In see which app is in front. */
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** Display over other apps: lets Lock In cover a blocked app. */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun usageAccessSettings(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlaySettings(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}
