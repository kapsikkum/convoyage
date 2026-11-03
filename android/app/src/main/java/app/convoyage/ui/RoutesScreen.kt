package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.ApiResult
import app.convoyage.api.LatLngDto
import app.convoyage.api.PsRoute
import app.convoyage.api.SaveRoute
import app.convoyage.api.SavedRoute
import app.convoyage.data.Geo
import app.convoyage.data.LatLngD
import app.convoyage.data.Polyline
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.CutSShape
import app.convoyage.design.Eyebrow
import app.convoyage.design.PinKind
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.map.ConvoyMap
import app.convoyage.map.MapPinData
import app.convoyage.nav.NavRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

/** Kilometres and minutes for a route, or what is known of them. */
internal fun routeStats(r: SavedRoute): String {
    val parts = mutableListOf(if (r.isCircuit) "Circuit" else "Point to point")
    r.distanceM?.let { parts += Geo.formatDistance(it) }
    r.durationS?.let { parts += Fmt.duration(it) }
    if (r.staging != null) parts += "staging point"
    if (r.source == "photoscout") parts += "from PhotoScout"
    return parts.joinToString(" · ")
}

/** The line to draw for a saved route: its road geometry, else straight between its waypoints. */
internal fun routeLineOf(r: SavedRoute): List<LatLngD> {
    val road = r.geometry?.let { runCatching { Polyline.decode(it, 6) }.getOrNull() }.orEmpty()
    if (road.size >= 2) return road
    val way = r.waypoints.map { LatLngD(it[1], it[0]) }
    return if (r.isCircuit && way.size >= 2) way + way.first() else way
}

/**
 * Saved routes: drives you can lead again (PhotoScout's sprint/circuit routes). Open one to drive it as a convoy, or
 * build a new one on the map, or import the routes you saved in PhotoScout.
 */
@Composable
fun RoutesScreen(onBack: () -> Unit, onNew: () -> Unit, onOpenConvoy: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val state by container.savedRoutes.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.savedRoutes.refresh() }
    var open by remember { mutableStateOf<SavedRoute?>(null) }
    var openFound by remember { mutableStateOf<app.convoyage.api.DiscoveredRoute?>(null) }
    var importing by remember { mutableStateOf(false) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var radiusKm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(50) }
    var found by remember { mutableStateOf<List<app.convoyage.api.DiscoveredRoute>?>(null) }
    var foundError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, radiusKm) {
        if (tab != 1) return@LaunchedEffect
        val here = container.tracker.fix.value ?: container.tracker.lastKnown()
        if (here == null) { foundError = "Waiting for your location"; return@LaunchedEffect }
        found = null; foundError = null
        when (val r = container.api.discoverRoutes(here.lat, here.lng, radiusKm)) {
            is ApiResult.Ok -> found = r.value
            is ApiResult.Err -> foundError = r.message
        }
    }

    Box(Modifier.fillMaxSize().background(c.void)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
                Column(Modifier.weight(1f)) {
                    Eyebrow("SAVED ROUTES · ${state.routes.size}")
                    CvText("Routes", Cv.type.title, c.ink)
                }
                CvIconButton(CvIcons.Plus, "Build a route", onNew, fill = c.surface200)
            }
            app.convoyage.design.Segmented(
                listOf(app.convoyage.design.SegOption("Mine"), app.convoyage.design.SegOption("Near me")),
                selected = tab, onSelect = { tab = it }, modifier = Modifier.fillMaxWidth(),
            )
            if (tab == 0) {
                state.error?.let {
                    Banner(BannerTone.Hazard, "Could not load your routes", sub = it, icon = CvIcons.Alert, actionLabel = "Retry", onAction = container.savedRoutes::refresh)
                }
                if (!state.loaded && state.error == null) Eyebrow("LOADING...")
                state.routes.forEach { r -> RouteRow(r, onClick = { open = r }) }
                if (state.loaded && state.routes.isEmpty()) {
                    CvText("No saved routes yet. Draw one on the map, or bring your PhotoScout routes across.", Cv.type.body, c.inkMuted)
                }
                CvButton("Build a route", onNew, Modifier.fillMaxWidth(), icon = CvIcons.Route)
                CvButton("Import from PhotoScout", { importing = true }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    listOf(25, 50, 150).forEach { km -> app.convoyage.design.CvChipToggle("$km km", radiusKm == km, { radiusKm = km }) }
                }
                foundError?.let { CvText(it, Cv.type.bodyS, c.pulse) }
                val list = found
                when {
                    list == null && foundError == null -> Eyebrow("LOOKING NEARBY...")
                    list != null && list.isEmpty() -> CvText("Nobody has shared a route starting within $radiusKm km yet. Make yours public from its sheet and it shows up here for others.", Cv.type.body, c.inkMuted)
                    list != null -> list.forEach { d -> FoundRow(d, onClick = { openFound = d }) }
                }
            }
        }

        open?.let { r ->
            RouteDetailSheet(
                r, onDismiss = { open = null },
                onDrive = { open = null; onOpenConvoy() },
            )
        }
        openFound?.let { d ->
            RouteDetailSheet(
                d.route, onDismiss = { openFound = null },
                onDrive = { openFound = null; onOpenConvoy() },
                found = d,
            )
        }
        if (importing) PhotoscoutImportSheet(onDismiss = { importing = false })
    }
}

