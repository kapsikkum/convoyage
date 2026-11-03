package app.convoyage.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.api.ApiResult
import app.convoyage.api.Comment
import app.convoyage.api.CreatePost
import app.convoyage.api.CreateReport
import app.convoyage.api.FeedScope
import app.convoyage.api.Post
import app.convoyage.api.ReportReasons
import app.convoyage.data.ImageSource
import app.convoyage.data.PageOf
import app.convoyage.data.Pager
import app.convoyage.data.Roster
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvAvatar
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import kotlinx.coroutines.launch
import java.io.File

/** Screen-level actions on posts: likes, comments, reports, deletes, and making a new post. */
class FeedViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val feed = container.feed
    private val commentPagers = HashMap<String, Pager<Comment>>()

    fun pager(scope: FeedScope): Pager<Post> = feed.pager(scope)

    fun comments(postId: String): Pager<Comment> = commentPagers.getOrPut(postId) {
        Pager(viewModelScope, { it.id }) { cursor ->
            when (val r = api.comments(postId, cursor, 20)) {
                is ApiResult.Ok -> ApiResult.Ok(PageOf(r.value.items, r.value.nextCursor))
                is ApiResult.Err -> r
            }
        }
    }

    /** Flips the heart at once and puts the server's count in when it answers; undoes the flip if it fails. */
    fun toggleLike(post: Post) {
        val wasLiked = post.likedByMe
        feed.flipLike(post.id)
        container.appScope.launch {
            when (val r = if (wasLiked) api.unlike(post.id) else api.like(post.id)) {
                is ApiResult.Ok -> feed.applyLike(post.id, r.value)
                is ApiResult.Err -> feed.flipLike(post.id)
            }
        }
    }

    /** Sends a comment; true when it went through. */
    suspend fun addComment(postId: String, text: String): ApiResult<Comment> {
        val r = api.addComment(postId, text.trim())
        if (r is ApiResult.Ok) {
            comments(postId).append(r.value)
            feed.bumpComments(postId)
        }
        return r
    }

    suspend fun report(postId: String, reason: String): ApiResult<*> = api.report(CreateReport("post", postId, reason))

    suspend fun delete(postId: String): ApiResult<*> = api.deletePost(postId).also { if (it is ApiResult.Ok) feed.remove(postId) }

    /** Posts text and/or one photo. */
    suspend fun publish(body: String, photo: File?, visibility: String, withLocation: Boolean): ApiResult<Post> {
        val here = if (withLocation) container.tracker.lastKnown() else null
        var mediaIds: List<String>? = null
        if (photo != null) {
            when (val up = api.uploadMedia(photo, "image/jpeg")) {
                is ApiResult.Ok -> mediaIds = listOf(up.value.id)
                is ApiResult.Err -> return up
            }
        }
        val r = api.createPost(
            CreatePost(
                kind = if (mediaIds != null) "photo" else "text", body = body.trim().ifBlank { null }, mediaIds = mediaIds,
                visibility = visibility, lat = here?.lat, lng = here?.lng,
            ),
        )
        if (r is ApiResult.Ok) {
            feed.addMine(r.value)
            photo?.delete()
        }
        return r
    }
}

