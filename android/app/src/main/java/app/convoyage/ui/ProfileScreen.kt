package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.api.ApiResult
import app.convoyage.api.badge
import app.convoyage.api.Post
import app.convoyage.api.RideSummary
import app.convoyage.api.toRideSummary
import app.convoyage.api.toVehicle
import app.convoyage.data.AuthKind
import app.convoyage.data.ImageSource
import app.convoyage.data.LatLngD
import app.convoyage.data.Roster
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvAvatar
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvSheet
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.Readout
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.design.gridBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUi(val saving: Boolean = false, val saveError: String? = null, val loadError: String? = null)

class ProfileViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ProfileUi())
    val ui: StateFlow<ProfileUi> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (container.session.kind.value != AuthKind.Account) return
        viewModelScope.launch {
            _ui.update { it.copy(loadError = null) }
            when (val r = container.account.loadMe()) {
                is ApiResult.Err -> if (!r.isUnauthorized) _ui.update { it.copy(loadError = r.message) } // 401 = signed out, AppRoot reacts
                is ApiResult.Ok -> Unit
            }
        }
    }

    fun clearSaveState() = _ui.update { it.copy(saveError = null) }

    /** Uploads [photo] as my profile picture, or removes the picture when null. */
    fun setPhoto(photo: java.io.File?) {
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val r = container.account.updateAvatar(photo)
            photo?.delete()
            _ui.update { it.copy(saving = false, saveError = (r as? ApiResult.Err)?.message) }
        }
    }

    fun saveName(name: String, onDone: () -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty() || _ui.value.saving) return
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            when (val r = container.account.updateDisplayName(clean)) {
                is ApiResult.Ok -> { _ui.update { it.copy(saving = false) }; onDone() }
                is ApiResult.Err -> _ui.update { it.copy(saving = false, saveError = r.message) }
            }
        }
    }
}

