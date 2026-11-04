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

    // Your marker (and the camera on it) sits exactly on the road: snapped to the route while navigating, else to the
    // nearest drawn road running your way. The road pieces near you are re-read from the map every couple of seconds.
    val snapper = remember { RoadSnapper() }
    val rawMe = me
    val meHeading = pins.firstOrNull { it.kind == PinKind.Me }?.headingDeg
    val snapped = remember(rawMe, meHeading, routeLine, snapper.version) { rawMe?.let { snapper.snap(it, meHeading, routeLine) } }
    @Suppress("NAME_SHADOWING") val me = snapped?.first ?: rawMe
    val pins = if (snapped == null) pins else pins.map { if (it.kind == PinKind.Me) it.copy(lat = snapped.first.latitude, lng = snapped.first.longitude, headingDeg = snapped.second ?: it.headingDeg) else it }
    val rawMeNow by rememberUpdatedState(rawMe)
    LaunchedEffect(map, style) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val roadLayers = s.layers.filter { it is org.maplibre.android.style.layers.LineLayer && MapNightStyle.roadClass(it.id).let { c -> c == MapNightStyle.RoadClass.Minor || c == MapNightStyle.RoadClass.Major } }.map { it.id }.toTypedArray()
        while (true) {
            rawMeNow?.let { snapper.refresh(m, it, roadLayers, with(density) { 90.dp.toPx() }) }
            kotlinx.coroutines.delay(1500)
        }
    }

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
    LaunchedEffect(map, bottomPadding, topPadding, buildings3d) {
        val m = map ?: return@LaunchedEffect
        val bottom = with(density) { bottomPadding.toPx() }.toInt()
        // 3D terrain (community MapLibre build) renders nothing with camera padding, so in 3D the follow camera aims
        // ahead of the car instead (see below) and the padding stays zero.
        m.setPadding(0, if (buildings3d) 0 else with(density) { topPadding.toPx() }.toInt(), 0, if (buildings3d) 0 else bottom)
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

    // Speed zoom, lagged over seconds so braking into a corner and accelerating out does not pump the camera.
    val ease = remember { FollowEase() }
    // Ground height under the car (refreshed every ~300 m): the 3D camera's centre sits on it, not at sea level,
    // or a street-level camera ends up inside the hill (Bathurst is ~700 m up).
    val meCell = me?.let { Pair(Math.round(it.latitude * 300), Math.round(it.longitude * 300)) }
    LaunchedEffect(buildings3d, meCell) {
        val p = me ?: return@LaunchedEffect
        if (buildings3d) TerrainHeight.at(p.latitude, p.longitude)?.let { ease.groundM = it }
    }
    LaunchedEffect(map, me, follow, recenterTick, followBearing, followZoom, scaling, userZoom) {
        val m = map ?: return@LaunchedEffect
        // Zoom gestures pivot on the followed marker; free-roaming pinches pivot on the fingers.
        m.uiSettings.focalPoint = if (follow && me != null) runCatching { m.projection.toScreenLocation(me) }.getOrNull() else null
        if (scaling) return@LaunchedEffect
        if (follow && me != null) {
            // 3D terrain: keep the camera centre on the ground under the car, not at sea level, or a street-level
            // camera ends up inside the hill (Bathurst is ~750 m up). Later camera moves keep this altitude.
            if (buildings3d && size.height > 0) TerrainCamera.keepOnGround(m, ease, (size.height / density.density).toDouble(), ease.groundM * MapNightStyle.TERRAIN_EXAGGERATION)
            val now = m.cameraPosition
            val target = userZoom ?: followZoom ?: now.zoom.coerceAtLeast(14.0)
            val near = now.target?.let { it.distanceTo(me) < (if (buildings3d) 800 else 250) } == true && kotlin.math.abs(now.zoom - target) < 2.5
            // Time-based easing (updates come every frame while the pin glides, so a per-update factor would
            // change speed with the frame rate). Zoom settles over ~4 s, the heading over ~0.5 s.
            val tNs = System.nanoTime()
            val dt = if (ease.lastNs == 0L) 0.0 else ((tNs - ease.lastNs) / 1e9).coerceIn(0.0, 0.5)
            ease.lastNs = tNs
            val zoom = if (near && userZoom == null) {
                val from = ease.zoom ?: now.zoom
                (from + (target - from) * (1 - kotlin.math.exp(-dt / 4.0))).also { ease.zoom = it }
            } else target.also { ease.zoom = it }
            val cam = CameraPosition.Builder(now).zoom(zoom)
            var bearing = now.bearing
            if (followBearing != null) {
                // Turn part of the way each update: [me] changes every frame while it glides, so this eases in.
                var d = (followBearing - now.bearing) % 360
                if (d > 180) d -= 360
                if (d < -180) d += 360
                bearing = (now.bearing + d * (1 - kotlin.math.exp(-dt / 0.5)) + 360) % 360
                cam.bearing(bearing).tilt(60.0)
            } else if (headingUpFallbackNorth) {
                bearing = 0.0
                cam.bearing(0.0)
            }
            // In 3D there is no padding: put the car where the padding would have, by looking a little ahead of it.
            val centre = if (buildings3d && size.height > 0) {
                val h = size.height.toDouble()
                val wantY = (with(density) { topPadding.toPx() } + h - with(density) { bottomPadding.toPx() }) / 2.0
                val offDp = (wantY - h / 2.0) / density.density
                val mpp = m.projection.getMetersPerPixelAtLatitude(me.latitude) * Math.pow(2.0, now.zoom - zoom)
                val tilt = if (followBearing != null) 60.0 else now.tilt
                aheadOf(me, offDp * mpp / kotlin.math.cos(Math.toRadians(tilt)).coerceAtLeast(0.3), bearing)
            } else me
            cam.target(centre)
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
                val p: PointF = TerrainCamera.screen(m, LatLng(pin.lat, pin.lng), buildings3d)
                if (p.x > -80 && p.y > -80 && p.x < size.width + 80 && p.y < size.height + 80) {
                    val half = with(density) { 28.dp.toPx() }
                    MapPin(
                        kind = pin.kind,
                        label = pin.label,
                        heading = pin.headingDeg?.let { (it - bearing).toFloat() },
                        tag = pin.tag,
                        vehicle = pin.vehicle,
                        tiltDeg = m.cameraPosition.tilt.toFloat(),
                        modifier = Modifier.offset { IntOffset((p.x - half).toInt(), (p.y - half).toInt()) },
                    )
                }
            }
        }
    }
}

