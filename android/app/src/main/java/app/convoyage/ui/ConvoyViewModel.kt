package app.convoyage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.convoyage.AppContainer
import app.convoyage.data.Chat
import app.convoyage.data.ConvoyAlert
import app.convoyage.data.Fix
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.Roster
import app.convoyage.data.StatusEvent
import app.convoyage.data.RosterEntry
import app.convoyage.data.Side
import app.convoyage.design.MemberState
import app.convoyage.design.PttState
import app.convoyage.protocol.ConvoyMessage
import app.convoyage.protocol.Destination
import kotlinx.coroutines.launch
import app.convoyage.protocol.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class ConvoyUi(
    val link: Link = Link.Offline,
    val meId: String? = null,
    val room: Room? = null,
    val roster: List<RosterEntry> = emptyList(),
    val alert: ConvoyAlert? = null,
    val chat: List<ConvoyMessage> = emptyList(),
    val fix: Fix? = null,
    val error: String? = null,
    val removed: Boolean = false,
    val spreadM: Double = 0.0,
    val isLeader: Boolean = false,
    /** Messages from others that arrived since the chat was last open. */
    val unread: Int = 0,
    /** The newest unread SOS from somebody else, for the banner. */
    val sos: StatusEvent? = null,
    /** Display name of [sos] sender. */
    val sosName: String = "",
) {
    val me: RosterEntry? get() = roster.firstOrNull { it.isMe }
    val onlineCount: Int get() = roster.count { it.state != MemberState.Offline }
    val leader: RosterEntry? get() = room?.leader?.let { id -> roster.firstOrNull { it.id == id } }

    /** The member nearest the back of the convoy as seen from us (largest distance behind). */
    val lastRider: RosterEntry? get() = roster.filter { !it.isMe && it.side == Side.Behind && it.distanceM != null }.maxByOrNull { it.distanceM!! }
}

/** Transient local-only UI state for the convoy screens. */
data class ConvoyLocal(
    val buildings3d: Boolean = true,
    val follow: Boolean = true,
    val recenterTick: Int = 0,
    val ptt: PttState = PttState.Idle,
    val iAmStopping: Boolean = false,
    /** A spot the leader long-pressed on the map and has not confirmed yet. */
    val pendingDest: PendingDest? = null,
    /** A short, self-clearing note ("Only the leader can set the destination"). */
    val hint: String? = null,
)

/** A destination waiting for the leader's confirmation. */
data class PendingDest(val lat: Double, val lng: Double)

class ConvoyViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.convoy
    private val _local = MutableStateFlow(ConvoyLocal())
    val local: StateFlow<ConvoyLocal> = _local

    /** Per room: the timestamp of the newest chat message that has been read (seeded when the room first appears). */
    private val reads = MutableStateFlow<Map<String, Long>>(emptyMap())

    init {
        viewModelScope.launch {
            repo.state.collect { s ->
                val missing = s.rooms.keys.filter { it !in reads.value }
                if (missing.isNotEmpty()) reads.update { r -> r + missing.associateWith { Chat.latestTs(s.chat[it].orEmpty()) } }
            }
        }
    }

    private val sosDismissedAt = MutableStateFlow(0L)
    fun dismissSos() { sosDismissedAt.value = System.currentTimeMillis() }

    val ui: StateFlow<ConvoyUi> = combine(repo.state, container.tracker.fix, reads, sosDismissedAt) { s, fix, readMap, dismissed ->
        val room = s.active
        val roster = room?.let { Roster.build(it, s.meId, fix, container.session.name.value) }.orEmpty()
        val chat = s.active?.let { s.chat[it.code] }.orEmpty()
        val readTs = room?.let { readMap[it.code] } ?: Long.MAX_VALUE
        val sosEvent = room?.let { s.statuses[it.code].orEmpty().values }.orEmpty()
            .filter { it.isSos && it.memberId != s.meId && it.atMs > dismissed }.maxByOrNull { it.atMs }
        ConvoyUi(
            link = s.link,
            meId = s.meId,
            room = room,
            roster = roster,
            alert = room?.let { Roster.alert(it, roster, s.meId) },
            chat = chat,
            fix = fix,
            error = s.error,
            removed = s.removedFrom != null,
            spreadM = room?.let { Roster.spreadM(it) } ?: 0.0,
            isLeader = room != null && s.meId == room.leader,
            unread = Chat.unread(chat, s.meId, readTs),
            sos = sosEvent,
            sosName = sosEvent?.let { e -> roster.firstOrNull { it.id == e.memberId }?.name } ?: "",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConvoyUi())

    /** The chat is on screen: everything in it counts as read. */
    fun markChatRead() {
        val s = repo.state.value
        val code = s.activeCode ?: return
        val latest = Chat.latestTs(s.chat[code].orEmpty())
        reads.update { r -> if ((r[code] ?: 0L) >= latest) r else r + (code to latest) }
    }

    /** Sends exactly what was typed (blank = the server's default name). [regroupSeparationM] non-null = automatic regroup on. */
    fun create(name: String, regroupSeparationM: Double? = null) = repo.createConvoy(name.trim(), regroupSeparationM)
    fun join(code: String) = repo.join(code)
    fun sendChat(text: String) = repo.sendChat(text)

    // ---- leader tools ----
    fun proposeDestination(lat: Double, lng: Double) {
        if (!ui.value.isLeader) {
            flashHint("Only the leader can set the destination")
            return
        }
        _local.update { it.copy(pendingDest = PendingDest(lat, lng)) }
    }

    fun cancelPendingDestination() = _local.update { it.copy(pendingDest = null) }

    fun confirmDestination(label: String) {
        val p = _local.value.pendingDest ?: return
        repo.setDestination(Destination(p.lat, p.lng, label.trim().ifBlank { "Dropped pin" }.take(80)), ui.value.fix)
        _local.update { it.copy(pendingDest = null) }
    }

    fun clearDestination() = repo.setDestination(null, null)
    fun handOver(memberId: String) = repo.handOver(memberId)
    fun remove(memberId: String) = repo.remove(memberId)
    fun continueFromRest() = repo.continueFromRest()

    private var hintJob: kotlinx.coroutines.Job? = null
    private fun flashHint(text: String) {
        _local.update { it.copy(hint = text) }
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3_000)
            _local.update { it.copy(hint = null) }
        }
    }

    fun leave() = repo.leave()
    fun findRestStop() = repo.findRestStop()
    fun dismissError() = repo.clearError()

    fun toggle3d() = _local.update { it.copy(buildings3d = !it.buildings3d) }
    fun userMovedMap() = _local.update { if (it.follow) it.copy(follow = false) else it }
    fun recenter() = _local.update { it.copy(follow = true, recenterTick = it.recenterTick + 1) }

    /** UI only: audio is not built yet. TODO(ptt): capture + stream voice; flip to Receiving from the server. */
    fun pttDown() = _local.update { it.copy(ptt = PttState.Live) }
    fun pttUp() = _local.update { it.copy(ptt = PttState.Idle) }

    /**
     * "I'm stopping": a local banner, plus a convoy chat message (`convoy.message`) because the v1 protocol has chat
     * but no dedicated stopped signal. Others also see the speed drop to ~0 on the roster.
     */
    fun toggleStopping() {
        val stopping = !_local.value.iAmStopping
        _local.update { it.copy(iAmStopping = stopping) }
        repo.sendStatus(if (stopping) "stopping" else "moving")
    }


    fun metersToLeader(ui: ConvoyUi): Double? = ui.leader?.takeIf { !it.isMe }?.distanceM

    companion object {
        fun speedKmh(fix: Fix?): Int = fix?.speedMps?.let { Geo.mpsToKmh(it).toInt() } ?: 0
    }
}
