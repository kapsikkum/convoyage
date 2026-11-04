package app.convoyage.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.convoyage.api.Build
import app.convoyage.api.Vehicle
import app.convoyage.api.VehicleBadge
import app.convoyage.render.Ps1Spec
import app.convoyage.render.Ps1SurfaceView
import app.convoyage.render.ThumbSize

fun Ps1Spec.Companion.of(kind: String?, build: Build?): Ps1Spec =
    of(kind, build?.paint, build?.finish, build?.wheel, build?.rideHeightMm, build?.body)

fun Vehicle.ps1() = Ps1Spec.of(kind, build)

fun VehicleBadge.ps1() = Ps1Spec.of(kind, build)

/**
 * The live PS1 renderer: a GL surface that turns on its own and under a finger. [spec] can change on every
 * recomposition (the garage editor does) and the model follows.
 */
@Composable
fun Ps1Live(spec: Ps1Spec, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val view = remember { arrayOfNulls<Ps1SurfaceView>(1) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> view[0]?.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ctx -> Ps1SurfaceView(ctx).also { it.setSpec(spec); view[0] = it; if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.onResume() else it.onPause() } },
        update = { it.setSpec(spec) },
        onRelease = { view[0] = null },
        modifier = modifier,
    )
}

/**
 * A static PS1 render from the shared offscreen renderer, drawn with NEAREST filtering so the pixels stay square.
 * Shows [fallback] until the render arrives, and for good on a device with no GL ES 3.
 */
@Composable
fun Ps1Thumb(
    spec: Ps1Spec,
    size: ThumbSize,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable () -> Unit = {},
) {
    val thumbs = LocalContainer.current.thumbs
    var image by remember(spec, size) { mutableStateOf(thumbs.cached(spec, size)) }
    var failed by remember(spec, size) { mutableStateOf(false) }
    LaunchedEffect(spec, size) {
        if (image == null) {
            image = thumbs.get(spec, size)
            failed = image == null
        }
    }
    val img = image
    if (img != null) {
        Image(img, contentDescription = null, modifier = modifier, contentScale = contentScale, alignment = Alignment.Center, filterQuality = FilterQuality.None)
    } else if (failed) {
        fallback()
    }
}
