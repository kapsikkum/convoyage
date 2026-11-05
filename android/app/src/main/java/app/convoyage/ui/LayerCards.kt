package app.convoyage.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.convoyage.api.PsEvent
import app.convoyage.api.PsSpot
import app.convoyage.data.ImageSource
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvSheet
import app.convoyage.design.CvText
import app.convoyage.design.CutMShape
import app.convoyage.design.Eyebrow
import app.convoyage.design.Space
import app.convoyage.nav.Place
import kotlinx.coroutines.launch

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
    val e = layers.events.firstOrNull { it.id == event.id } ?: event
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
