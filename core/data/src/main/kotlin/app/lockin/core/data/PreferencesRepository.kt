package app.lockin.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.lockin.core.model.AccentColor
import app.lockin.core.model.StudyPreferences
import app.lockin.core.model.ThemeMode
import app.lockin.core.timer.TimerMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.studyPreferences by preferencesDataStore(name = "study_prefs")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val preferences: Flow<StudyPreferences> = context.studyPreferences.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)
        .distinctUntilChanged()

    suspend fun current(): StudyPreferences = preferences.first()

    suspend fun update(transform: (StudyPreferences) -> StudyPreferences) {
        context.studyPreferences.edit { prefs ->
            val next = transform(read(prefs))
            prefs[FOCUS_MIN] = next.pomodoroFocusMin.coerceIn(1, 180)
            prefs[SHORT_BREAK_MIN] = next.shortBreakMin.coerceIn(1, 60)
            prefs[LONG_BREAK_MIN] = next.longBreakMin.coerceIn(1, 90)
            prefs[LONG_BREAK_EVERY] = next.longBreakEvery.coerceIn(1, 12)
            prefs[AUTO_BREAKS] = next.autoStartBreaks
            prefs[AUTO_FOCUS] = next.autoStartFocus
            prefs[DAY_START_HOUR] = next.dayStartHour.coerceIn(0, 23)
            prefs[BLOCKING] = next.blockingEnabled
            prefs[PREFER_POMODORO] = next.preferPomodoro
            prefs[THEME_MODE] = next.themeMode.name
            prefs[ACCENT] = next.accent.name
        }
    }

    private fun read(prefs: Preferences): StudyPreferences {
        val defaults = StudyPreferences()
        return StudyPreferences(
            pomodoroFocusMin = prefs[FOCUS_MIN] ?: defaults.pomodoroFocusMin,
            shortBreakMin = prefs[SHORT_BREAK_MIN] ?: defaults.shortBreakMin,
            longBreakMin = prefs[LONG_BREAK_MIN] ?: defaults.longBreakMin,
            longBreakEvery = prefs[LONG_BREAK_EVERY] ?: defaults.longBreakEvery,
            autoStartBreaks = prefs[AUTO_BREAKS] ?: defaults.autoStartBreaks,
            autoStartFocus = prefs[AUTO_FOCUS] ?: defaults.autoStartFocus,
            dayStartHour = prefs[DAY_START_HOUR] ?: defaults.dayStartHour,
            blockingEnabled = prefs[BLOCKING] ?: defaults.blockingEnabled,
            preferPomodoro = prefs[PREFER_POMODORO] ?: defaults.preferPomodoro,
            // Stored by name; an unknown name (from a newer or older version) falls back to the default.
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[THEME_MODE] } ?: defaults.themeMode,
            accent = AccentColor.entries.firstOrNull { it.name == prefs[ACCENT] } ?: defaults.accent,
        )
    }

    private companion object {
        val FOCUS_MIN = intPreferencesKey("pomodoro_focus_min")
        val SHORT_BREAK_MIN = intPreferencesKey("pomodoro_short_break_min")
        val LONG_BREAK_MIN = intPreferencesKey("pomodoro_long_break_min")
        val LONG_BREAK_EVERY = intPreferencesKey("pomodoro_long_break_every")
        val AUTO_BREAKS = booleanPreferencesKey("pomodoro_auto_breaks")
        val AUTO_FOCUS = booleanPreferencesKey("pomodoro_auto_focus")
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        val BLOCKING = booleanPreferencesKey("blocking_enabled")
        val PREFER_POMODORO = booleanPreferencesKey("prefer_pomodoro")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
    }
}

/** The Pomodoro mode a new session starts with, from the user's settings. */
fun StudyPreferences.pomodoroMode(): TimerMode.Pomodoro = TimerMode.Pomodoro(
    focusMs = pomodoroFocusMin.coerceAtLeast(1) * 60_000L,
    shortBreakMs = shortBreakMin.coerceAtLeast(1) * 60_000L,
    longBreakMs = longBreakMin.coerceAtLeast(1) * 60_000L,
    longBreakEvery = longBreakEvery.coerceAtLeast(1),
    autoStartBreaks = autoStartBreaks,
    autoStartFocus = autoStartFocus,
)
