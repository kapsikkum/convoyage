package app.convoyage.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.PsEvent
import app.convoyage.api.PsSpot
import app.convoyage.data.Bbox
import app.convoyage.data.ImageLoader
import app.convoyage.data.ImageSource
import app.convoyage.data.SPOT_SENT_PHOTO_FAILED
import app.convoyage.data.parseSpotTags
import app.convoyage.design.Banner
import app.convoyage.design.BannerTone
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.CutMShape
import app.convoyage.design.Eyebrow
import app.convoyage.design.SegOption
import app.convoyage.design.Segmented
import app.convoyage.design.Space
import app.convoyage.nav.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A PhotoScout spot tapped on the Drive map: cover photo, notes, when it is good, and a way to drive there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpotSheet(spot: PsSpot, onNavigate: (Place) -> Unit, onDismiss: () -> Unit, bottomInset: Dp = 0.dp) {
    val c = Cv.colors
    LayerScrim(onDismiss, bottomInset) {
        Eyebrow("PHOTOSCOUT SPOT" + if (spot.photoCount > 0) " · ${spot.photoCount} PHOTO${if (spot.photoCount == 1) "" else "S"}" else "")
        CvText(spot.name, Cv.type.title, c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        spot.photo?.let {
            CvImage(ImageSource.Remote(it.thumbUrl), Modifier.fillMaxWidth().height(180.dp).clip(CutMShape), maxEdgePx = 800)
        }
        if (spot.notes.isNotBlank()) CvText(spot.notes, Cv.type.body, c.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        goodTimesLine(spot)?.let { Eyebrow("GOOD $it") }
        if (spot.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                for (t in spot.tags.take(8)) CvChipToggle(t, false, {})
            }
        }
        CvButton("Navigate here", { onNavigate(Place(spot.name, "PhotoScout spot", spot.lat, spot.lng)) }, Modifier.fillMaxWidth())
    }
}

/** A PhotoScout event tapped on the map: when and where, RSVP, drive there, and its convoy if it is a cruise. */
@Composable
fun EventSheet(event: PsEvent, onNavigate: (Place) -> Unit, onJoinCruise: (() -> Unit)?, onDismiss: () -> Unit, bottomInset: Dp = 0.dp) {
    val c = Cv.colors
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The list in the repository updates after an RSVP; show the latest copy of this event.
    val layers by container.layers.state.collectAsStateWithLifecycle()
    val e = layers.events.firstOrNull { it.id == event.id } ?: layers.homeEvents.firstOrNull { it.id == event.id } ?: event
    val community = e.source == "community"

    fun rsvp(status: String?) {
        if (busy) return
        busy = true
        scope.launch { error = container.layers.rsvp(e, status); busy = false }
    }

    LayerScrim(onDismiss, bottomInset) {
        Eyebrow(if (e.cruise != null) "CRUISE" else "EVENT")
        CvText(e.title, Cv.type.title, c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Eyebrow(listOfNotNull(Fmt.whenLine(e.startTime).ifBlank { null }, e.venue.ifBlank { null }, e.distanceKm?.let { "%.0f KM AWAY".format(it) }).joinToString(" · "))
        e.cruise?.let { cr ->
            CvText(
                listOfNotNull(cr.routeName.ifBlank { null }?.let { "Route: $it" }, cr.meet.note.ifBlank { null }?.let { "Meet: $it" }, cr.rollOut.ifBlank { null }?.let { "Rolling out $it" })
                    .joinToString("\n"),
                Cv.type.body, c.ink,
            )
        }
        if (e.description.isNotBlank()) CvText(e.description, Cv.type.body, c.inkMuted, maxLines = 5, overflow = TextOverflow.Ellipsis)
        Eyebrow("${e.going} GOING" + if (!community) " · ${e.interested} INTERESTED" else "")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            CvChipToggle("Going", e.myRsvp == "going", { rsvp(if (e.myRsvp == "going") null else "going") })
            if (!community) CvChipToggle("Interested", e.myRsvp == "interested", { rsvp(if (e.myRsvp == "interested") null else "interested") })
        }
        error?.let { Eyebrow(it.uppercase(), color = c.hazard) }
        // A cruise meets somewhere: drive to the meet point, not the event's address.
        val target = e.cruise?.meet?.let { Place(e.title, "Cruise meet", it.lat, it.lng) }
            ?: if (e.lat != null && e.lng != null) Place(e.title, e.venue, e.lat, e.lng) else null
        if (onJoinCruise != null && e.cruise != null) CvButton("Join the cruise convoy", onJoinCruise, Modifier.fillMaxWidth())
        if (target != null) {
            CvButton(
                if (e.cruise != null) "Navigate to the meet" else "Navigate here", { onNavigate(target) }, Modifier.fillMaxWidth(),
                if (onJoinCruise != null && e.cruise != null) CvButtonKind.Ghost else CvButtonKind.Primary,
            )
        }
    }
}

@Composable
internal fun LayerScrim(onDismiss: () -> Unit, bottomInset: Dp, content: @Composable () -> Unit) {
    // Back closes the sheet, not the app.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickableNoRipple(onDismiss))
        CvSheet(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).clickableNoRipple {}) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) { content() }
        }
    }
}

