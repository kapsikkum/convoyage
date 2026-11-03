package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.api.ApiResult
import app.convoyage.api.CreatePost
import app.convoyage.api.UpdateRide
import app.convoyage.data.ImageSource
import app.convoyage.data.LatLngD
import app.convoyage.data.Polyline
import app.convoyage.data.RideStage
import app.convoyage.data.TraceMath
import app.convoyage.data.UploadState
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.PinKind
import app.convoyage.design.Readout
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.map.ConvoyMap
import app.convoyage.map.MapPinData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.io.File

data class RecapPhoto(val id: String, val lat: Double?, val lng: Double?, val thumb: ImageSource, val full: ImageSource)

data class RecapUi(
    val loading: Boolean = false,
    val error: String? = null,
    /** The ride the recorder holds (just stopped), as opposed to an old ride opened from the profile. */
    val isLocal: Boolean = true,
    val rideId: String? = null,
    val title: String = "",
    val whenLine: String = "",
    val vehicleName: String? = null,
    val route: List<LatLngD> = emptyList(),
    val distanceM: Double = 0.0,
    val durationS: Long = 0,
    /** Null for rides opened from the server: it does not store moving time. */
    val movingS: Long? = null,
    val avgMps: Double = 0.0,
    val maxMps: Double = 0.0,
    val elevationM: Double? = null,
    val photos: List<RecapPhoto> = emptyList(),
    val upload: UploadState = UploadState.Done,
    val visibility: String = "followers",
    val posting: Boolean = false,
    val posted: Boolean = false,
    val postError: String? = null,
    val gone: Boolean = false,
)

class RecapViewModel(private val container: AppContainer, private val remoteRideId: String?) : ViewModel() {
    private val _ui = MutableStateFlow(RecapUi(isLocal = remoteRideId == null, loading = remoteRideId != null))
    val ui: StateFlow<RecapUi> = _ui.asStateFlow()
    private val recorder = container.recorder
    private var cachedRoute: List<LatLngD> = emptyList()
    private var cachedRoutePoints = -1

    init {
        if (remoteRideId == null) {
            viewModelScope.launch { recorder.state.collect { fromRecorder(it) } }
        } else {
            loadRemote(remoteRideId)
        }
    }

    private fun fromRecorder(s: app.convoyage.data.RecorderState) {
        val m = s.meta
        if (m == null) {
            _ui.update { it.copy(gone = true, loading = false) }
            return
        }
        // Unfilled points would be cheaper, but the route shown must be the route uploaded.
        if (cachedRoutePoints != s.pointCount || cachedRoute.isEmpty()) {
            cachedRoute = recorder.route()
            cachedRoutePoints = s.pointCount
        }
        val photos = m.photos.map { p ->
            val file = File(p.file)
            val local = ImageSource.Local(file)
            val remoteThumb = p.thumbPath?.let { ImageSource.Remote(it) }
            RecapPhoto(
                p.id, p.lat, p.lng,
                thumb = if (file.isFile) local else remoteThumb ?: local,
                full = if (file.isFile) local else remoteThumb ?: local,
            )
        }
        _ui.update {
            it.copy(
                loading = false, isLocal = true, rideId = m.serverRideId, title = m.title,
                whenLine = Fmt.whenLine(m.startedAtMs), vehicleName = m.vehicleName, route = cachedRoute,
                distanceM = s.stats.distanceM, durationS = s.stats.durationS, movingS = s.stats.movingTimeS,
                avgMps = s.stats.avgSpeedMps, maxMps = s.stats.maxSpeedMps, elevationM = s.stats.elevationGainM,
                photos = photos, upload = if (m.stage == RideStage.Uploaded) UploadState.Done else s.upload,
                visibility = m.visibility, posted = m.postId != null, gone = false,
            )
        }
    }

    private fun loadRemote(id: String) {
        viewModelScope.launch {
            when (val r = container.rides.ride(id)) {
                is ApiResult.Err -> _ui.update { it.copy(loading = false, error = r.message) }
                is ApiResult.Ok -> {
                    val ride = r.value
                    val route = ride.polyline?.let { runCatching { Polyline.decode(it, ride.polylinePrecision) }.getOrNull() }.orEmpty()
                    _ui.update {
                        it.copy(
                            loading = false, isLocal = false, rideId = ride.id, title = ride.title.ifBlank { "Ride" },
                            whenLine = Fmt.whenLine(ride.startedAt), route = route,
                            distanceM = ride.distanceM ?: 0.0, durationS = ride.durationS ?: 0, movingS = null,
                            avgMps = ride.avgSpeedMps ?: 0.0, maxMps = ride.maxSpeedMps ?: 0.0, elevationM = ride.elevationGainM,
                            photos = ride.photos.map { p ->
                                RecapPhoto(p.id, p.lat, p.lng, ImageSource.Remote(p.media.thumbUrl), ImageSource.Remote(p.media.url))
                            },
                            upload = UploadState.Done, visibility = ride.visibility.takeIf { v -> v != "private" } ?: "followers",
                        )
                    }
                }
            }
        }
    }