@Composable
private fun RouteRow(r: SavedRoute, onClick: () -> Unit) {
    val c = Cv.colors
    CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = CutSShape, onClick = onClick) {
        Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            app.convoyage.design.CvIcon(CvIcons.Route, if (r.isCircuit) c.signal else c.inkMuted)
            Column(Modifier.weight(1f)) {
                CvText(r.name, Cv.type.bodyStrong, c.ink, maxLines = 1)
                CvText(routeStats(r), Cv.type.bodyS, c.inkMuted, maxLines = 2)
            }
            app.convoyage.design.CvIcon(CvIcons.Chevron, c.inkMuted)
        }
    }
}

/** A route somebody shares nearby: name, author, how far, and its character (Konvoi's community route cards). */
@Composable
private fun FoundRow(d: app.convoyage.api.DiscoveredRoute, onClick: () -> Unit) {
    val c = Cv.colors
    CvCard(Modifier.fillMaxWidth(), edge = c.line, shape = CutSShape, onClick = onClick) {
        Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            app.convoyage.design.CvIcon(CvIcons.Route, characterColor(d.character))
            Column(Modifier.weight(1f)) {
                CvText(d.route.name, Cv.type.bodyStrong, c.ink, maxLines = 1)
                CvText("@${d.owner.handle} · ${routeStats(d.route)}", Cv.type.bodyS, c.inkMuted, maxLines = 2)
                Eyebrow("${d.character.uppercase()} · ${app.convoyage.data.Geo.formatDistance(d.distanceM).uppercase()} AWAY", color = characterColor(d.character))
            }
            app.convoyage.design.CvIcon(CvIcons.Chevron, c.inkMuted)
        }
    }
}

@Composable
private fun characterColor(character: String) = when (character) {
    "twisty" -> Cv.colors.pulse
    "flowing" -> Cv.colors.hazard
    else -> Cv.colors.go
}

/**
 * Leader action shared by Routes and the convoy's leader tools: in a convoy you lead, the route is shared; with no
 * convoy, one is started for it. Returns an error to show, or null when it went ahead.
 */
internal fun driveSavedRoute(container: app.convoyage.AppContainer, r: SavedRoute): String? {
    val s = container.convoy.state.value
    val fix = container.tracker.fix.value
    val room = s.active
    return when {
        r.convoyStops.size < 2 -> "This route has no line to follow."
        room == null -> { container.convoy.startConvoyWithSavedRoute(r, fix); null }
        room.leader != s.meId -> "Only the convoy leader can share a route."
        else -> { container.convoy.shareSavedRoute(r, fix); null }
    }
}