@Composable
fun FeedScreen() {
    val container = LocalContainer.current
    val vm: FeedViewModel = viewModel(factory = viewModelFactory { initializer { FeedViewModel(container) } })
    val c = Cv.colors
    var scopeIndex by rememberSaveable { mutableIntStateOf(0) }
    val scope = FeedScope.entries[scopeIndex]
    val pager = vm.pager(scope)
    val state by pager.state.collectAsStateWithLifecycle()
    val me by container.account.me.collectAsStateWithLifecycle()
    val permission = rememberLocationPermission()
    var composing by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ImageSource?>(null) }
    val listState = rememberLazyListState()

    // First visit to a tab loads it. Nearby also needs location updates running so there is a position to send.
    LaunchedEffect(scope) { if (!state.loadedOnce && !state.refreshing) pager.refresh() }
    LaunchedEffect(scope, permission.granted) { if (scope == FeedScope.Nearby && permission.granted && state.error != null) pager.refresh() }
    LifecycleStartEffect(scope, permission.granted) {
        if (scope == FeedScope.Nearby) container.tracker.acquire()
        onStopOrDispose { if (scope == FeedScope.Nearby) container.tracker.release() }
    }

    // Page in the next chunk when the last few cards come into view.
    LaunchedEffect(pager, state.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= listState.layoutInfo.totalItemsCount - 3) pager.loadMore()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefresh(refreshing = state.refreshing, onRefresh = pager::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.gutter, end = Space.gutter,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + Space.s6,
                    bottom = TabBarClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.s4),
            ) {
                item(key = "head") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            CvText("Feed", Cv.type.displayL, c.ink)
                            CvIconButton(CvIcons.Plus, "New post", { composing = true })
                        }
                        Segmented(
                            listOf(SegOption("Everyone"), SegOption("Following"), SegOption("Nearby")),
                            scopeIndex, { scopeIndex = it }, Modifier.fillMaxWidth(),
                        )
                        if (scope == FeedScope.Nearby && !permission.granted) {
                            Banner(
                                BannerTone.Hazard, "Location is off", sub = "Nearby shows posts around you.",
                                icon = CvIcons.Locate, actionLabel = "Allow", onAction = permission.request,
                            )
                        }
                        state.error?.let { msg ->
                            Banner(BannerTone.Hazard, if (state.items.isEmpty()) "Could not load the feed" else "Could not load more", sub = msg, icon = CvIcons.Alert, actionLabel = "Retry", onAction = { if (state.items.isEmpty()) pager.refresh() else pager.loadMore() })
                        }
                    }
                }
                items(state.items, key = { it.id }) { post ->
                    PostCard(post, mine = me?.id == post.author.id, vm = vm, onView = { viewing = it })
                }
                item(key = "foot") {
                    Box(Modifier.fillMaxWidth().padding(vertical = Space.s2), contentAlignment = Alignment.Center) {
                        when {
                            state.loadingMore -> Eyebrow("LOADING MORE...")
                            state.refreshing && state.items.isEmpty() -> Eyebrow("LOADING...")
                            state.loadedOnce && state.items.isEmpty() -> Eyebrow(
                                when (scope) {
                                    FeedScope.Everyone -> "NO POSTS YET. BE THE FIRST."
                                    FeedScope.Following -> "NOTHING FROM PEOPLE YOU FOLLOW YET."
                                    FeedScope.Nearby -> "NOTHING NEAR YOU YET."
                                },
                            )
                            state.endReached -> Eyebrow("YOU'RE UP TO DATE")
                        }
                    }
                }
            }
        }

        if (composing) {
            NewPostSheet(vm, onDismiss = { composing = false })
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

private fun kindBadge(kind: String) = when (kind) {
    "ride" -> "RIDE"
    "build" -> "BUILD"
    "photo" -> "PHOTO"
    else -> "POST"
}

@Composable
private fun PostCard(post: Post, mine: Boolean, vm: FeedViewModel, onView: (ImageSource) -> Unit) {
    val c = Cv.colors
    val scope = rememberCoroutineScope()
    var showComments by rememberSaveable(post.id) { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    CvCard(Modifier.fillMaxWidth(), shape = CutLShape) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                CvAvatar(post.author.avatarPath, post.author.primaryVehicle, Roster.initials(post.author.name))
                Column(Modifier.weight(1f)) {
                    Row {
                        CvText(post.author.name, Cv.type.bodyStrong, c.ink, maxLines = 1)
                        if (post.author.handle.isNotBlank()) CvText(" · @${post.author.handle}", Cv.type.bodyS, c.inkMuted, maxLines = 1)
                    }
                    Eyebrow(Fmt.timeAgo(post.createdAt) + if (post.visibility == "followers") " · FOLLOWERS" else "")
                }
                Box(Modifier.cutPanel(c.surface300, c.lineStrong, CutSShape).padding(horizontal = 8.dp, vertical = 4.dp)) { Eyebrow(kindBadge(post.kind), color = c.signal) }
            }

            when (post.kind) {
                "ride" -> post.ride?.let { RidePostBody(it, vm) } ?: Eyebrow("THIS RIDE IS NO LONGER AVAILABLE")
                "build" -> post.vehicle?.let { v ->
                    VehicleViewport(v, Modifier.fillMaxWidth().height(150.dp))
                    CvText(v.displayName, Cv.type.title, c.ink)
                    CvText(buildSummary(v), Cv.type.bodyS, c.inkMuted)
                } ?: Eyebrow("THIS VEHICLE IS NO LONGER AVAILABLE")
                "photo" -> if (post.media.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(220.dp).clip(CutMShape).clickableNoRipple { onView(ImageSource.Remote(post.media[0].url)) }) {
                        CvImage(ImageSource.Remote(post.media[0].thumbUrl), Modifier.fillMaxSize(), maxEdgePx = 800)
                        if (post.media.size > 1) {
                            Box(Modifier.align(Alignment.BottomEnd).padding(Space.s2).cutPanel(c.glass, c.lineStrong, CutSShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Eyebrow("+${post.media.size - 1}")
                            }
                        }
                    }
                }
            }

            if (post.body.isNotBlank()) CvText(post.body, Cv.type.body, c.ink)

            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.defaultMinTarget().clickableNoRipple { vm.toggleLike(post) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CvIcon(if (post.likedByMe) CvIcons.HeartFilled else CvIcons.Heart, if (post.likedByMe) c.pulse else c.inkMuted, size = 20.dp)
                    CvText("${post.likeCount}", Cv.type.telemetry, c.ink)
                }
                Row(
                    Modifier.defaultMinTarget().clickableNoRipple { showComments = !showComments },
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CvIcon(CvIcons.Comment, if (showComments) c.signal else c.inkMuted, size = 20.dp)
                    CvText("${post.commentCount}", Cv.type.telemetry, c.ink)
                }
                Box(Modifier.weight(1f))
                if (mine) {
                    Box(Modifier.defaultMinTarget().clickableNoRipple { if (confirmDelete) scope.launch { (vm.delete(post.id) as? ApiResult.Err)?.let { actionError = it.message } } else confirmDelete = true }, contentAlignment = Alignment.Center) {
                        if (confirmDelete) Eyebrow("DELETE?", color = c.pulse) else CvIcon(CvIcons.Trash, c.inkMuted, size = 20.dp)
                    }
                } else {
                    Box(Modifier.defaultMinTarget().clickableNoRipple { reporting = !reporting }, contentAlignment = Alignment.Center) {
                        CvIcon(CvIcons.Flag, if (reported) c.hazard else c.inkMuted, size = 20.dp)
                    }
                }
            }

            if (reporting && !reported) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Eyebrow("REPORT THIS POST AS")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        ReportReasons.ALL.forEach { reason ->
                            CvChipToggle(reason, false, {
                                scope.launch {
                                    when (val r = vm.report(post.id, reason)) {
                                        is ApiResult.Ok -> { reported = true; reporting = false }
                                        is ApiResult.Err -> actionError = r.message
                                    }
                                }
                            })
                        }
                    }
                }
            }
            if (reported) Eyebrow("REPORTED. THANKS, WE'LL TAKE A LOOK.", color = c.hazard)
            actionError?.let { Banner(BannerTone.Pulse, it) }

            if (showComments) CommentsSection(post, vm)
        }
    }
}

