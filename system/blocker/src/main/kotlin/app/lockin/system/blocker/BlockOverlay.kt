package app.lockin.system.blocker

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MainThread
import app.lockin.core.model.AccentColor
import app.lockin.core.model.ThemeMode
import app.lockin.core.model.isDark
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How the lock screen looks: the accent and light/dark the user picked in Settings. */
data class LockScreenStyle(val accent: AccentColor, val mode: ThemeMode)

/**
 * The full-screen lock shown during focus over anything that isn't allowed, the home screen
 * included. Like Yeolpumta's, it doubles as a launcher: the apps the user allowed are shortcuts
 * here, plus the phone dialer, and the button at the bottom goes back to Lock In.
 *
 * Plain Views (no Compose) because it lives in a WindowManager window without an Activity. It
 * takes all touches; Back goes back to Lock In.
 */
@Singleton
class BlockOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var shownKey: Any? = null

    @Volatile
    private var handoffUntil = 0L

    val isShowing: Boolean get() = root != null

    /**
     * True for a moment after the lock screen opened an app. The opened app takes a little while to
     * reach the front, and showing the lock again in between would cover it.
     */
    val isHandingOff: Boolean get() = SystemClock.elapsedRealtime() < handoffUntil

    @MainThread
    fun show(subject: String, allowedPackages: Set<String>, style: LockScreenStyle) {
        val dark = style.mode.isDark(systemDark())
        val key = listOf(subject, allowedPackages, style, dark)
        if (root != null && key == shownKey) return
        if (!Settings.canDrawOverlays(context)) return
        hide()
        val view = buildView(subject, shortcuts(allowedPackages), Palette.of(style.accent, dark))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                root = view
                shownKey = key
            }
    }

    @MainThread
    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        shownKey = null
    }

    // region Content

    private class Shortcut(val label: String, val icon: Drawable?, val intent: Intent, val isDialer: Boolean = false)

    /** The dialer first (calls must always be reachable), then the allowed apps by name. */
    private fun shortcuts(allowedPackages: Set<String>): List<Shortcut> {
        val pm = context.packageManager
        val dialer = runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        val result = ArrayList<Shortcut>()
        if (dialer != null) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(dialer, 0)).toString() }.getOrDefault("Phone")
            val icon = runCatching { pm.getApplicationIcon(dialer) }.getOrNull()
            result += Shortcut(label, icon, Intent(Intent.ACTION_DIAL).setPackage(dialer), isDialer = true)
        }
        result += allowedPackages
            .filter { it != dialer && it != context.packageName }
            .mapNotNull { pkg ->
                val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                Shortcut(label, runCatching { pm.getApplicationIcon(pkg) }.getOrNull(), launch)
            }
            .sortedBy { it.label.lowercase() }
        return result
    }

    private fun buildView(subject: String, shortcuts: List<Shortcut>, palette: Palette): View {
        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) openLockIn()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.setBackgroundColor(palette.background)
        container.isClickable = true
        container.isFocusable = true
        // Below Android 11 the window can reach under the system bars; keep content out from under them.
        container.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }
        content.addView(
            text("Locked in", palette.onBackground, 28f, bold = false).apply { gravity = Gravity.CENTER },
        )
        content.addView(
            text(
                "You're focusing on $subject. Only these apps work until your next break.",
                palette.muted,
                16f,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(32))
            },
        )
        content.addView(appGrid(shortcuts, palette))
        if (shortcuts.none { !it.isDialer }) {
            content.addView(
                text(
                    "No apps are allowed yet. Pick them in Lock In → Settings → Focus protection.",
                    palette.muted,
                    14f,
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, 0)
                },
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        page.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom bar: one big button back to the timer, like Yeolpumta's.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bar)
            setPadding(dp(24), dp(16), dp(24), dp(20))
        }
        bar.addView(primaryButton("Back to Lock In", palette) { openLockIn() })
        page.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        container.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun appGrid(shortcuts: List<Shortcut>, palette: Palette): View {
        val grid = GridLayout(context).apply {
            columnCount = COLUMNS
            useDefaultMargins = false
        }
        shortcuts.forEach { shortcut ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = shortcut.label
                // Touch feedback: a rounded ripple in the text colour at 12% opacity.
                background = RippleDrawable(
                    ColorStateList.valueOf((palette.onBackground and 0x00FFFFFF) or 0x1F000000),
                    null,
                    GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(0xFF000000.toInt())
                    },
                )
                setOnClickListener { open(shortcut.intent) }
            }
            cell.addView(
                ImageView(context).apply {
                    setImageDrawable(shortcut.icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(56), dp(56)),
            )
            cell.addView(
                text(shortcut.label, palette.onBackground, 13f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            grid.addView(
                cell,
                GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                ).apply { width = 0 },
            )
        }
        // Pad a short last row so the cells keep their width instead of stretching.
        repeat((COLUMNS - shortcuts.size % COLUMNS) % COLUMNS) {
            grid.addView(
                View(context),
                GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0
                    height = 0
                },
            )
        }
        return grid
    }

    private fun text(value: String, color: Int, sizeSp: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun primaryButton(label: String, palette: Palette, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(palette.onPrimary)
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(palette.primary)
        }
        minHeight = dp(56)
        setPadding(dp(24), dp(14), dp(24), dp(14))
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // endregion

    private fun open(intent: Intent) {
        handoffUntil = SystemClock.elapsedRealtime() + HANDOFF_MS
        val started = runCatching {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (started) hide() else handoffUntil = 0L
    }

    private fun openLockIn() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        open(launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private fun systemDark(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    /** The app's neutral surfaces (core/designsystem Theme.kt) with the user's accent on top. */
    private class Palette(
        val background: Int,
        val onBackground: Int,
        val muted: Int,
        val bar: Int,
        val primary: Int,
        val onPrimary: Int,
    ) {
        companion object {
            fun of(accent: AccentColor, dark: Boolean): Palette {
                val tones = accent.tonesFor(dark)
                return if (dark) {
                    Palette(0xFF0F1216.toInt(), 0xFFE2E4E9.toInt(), 0xFFB3BAC5.toInt(), 0xFF171B21.toInt(), tones.primary, tones.onPrimary)
                } else {
                    Palette(0xFFF7F8FA.toInt(), 0xFF1A1C1F.toInt(), 0xFF44494F.toInt(), 0xFFEDEFF3.toInt(), tones.primary, tones.onPrimary)
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 4
        const val HANDOFF_MS = 3_000L
    }
}