/** Camera easing state kept across follow updates (plain fields: writing it must not recompose the map). */
internal class FollowEase {
    var zoom: Double? = null
    var lastNs = 0L
    var groundM = 0.0
}


/** The point [metres] from [p] towards [bearingDeg] (negative goes behind). Flat-earth: fine for a few hundred metres. */
private fun aheadOf(p: LatLng, metres: Double, bearingDeg: Double): LatLng {
    val b = Math.toRadians(bearingDeg)
    val dLat = metres * kotlin.math.cos(b) / 111_320.0
    val dLng = metres * kotlin.math.sin(b) / (111_320.0 * kotlin.math.cos(Math.toRadians(p.latitude)))
    return LatLng(p.latitude + dLat, p.longitude + dLng)
}

/** Keeping the camera and the Compose pins right when the camera centre is raised onto 3D terrain. */
internal object TerrainCamera {
    /**
     * Raise (or lower) the centre to [groundM] when it is more than 20 m off, keeping the view. The engine refuses when
     * the camera is already at or under that ground, so first back out to a zoom whose camera clears it.
     */
    fun keepOnGround(m: org.maplibre.android.maps.MapLibreMap, ease: FollowEase, heightDp: Double, groundM: Double) {
        if (groundM <= 0.0 || kotlin.math.abs(m.centerAltitude - groundM) < 20.0) return
        if (!m.setCenterAltitudeKeepingView(groundM)) {
            val cam = m.cameraPosition
            val lat = cam.target?.latitude ?: return
            val eyeDp = heightDp / 2.0 / kotlin.math.tan(Math.toRadians((if (cam.fov > 1.0) cam.fov else 36.87) / 2.0))
            val mpp0 = m.projection.getMetersPerPixelAtLatitude(lat) * Math.pow(2.0, cam.zoom)
            val rise = groundM - m.centerAltitude
            // The zoom at which the eye is about twice the rise above the current centre.
            val safe = kotlin.math.log2(eyeDp * mpp0 * kotlin.math.cos(Math.toRadians(cam.tilt)).coerceAtLeast(0.2) / (2.0 * kotlin.math.abs(rise) + 200.0))
            if (safe < cam.zoom) {
                m.moveCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder(cam).zoom(safe).build(),
                    ),
                )
            }
            if (!m.setCenterAltitudeKeepingView(groundM)) return
        }
        // The move changed the zoom: let the follow easing carry on from here rather than jump back.
        ease.zoom = m.cameraPosition.zoom
    }

    /**
     * Where a point on the ground shows on screen. MapLibre's projection works on the sea-level plane; with the
     * centre raised, the ground near it is on the plane at the centre's altitude, so refine the sea-level answer
     * against the inverse projection at that altitude (a few Newton steps).
     */
    fun screen(m: org.maplibre.android.maps.MapLibreMap, p: LatLng, terrain: Boolean): PointF {
        val proj = m.projection
        val first = proj.toScreenLocation(p)
        if (!terrain || m.centerAltitude < 1.0) return first
        var x = first.x
        var y = first.y
        repeat(3) {
            val q = proj.fromScreenLocationAtCenterAltitude(PointF(x, y)) ?: return PointF(x, y)
            val qx = proj.fromScreenLocationAtCenterAltitude(PointF(x + 20f, y)) ?: return PointF(x, y)
            val qy = proj.fromScreenLocationAtCenterAltitude(PointF(x, y + 20f)) ?: return PointF(x, y)
            // Jacobian of screen -> (lng, lat), per 20 px.
            val a = qx.longitude - q.longitude
            val b = qy.longitude - q.longitude
            val c = qx.latitude - q.latitude
            val d = qy.latitude - q.latitude
            val det = a * d - b * c
            if (kotlin.math.abs(det) < 1e-18) return PointF(x, y)
            val el = p.longitude - q.longitude
            val et = p.latitude - q.latitude
            x += (20.0 * (d * el - b * et) / det).toFloat()
            y += (20.0 * (-c * el + a * et) / det).toFloat()
        }
        return PointF(x, y)
    }
}