@Composable
private fun RidePostBody(ride: app.convoyage.api.RideSummary, vm: FeedViewModel) {
    val c = Cv.colors
    val container = LocalContainer.current
    val route by produceState<List<app.convoyage.data.LatLngD>?>(container.rides.cachedRoute(ride.id), ride.id) {
        if (value == null) value = container.rides.route(ride.id) ?: emptyList()
    }
    if (ride.title.isNotBlank()) CvText(ride.title, Cv.type.title, c.ink)
    RoutePreview(route, Modifier.fillMaxWidth().height(150.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
        ride.distanceM?.let { StatText(Fmt.km(it), "KM") }
        ride.durationS?.let { StatText(Fmt.clock(it), "") }
        ride.avgSpeedMps?.takeIf { it > 0 }?.let { StatText(Fmt.kmh(it), "KM/H AVG") }
    }
    ride.convoyCode?.let { Eyebrow("CONVOY RIDE", color = c.lead) }
}

@Composable
private fun CommentsSection(post: Post, vm: FeedViewModel) {
    val c = Cv.colors
    val pager = remember(post.id) { vm.comments(post.id) }
    val state by pager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(post.id) { if (!state.loadedOnce) pager.refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        if (state.refreshing && state.items.isEmpty()) Eyebrow("LOADING COMMENTS...")
        if (state.loadedOnce && state.items.isEmpty()) Eyebrow("NO COMMENTS YET")
        state.items.forEach { cm ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.Top) {
                CvAvatar(cm.author.avatarPath, cm.author.primaryVehicle, Roster.initials(cm.author.name), size = 28.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CvText(cm.author.name, Cv.type.bodyStrong, c.ink, maxLines = 1)
                        Eyebrow(Fmt.timeAgo(cm.createdAt))
                    }
                    CvText(cm.body, Cv.type.bodyS, c.ink)
                }
            }
        }
        if (state.nextCursor != null) {
            CvButton(if (state.loadingMore) "Loading..." else "More comments", pager::loadMore, Modifier.fillMaxWidth(), CvButtonKind.Ghost, enabled = !state.loadingMore)
        }
        state.error?.let { Eyebrow(it, color = c.hazard) }
        CvTextField(draft, { draft = it.take(1000) }, "Add a comment", placeholder = "Say something", singleLine = false, maxLines = 3)
        error?.let { Eyebrow(it, color = c.pulse) }
        CvButton(
            if (sending) "Sending..." else "Comment",
            {
                sending = true
                error = null
                scope.launch {
                    when (val r = vm.addComment(post.id, draft)) {
                        is ApiResult.Ok -> draft = ""
                        is ApiResult.Err -> error = r.message
                    }
                    sending = false
                }
            },
            Modifier.fillMaxWidth(), enabled = draft.isNotBlank() && !sending,
        )
    }
}

