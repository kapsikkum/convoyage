package app.convoyage.data

import app.convoyage.design.MemberState
import app.convoyage.protocol.Member
import app.convoyage.protocol.Room

enum class Side { Ahead, Behind, Away }

data class RosterEntry(
    val id: String,
    val name: String,
    val initials: String,
    val state: MemberState,
    val isMe: Boolean,
    val speedKmh: Int?,
    val distanceM: Double?,
    val side: Side,
    val lat: Double?,
    val lng: Double?,
    val headingDeg: Double?,
    /** Their primary vehicle (avatar badge) and profile photo path, when the server sent them. */
    val vehicle: app.convoyage.api.VehicleBadge? = null,
    val photoUrl: String? = null,
)

enum class AlertKind { Stopped, Resting }

data class ConvoyAlert(
    val kind: AlertKind,
    val title: String,
    val sub: String,
    val canRegroup: Boolean,
    /** The leader can end a regroup stop and carry on. */
    val canContinue: Boolean = false,
)

/** Turns a server [Room] plus our own fix into what the roster, pins and banners show. */
object Roster {
    /** Below this a moving vehicle counts as stopped (m/s, about 3 km/h). */
    const val STOPPED_MPS = 0.8

    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    fun isStopped(m: Member): Boolean = m.online && (m.position?.speed ?: return false) < STOPPED_MPS

    fun stateOf(m: Member, room: Room): MemberState = when {
        !m.online -> MemberState.Offline
        m.id == room.leader -> MemberState.Lead
        isStopped(m) -> MemberState.Stopped
        else -> MemberState.Online
    }

    fun build(room: Room, meId: String?, me: Fix?, myName: String? = null): List<RosterEntry> =
        room.members.map { m ->
            val p = m.position
            val isMe = m.id == meId
            val dist = if (!isMe && p != null && me != null) Geo.distanceM(me.lat, me.lng, p.lat, p.lng) else null
            val side = when {
                dist == null || p == null || me?.headingDeg == null -> Side.Away
                Geo.angleDiff(Geo.bearingDeg(me.lat, me.lng, p.lat, p.lng), me.headingDeg) < 90.0 -> Side.Ahead
                else -> Side.Behind
            }
            RosterEntry(
                id = m.id,
                name = if (isMe) "You" else Names.friendly(m.username),
                initials = initials(if (isMe && !myName.isNullOrBlank()) myName else Names.friendly(m.username)),
                state = stateOf(m, room),
                isMe = isMe,
                speedKmh = p?.speed?.let { Geo.mpsToKmh(it).toInt() },
                distanceM = dist,
                side = side,
                lat = p?.lat,
                lng = p?.lng,
                headingDeg = p?.heading,
                vehicle = m.vehicle,
                photoUrl = m.avatar.takeIf { it.startsWith("/v1/media/") },
            )
        }.sortedWith(compareBy<RosterEntry>({ it.id != room.leader }, { !it.isMe }, { it.distanceM ?: Double.MAX_VALUE }))

    /** The single most important alert for the banner, or null. */
    fun alert(room: Room, entries: List<RosterEntry>, meId: String?): ConvoyAlert? {
        if (room.resting) {
            val iAmLeader = meId != null && meId == room.leader
            return ConvoyAlert(
                AlertKind.Resting, "Regroup stop", room.destination?.label?.ifBlank { null } ?: "The convoy is regrouping",
                canRegroup = false, canContinue = iAmLeader,
            )
        }
        val stopped = entries.firstOrNull { it.state == MemberState.Stopped && !it.isMe } ?: return null
        val where = stopped.distanceM?.let { " · " + Geo.formatDistance(it) + (if (stopped.side == Side.Behind) " behind" else if (stopped.side == Side.Ahead) " ahead" else " away") } ?: ""
        val iAmLeader = meId != null && meId == room.leader
        return ConvoyAlert(
            kind = AlertKind.Stopped,
            title = "${stopped.name} stopped$where",
            sub = if (iAmLeader) "Call a regroup?" else "The convoy leader has been told",
            canRegroup = iAmLeader && room.route != null,
        )
    }

    /** "SPREAD" for the sheet header: the largest distance between any two positioned members. */
    fun spreadM(room: Room): Double {
        val pts = room.members.filter { it.online }.mapNotNull { it.position }
        var best = 0.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            best = maxOf(best, Geo.distanceM(pts[i].lat, pts[i].lng, pts[j].lat, pts[j].lng))
        }
        return best
    }
}