/**
 * Puts the marker on the road. Keeps the road lines near you (from the rendered map) in local metres and picks the
 * nearest piece that runs your way; the route, while navigating, wins ties. Off any road by more than 25 m it lets go.
 */
internal class RoadSnapper {
    private var segs: List<DoubleArray> = emptyList() // lng1, lat1, lng2, lat2
    private var at: LatLng? = null
    private var atMs = 0L
    /** Bumped when the road pieces change, so the snapped position is worked out again. */
    var version by mutableIntStateOf(0)
        private set

    fun refresh(m: MapLibreMap, me: LatLng, layers: Array<String>, radiusPx: Float) {
        val now = System.currentTimeMillis()
        val last = at
        if (last != null && last.distanceTo(me) < 30 && now - atMs < 6000) return
        val p = TerrainCamera.screen(m, me, m.centerAltitude > 1.0)
        val feats = runCatching { m.queryRenderedFeatures(android.graphics.RectF(p.x - radiusPx, p.y - radiusPx, p.x + radiusPx, p.y + radiusPx), *layers) }.getOrNull() ?: return
        val out = ArrayList<DoubleArray>()
        fun line(pts: List<org.maplibre.geojson.Point>) { for (i in 1 until pts.size) out += doubleArrayOf(pts[i - 1].longitude(), pts[i - 1].latitude(), pts[i].longitude(), pts[i].latitude()) }
        for (f in feats) when (val g = f.geometry()) {
            is org.maplibre.geojson.LineString -> line(g.coordinates())
            is org.maplibre.geojson.MultiLineString -> g.coordinates().forEach { line(it) }
            else -> Unit
        }
        segs = out
        at = me
        atMs = now
        version++
    }

    /** The snapped position and the road's direction (turned to match [heading]), or null to leave the marker alone. */
    fun snap(me: LatLng, heading: Double?, route: List<LatLngD>): Pair<LatLng, Double?>? {
        val lat0 = me.latitude; val lng0 = me.longitude
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0)); val ky = 111_320.0
        var best: DoubleArray? = null
        var bestScore = 25.0
        fun consider(aLng: Double, aLat: Double, bLng: Double, bLat: Double, bonus: Double) {
            val ax = (aLng - lng0) * kx; val ay = (aLat - lat0) * ky
            val bx = (bLng - lng0) * kx; val by = (bLat - lat0) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-6) return
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = kotlin.math.sqrt(px * px + py * py)
            var bearing = (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
            var score = d - bonus
            if (heading != null) {
                var diff = kotlin.math.abs(((bearing - heading) % 360 + 540) % 360 - 180)
                if (diff > 90) { bearing = (bearing + 180) % 360; diff = 180 - diff }
                if (diff > 45) score += 15.0
            }
            if (score < bestScore) { bestScore = score; best = doubleArrayOf(px, py, bearing) }
        }
        for (s in segs) consider(s[0], s[1], s[2], s[3], 0.0)
        for (i in 1 until route.size) {
            val a = route[i - 1]; val b = route[i]
            if (kotlin.math.abs(a.lat - lat0) > 0.01 || kotlin.math.abs(a.lng - lng0) > 0.01) continue
            consider(a.lng, a.lat, b.lng, b.lat, 6.0)
        }
        val b = best ?: return null
        return Pair(LatLng(lat0 + b[1] / ky, lng0 + b[0] / kx), if (heading != null) b[2] else null)
    }
}
