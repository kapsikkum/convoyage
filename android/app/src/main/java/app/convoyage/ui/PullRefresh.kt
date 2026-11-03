package app.convoyage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import kotlin.math.roundToInt

/**
 * Pull-to-refresh without Material: drag the list down while it is at the top, release past the threshold to refresh.
 * The chip that follows the finger says what will happen.
 */
@Composable
fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    val max = threshold * 1.6f
    var pull by remember { mutableFloatStateOf(0f) }
    val refreshNow by rememberUpdatedState(onRefresh)
    val busy by rememberUpdatedState(refreshing)

    val connection = remember(threshold) {
        object : NestedScrollConnection {
            // The list scrolled up as far as it goes and the finger keeps pulling down: the leftover is the pull.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || busy || available.y <= 0f) return Offset.Zero
                pull = (pull + available.y * 0.5f).coerceAtMost(max)
                return Offset(0f, available.y)
            }

            // Pushing back up first undoes the pull.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || pull <= 0f || available.y >= 0f) return Offset.Zero
                val d = maxOf(available.y * 0.5f, -pull)
                pull += d
                return Offset(0f, d * 2f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull >= threshold && !busy) refreshNow()
                pull = 0f
                return Velocity.Zero
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        content()
        val shown = if (refreshing) threshold * 0.7f else pull
        if (shown > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .offset { IntOffset(0, (shown - 24.dp.toPx()).roundToInt()) }
                    .padding(top = Space.s2)
                    .cutPanel(Cv.colors.glass, Cv.colors.signal, CutSShape)
                    .padding(horizontal = Space.s3, vertical = Space.s2),
            ) {
                Eyebrow(
                    when {
                        refreshing -> "REFRESHING..."
                        pull >= threshold -> "RELEASE TO REFRESH"
                        else -> "PULL TO REFRESH"
                    },
                    color = Cv.colors.signal,
                )
            }
        }
    }
}
