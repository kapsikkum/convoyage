package app.convoyage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.Space
import app.convoyage.design.sheetPanel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The three resting heights of the convoy sheet. */
enum class Detent { Peek, Half, Full }

/** Height of the sheet at [Detent.Peek], not counting the navigation bar. The right rail sits just above it. */
val SheetPeekHeight = 128.dp

/** Pure: which detent the sheet should settle on, given where it is and how fast it was thrown (px, px/s, down positive). */
fun settleDetent(current: Float, velocityDown: Float, peek: Float, half: Float, full: Float): Detent {
    val projected = current - velocityDown * 0.18f
    return listOf(Detent.Peek to peek, Detent.Half to half, Detent.Full to full).minByOrNull { abs(it.second - projected) }!!.first
}

/**
 * A bottom sheet with three detents. Drag the handle or any non-scrolling part to move it; scrolling content inside
 * hands its overscroll to the sheet (pull down at the top of a list to collapse, push up to expand). It swallows every
 * touch inside its bounds, so a drag on the sheet can never pan the map underneath.
 *
 * Top-left chamfer only; the bottom is flush with the screen edge.
 *
 * @param containerHeightPx height of the screen area the sheet may fill; [topReservePx] stays free above it.
 * @param content gets whether the sheet is taller than its peek (so a header can show more).
 */
@Composable
fun BoxScope.ConvoySheet(
    detent: Detent,
    onDetentChange: (Detent) -> Unit,
    containerHeightPx: Int,
    topReservePx: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(expanded: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val navPx = WindowInsets.navigationBars.getBottom(density) + with(density) { LocalBottomChrome.current.roundToPx() }
    val peekPx = with(density) { SheetPeekHeight.toPx() } + navPx
    val fullPx = (containerHeightPx - topReservePx).toFloat().coerceAtLeast(peekPx + 1f)
    val halfPx = (containerHeightPx * 0.52f).coerceIn(peekPx + 1f, fullPx)
    fun pxOf(d: Detent) = when (d) { Detent.Peek -> peekPx; Detent.Half -> halfPx; Detent.Full -> fullPx }

    val h = remember { Animatable(peekPx) }
    val scope = rememberCoroutineScope()
    val spec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    LaunchedEffect(detent, peekPx, halfPx, fullPx) {
        val target = pxOf(detent)
        if (containerHeightPx == 0) h.snapTo(peekPx) else h.animateTo(target, spec)
    }

    fun moveBy(growPx: Float): Float {
        val next = (h.value + growPx).coerceIn(peekPx, fullPx)
        val applied = next - h.value
        if (applied != 0f) scope.launch(start = CoroutineStart.UNDISPATCHED) { h.snapTo(next) }
        return applied
    }

    fun settle(velocityDown: Float) {
        val target = settleDetent(h.value, velocityDown, peekPx, halfPx, fullPx)
        onDetentChange(target)
        scope.launch { h.animateTo(pxOf(target), spec) }
    }

    val dragState = rememberDraggableState { dy -> moveBy(-dy) }
    val nested = remember(peekPx, halfPx, fullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Finger moving up while the sheet is not full: grow the sheet first, list scrolls with what is left.
                if (available.y < 0f && h.value < fullPx) return Offset(0f, -moveBy(-available.y))
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Finger moving down and the list is already at its top: shrink the sheet.
                if (available.y > 0f && h.value > peekPx) return Offset(0f, -moveBy(-available.y))
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val resting = listOf(peekPx, halfPx, fullPx).any { abs(it - h.value) < 2f }
                if (resting) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    val expanded by remember { derivedStateOf { h.value > peekPx + with(density) { 24.dp.toPx() } } }
    val c = Cv.colors

    Box(
        modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding()
            .layout { measurable, constraints ->
                val hh = h.value.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, hh))
                layout(constraints.maxWidth, hh) { placeable.place(0, 0) }
            }
            .sheetPanel(c.surface100, c.lineStrong)
            // Swallow taps and drags so nothing reaches the map through the sheet.
            .pointerInput(Unit) { detectTapGestures { } }
            .draggable(dragState, Orientation.Vertical, onDragStopped = { v -> settle(v) })
            .nestedScroll(nested),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            // Handle: tap toggles peek/half, drag moves the sheet.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                        onClick = { val next = if (detent == Detent.Peek) Detent.Half else Detent.Peek; onDetentChange(next) },
                    )
                    .semantics { contentDescription = if (detent == Detent.Peek) "Expand sheet" else "Collapse sheet" },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.padding(top = Space.s2).width(40.dp).height(4.dp).clip(CutSShape).background(c.lineStrong, CutSShape))
            }
            Column(Modifier.weight(1f).padding(bottom = with(density) { navPx.toDp() })) { content(expanded) }
        }
    }
}

/** Convenience for callers that want the detent as saveable state. */
fun nextDetentUp(d: Detent) = when (d) { Detent.Peek -> Detent.Half; Detent.Half -> Detent.Full; Detent.Full -> Detent.Full }
