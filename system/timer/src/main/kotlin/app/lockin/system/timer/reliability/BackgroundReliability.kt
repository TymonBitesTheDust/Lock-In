package app.lockin.system.timer.reliability

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that decides whether Lock In survives in the background on *this* phone, in one
 * place, so onboarding and a "Timer keeps stopping?" help screen can show the user what to fix.
 *
 * Design rule: none of this is required for correct study time (the engine guarantees that).
 * These settings only decide whether the notification, break alerts and (later) the app
 * blocker keep running while the screen is off.
 */
@Singleton
class BackgroundReliability @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Report(
        val notificationsEnabled: Boolean,
        /** Null below Android 16 or when the platform build doesn't support Live Updates yet. */
        val liveUpdatesAllowed: Boolean?,
        val exactAlarmsAllowed: Boolean,
        val ignoringBatteryOptimizations: Boolean,
        /** The user (or an OEM preset) put Lock In under "Restricted" battery usage. */
        val backgroundRestricted: Boolean,
        val manufacturer: String,
        val hasKnownOemBatterySettings: Boolean,
        /** How the previous process ended, when the platform records it (API 30+). */
        val lastExit: LastExit?,
    )

    data class LastExit(val reason: Int, val description: String?, val timestampMs: Long) {
        /** Exits that typically mean an OEM task killer or a user force-stop, not normal memory pressure. */
        val looksLikeForcedKill: Boolean
            get() = reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                reason == ApplicationExitInfo.REASON_SIGNALED ||
                reason == ApplicationExitInfo.REASON_OTHER
    }

    fun report(): Report = Report(
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        liveUpdatesAllowed = liveUpdatesAllowed(),
        exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        hasKnownOemBatterySettings = oemBatterySettingsIntents().isNotEmpty(),
        lastExit = lastExit(),
    )

    // region Settings intents (start these from an Activity)

    /**
     * The system list where the user can set Lock In to "Unrestricted". We deliberately do not use
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: it needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
     * which Play only allows when the app's core function is broken without it.
     */
    fun batteryOptimizationSettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun exactAlarmSettings(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())
        } else {
            null
        }

    fun liveUpdateSettings(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Intent(Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            null
        }

    fun appDetailsSettings(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    /**
     * Vendor screens for autostart / background protection, most specific first. These component
     * names change between OS releases, so treat the list as data (move it to remote config) and
     * always fall back to [appDetailsSettings]. Reference: dontkillmyapp.com.
     */
    fun oemBatterySettingsIntents(): List<Intent> {
        val vendor = Build.MANUFACTURER.orEmpty().lowercase()
        return OEM_COMPONENTS
            .filter { (prefix, _) -> vendor.startsWith(prefix) }
            .map { (_, component) -> Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    /** Tries each intent in order; returns false if none could be opened. */
    fun launchFirstAvailable(activity: Activity, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    // endregion

    @SuppressLint("NewApi") // Live Updates APIs arrived in an Android 16 feature drop; guarded below.
    private fun liveUpdatesAllowed(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return try {
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        } catch (e: NoSuchMethodError) {
            null
        }
    }

    private fun lastExit(): LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val info = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull() ?: return null
        return LastExit(reason = info.reason, description = info.description, timestampMs = info.timestamp)
    }

    private fun packageUri(): Uri = Uri.fromParts("package", context.packageName, null)

    private companion object {
        val OEM_COMPONENTS: List<Pair<String, ComponentName>> = listOf(
            "xiaomi" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            "redmi" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            "poco" to ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            "huawei" to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            "huawei" to ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            "honor" to ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            "oppo" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            "realme" to ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            "oneplus" to ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            "vivo" to ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            "vivo" to ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            "samsung" to ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            "asus" to ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"),
        )
    }
}