    fun setTitle(title: String) {
        if (!_ui.value.isLocal) return
        recorder.setDetails(title = title.take(120))
    }

    fun setVisibility(v: String) {
        if (_ui.value.isLocal) recorder.setDetails(visibility = v) else _ui.update { it.copy(visibility = v) }
    }

    fun retryUpload() = recorder.syncNow()

    fun postToFeed(caption: String) {
        val s = _ui.value
        val rideId = s.rideId ?: return
        if (s.posting || s.posted) return
        _ui.update { it.copy(posting = true, postError = null) }
        viewModelScope.launch {
            // The post can be no more public than its ride, and the ride must not be private: set both together.
            val patch = container.api.updateRide(rideId, UpdateRide(title = s.title.ifBlank { null }, visibility = s.visibility))
            if (patch is ApiResult.Err) {
                _ui.update { it.copy(posting = false, postError = patch.message) }
                return@launch
            }
            when (val r = container.api.createPost(CreatePost(kind = "ride", rideId = rideId, body = caption.trim().ifBlank { null }, visibility = s.visibility))) {
                is ApiResult.Ok -> {
                    container.feed.addMine(r.value)
                    if (s.isLocal) recorder.setPostId(r.value.id)
                    _ui.update { it.copy(posting = false, posted = true) }
                }
                is ApiResult.Err -> _ui.update { it.copy(posting = false, postError = r.message) }
            }
        }
    }

    /** Local rides: forget the recap once it is safe on the server. */
    fun done() = recorder.dismiss()

    fun discard() = recorder.discard()
}

/**
 * After Stop: the route on the map in signal cyan with start/end/photo pins, the numbers, a photo strip and
 * "Post to feed". Also opens older rides from the profile (then [rideId] is set and the data comes from the server).
 */
@Composable
fun RecapScreen(rideId: String?, onClose: () -> Unit) {
    val container = LocalContainer.current
    val vm: RecapViewModel = viewModel(key = rideId ?: "local", factory = viewModelFactory { initializer { RecapViewModel(container, rideId) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Cv.colors
    var caption by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<RecapPhoto?>(null) }

    // Leave once, however it happens: the buttons below, or the ride vanishing (signed out, discarded elsewhere).
    var closing by remember { mutableStateOf(false) }
    val close = { if (!closing) { closing = true; onClose() } }
    androidx.compose.runtime.LaunchedEffect(ui.gone, ui.isLocal) { if (ui.gone && ui.isLocal) close() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).screenGutters().navigationBarsPadding().padding(top = Space.s2, bottom = Space.s6),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                CvIconButton(CvIcons.Back, "Back", { close() }, fill = c.surface200)
                Eyebrow(if (ui.isLocal) "RIDE RECAP" else "RIDE")
                Box(Modifier.size(Space.target))
            }

            if (ui.loading) Eyebrow("LOADING RIDE...")
            ui.error?.let { Banner(BannerTone.Pulse, "Could not open this ride", sub = it, icon = CvIcons.Alert) }

            CvCard(Modifier.fillMaxWidth(), edge = c.signal, fill = c.surface200, shape = CutLShape) {
                Column {
                    RecapMap(ui, Modifier.fillMaxWidth().height(230.dp).padding(1.dp).clip(CutLShape))
                    Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (ui.isLocal && !ui.posted) {
                            CvTextField(ui.title, vm::setTitle, "Title", placeholder = "Sunday run")
                        } else {
                            CvText(ui.title.ifBlank { "Ride" }, Cv.type.displayL, c.ink, maxLines = 2)
                        }
                        CvText(
                            listOfNotNull(ui.whenLine.ifBlank { null }, ui.vehicleName).joinToString(" · "),
                            Cv.type.bodyS, c.inkMuted,
                        )
                        Stats(ui)
                    }
                }
            }

            if (ui.isLocal) UploadStatus(ui, onRetry = vm::retryUpload)

            if (ui.photos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Eyebrow("PHOTOS · ${ui.photos.size}${ui.photos.count { it.lat != null }.let { if (it > 0) " · $it ON THE MAP" else "" }}")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        ui.photos.forEach { p ->
                            CvImage(
                                p.thumb, Modifier.size(96.dp).clip(CutSShape).clickableNoRipple { viewing = p },
                                maxEdgePx = 300,
                            )
                        }
                    }
                }
            }

            if (ui.posted) {
                Banner(BannerTone.Go, "Posted to your feed", icon = CvIcons.Check)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Eyebrow("SHARE")
                    Segmented(
                        listOf(SegOption("Followers"), SegOption("Public")),
                        if (ui.visibility == "public") 1 else 0,
                        { vm.setVisibility(if (it == 1) "public" else "followers") },
                        Modifier.fillMaxWidth(),
                    )
                    CvTextField(caption, { caption = it.take(500) }, "Caption (optional)", placeholder = "How was it?", singleLine = false, maxLines = 4)
                    ui.postError?.let { Banner(BannerTone.Pulse, "Could not post", sub = it) }
                    val ready = ui.rideId != null && ui.upload == UploadState.Done
                    CvButton(
                        when {
                            ui.posting -> "Posting..."
                            !ready -> "Waiting for upload..."
                            else -> "Post to feed"
                        },
                        { vm.postToFeed(caption) }, Modifier.fillMaxWidth(), icon = CvIcons.Share,
                        enabled = ready && !ui.posting,
                    )
                }
            }

            if (ui.isLocal) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CvButton(
                        if (confirmDiscard) "Tap again to discard" else "Discard ride",
                        { if (confirmDiscard) { vm.discard(); close() } else confirmDiscard = true },
                        Modifier.weight(1f), if (confirmDiscard) CvButtonKind.Alert else CvButtonKind.Ghost,
                    )
                    CvButton(
                        "Done", { vm.done(); close() }, Modifier.weight(1f), CvButtonKind.Ghost,
                        enabled = ui.upload == UploadState.Done,
                    )
                }
                if (ui.upload != UploadState.Done) Eyebrow("DONE UNLOCKS ONCE THE RIDE IS UPLOADED. IT STAYS SAFE ON THIS PHONE UNTIL THEN.")
            }
        }

        viewing?.let { p ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickableNoRipple { viewing = null }, contentAlignment = Alignment.Center) {
                CvImage(p.full, Modifier.fillMaxSize(), maxEdgePx = 1600, contentScale = ContentScale.Fit)
                Box(Modifier.align(Alignment.TopEnd).screenGutters().padding(top = Space.s2)) {
                    CvIconButton(CvIcons.Close, "Close photo", { viewing = null })
                }
            }
        }
    }
}

