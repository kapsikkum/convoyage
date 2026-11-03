package app.convoyage.data

/** Something worth saying out loud about the convoy. [message] lines are chat; the rest are events. */
data class ConvoyNotice(val room: String, val text: String, val message: Boolean = false)

/**
 * What changed between two convoy states, as spoken lines (PhotoScout's convoy events): joins, leaves, reconnects,
 * a new leader, destination / rest stop / route changes, stopping and SOS, and other members' chat.
 * Only the active convoy is announced. Pure, so it is unit tested.
 */
object ConvoyEvents {
    const val ARRIVED_M = 100.0
    const val LEFT_M = 150.0

    fun between(prev: ConvoyState, next: ConvoyState): List<ConvoyNotice> {
        val code = next.activeCode ?: return emptyList()
        val room = next.rooms[code] ?: return emptyList()
        val before = prev.rooms[code]
        val me = next.meId
        if (before == null) {
            // Joining (or the first hello after a reconnect) is announced once, without replaying history.
            return if (prev.link == Link.Online && prev.meId != null) listOf(ConvoyNotice(code, "Joined ${room.name.ifBlank { "the convoy" }}")) else emptyList()
        }
        val out = mutableListOf<ConvoyNotice>()
        fun say(text: String) { out += ConvoyNotice(code, text) }
        val name = { id: String -> room.members.firstOrNull { it.id == id }?.username?.ifBlank { null } ?: before.members.firstOrNull { it.id == id }?.username?.ifBlank { null } ?: "A member" }

        room.members.filter { m -> m.id != me && before.members.none { it.id == m.id } }.forEach { say("${it.username.ifBlank { "Someone" }} joined the convoy") }
        before.members.filter { m -> m.id != me && room.members.none { it.id == m.id } }.forEach { say("${it.username.ifBlank { "Someone" }} left the convoy") }
        room.members.forEach { m ->
            val was = before.members.firstOrNull { it.id == m.id } ?: return@forEach
            if (m.id != me && was.online != m.online) say("${name(m.id)} ${if (m.online) "reconnected" else "disconnected"}")
        }
        if (before.leader != room.leader && room.leader.isNotEmpty()) say(if (room.leader == me) "You are now leading" else "${name(room.leader)} is now leading")
        if (before.destination != room.destination) {
            val d = room.destination
            say(when {
                d == null -> "Convoy destination cleared"
                room.resting -> "Rest stop: ${d.label.ifBlank { "new regroup point" }}"
                else -> "Destination: ${d.label.ifBlank { "new destination" }}"
            })
        }
        if (before.route?.name != room.route?.name || (before.route == null) != (room.route == null)) {
            say(room.route?.let { "Route shared: ${it.name.ifBlank { "convoy route" }}" } ?: "Convoy route cleared")
        }

        // Arrivals (Konvoyage's arrival detection): another member comes within 100 m of the destination they were
        // driving to. The 150 m "was away" edge keeps GPS jitter at the gate from announcing it twice.
        val dest = room.destination
        if (dest != null && dest == before.destination && !room.resting) {
            room.members.forEach { m ->
                if (m.id == me) return@forEach
                val now = m.position ?: return@forEach
                val then = before.members.firstOrNull { it.id == m.id }?.position ?: return@forEach
                val dNow = Geo.distanceM(now.lat, now.lng, dest.lat, dest.lng)
                val dThen = Geo.distanceM(then.lat, then.lng, dest.lat, dest.lng)
                if (dNow <= ARRIVED_M && dThen > LEFT_M) say("${name(m.id)} has arrived" + dest.label.takeIf { it.isNotBlank() }?.let { " at $it" }.orEmpty())
            }
        }

        // Stopping / SOS from others, once per new status frame.
        val was = prev.statuses[code].orEmpty()
        next.statuses[code].orEmpty().values.filter { it.memberId != me && was[it.memberId]?.atMs != it.atMs }.forEach { s ->
            when (s.status) {
                "stopping" -> say("${name(s.memberId)} is stopping" + (s.note?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                "sos" -> say("SOS from ${name(s.memberId)}. Check the map.")
                "moving" -> say("${name(s.memberId)} is moving again")
            }
        }

        // Other members' chat that has just arrived.
        val seen = prev.chat[code].orEmpty().mapTo(HashSet()) { it.id.ifEmpty { it.clientId } }
        next.chat[code].orEmpty().filter { it.userId != me && it.id.ifEmpty { it.clientId } !in seen }.takeLast(3).forEach {
            out += ConvoyNotice(code, "${it.username.ifBlank { "Someone" }} says: ${it.text}", message = true)
        }
        return out
    }
}