@Composable
private fun RouteDetailSheet(r: SavedRoute, onDismiss: () -> Unit, onDrive: () -> Unit, found: app.convoyage.api.DiscoveredRoute? = null) {
    val c = Cv.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val convoy by container.convoy.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val line = remember(r.id, r.updatedAt) { routeLineOf(r) }
    val inConvoy = convoy.active != null

    Box(Modifier.fillMaxSize().background(c.void.copy(alpha = 0.6f)).clickableNoRipple(onDismiss), contentAlignment = Alignment.BottomCenter) {
        CvSheet(Modifier.clickableNoRipple {}) {
            Column(Modifier.padding(bottom = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvText(r.name, Cv.type.title, c.ink, maxLines = 2)
                if (found != null) Eyebrow("BY @${found.owner.handle.uppercase()} · ${found.character.uppercase()}", color = characterColor(found.character))
                CvText(routeStats(r), Cv.type.bodyS, c.inkMuted)
                if (r.notes.isNotBlank()) CvText(r.notes, Cv.type.body, c.ink)
                ConvoyMap(
                    Modifier.fillMaxWidth().height(200.dp),
                    pins = listOfNotNull(
                        line.firstOrNull()?.let { MapPinData("start", PinKind.Start, "Start", it.lat, it.lng) },
                        line.lastOrNull()?.takeIf { !r.isCircuit }?.let { MapPinData("end", PinKind.End, "Finish", it.lat, it.lng) },
                        r.staging?.let { MapPinData("staging", PinKind.Destination, "Staging", it.lat, it.lng) },
                    ),
                    me = null, route = emptyList(), routeLine = line, buildings3d = false, follow = false, recenterTick = 0,
                    onUserMoved = {}, fitTo = line.map { LatLng(it.lat, it.lng) } + listOfNotNull(r.staging?.let { LatLng(it.lat, it.lng) }),
                    interactive = false,
                )
                error?.let { CvText(it, Cv.type.bodyS, c.pulse) }
                CvButton(
                    if (inConvoy) "Share with the convoy" else "Drive as a convoy",
                    { error = driveSavedRoute(container, r); if (error == null) onDrive() },
                    Modifier.fillMaxWidth(), icon = CvIcons.Convoy,
                )
                if (r.staging != null) CvText("Everyone is sent to the staging point first. Roll out from the leader tools when you're all there.", Cv.type.bodyS, c.inkMuted)
                var saved by remember { mutableStateOf(false) }
                if (found == null) {
                    var vis by remember(r.id) { mutableStateOf(r.visibility) }
                    Eyebrow("WHO CAN SEE IT")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        listOf("private" to "Only me", "followers" to "Followers", "public" to "Everyone").forEach { (v, label) ->
                            app.convoyage.design.CvChipToggle(label, vis == v, {
                                scope.launch {
                                    when (val u = container.savedRoutes.setVisibility(r.id, v)) {
                                        is ApiResult.Ok -> vis = u.value.visibility
                                        is ApiResult.Err -> error = u.message
                                    }
                                }
                            })
                        }
                    }
                    if (vis == "public") CvText("Drivers nearby find it under Routes, Near me.", Cv.type.bodyS, c.inkMuted)
                }
                if (found != null) {
                    CvButton(if (saved) "Saved to your routes" else "Save to my routes", {
                        scope.launch {
                            val body = app.convoyage.api.SaveRoute(
                                name = r.name, waypoints = r.waypoints, type = r.type, notes = r.notes.ifBlank { null },
                                geometry = r.geometry, distanceM = r.distanceM, durationS = r.durationS, staging = r.staging, visibility = "private",
                            )
                            when (val s = container.savedRoutes.save(body)) {
                                is ApiResult.Ok -> saved = true
                                is ApiResult.Err -> error = s.message
                            }
                        }
                    }, Modifier.fillMaxWidth(), CvButtonKind.Ghost, enabled = !saved, compact = true)
                } else if (confirmDelete) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        CvButton("Keep it", { confirmDelete = false }, Modifier.weight(1f), CvButtonKind.Ghost, compact = true)
                        CvButton("Delete", {
                            scope.launch {
                                when (val d = container.savedRoutes.delete(r.id)) {
                                    is ApiResult.Ok -> onDismiss()
                                    is ApiResult.Err -> { error = d.message; confirmDelete = false }
                                }
                            }
                        }, Modifier.weight(1f), CvButtonKind.Alert, compact = true)
                    }
                } else {
                    CvButton("Delete route", { confirmDelete = true }, Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Trash, compact = true)
                }
            }
        }
    }
}

