package app.lockin.feature.blocker

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lockin.core.data.AllowedAppsRepository
import app.lockin.core.data.BlockerPermissions
import app.lockin.core.data.InstalledApps
import app.lockin.core.data.LaunchableApp
import app.lockin.core.data.PreferencesRepository
import app.lockin.core.model.AllowedApp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(val packageName: String, val label: String, val allowed: Boolean, val alwaysAllowed: Boolean)

data class BlockerState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val query: String = "",
    val apps: List<AppItem> = emptyList(),
    val allowedCount: Int = 0,
)

@HiltViewModel
class BlockerViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val allowedApps: AllowedAppsRepository,
    private val installedApps: InstalledApps,
    private val permissions: BlockerPermissions,
) : ViewModel() {

    private data class Permissions(val usage: Boolean, val overlay: Boolean)

    private val permissionState = MutableStateFlow(Permissions(false, false))
    private val launchable = MutableStateFlow<List<LaunchableApp>?>(null)
    private val alwaysAllowed = MutableStateFlow<Set<String>>(emptySet())
    private val query = MutableStateFlow("")
    private val icons = HashMap<String, ImageBitmap?>()

    val state: StateFlow<BlockerState> = combine(
        preferences.preferences,
        allowedApps.allowedPackages,
        launchable,
        combine(permissionState, alwaysAllowed) { p, a -> p to a },
        query,
    ) { prefs, allowed, apps, (perms, always), q ->
        val items = apps.orEmpty()
            .filter { q.isBlank() || it.label.contains(q, ignoreCase = true) }
            .map { AppItem(it.packageName, it.label, it.packageName in allowed, it.packageName in always) }
            .sortedWith(compareByDescending<AppItem> { it.allowed || it.alwaysAllowed }.thenBy { it.label.lowercase() })
        BlockerState(
            loading = apps == null,
            enabled = prefs.blockingEnabled,
            usageAccess = perms.usage,
            overlay = perms.overlay,
            query = q,
            apps = items,
            allowedCount = allowed.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockerState())

    init {
        viewModelScope.launch {
            launchable.value = installedApps.launchable()
            alwaysAllowed.value = withContext(Dispatchers.IO) { installedApps.alwaysAllowed() }
        }
        refreshPermissions()
    }

    /** Called on every resume: the user may be coming back from the system settings screens. */
    fun refreshPermissions() {
        permissionState.value = Permissions(permissions.hasUsageAccess(), permissions.canDrawOverlays())
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { preferences.update { it.copy(blockingEnabled = enabled) } }

    fun setAllowed(item: AppItem, allowed: Boolean) = viewModelScope.launch {
        allowedApps.setAllowed(AllowedApp(item.packageName, item.label), allowed)
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun usageAccessIntent(): Intent = permissions.usageAccessSettings()

    fun overlayIntent(): Intent = permissions.overlaySettings()

    suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? {
        icons[packageName]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { installedApps.icon(packageName, sizePx)?.asImageBitmap() }
        icons[packageName] = bitmap
        return bitmap
    }
}
