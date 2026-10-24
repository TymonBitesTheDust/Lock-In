package app.lockin.system.blocker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The full-screen lock shown over a blocked app. Plain Views (no Compose) because it lives in a
 * WindowManager window without an Activity. It takes all touches; Back goes to the home screen.
 */
@Singleton
class BlockOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var message: TextView? = null

    val isShowing: Boolean get() = root != null

    @MainThread
    fun show(appLabel: String, subject: String) {
        val text = "$appLabel is blocked while you focus on $subject.\nIt unlocks on your next break."
        if (root != null) {
            message?.text = text
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        val view = buildView(text)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(view, params) }
            .onSuccess { root = view }
    }

    @MainThread
    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        message = null
    }

    private fun buildView(text: String): View {
        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) goHome()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.setBackgroundColor(Color.parseColor("#F70F1216"))
        container.isClickable = true
        container.isFocusable = true

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        column.addView(
            TextView(context).apply {
                this.text = "Locked in"
                setTextColor(Color.parseColor("#E2E4E9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                gravity = Gravity.CENTER
            },
        )
        val body = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#B3BAC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(32))
        }
        message = body
        column.addView(body)
        column.addView(button("Back to Lock In", primary = true) { openLockIn() })
        column.addView(View(context), LinearLayout.LayoutParams(1, dp(12)))
        column.addView(button("Go to home screen", primary = false) { goHome() })

        container.addView(
            column,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        return container
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Color.parseColor(if (primary) "#0B1D36" else "#D5E3FF"))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (primary) setColor(Color.parseColor("#9DC3FF")) else setStroke(dp(1), Color.parseColor("#59616D"))
        }
        minHeight = dp(48)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun openLockIn() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(launch) }
        hide()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(home) }
        hide()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
