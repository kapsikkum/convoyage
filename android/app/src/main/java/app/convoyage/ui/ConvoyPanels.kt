package app.convoyage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.convoyage.data.Chat
import app.convoyage.data.Geo
import app.convoyage.data.Names
import app.convoyage.data.RosterEntry
import app.convoyage.data.Side
import app.convoyage.data.SosMessage
import app.convoyage.design.CutMShape
import app.convoyage.design.CutSShape
import app.convoyage.design.Cv
import app.convoyage.design.CvButton
import app.convoyage.design.CvButtonKind
import app.convoyage.design.CvChipToggle
import app.convoyage.design.CvIconButton
import app.convoyage.design.CvIcons
import app.convoyage.design.CvText
import app.convoyage.design.CvTextField
import app.convoyage.design.Eyebrow
import app.convoyage.design.MemberState
import app.convoyage.design.RosterChip
import app.convoyage.design.Space
import app.convoyage.design.cutPanel
import app.convoyage.protocol.ConvoyMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private fun clockOf(ts: Long) = if (ts <= 0) "" else clockFormat.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

// ---- Roster -------------------------------------------------------------------------------------

@Composable
fun RosterPanel(ui: ConvoyUi, onShare: () -> Unit, onCopy: () -> Unit, onQr: () -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var liveError by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        item(key = "invite") {
            InviteRow(onShare, onCopy, onQr)
        }
        item(key = "live") {
            val room = ui.room
            CvButton("Live link for people without the app", {
                if (room != null) scope.launch {
                    when (val r = container.api.liveLink(room.code)) {
                        is app.convoyage.api.ApiResult.Ok -> { liveError = null; shareLiveLink(context, r.value.url, room.name) }
                        is app.convoyage.api.ApiResult.Err -> liveError = r.message
                    }
                }
            }, Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Share, compact = true)
            liveError?.let { CvText(it, Cv.type.bodyS, Cv.colors.pulse) }
        }
        items(ui.roster, key = { it.id }) { e -> RosterRow(e) }
        item(key = "leave") {
            CvButton("Leave convoy", onLeave, Modifier.fillMaxWidth().padding(top = Space.s2, bottom = Space.s2), CvButtonKind.Ghost)
        }
    }
}

@Composable
private fun InviteRow(onShare: () -> Unit, onCopy: () -> Unit, onQr: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        CvButton("Share", onShare, Modifier.weight(1f), icon = CvIcons.Share, compact = true)
        CvButton("Copy", onCopy, Modifier.weight(1f), CvButtonKind.Ghost, compact = true)
        CvButton("QR", onQr, Modifier.weight(1f), CvButtonKind.Ghost, icon = CvIcons.Qr, compact = true)
    }
}

@Composable
private fun RosterRow(e: RosterEntry) {
    val c = Cv.colors
    val meta = when {
        e.state == MemberState.Offline -> "Offline"
        e.state == MemberState.Lead -> "Leading" + (e.speedKmh?.let { " · $it km/h" } ?: "")
        e.state == MemberState.Stopped -> "Stopped"
        else -> e.speedKmh?.let { "$it km/h" } ?: "Online"
    }
    RosterChip(
        initials = e.initials,
        name = e.name,
        meta = meta,
        state = e.state,
        distance = e.distanceM?.let { Geo.formatDistance(it).uppercase() },
        distanceCaption = when (e.side) { Side.Ahead -> "ahead"; Side.Behind -> "behind"; Side.Away -> null },
        metaColor = if (e.state == MemberState.Stopped) c.pulse else c.inkMuted,
        photoUrl = e.photoUrl,
        vehicle = e.vehicle,
    )
}