@Composable
private fun PhotoscoutImportSheet(onDismiss: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<PsRoute>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val imported = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        when (val r = container.savedRoutes.photoscoutRoutes()) {
            is ApiResult.Ok -> list = r.value
            is ApiResult.Err -> error = when (r.status) {
                409, 401 -> "Link your PhotoScout account first (Settings)."
                403 -> "Reconnect PhotoScout in Settings so Convoyage may read your routes."
                503 -> "This server is not connected to PhotoScout."
                else -> r.message
            }
        }
    }
    Box(Modifier.fillMaxSize().background(c.void.copy(alpha = 0.6f)).clickableNoRipple(onDismiss), contentAlignment = Alignment.BottomCenter) {
        CvSheet(Modifier.clickableNoRipple {}) {
            Column(Modifier.padding(bottom = Space.s4).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvText("PhotoScout routes", Cv.type.title, c.ink)
                when {
                    error != null -> CvText(error!!, Cv.type.body, c.inkMuted)
                    list == null -> Eyebrow("LOADING...")
                    list!!.isEmpty() -> CvText("No routes saved in PhotoScout.", Cv.type.body, c.inkMuted)
                    else -> list!!.forEach { p ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                CvText(p.name, Cv.type.bodyStrong, c.ink, maxLines = 1)
                                CvText(if (p.type == "circuit") "Circuit" else "Point to point", Cv.type.bodyS, c.inkMuted)
                            }
                            val done = p.id in imported
                            CvButton(if (done) "Imported" else "Import", {
                                if (!done) scope.launch {
                                    when (val r = container.savedRoutes.importFromPhotoscout(p.id)) {
                                        is ApiResult.Ok -> imported += p.id
                                        is ApiResult.Err -> error = r.message
                                    }
                                }
                            }, kind = if (done) CvButtonKind.Ghost else CvButtonKind.Primary, enabled = !done, compact = true)
                        }
                    }
                }
                CvButton("Done", onDismiss, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
            }
        }
    }
}

/** At most this many waypoints can follow roads (OSRM via points are capped at 20 by the server). */
private const val MAX_SNAPPED = 21

/**
 * Draw a route on the map: tap to add waypoints, choose sprint or circuit, optionally place a staging point where the
 * convoy gathers. The line follows roads (via /v1/route) while it has at most [MAX_SNAPPED] points.
 */
