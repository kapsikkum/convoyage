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
import app.convoyage.data.Bbox
import app.convoyage.data.LatLngD
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
    val vehicle: app.convoyage.api.VehicleBadge? = null,
)

/** Where the camera should look when nothing has a position yet: Bathurst, NSW (home of Mount Panorama). */
private val DEFAULT_CENTER = LatLng(-33.4193, 149.5775)

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
    /** Called when the camera comes to rest, with the visible lat/lng box (for loading hazards). */
    onViewport: (Bbox) -> Unit = {},
    /** When two or more points are given, the camera frames them (the ride recap's route) instead of following [me]. */
    fitTo: List<LatLng> = emptyList(),
    /** False for a map inside a scrolling page: no pan/zoom/rotate gestures. */
    interactive: Boolean = true,
    /** A long press on the map (the leader sets the destination with it). Null = long press does nothing. */
    onLongPress: ((LatLng) -> Unit)? = null,
    /** A tap on the map (the route builder adds a waypoint with it). Null = taps do nothing. */
    onTap: ((LatLng) -> Unit)? = null,
    /** A road-following route; drawn instead of [route] when it has two or more points. */
    routeLine: List<LatLngD> = emptyList(),
    /** The route in coloured pieces (road quality); wins over [routeLine] when not empty. */
    routeSegments: List<Pair<List<LatLngD>, String>> = emptyList(),
    /** Graded roads (GeoJSON from GET /v1/roads) drawn under the route, or null for none. */
    roadQuality: String? = null,
    /** Off track: the point on the route to head for. A pulsing purple line runs to it from [me]. */
    rejoinTo: LatLngD? = null,
    /** Navigation: while following, point the camera this way (degrees clockwise from north) and tilt it. Null = keep the bearing. */
    followBearing: Double? = null,
    /** Navigation: zoom level while following. Null = keep the current zoom (at least 14). */
    followZoom: Double? = null,
    /** While following with no [followBearing] (stopped), turn back to north-up. */
    headingUpFallbackNorth: Boolean = false,
    /** Extra padding above the visible map, so the followed position sits in the lower part of the screen. */
    topPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var extrusions by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    // A pinch in progress: following pauses so the camera does not fight the fingers.
    var scaling by remember { mutableStateOf(false) }
    // The zoom the person pinched to while following; it wins over [followZoom] until they recentre.
    var userZoom by remember(recenterTick) { mutableStateOf<Double?>(null) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val onUserMovedNow by rememberUpdatedState(onUserMoved)
    val onViewportNow by rememberUpdatedState(onViewport)
    val onLongPressNow by rememberUpdatedState(onLongPress)
    val onTapNow by rememberUpdatedState(onTap)
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
            m.uiSettings.isRotateGesturesEnabled = interactive
            if (!interactive) m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
            m.setCameraPosition(CameraPosition.Builder().target(me ?: DEFAULT_CENTER).zoom(if (me != null) 15.0 else 11.0).tilt(if (initial3d) 60.0 else 0.0).build())
            m.addOnCameraMoveListener { tick++ }
            m.addOnCameraIdleListener {
                tick++
                runCatching {
                    val b = m.projection.visibleRegion.latLngBounds
                    onViewportNow(Bbox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
                }
            }
            m.addOnMapLongClickListener { latLng ->
                val handler = onLongPressNow
                if (handler != null) handler(latLng)
                handler != null
            }
            m.addOnMapClickListener { latLng ->
                val handler = onTapNow
                if (handler != null) handler(latLng)
                handler != null
            }
            // Pinch: keep following and zoom around the marker (the focal point below). Rotating by hand stops following.
            m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) { scaling = true }
                override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) = Unit
                override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                    userZoom = m.cameraPosition.zoom
                    scaling = false
                }
            })
            m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: org.maplibre.android.gestures.RotateGestureDetector) = onUserMovedNow()
                override fun onRotate(detector: org.maplibre.android.gestures.RotateGestureDetector) = Unit
                override fun onRotateEnd(detector: org.maplibre.android.gestures.RotateGestureDetector) = Unit
            })
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
    LaunchedEffect(map, bottomPadding, topPadding) {
        val m = map ?: return@LaunchedEffect
        val bottom = with(density) { bottomPadding.toPx() }.toInt()
        m.setPadding(0, with(density) { topPadding.toPx() }.toInt(), 0, bottom)
        m.uiSettings.setAttributionMargins(with(density) { 12.dp.toPx() }.toInt(), 0, 0, bottom + with(density) { 4.dp.toPx() }.toInt())
    }

    // Invisible collision boxes under every pin, so place labels make room for them.
    LaunchedEffect(style, pins) {
        val s = style ?: return@LaunchedEffect
        MapNightStyle.setColliders(s, pins.map { it.lat to it.lng })
    }

    LaunchedEffect(style, me, rejoinTo) {
        val s = style ?: return@LaunchedEffect
        MapNightStyle.setRejoin(s, me?.let { LatLngD(it.latitude, it.longitude) }.takeIf { rejoinTo != null }, rejoinTo)
    }
    val pulsing = rejoinTo != null
    LaunchedEffect(style, pulsing) {
        val s = style ?: return@LaunchedEffect
        if (!pulsing) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            val t = (System.nanoTime() - start) / 1e9
            // About one breath a second and a half, never fully gone.
            MapNightStyle.setRejoinOpacity(s, (0.6 + 0.4 * kotlin.math.sin(t * 2 * Math.PI / 1.4)).toFloat())
        }
    }

    LaunchedEffect(style, roadQuality) {
        val s = style ?: return@LaunchedEffect
        MapNightStyle.setRoadQuality(s, roadQuality)
    }

    LaunchedEffect(style, route, routeLine, routeSegments) {
        val s = style ?: return@LaunchedEffect
        if (routeSegments.isNotEmpty()) MapNightStyle.setRouteSegments(s, routeSegments)
        else if (routeLine.size >= 2) MapNightStyle.setRouteLine(s, routeLine) else MapNightStyle.setRoute(s, route)
    }

    LaunchedEffect(style, extrusions, buildings3d) {
        val s = style ?: return@LaunchedEffect
        MapNightStyle.setBuildings3d(s, extrusions, buildings3d)
        val m = map ?: return@LaunchedEffect
        if (fitTo.size >= 2) return@LaunchedEffect // a framed route (the recap) keeps its camera
        val cam = m.cameraPosition
        m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam).tilt(if (buildings3d) 60.0 else 0.0).build()), 600)
    }

    LaunchedEffect(map, me, follow, recenterTick, followBearing, followZoom, scaling, userZoom) {
        val m = map ?: return@LaunchedEffect
        // Zoom gestures pivot on the followed marker; free-roaming pinches pivot on the fingers.
        m.uiSettings.focalPoint = if (follow && me != null) runCatching { m.projection.toScreenLocation(me) }.getOrNull() else null
        if (scaling) return@LaunchedEffect
        if (follow && me != null) {
            val now = m.cameraPosition
            val target = userZoom ?: followZoom ?: now.zoom.coerceAtLeast(14.0)
            val near = now.target?.let { it.distanceTo(me) < 250 } == true && kotlin.math.abs(now.zoom - target) < 2.5
            // Speed-based zoom changes in steps: drift towards it each update instead of snapping.
            val zoom = if (near && userZoom == null) now.zoom + (target - now.zoom) * 0.04 else target
            val cam = CameraPosition.Builder(now).target(me).zoom(zoom)
            if (followBearing != null) {
                // Turn part of the way each update: [me] changes every frame while it glides, so this eases in.
                var d = (followBearing - now.bearing) % 360
                if (d > 180) d -= 360
                if (d < -180) d += 360
                cam.bearing((now.bearing + d * 0.12 + 360) % 360).tilt(60.0)
            } else if (headingUpFallbackNorth) {
                cam.bearing(0.0)
            }
            // Close by: follow the gliding position exactly. Far (first fix, recentre): fly there.
            if (near) m.moveCamera(CameraUpdateFactory.newCameraPosition(cam.build()))
            else m.easeCamera(CameraUpdateFactory.newCameraPosition(cam.build()), 900)
        }
    }

    LaunchedEffect(map, style, fitTo, size) {
        val m = map ?: return@LaunchedEffect
        if (style == null || fitTo.size < 2 || size.width == 0 || size.height == 0) return@LaunchedEffect
        runCatching {
            val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().includes(fitTo).build()
            val pad = with(density) { 48.dp.toPx() }.toInt()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, pad, pad, pad))
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
                        vehicle = pin.vehicle,
                        modifier = Modifier.offset { IntOffset((p.x - half).toInt(), (p.y - half).toInt()) },
                    )
                }
            }
        }
    }
}
