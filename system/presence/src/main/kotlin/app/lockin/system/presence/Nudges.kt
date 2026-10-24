package app.lockin.system.presence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.lockin.core.network.AuthRepository
import app.lockin.core.network.GroupRepository
import app.lockin.core.network.Nudge
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** "Shake to wake": a nudge arrives as a high-priority notification with a shaking vibration. */
object NudgeNotifier {
    private const val CHANNEL = "nudges"

    /** Nudges older than this are marked seen without a notification (the moment has passed). */
    const val FRESH_MS = 2 * 3_600_000L

    private val SHAKE = longArrayOf(0, 120, 80, 120, 80, 120, 80, 320)

    @SuppressLint("MissingPermission") // Checked below.
    fun post(context: Context, nudge: Nudge) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Nudges from your groups")
                .setDescription("When a friend shakes you awake to study")
                .setVibrationEnabled(true)
                .setVibrationPattern(SHAKE)
                .build(),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = launch?.let {
            PendingIntent.getActivity(context, 3001, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("${nudge.fromName} is shaking you awake")
            .setContentText(if (nudge.groupName.isNotBlank()) "${nudge.groupName} is studying. Time to lock in." else "Time to lock in.")
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(SHAKE)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        manager.notify(nudge.id.hashCode(), notification)
    }
}

/**
 * Listens for nudges while the process is alive (always true during a session, thanks to the
 * timer's foreground service) and keeps the 15-minute background check scheduled while signed in.
 */
@Singleton
class NudgeListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val groups: GroupRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started || !auth.isAvailable) return
        started = true
        scope.launch {
            auth.account
                .flatMapLatest { account ->
                    if (account == null) emptyFlow() else groups.pendingNudges(account.uid).map { account.uid to it }
                }
                .collect { (uid, nudges) ->
                    val now = System.currentTimeMillis()
                    nudges.forEach { nudge ->
                        if (now - nudge.sentAtMs < NudgeNotifier.FRESH_MS) NudgeNotifier.post(context, nudge)
                        runCatching { groups.markNudgeSeen(uid, nudge.id) }.onFailure { Log.w("LockInNudges", "mark seen", it) }
                    }
                }
        }
        scope.launch {
            auth.account.map { it != null }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) NudgeCheckWorker.schedule(context) else NudgeCheckWorker.cancel(context)
            }
        }
    }
}

/** Background fallback when the app isn't running: checks for nudges every 15 minutes. */
class NudgeCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val configured = FirebaseApp.getApps(applicationContext).isNotEmpty() ||
            runCatching { FirebaseApp.initializeApp(applicationContext) != null }.getOrDefault(false)
        if (!configured) return Result.success()
        val user = FirebaseAuth.getInstance().currentUser ?: return Result.success()
        return try {
            val items = FirebaseFirestore.getInstance()
                .collection("nudges").document(user.uid).collection("items")
                .whereEqualTo("seen", false)
            val snapshot = Tasks.await(items.get(), 30, TimeUnit.SECONDS)
            val now = System.currentTimeMillis()
            snapshot.documents.forEach { doc ->
                val nudge = doc.toNudge()
                if (now - nudge.sentAtMs < NudgeNotifier.FRESH_MS) NudgeNotifier.post(applicationContext, nudge)
                Tasks.await(doc.reference.update("seen", true), 10, TimeUnit.SECONDS)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun DocumentSnapshot.toNudge() = Nudge(
        id = id,
        fromUid = getString("fromUid").orEmpty(),
        fromName = getString("fromName").orEmpty(),
        groupName = getString("groupName").orEmpty(),
        sentAtMs = getTimestamp("sentAt")?.toDate()?.time ?: 0L,
    )

    companion object {
        private const val NAME = "nudge-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequest.Builder(NudgeCheckWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
