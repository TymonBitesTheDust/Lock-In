package app.lockin.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import app.lockin.core.designsystem.R

/** Icons missing from material-icons-core, as vector drawables (Material Icons paths, Apache 2.0). */
object LockInIcons {
    val timer: Painter @Composable get() = painterResource(R.drawable.lockin_ic_timer)
    val stats: Painter @Composable get() = painterResource(R.drawable.lockin_ic_stats)
    val groups: Painter @Composable get() = painterResource(R.drawable.lockin_ic_groups)
    val planner: Painter @Composable get() = painterResource(R.drawable.lockin_ic_planner)
}
