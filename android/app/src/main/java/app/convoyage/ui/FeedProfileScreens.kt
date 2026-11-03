package app.convoyage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.convoyage.AppContainer
import app.convoyage.api.ApiResult
import app.convoyage.data.AuthKind
import app.convoyage.data.Roster
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.CvTextField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.convoyage.design.CutLShape
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvAvatar
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvCard
import app.convoyage.design.CvIcon
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.Eyebrow
import app.convoyage.design.MemberState
import app.convoyage.design.Readout
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.design.gridBackdrop

/** Static placeholder feed. TODO(feed): the social feed API does not exist yet. */
@Composable
fun FeedScreen() {
    val c = Cv.colors
    var filter by remember { mutableIntStateOf(1) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenGutters().padding(top = Space.s6, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CvText("Feed", Cv.type.displayL, c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                CvIconButton(CvIcons.Comment, "Messages", {})
                CvIconButton(CvIcons.Plus, "New post", {})
            }
        }
        Segmented(listOf(SegOption("Everyone"), SegOption("Following"), SegOption("Nearby")), filter, { filter = it }, Modifier.fillMaxWidth())

        PostCard(
            initials = "JK", state = MemberState.Lead, who = "Jordan K", what = "WRX STI", sub = "LED SUNDAY RUN · 2 H AGO", badge = "RIDE",
            body = { RouteThumb("9 PHOTOS ON ROUTE") },
            stats = listOf("186" to "KM", "3:42" to "H", "7" to "IN CONVOY"), likes = 48, comments = 12, footer = "You rode this",
        )
        PostCard(
            initials = "PR", state = null, who = "Priya R", what = "new build", sub = "GARAGE · 5 H AGO", badge = "BUILD",
            body = { Box(Modifier.fillMaxWidth().height(150.dp).cutPanel(c.surface100, c.line, CutMShape).gridBackdrop(c.line.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Eyebrow("VEHICLE RENDER PLACEHOLDER") } },
            stats = emptyList(), likes = 31, comments = 6, footer = "Sonic blue + signal rims. Lowered 30 mm. Track day Saturday?",
        )
    }
}

@Composable
private fun RouteThumb(caption: String) {
    val c = Cv.colors
    Box(Modifier.fillMaxWidth().height(150.dp).cutPanel(c.surface100, c.line, CutMShape).gridBackdrop(c.line.copy(alpha = 0.5f))) {
        Box(Modifier.align(Alignment.BottomStart).padding(Space.s2).cutPanel(c.glass, c.lineStrong, CutSShape).padding(horizontal = Space.s2, vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CvIcon(CvIcons.Camera, c.signal, size = 14.dp)
                Eyebrow(caption)
            }
        }
        Box(Modifier.align(Alignment.Center)) { CvIcon(CvIcons.Route, c.signal, size = 40.dp) }
    }
}

@Composable
private fun PostCard(
    initials: String, state: MemberState?, who: String, what: String, sub: String, badge: String,
    body: @Composable () -> Unit, stats: List<Pair<String, String>>, likes: Int, comments: Int, footer: String,
) {
    val c = Cv.colors
    CvCard(Modifier.fillMaxWidth(), shape = CutLShape) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                CvAvatar(initials, state)
                Column(Modifier.weight(1f)) {
                    Row { CvText(who, Cv.type.bodyStrong, c.ink); CvText(" · $what", Cv.type.bodyS, c.inkMuted) }
                    Eyebrow(sub)
                }
                Box(Modifier.cutPanel(c.surface300, c.lineStrong, CutSShape).padding(horizontal = 8.dp, vertical = 4.dp)) { Eyebrow(badge, color = c.signal) }
            }
            body()
            if (stats.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                    stats.forEach { (v, u) ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            CvText(v, Cv.type.telemetry.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600), c.ink)
                            CvText(" $u", Cv.type.label.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)), c.inkMuted)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { CvIcon(CvIcons.Heart, c.inkMuted, size = 20.dp); CvText("$likes", Cv.type.telemetry, c.ink) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { CvIcon(CvIcons.Comment, c.inkMuted, size = 20.dp); CvText("$comments", Cv.type.telemetry, c.ink) }
                CvText(footer, Cv.type.bodyS, c.inkMuted, modifier = Modifier.weight(1f))
            }
        }
    }
}

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

/** Profile: display name and handle come from `GET /v1/me` (editable); the stats and posts are placeholders. */
@Composable
fun ProfileScreen(onOpenGarage: () -> Unit, onSignOut: () -> Unit) {
    val c = Cv.colors
    val container = LocalContainer.current
    val vm: ProfileViewModel = viewModel(factory = viewModelFactory { initializer { ProfileViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val me by container.account.me.collectAsStateWithLifecycle()
    val name by container.session.name.collectAsState()
    val kind by container.session.kind.collectAsState()
    val serverUrl by container.session.serverUrl.collectAsState()
    val night by container.session.night.collectAsState()
    var section by remember { mutableIntStateOf(1) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val shownName = me?.displayName?.ifBlank { null } ?: me?.handle ?: name ?: "Guest"
    val handle = me?.handle?.let { "@$it" } ?: if (kind == AuthKind.Dev) "dev sign-in, no account" else "@..."
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).screenGutters().padding(top = Space.s6, bottom = TabBarClearance),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CvIcon(CvIcons.Lock, c.inkMuted, size = 16.dp); Eyebrow(if (me?.isPrivate == false) "PUBLIC ACCOUNT" else "PRIVATE ACCOUNT")
            }
            CvIconButton(CvIcons.Settings, "Settings", {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.CenterVertically) {
            CvAvatar(Roster.initials(shownName), null, size = 64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                CvText(shownName, Cv.type.displayL, c.ink, maxLines = 2)
                CvText("$handle · drives + rides", Cv.type.bodyS, c.inkMuted)
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
                Column(Modifier.weight(1f)) { Eyebrow("PRIMARY · ${SampleVehicles.size} IN GARAGE", color = c.signal); CvText(SampleVehicles.first().name, Cv.type.title, c.ink) }
                CvIcon(CvIcons.Chevron, c.inkMuted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Readout("Rides", "0", Modifier.weight(1f)); Readout("Distance", "0", Modifier.weight(1f), unit = "km"); Readout("Seat time", "0", Modifier.weight(1f), unit = "h")
        }
        Segmented(listOf(SegOption("Posts"), SegOption("Routes"), SegOption("Photos"), SegOption("Garage")), section, { section = it }, Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().height(120.dp).cutPanel(c.surface100, c.line, CutMShape).gridBackdrop(c.line.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Eyebrow("NOTHING HERE YET") }

        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Eyebrow("THIS DEVICE")
            CvText("Server  $serverUrl", Cv.type.telemetry.copy(fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)), c.inkMuted)
            CvButton(if (night) "Switch to day theme" else "Switch to night theme", { container.session.setNight(!night) }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
            CvButton("Sign out", onSignOut, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        }
    }
}