private val PHASES = mapOf("golden" to "golden hour", "blue" to "blue hour", "night" to "at night", "sunrise" to "sunrise", "sunset" to "sunset", "day" to "by day")
private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

/** "GOLDEN HOUR, SUNSET · MAY-AUG"-style summary of a spot's good times, or null when it has none. */
internal fun goodTimesLine(spot: PsSpot): String? {
    val g = spot.goodTimes
    val phases = g.phases.map { PHASES[it] ?: it }.joinToString(", ")
    val months = g.months.filter { it in 1..12 }.sorted().let { ms ->
        when {
            ms.isEmpty() || ms.size == 12 -> ""
            ms.size == ms.last() - ms.first() + 1 && ms.size > 2 -> "${MONTHS[ms.first() - 1]}-${MONTHS[ms.last() - 1]}"
            else -> ms.joinToString(" ") { MONTHS[it - 1] }
        }
    }
    val parts = listOf(phases, months, g.conditions.joinToString(", ")).filter { it.isNotBlank() }
    return parts.joinToString(" · ").uppercase().ifBlank { null }
}

/** Opens PhotoScout's consent page in the browser; it returns to the app at convoyage://photoscout/linked. */
@Composable
fun rememberPhotoScoutConnect(): Pair<() -> Unit, String?> {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val connect: () -> Unit = {
        error = null
        scope.launch {
            container.layers.connectUrl()
                .onSuccess { url ->
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        .onFailure { error = "No browser to open PhotoScout in" }
                }
                .onFailure { error = it.message ?: "Could not reach the server" }
        }
    }
    return connect to error
}

