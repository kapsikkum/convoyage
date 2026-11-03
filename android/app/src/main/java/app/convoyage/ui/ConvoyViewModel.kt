package app.convoyage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.convoyage.AppContainer
import app.convoyage.data.ConvoyAlert
import app.convoyage.data.Fix
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.Roster
import app.convoyage.data.RosterEntry
import app.convoyage.data.Side
import app.convoyage.design.MemberState
import app.convoyage.design.PttState
import app.convoyage.protocol.ConvoyMessage
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
)

class ConvoyViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.convoy
    private val _local = MutableStateFlow(ConvoyLocal())
    val local: StateFlow<ConvoyLocal> = _local

    val ui: StateFlow<ConvoyUi> = combine(repo.state, container.tracker.fix) { s, fix ->
        val room = s.active
        val roster = room?.let { Roster.build(it, s.meId, fix) }.orEmpty()
        ConvoyUi(
            link = s.link,
            meId = s.meId,
            room = room,
            roster = roster,
            alert = room?.let { Roster.alert(it, roster, s.meId) },
            chat = s.active?.let { s.chat[it.code] }.orEmpty(),
            fix = fix,
            error = s.error,
            removed = s.removedFrom != null,
            spreadM = room?.let { Roster.spreadM(it) } ?: 0.0,
            isLeader = room != null && s.meId == room.leader,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConvoyUi())

    fun create(name: String) = repo.createConvoy(name.ifBlank { "${container.session.name.value ?: "My"}'s convoy" })
    fun join(code: String) = repo.join(code)
    fun leave() = repo.leave()
    fun findRestStop() = repo.findRestStop()
    fun dismissError() = repo.clearError()

    fun toggle3d() = _local.update { it.copy(buildings3d = !it.buildings3d) }
    fun userMovedMap() = _local.update { if (it.follow) it.copy(follow = false) else it }
    fun recenter() = _local.update { it.copy(follow = true, recenterTick = it.recenterTick + 1) }

    /** UI only: audio is not built yet. TODO(ptt): capture + stream voice; flip to Receiving from the server. */
    fun pttDown() = _local.update { it.copy(ptt = PttState.Live) }
    fun pttUp() = _local.update { it.copy(ptt = PttState.Idle) }

    /** "I'm stopping": local flag only. TODO(protocol): the v1 schema has no stopped signal; speed ~0 is what others see. */
    fun toggleStopping() = _local.update { it.copy(iAmStopping = !it.iAmStopping) }


    fun metersToLeader(ui: ConvoyUi): Double? = ui.leader?.takeIf { !it.isMe }?.distanceM

    companion object {
        fun speedKmh(fix: Fix?): Int = fix?.speedMps?.let { Geo.mpsToKmh(it).toInt() } ?: 0
    }
}