@Composable
fun RouteBuilderScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val fix by container.tracker.fix.collectAsStateWithLifecycle()
    val waypoints = remember { mutableStateListOf<LatLngD>() }
    var staging by remember { mutableStateOf<LatLngD?>(null) }
    var placingStaging by remember { mutableStateOf(false) }
    var circuit by remember { mutableStateOf(false) }
    var followRoads by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var road by remember { mutableStateOf<Pair<String, NavRoute>?>(null) }
    var routing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val me = remember { fix?.let { LatLng(it.lat, it.lng) } }

    // Snap to roads a moment after the last change.
    val key = waypoints.toList() to circuit
    LaunchedEffect(key, followRoads) {
        road = null
        if (!followRoads || waypoints.size < 2 || waypoints.size > MAX_SNAPPED) return@LaunchedEffect
        delay(400)
        routing = true
        val pts = waypoints.toList()
        val to = if (circuit) pts.first() else pts.last()
        val via = if (circuit) pts.drop(1) else pts.subList(1, pts.size - 1)
        val r = container.api.route(pts.first(), to, if (container.session.mode.value == app.convoyage.data.RideMode.Ride) "motorcycle" else "car", via)
        routing = false
        road = (r as? ApiResult.Ok)?.let { ok -> NavRoute.from(ok.value)?.let { ok.value.geometry to it } }
        if (r is ApiResult.Err) error = "Could not follow the roads: ${r.message}"
    }

    val straight = if (circuit && waypoints.size >= 2) waypoints + waypoints.first() else waypoints.toList()
    val line = road?.second?.points ?: straight
    val canSave = waypoints.size >= 2 && name.isNotBlank() && !saving && !routing

    Box(Modifier.fillMaxSize().background(c.void)) {
        ConvoyMap(
            Modifier.fillMaxSize(),
            pins = waypoints.mapIndexed { i, p ->
                MapPinData("w$i", if (i == 0) PinKind.Start else if (i == waypoints.size - 1 && !circuit) PinKind.End else PinKind.Member, "${i + 1}", p.lat, p.lng)
            } + listOfNotNull(staging?.let { MapPinData("staging", PinKind.Destination, "Staging", it.lat, it.lng) }),
            me = me, route = emptyList(), routeLine = line, buildings3d = false, follow = false, recenterTick = 0, onUserMoved = {},
            bottomPadding = 320.dp,
            onTap = { ll ->
                error = null
                val p = LatLngD(ll.latitude, ll.longitude)
                if (placingStaging) { staging = p; placingStaging = false } else if (waypoints.size < 100) waypoints += p
            },
        )
        Row(Modifier.statusBarsPadding().padding(Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
            CvIconButton(CvIcons.Back, "Back", onBack, fill = c.surface200)
            CvCard(Modifier.weight(1f), shape = CutSShape) {
                CvText(
                    when {
                        placingStaging -> "Tap where the convoy gathers."
                        waypoints.isEmpty() -> "Tap the map to place the start."
                        waypoints.size == 1 -> "Tap to add the next point."
                        routing -> "Following the roads..."
                        else -> "${waypoints.size} points" + (road?.second?.let { " · " + Geo.formatDistance(it.distanceM) + " · " + Fmt.duration(it.durationS) } ?: "")
                    },
                    Cv.type.bodyS, c.ink, modifier = Modifier.padding(Space.s3),
                )
            }
        }
        CvSheet(Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.padding(bottom = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvTextField(
                    name, { name = it.take(80) }, "Route name", Modifier.fillMaxWidth(), placeholder = "Mount Panorama lap",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                )
                Segmented(listOf(SegOption("Point to point"), SegOption("Circuit")), if (circuit) 1 else 0, { circuit = it == 1 }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CvChipToggle("Follow roads", followRoads, { followRoads = !followRoads })
                    CvChipToggle(if (staging != null) "Staging set" else "Staging point", placingStaging || staging != null, {
                        if (staging != null && !placingStaging) staging = null else placingStaging = !placingStaging
                    })
                }
                if (followRoads && waypoints.size > MAX_SNAPPED) CvText("Over $MAX_SNAPPED points: the line is drawn straight between them.", Cv.type.bodyS, c.inkMuted)
                error?.let { CvText(it, Cv.type.bodyS, c.pulse) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    CvButton("Undo", { if (waypoints.isNotEmpty()) waypoints.removeAt(waypoints.size - 1) }, Modifier.weight(1f), CvButtonKind.Ghost, enabled = waypoints.isNotEmpty(), compact = true)
                    CvButton("Clear", { waypoints.clear(); staging = null }, Modifier.weight(1f), CvButtonKind.Ghost, enabled = waypoints.isNotEmpty() || staging != null, compact = true)
                    CvButton(if (saving) "Saving..." else "Save", {
                        saving = true
                        val snapped = road
                        scope.launch {
                            val body = SaveRoute(
                                name = name.trim(), waypoints = waypoints.map { listOf(it.lng, it.lat) }, type = if (circuit) "circuit" else "sprint",
                                geometry = snapped?.first, distanceM = snapped?.second?.distanceM, durationS = snapped?.second?.durationS,
                                staging = staging?.let { LatLngDto(it.lat, it.lng) },
                            )
                            when (val r = container.savedRoutes.save(body)) {
                                is ApiResult.Ok -> onSaved()
                                is ApiResult.Err -> { error = r.message; saving = false }
                            }
                        }
                    }, Modifier.weight(1f), enabled = canSave, compact = true)
                }
            }
        }
    }
}

/** The leader's "Drive a saved route" picker, over the convoy screen. */
@Composable
fun SavedRoutePickerSheet(onDismiss: () -> Unit, onBuild: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val state by container.savedRoutes.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.savedRoutes.refresh() }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(c.void.copy(alpha = 0.6f)).clickableNoRipple(onDismiss), contentAlignment = Alignment.BottomCenter) {
        CvSheet(Modifier.clickableNoRipple {}) {
            Column(Modifier.padding(bottom = Space.s4).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                CvText("Drive a saved route", Cv.type.title, c.ink)
                if (!state.loaded) Eyebrow("LOADING...")
                if (state.loaded && state.routes.isEmpty()) CvText("No saved routes yet.", Cv.type.body, c.inkMuted)
                state.routes.forEach { r ->
                    RouteRow(r, onClick = { error = driveSavedRoute(container, r); if (error == null) onDismiss() })
                }
                error?.let { CvText(it, Cv.type.bodyS, c.pulse) }
                CvButton("Build a route", onBuild, Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Plus)
            }
        }
    }
}