// ---- Chat ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(ui: ConvoyUi, onSend: (String) -> Boolean, onRead: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cv.colors
    var draft by rememberSaveable { mutableStateOf("") }
    // The chat is on screen: whatever is in it counts as read, including what arrives while it stays open.
    LaunchedEffect(ui.chat.size) { onRead() }

    fun send(text: String) {
        if (onSend(text)) draft = ""
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.chat.isEmpty()) {
                CvText("No messages yet. Tap a quick reply or type below.", Cv.type.bodyS, c.inkMuted, Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    items(ui.chat.asReversed(), key = { it.id.ifBlank { it.clientId + it.ts } }) { m -> ChatBubble(m, mine = m.userId == ui.meId) }
                }
            }
        }
        // All four replies show: they wrap onto a second line on a narrow phone instead of running off the edge.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Chat.QUICK_REPLIES.forEach { q -> CvChipToggle(q, false, { send(q) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(500) },
                singleLine = true,
                textStyle = Cv.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.signal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(draft) }),
                modifier = Modifier.weight(1f).cutPanel(c.surface200, c.lineStrong, CutSShape).defaultMinSize(minHeight = Space.target),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = Space.s3, vertical = Space.s3), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) CvText("Message the convoy", Cv.type.body, c.inkMuted)
                        inner()
                    }
                },
            )
            CvIconButton(CvIcons.Navigate, "Send message", { send(draft) }, tint = if (draft.isBlank()) c.inkMuted else c.signal, edge = if (draft.isBlank()) c.lineStrong else c.signal)
        }
    }
}

@Composable
private fun ChatBubble(m: ConvoyMessage, mine: Boolean) {
    val c = Cv.colors
    val sos = SosMessage.isSos(m.text)
    val edge = when { sos -> c.pulse; mine -> c.signal; else -> c.lineStrong }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.defaultMinSize(minWidth = 96.dp).fillMaxWidth(0.82f)
                .cutPanel(if (sos) c.pulse.copy(alpha = 0.18f) else if (mine) c.signalDim else c.surface200, edge, CutMShape)
                .padding(horizontal = Space.s3, vertical = Space.s2),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Eyebrow(if (mine) "YOU" else Names.friendly(m.username).uppercase(), color = if (sos) c.pulse else c.inkMuted)
                Eyebrow(clockOf(m.ts))
            }
            CvText(m.text, Cv.type.body, c.ink)
        }
    }
}

// ---- Leader tools -------------------------------------------------------------------------------