/** Tapped SPOTS or EVENTS before linking: what they are, and the one button that sets them up. */
@Composable
fun ConnectPhotoScoutSheet(onDismiss: () -> Unit, bottomInset: Dp = 0.dp) {
    val c = Cv.colors
    val (connect, error) = rememberPhotoScoutConnect()
    LayerScrim(onDismiss, bottomInset) {
        Eyebrow("PHOTOSCOUT")
        CvText("See photo spots and car events on the map", Cv.type.title, c.ink)
        CvText(
            "Link your PhotoScout account to show its photo spots (with the best times to shoot them) and upcoming events, " +
                "and to join cruise convoys from them. You can unlink it any time in Settings.",
            Cv.type.body, c.inkMuted,
        )
        error?.let { Eyebrow(it.uppercase(), color = c.hazard) }
        CvButton("Connect PhotoScout", connect, Modifier.fillMaxWidth())
        CvButton("Not now", onDismiss, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
    }
}

/** Settings: link or unlink PhotoScout. Hidden on servers without PhotoScout. */
@Composable
fun PhotoScoutSettings() {
    val c = Cv.colors
    val container = LocalContainer.current
    val state by container.layers.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val (connect, error) = rememberPhotoScoutConnect()
    var unlinkError by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { container.layers.refreshStatus() }
    if (!state.configured) return
    Eyebrow("PHOTOSCOUT")
    if (state.linked) {
        CvText("Linked" + (state.username?.let { " as $it" } ?: "") + ". Spots, events and saved routes come from your PhotoScout account.", Cv.type.bodyS, c.inkMuted)
        CvButton("Unlink PhotoScout", { scope.launch { unlinkError = container.layers.unlink() } }, Modifier.fillMaxWidth(), CvButtonKind.Ghost)
        unlinkError?.let { Eyebrow(it.uppercase(), color = c.hazard) }
    } else {
        CvText("Link your PhotoScout account for photo spots and events on the map, cruise convoys, and your saved routes.", Cv.type.bodyS, c.inkMuted)
        CvButton("Connect PhotoScout", connect, Modifier.fillMaxWidth())
        error?.let { Eyebrow(it.uppercase(), color = c.hazard) }
    }
}

/** Shown after a spot goes to PhotoScout; the same line as the iOS app. */
internal const val SPOT_REPORT_SUCCESS = "Sent to PhotoScout. It shows on the map once a moderator approves it."

/** Suggest a new PhotoScout spot: where, what it is, optional tags and photo. It waits for a moderator. */
@Composable
fun ReportSpotSheet(viewport: Bbox?, onSent: (String) -> Unit, onDismiss: () -> Unit, bottomInset: Dp = 0.dp) {
    val c = Cv.colors
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var atMapCentre by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var photo by remember { mutableStateOf<File?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // The library picker, then the same JPEG preparation the feed uses (upright, at most 2560 px, HEIC converted).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    ImageLoader.importPicked(context.contentResolver, uri, File(context.cacheDir, "photoscout"))
                }
                if (file != null) {
                    photo?.delete()
                    photo = file
                    error = null
                } else {
                    error = "That picture could not be read."
                }
            }
        }
    }

    fun submit() {
        if (sending) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            error = "Give the spot a name."
            return
        }
        val lat: Double
        val lng: Double
        if (atMapCentre) {
            val box = viewport
            if (box == null) {
                error = "The map is not ready yet. Try again in a moment."
                return
            }
            lat = (box.south + box.north) / 2
            lng = (box.west + box.east) / 2
        } else {
            val fix = container.tracker.lastKnown()
            if (fix == null) {
                error = "No location yet. Wait for a fix, or use the map centre."
                return
            }
            lat = fix.lat
            lng = fix.lng
        }
        error = null
        sending = true
        scope.launch {
            val bytes = photo?.let { file -> withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } }
            if (photo != null && bytes == null) {
                error = "That picture could not be read."
                sending = false
                return@launch
            }
            val message = container.layers.reportSpot(trimmed, notes, lat, lng, parseSpotTags(tags), bytes)
            sending = false
            when {
                message == null -> { photo?.delete(); photo = null; onSent(SPOT_REPORT_SUCCESS) }
                // The spot is in; only its photo failed. Say so rather than inviting a duplicate report.
                message.startsWith(SPOT_SENT_PHOTO_FAILED) -> { photo?.delete(); photo = null; onSent(message) }
                else -> error = message
            }
        }
    }

    LayerScrim(onDismiss, bottomInset) {
        Eyebrow("PHOTOSCOUT")
        CvText("Report a spot", Cv.type.title, c.ink)
        CvText("Send a spot you found to PhotoScout. A moderator checks it before it appears on the map.", Cv.type.body, c.inkMuted)
        Segmented(
            listOf(SegOption("Where I am"), SegOption("Map centre")),
            if (atMapCentre) 1 else 0,
            { atMapCentre = it == 1; error = null },
            Modifier.fillMaxWidth(),
        )
        CvTextField(name, { name = it.take(120); error = null }, "Name", placeholder = "Mount Panorama lookout", enabled = !sending)
        CvTextField(
            notes, { notes = it.take(1000) }, "Notes", placeholder = "Parking, access, the view",
            enabled = !sending, singleLine = false, maxLines = 4,
        )
        CvTextField(tags, { tags = it.take(400) }, "Tags (comma separated)", placeholder = "sunset, lookout", enabled = !sending)
        photo?.let { file ->
            Box(Modifier.fillMaxWidth().height(180.dp).clip(CutMShape)) {
                CvImage(ImageSource.Local(file), Modifier.fillMaxSize(), maxEdgePx = 900)
                Box(Modifier.align(Alignment.TopEnd).padding(Space.s2)) {
                    CvIconButton(CvIcons.Close, "Remove photo", { file.delete(); photo = null }, size = 40.dp)
                }
            }
        } ?: CvButton(
            "Add a photo", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Image, enabled = !sending, compact = true,
        )
        error?.let { Banner(BannerTone.Pulse, "Could not report the spot", sub = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            CvButton("Cancel", { photo?.delete(); photo = null; onDismiss() }, Modifier.weight(1f), CvButtonKind.Ghost, enabled = !sending)
            CvButton(if (sending) "Sending..." else "Report spot", ::submit, Modifier.weight(1f), enabled = !sending, icon = CvIcons.Flag)
        }
    }
}
