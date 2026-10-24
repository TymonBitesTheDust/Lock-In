package app.lockin.system.timer.persistence

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.lockin.core.timer.TimerSnapshotCodec
import app.lockin.core.timer.TimerSnapshotStore
import app.lockin.core.timer.TimerState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.timerSnapshotStore by preferencesDataStore(
    name = "timer_snapshot",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The live session, one small file. Written on every transition and on each heartbeat
 * (about once a minute while running), which is well within what DataStore handles comfortably.
 * Finished focus time does not live here; it goes to Room as segments.
 */
@Singleton
class DataStoreTimerSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TimerSnapshotStore {

    override suspend fun read(): TimerState? {
        val prefs = context.timerSnapshotStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        return TimerSnapshotCodec.decode(prefs.asMap().mapKeys { (key, _) -> key.name })
    }

    override suspend fun write(state: TimerState) {
        val encoded = TimerSnapshotCodec.encode(state)
        context.timerSnapshotStore.edit { prefs ->
            prefs.clear()
            for ((key, value) in encoded) {
                when (value) {
                    is Int -> prefs[intPreferencesKey(key)] = value
                    is Long -> prefs[longPreferencesKey(key)] = value
                    is String -> prefs[stringPreferencesKey(key)] = value
                    is Boolean -> prefs[booleanPreferencesKey(key)] = value
                    else -> error("Unsupported snapshot value type for '$key': ${value::class}")
                }
            }
        }
    }
}