@Composable
private fun RecapMap(ui: RecapUi, modifier: Modifier) {
    val pins = remember(ui.route, ui.photos) {
        buildList {
            ui.route.firstOrNull()?.let { add(MapPinData("start", PinKind.Start, "S", it.lat, it.lng)) }
            if (ui.route.size > 1) ui.route.last().let { add(MapPinData("end", PinKind.End, "E", it.lat, it.lng)) }
            ui.photos.forEach { p -> if (p.lat != null && p.lng != null) add(MapPinData("photo-${p.id}", PinKind.Photo, "", p.lat, p.lng)) }
        }
    }
    val line = remember(ui.route) { TraceMath.thin(ui.route, 3000).map { listOf(it.lng, it.lat) } }
    val fit = remember(ui.route) { TraceMath.thin(ui.route, 400).map { LatLng(it.lat, it.lng) } }
    Box(modifier) {
        ConvoyMap(
            modifier = Modifier.fillMaxSize(), pins = pins, me = null, route = line, buildings3d = false,
            follow = false, recenterTick = 0, onUserMoved = {}, fitTo = fit, interactive = false,
        )
        if (ui.route.size < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.cutPanel(Cv.colors.glass, Cv.colors.lineStrong, CutSShape).padding(horizontal = Space.s3, vertical = Space.s2)) {
                    Eyebrow(if (ui.loading) "LOADING ROUTE" else "NO ROUTE RECORDED")
                }
            }
        }
    }
}

@Composable
private fun Stats(ui: RecapUi) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Readout("Distance", Fmt.km(ui.distanceM), Modifier.weight(1f), unit = "km", valueColor = Cv.colors.signal)
            Readout("Moving", ui.movingS?.let { Fmt.clock(it) } ?: Fmt.clock(ui.durationS), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Readout("Avg speed", Fmt.kmh(ui.avgMps), Modifier.weight(1f), unit = "km/h")
            Readout("Max speed", Fmt.kmh(ui.maxMps), Modifier.weight(1f), unit = "km/h")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Readout("Elevation", ui.elevationM?.let { Fmt.metres(it) } ?: "--", Modifier.weight(1f), unit = "m")
            // TODO(lean): needs the rotation-vector sensor.
            Readout("Max lean", "--", Modifier.weight(1f), unit = "°")
        }
        if (ui.movingS != null && ui.durationS > ui.movingS + 5) {
            Eyebrow("TOTAL TIME ${Fmt.clock(ui.durationS)} · STOPPED ${Fmt.clock(ui.durationS - ui.movingS)}")
        }
    }
}

@Composable
private fun UploadStatus(ui: RecapUi, onRetry: () -> Unit) {
    when (val u = ui.upload) {
        UploadState.Uploading -> Banner(BannerTone.Police, "Uploading your ride", sub = "Route and ${ui.photos.size} photo(s)", icon = CvIcons.Share)
        is UploadState.Failed -> Banner(
            BannerTone.Hazard, if (u.retryable) "Saved on this phone" else "Upload failed", sub = u.message,
            icon = CvIcons.Alert, actionLabel = "Retry", onAction = onRetry,
        )
        UploadState.Idle -> Banner(BannerTone.Police, "Ready to upload", icon = CvIcons.Share, actionLabel = "Upload", onAction = onRetry)
        UploadState.Done -> Unit
    }
}
