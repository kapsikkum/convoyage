package app.convoyage.map

import android.graphics.PointF
import android.view.Gravity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.convoyage.BuildConfig
import app.convoyage.design.MapPin
import app.convoyage.design.PinKind
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** A pin drawn over the map in Compose (so it can reuse the design-system pin). */
data class MapPinData(
    val id: String,
    val kind: PinKind,
    val label: String,
    val lat: Double,
    val lng: Double,
    val headingDeg: Double? = null,
    val tag: String? = null,
)

/** Where the camera should look when nothing has a position yet (Melbourne, like the mockups). */
private val DEFAULT_CENTER = LatLng(-37.8136, 144.9631)

/**
 * Full-bleed dark MapLibre map (OpenFreeMap liberty, darkened at runtime by [MapNightStyle]).
 * Pins are Compose overlays projected from lat/lng on every camera move.
 *
 * @param me our own position; the camera follows it while [follow] is true.
 * @param route convoy route as [lng, lat] stops, drawn as the signal line.
 * @param recenterTick increment to re-enable following and fly to [me].
 */
@Composable
fun ConvoyMap(
    modifier: Modifier = Modifier,
    pins: List<MapPinData>,
    me: LatLng?,
    route: List<List<Double>>,
    buildings3d: Boolean,
    follow: Boolean,
    recenterTick: Int,
    onUserMoved: () -> Unit,
    bottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var extrusions by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val onUserMovedNow by rememberUpdatedState(onUserMoved)
    val initial3d = remember { buildings3d }

    DisposableEffect(lifecycle, mapView) {
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // The view's own onStart/onResume replay is handled by the observer registered above.
        onDispose {
            lifecycle.removeObserver(observer)
            val st = lifecycle.currentState
            if (st.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (st.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
            m.setCameraPosition(CameraPosition.Builder().target(me ?: DEFAULT_CENTER).zoom(if (me != null) 15.0 else 11.0).tilt(if (initial3d) 60.0 else 0.0).build())
            m.addOnCameraMoveListener { tick++ }
            m.addOnCameraIdleListener { tick++ }
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) = onUserMovedNow()
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) = Unit
            })
            m.setStyle(Style.Builder().fromUri(BuildConfig.MAP_STYLE_URL)) { s ->
                extrusions = MapNightStyle.apply(s, initial3d)
                style = s
            }
        }
    }

    // Keep the map clear of the bottom sheet so "centre" means centre of the visible part.
    LaunchedEffect(map, bottomPadding) {
        val m = map ?: return@LaunchedEffect
        val bottom = with(density) { bottomPadding.toPx() }.toInt()
        m.setPadding(0, 0, 0, bottom)
        m.uiSettings.setAttributionMargins(with(density) { 12.dp.toPx() }.toInt(), 0, 0, bottom + with(density) { 4.dp.toPx() }.toInt())
    }

    LaunchedEffect(style, route) {
        val s = style ?: return@LaunchedEffect
        MapNightStyle.setRoute(s, route)
    }

    LaunchedEffect(style, extrusions, buildings3d) {
        val s = style ?: return@LaunchedEffect
        MapNightStyle.setBuildings3d(s, extrusions, buildings3d)
        val m = map ?: return@LaunchedEffect
        val cam = m.cameraPosition
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam).tilt(if (buildings3d) 60.0 else 0.0).build()), 600)
    }

    LaunchedEffect(map, me, follow, recenterTick) {
        val m = map ?: return@LaunchedEffect
        if (follow && me != null) {
            val zoom = m.cameraPosition.zoom.coerceAtLeast(14.0)
            m.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).target(me).zoom(zoom).build()), 700)
        }
    }

    Box(modifier.onSizeChanged { size = it }) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        val m = map
        if (m != null && tick >= 0) {
            val bearing = m.cameraPosition.bearing
            pins.forEach { pin ->
                val p: PointF = m.projection.toScreenLocation(LatLng(pin.lat, pin.lng))
                if (p.x > -80 && p.y > -80 && p.x < size.width + 80 && p.y < size.height + 80) {
                    val half = with(density) { 28.dp.toPx() }
                    MapPin(
                        kind = pin.kind,
                        label = pin.label,
                        heading = pin.headingDeg?.let { (it - bearing).toFloat() },
                        tag = pin.tag,
                        modifier = Modifier.offset { IntOffset((p.x - half).toInt(), (p.y - half).toInt()) },
                    )
                }
            }
        }
    }
}