@Composable
fun LeaderPanel(
    ui: ConvoyUi,
    onClearDestination: () -> Unit,
    onRename: (String) -> Unit,
    onFindRest: () -> Unit,
    onContinue: () -> Unit,
    onHandOver: (RosterEntry) -> Unit,
    onRemove: (RosterEntry) -> Unit,
    modifier: Modifier = Modifier,
    sharedRoute: app.convoyage.data.SharedSavedRoute? = null,
    onPickRoute: () -> Unit = {},
    onRollOut: () -> Unit = {},
    onSetOpen: (Boolean) -> Unit = {},
    onJustCruise: () -> Unit = {},
) {
    val c = Cv.colors
    val room = ui.room ?: return
    val regroup = room.automaticRegroup
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        item(key = "name") { RenameRow(room.name, onRename) }
        item(key = "dest") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Eyebrow("DESTINATION")
                val d = room.destination
                if (d == null) {
                    CvText("None yet. Long-press the map to drop a pin, then confirm.", Cv.type.bodyS, c.inkMuted)
                } else {
                    CvText(d.label.ifBlank { "Dropped pin" }, Cv.type.bodyStrong, c.ink)
                    CvText("%.4f, %.4f".format(java.util.Locale.ROOT, d.lat, d.lng), Cv.type.telemetry, c.inkMuted)
                    CvButton("Clear destination", onClearDestination, Modifier.fillMaxWidth(), CvButtonKind.Ghost, compact = true)
                }
            }
        }
        item(key = "route") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Eyebrow("ROUTE")
                val r = room.route
                if (r == null) {
                    CvText("No shared route. Drive one of your saved routes, or set a destination.", Cv.type.bodyS, c.inkMuted)
                } else {
                    CvText(r.name.ifBlank { "Convoy route" }, Cv.type.bodyStrong, c.ink)
                    CvText("${r.stops.size} stops", Cv.type.telemetry, c.inkMuted)
                }
                val shared = sharedRoute?.takeIf { it.code == room.code }
                val atStaging = shared?.staging != null && room.destination?.let { d -> d.lat == shared.staging.lat && d.lng == shared.staging.lng } == true
                if (atStaging) {
                    CvText("Everyone is heading to the staging point. Roll out when the convoy is together.", Cv.type.bodyS, c.inkMuted)
                    CvButton("Roll out", onRollOut, Modifier.fillMaxWidth(), compact = true)
                }
                CvButton(if (r == null) "Drive a saved route" else "Change route", onPickRoute, Modifier.fillMaxWidth(), CvButtonKind.Ghost, icon = CvIcons.Route, compact = true)
                if (r != null || room.destination != null) {
                    CvButton("Just cruise", onJustCruise, Modifier.fillMaxWidth(), CvButtonKind.Ghost, compact = true)
                    CvText("Clears the destination and route: everyone just follows the leader.", Cv.type.bodyS, c.inkMuted)
                }
            }
        }
        item(key = "open") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Column(Modifier.weight(1f)) {
                    CvText("Open convoy", Cv.type.bodyStrong, c.ink)
                    CvText("Open Cruise drivers within 5 km can see it and join without the code.", Cv.type.bodyS, c.inkMuted)
                }
                CvChipToggle(if (room.open) "On" else "Off", room.open, { onSetOpen(!room.open) })
            }
        }
        item(key = "regroup") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Eyebrow("REGROUP")
                CvText(
                    if (regroup.enabled) "Automatic regroup is on: a stop is called when the convoy spreads past ${Geo.formatDistance(regroup.separationM)}."
                    else "Automatic regroup is off. It is chosen when a convoy starts.",
                    Cv.type.bodyS, c.inkMuted,
                )
                if (room.resting) {
                    CvButton("Continue to destination", onContinue, Modifier.fillMaxWidth(), compact = true)
                } else {
                    CvButton("Find a rest stop", onFindRest, Modifier.fillMaxWidth(), CvButtonKind.Ghost, enabled = room.route != null, compact = true)
                    if (room.route == null) CvText("Set a destination first: the rest stop is searched along the route.", Cv.type.bodyS, c.inkMuted)
                }
            }
        }
        item(key = "members") { Eyebrow("MEMBERS") }
        val others = ui.roster.filter { !it.isMe }
        if (others.isEmpty()) {
            item(key = "none") { CvText("Nobody else has joined yet. Share the code.", Cv.type.bodyS, c.inkMuted) }
        }
        items(others, key = { "m-" + it.id }) { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Column(Modifier.weight(1f)) {
                    CvText(e.name, Cv.type.bodyStrong, c.ink, maxLines = 1)
                    Eyebrow(if (e.state == MemberState.Offline) "OFFLINE" else "ONLINE")
                }
                CvButton("Lead", { onHandOver(e) }, kind = CvButtonKind.Ghost, compact = true)
                CvButton("Remove", { onRemove(e) }, kind = CvButtonKind.Alert, compact = true)
            }
        }
    }
}

/** The convoy name, editable by the leader. Saves on the check button or the keyboard's Done. */
@Composable
private fun RenameRow(current: String, onRename: (String) -> Unit) {
    val c = Cv.colors
    var draft by rememberSaveable(current) { mutableStateOf(current) }
    val changed = draft.trim().isNotEmpty() && draft.trim() != current
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.Bottom) {
            CvTextField(
                draft, { draft = it.take(Names.NAME_MAX) }, "Convoy name", Modifier.weight(1f), placeholder = "Name your convoy",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
            CvIconButton(
                CvIcons.Check, "Save convoy name", { if (changed) onRename(draft.trim()) },
                tint = if (changed) c.signal else c.inkMuted, edge = if (changed) c.signal else c.lineStrong,
            )
        }
    }
}