/** Text and/or a photo (camera or library) as a new post. */
@Composable
private fun NewPostSheet(vm: FeedViewModel, onDismiss: () -> Unit) {
    val c = Cv.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val capture = rememberRidePhotoCapture()
    var body by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableIntStateOf(0) } // 0 public, 1 followers
    var withLocation by rememberSaveable { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val photo = capture.pending?.let(::File)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    app.convoyage.data.ImageLoader.importPicked(context.contentResolver, uri, File(context.cacheDir, "camera"))
                }
                if (file != null) capture.pending = file.path else error = "That picture could not be read."
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickableNoRipple(onDismiss))
        CvSheet(Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = TabBarClearance).clickableNoRipple {}) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = Space.s4).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                CvText("New post", Cv.type.title, c.ink)
                CvTextField(body, { body = it.take(2000) }, "What's up?", placeholder = "Tell the convoy...", singleLine = false, maxLines = 5)
                if (photo != null) {
                    Box(Modifier.fillMaxWidth().height(180.dp).clip(CutMShape)) {
                        CvImage(ImageSource.Local(photo), Modifier.fillMaxSize(), maxEdgePx = 900)
                        Box(Modifier.align(Alignment.TopEnd).padding(Space.s2)) {
                            CvIconButton(CvIcons.Close, "Remove photo", { photo.delete(); capture.pending = null }, size = 40.dp)
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        CvButton("Camera", { capture.shoot(recording = true) }, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Camera, compact = true)
                        CvButton(
                            "Library", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Image, compact = true,
                        )
                    }
                }
                PhotoHint(capture)
                Segmented(listOf(SegOption("Public"), SegOption("Followers")), visibility, { visibility = it }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        CvText("Show in Nearby", Cv.type.bodyStrong, c.ink)
                        CvText("Attach roughly where you are", Cv.type.bodyS, c.inkMuted)
                    }
                    CvChipToggle(if (withLocation) "On" else "Off", withLocation, { withLocation = !withLocation })
                }
                error?.let { Banner(BannerTone.Pulse, "Could not post", sub = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    CvButton("Cancel", { photo?.delete(); capture.pending = null; onDismiss() }, Modifier.weight(1f), CvButtonKind.Ghost, enabled = !posting)
                    CvButton(
                        if (posting) "Posting..." else "Post",
                        {
                            posting = true
                            error = null
                            scope.launch {
                                when (val r = vm.publish(body, photo, if (visibility == 0) "public" else "followers", withLocation)) {
                                    is ApiResult.Ok -> { capture.pending = null; onDismiss() }
                                    is ApiResult.Err -> { error = r.message; posting = false }
                                }
                            }
                        },
                        Modifier.weight(1f), enabled = !posting && (body.isNotBlank() || photo != null),
                    )
                }
            }
        }
    }
}