/** Profile: name and handle from `GET /v1/me` (name editable), lifetime numbers from my rides, and the four tabs. */
@Composable
fun ProfileScreen(
    onOpenGarage: () -> Unit,
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenRide: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = Cv.colors
    val container = LocalContainer.current
    val vm: ProfileViewModel = viewModel(factory = viewModelFactory { initializer { ProfileViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val me by container.account.me.collectAsStateWithLifecycle()
    val name by container.session.name.collectAsState()
    val kind by container.session.kind.collectAsState()
    val garage by container.garage.state.collectAsStateWithLifecycle()
    val rides by container.rides.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf<ImageSource?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.convoyage.data.ImageLoader.importPicked(context.contentResolver, uri, java.io.File(context.cacheDir, "avatar"))
            }
            vm.setPhoto(file ?: return@launch)
        }
    }
    val shownName = me?.displayName?.ifBlank { null } ?: me?.handle ?: name ?: "Guest"
    val handle = me?.handle?.takeIf { it.isNotBlank() }
    val totals = remember(rides.rides) { container.rides.totals() }

    LaunchedEffect(Unit) {
        container.rides.refresh()
        container.garage.refresh()
    }
    val postsPager = remember(handle) { handle?.let { container.feed.userPosts(it) } }
    val posts = postsPager?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(postsPager) { postsPager?.refresh() }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).screenGutters().padding(top = Space.s6, bottom = TabBarClearance),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CvIcon(CvIcons.Lock, c.inkMuted, size = 16.dp); Eyebrow(if (me?.isPrivate == false) "PUBLIC ACCOUNT" else "PRIVATE ACCOUNT")
                }
                CvIconButton(CvIcons.Settings, "Settings", onOpenSettings)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clickableNoRipple { if (kind == AuthKind.Account && !ui.saving) photoMenu = true }
                        .semantics { contentDescription = "Profile photo. Tap to change." },
                ) {
                    CvAvatar(me?.avatarPath, garage.primary?.badge() ?: me?.primaryVehicle?.badge(), Roster.initials(shownName), size = 64.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    CvText(shownName, Cv.type.displayL, c.ink, maxLines = 2)
                    CvText((handle?.let { "@$it" } ?: if (kind == AuthKind.Dev) "dev sign-in, no account" else "@...") + " · drives + rides", Cv.type.bodyS, c.inkMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                        CvText("${me?.followerCount ?: 0} followers", Cv.type.bodyS, c.ink); CvText("${me?.followingCount ?: 0} following", Cv.type.bodyS, c.ink)
                    }
                }
            }
            if (ui.loadError != null) Banner(BannerTone.Hazard, "Could not load your profile", sub = ui.loadError, icon = CvIcons.Alert, actionLabel = "Retry", onAction = vm::load)
            if (kind == AuthKind.Account) {
                if (!editing) {
                    CvButton("Edit display name", { draft = shownName; editing = true; vm.clearSaveState() }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
                } else {
                    CvTextField(draft, { draft = it.take(60) }, "Display name", placeholder = "Alex")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CvButton("Cancel", { editing = false }, Modifier.weight(1f), CvButtonKind.Ghost, enabled = !ui.saving)
                        CvButton(
                            if (ui.saving) "Saving..." else "Save",
                            { vm.saveName(draft) { editing = false } },
                            Modifier.weight(1f), enabled = !ui.saving && draft.isNotBlank(),
                        )
                    }
                }
                ui.saveError?.let { Banner(BannerTone.Pulse, it) }
            }

            CvCard(Modifier.fillMaxWidth(), edge = c.lineStrong, shape = CutMShape, onClick = onOpenGarage) {
                Row(Modifier.gridBackdrop(c.line.copy(alpha = 0.4f)).padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val p = garage.primary
                        Eyebrow(if (p != null) "PRIMARY · ${garage.owned.size} IN GARAGE" else "GARAGE", color = c.signal)
                        CvText(p?.displayName ?: "Add your first vehicle", Cv.type.title, c.ink)
                    }
                    CvIcon(CvIcons.Chevron, c.inkMuted)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Readout("Rides", "${totals.rides}", Modifier.weight(1f))
                Readout("Distance", Fmt.km(totals.distanceM), Modifier.weight(1f), unit = "km")
                Fmt.hoursOrMinutes(totals.durationS).let { (n, u) -> Readout("Seat time", n, Modifier.weight(1f), unit = u) }
            }
            rides.error?.let { Eyebrow("RIDE TOTALS UNAVAILABLE: $it", color = c.hazard) }

            Segmented(listOf(SegOption("Posts"), SegOption("Routes"), SegOption("Photos"), SegOption("Garage")), section, { section = it }, Modifier.fillMaxWidth())

            when (section) {
                0 -> PostsTab(posts, handle != null, onRetry = { postsPager?.refresh() }, onMore = { postsPager?.loadMore() })
                1 -> RoutesTab(rides.finished, rides.loaded, onOpenRide)
                2 -> PhotosTab(posts?.items.orEmpty(), posts?.loadedOnce == true, onView = { viewing = it })
                else -> GarageTab(garage.vehicles, garage.loaded, onOpenVehicle, onAddVehicle)
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Eyebrow("THIS DEVICE")
                CvButton("Settings", onOpenSettings, Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Settings)
                CvButton("Sign out", onSignOut, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
            }
        }
        if (photoMenu) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickableNoRipple { photoMenu = false })
            CvSheet(Modifier.align(Alignment.BottomCenter).padding(bottom = TabBarClearance).clickableNoRipple {}) {
                Column(Modifier.fillMaxWidth().padding(bottom = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CvText("Profile photo", Cv.type.title, c.ink)
                    CvText("Your primary vehicle shows as a badge on it.", Cv.type.bodyS, c.inkMuted)
                    CvButton(
                        "Choose a photo",
                        { photoMenu = false; picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.fillMaxWidth(), icon = CvIcons.Image,
                    )
                    if (me?.avatarMediaId != null) {
                        CvButton("Remove photo", { photoMenu = false; vm.setPhoto(null) }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
                    }
                    CvButton("Cancel", { photoMenu = false }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
                }
            }
        }
        viewing?.let { src ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickableNoRipple { viewing = null }, contentAlignment = Alignment.Center) {
                CvImage(src, Modifier.fillMaxSize(), maxEdgePx = 1600, contentScale = ContentScale.Fit)
                Box(Modifier.align(Alignment.TopEnd).screenGutters().padding(top = Space.s2)) {
                    CvIconButton(CvIcons.Close, "Close photo", { viewing = null })
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    val c = Cv.colors
    Box(Modifier.fillMaxWidth().height(120.dp).cutPanel(c.surface100, c.line, CutMShape).gridBackdrop(c.line.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Eyebrow(text) }
}

@Composable
private fun PostsTab(posts: app.convoyage.data.PagerState<Post>?, hasHandle: Boolean, onRetry: () -> Unit, onMore: () -> Unit) {
    val c = Cv.colors
    when {
        !hasHandle -> Empty("SIGN IN WITH YOUR EMAIL TO SEE POSTS")
        posts == null || (!posts.loadedOnce && posts.error == null) -> Empty("LOADING...")
        posts.error != null && posts.items.isEmpty() -> Banner(BannerTone.Hazard, "Could not load posts", sub = posts.error, icon = CvIcons.Alert, actionLabel = "Retry", onAction = onRetry)
        posts.items.isEmpty() -> Empty("NO POSTS YET")
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            posts.items.forEach { p ->
                CvCard(Modifier.fillMaxWidth(), shape = CutMShape) {
                    Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Eyebrow(Fmt.timeAgo(p.createdAt))
                            Eyebrow(p.kind.uppercase(), color = c.signal)
                        }
                        p.ride?.toRideSummary()?.let { r ->
                            CvText(r.title.ifBlank { "Ride" }, Cv.type.bodyStrong, c.ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                                r.distanceM?.let { StatText(Fmt.km(it), "KM") }
                                r.durationS?.let { StatText(Fmt.clock(it), "") }
                            }
                        }
                        p.vehicle?.toVehicle()?.let { CvText(it.displayName, Cv.type.bodyStrong, c.ink) }
                        if (p.body.isNotBlank()) CvText(p.body, Cv.type.bodyS, c.ink, maxLines = 3)
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { CvIcon(CvIcons.Heart, c.inkMuted, size = 16.dp); CvText("${p.likeCount}", Cv.type.telemetry, c.ink) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { CvIcon(CvIcons.Comment, c.inkMuted, size = 16.dp); CvText("${p.commentCount}", Cv.type.telemetry, c.ink) }
                        }
                    }
                }
            }
            if (!posts.endReached) CvButton(if (posts.loadingMore) "Loading..." else "More posts", onMore, Modifier.fillMaxWidth(), CvButtonKind.Ghost, enabled = !posts.loadingMore)
        }
    }
}

@Composable
private fun RoutesTab(rides: List<RideSummary>, loaded: Boolean, onOpenRide: (String) -> Unit) {
    when {
        !loaded -> Empty("LOADING...")
        rides.isEmpty() -> Empty("NO RIDES YET. HIT RECORD ON HOME.")
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            rides.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    row.forEach { r -> RouteTile(r, Modifier.weight(1f)) { onOpenRide(r.id) } }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RouteTile(ride: RideSummary, modifier: Modifier, onClick: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val route by produceState<List<LatLngD>?>(container.rides.cachedRoute(ride.id), ride.id) {
        if (value == null) value = container.rides.route(ride.id) ?: emptyList()
    }
    Box(modifier.aspectRatio(1f).clickableNoRipple(onClick)) {
        RoutePreview(route, Modifier.fillMaxSize(), showEnds = false)
        Column(Modifier.align(Alignment.BottomStart).padding(Space.s2).cutPanel(c.glass, c.lineStrong, CutSShape).padding(horizontal = Space.s2, vertical = 4.dp)) {
            CvText("${Fmt.km(ride.distanceM ?: 0.0)} KM", Cv.type.telemetry, c.ink)
            Eyebrow(Fmt.timeAgo(ride.startedAt))
        }
    }
}

@Composable
private fun PhotosTab(posts: List<Post>, loaded: Boolean, onView: (ImageSource) -> Unit) {
    val media = posts.flatMap { it.media }
    when {
        !loaded -> Empty("LOADING...")
        media.isEmpty() -> Empty("NO PHOTOS YET. RIDE PHOTOS LIVE ON EACH RIDE'S RECAP.")
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            media.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    row.forEach { m ->
                        CvImage(
                            ImageSource.Remote(m.thumbUrl),
                            Modifier.weight(1f).aspectRatio(1f).clip(CutSShape).clickableNoRipple { onView(ImageSource.Remote(m.url)) },
                            maxEdgePx = 400,
                        )
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GarageTab(vehicles: List<app.convoyage.api.Vehicle>, loaded: Boolean, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    when {
        !loaded -> Empty("LOADING...")
        vehicles.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Empty("NOTHING IN THE GARAGE")
            CvButton("Add a vehicle", onAdd, Modifier.fillMaxWidth(), icon = CvIcons.Plus)
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            vehicles.sortedWith(compareBy({ it.isSold }, { !it.isPrimary })).forEach { v -> VehicleRow(v, onClick = { onOpen(v.id) }, tall = false) }
            CvButton("Add a vehicle", onAdd, Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Plus)
        }
    }
}
