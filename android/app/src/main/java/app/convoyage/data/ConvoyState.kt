package app.convoyage.data

import app.convoyage.protocol.ConvoyMessage
import app.convoyage.protocol.Position
import app.convoyage.protocol.Room
import app.convoyage.protocol.ServerMessage

/** A member's last `status` message. [atMs] is when we received it. */
data class StatusEvent(val memberId: String, val status: String, val note: String?, val position: Position?, val atMs: Long) {
    val isSos: Boolean get() = status == "sos"
}

enum class Link { Offline, Connecting, Online, Rejected }

/** Everything the UI knows about the socket and the convoys on it. Pure data; see [apply]. */
data class ConvoyState(
    val link: Link = Link.Offline,
    val meId: String? = null,
    val rooms: Map<String, Room> = emptyMap(),
    val activeCode: String? = null,
    val chat: Map<String, List<ConvoyMessage>> = emptyMap(),
    /** Last error text from the server or the connection; cleared by the UI. */
    val error: String? = null,
    /** Set when the server removed us from [removedFrom]'s convoy. */
    val removedFrom: String? = null,
    /** The latest `status` frame per room per member (stopping / moving / sos). */
    val statuses: Map<String, Map<String, StatusEvent>> = emptyMap(),
    /** Open Cruise as the server has it (it forgets on reconnect; the repository turns it back on). */
    val openCruise: Boolean = false,
    val nearbyDrivers: List<app.convoyage.protocol.NearbyDriver> = emptyList(),
    val nearbyConvoys: List<app.convoyage.protocol.NearbyConvoy> = emptyList(),
    /** An invite from a driver nearby, until joined, dismissed or expired. */
    val invite: ServerMessage.CruiseInvite? = null,
    /** Who we invited last (driver id), for a "Sent" label. */
    val invited: Set<String> = emptySet(),
) {
    val active: Room? get() = activeCode?.let { rooms[it] }

    /** Folds one server message into the state. */
    fun apply(message: ServerMessage): ConvoyState = when (message) {
        ServerMessage.Pong -> this

        is ServerMessage.Hello -> {
            val map = message.rooms.associateBy { it.code }
            copy(
                openCruise = false, nearbyDrivers = emptyList(), nearbyConvoys = emptyList(),
                meId = message.me,
                link = Link.Online,
                rooms = map,
                activeCode = activeCode?.takeIf { it in map } ?: map.keys.firstOrNull(),
                chat = map.mapValues { (_, r) -> r.messages },
            )
        }

        is ServerMessage.RoomUpdate -> {
            val room = message.room
            copy(
                invite = invite?.takeIf { it.code != room.code },
                rooms = rooms + (room.code to room),
                // A room we were not in is the answer to create/join: it becomes the active one.
                activeCode = if (room.code in rooms) activeCode ?: room.code else room.code,
                chat = chat + (room.code to mergeChat(chat[room.code].orEmpty(), room.messages)),
            )
        }

        is ServerMessage.Left -> copy(
            rooms = rooms - message.room,
            chat = chat - message.room,
            statuses = statuses - message.room,
            activeCode = if (activeCode == message.room) (rooms.keys - message.room).firstOrNull() else activeCode,
            removedFrom = if (message.removed) message.room else removedFrom,
        )

        is ServerMessage.PosUpdate -> {
            val room = rooms[message.room]
            if (room == null) this else {
                val pos = Position(message.lat, message.lng, message.heading, message.speed, message.ts)
                val members = room.members.map { if (it.id == message.id) it.copy(position = pos, online = true, lastSeen = message.ts) else it }
                copy(rooms = rooms + (room.code to room.copy(members = members)))
            }
        }

        // Hazards are kept by HazardRepository, which is fed the same frames.
        is ServerMessage.HazardReported -> this

        is ServerMessage.Status -> copy(
            statuses = statuses + (message.room to (statuses[message.room].orEmpty() +
                (message.id to StatusEvent(message.id, message.status, message.note, message.position, System.currentTimeMillis())))),
        )

        is ServerMessage.Error -> copy(error = message.error)

        is ServerMessage.CruiseState -> if (message.on) copy(openCruise = true) else copy(openCruise = false, nearbyDrivers = emptyList(), nearbyConvoys = emptyList())
        is ServerMessage.CruiseNearby -> copy(nearbyDrivers = message.drivers, nearbyConvoys = message.convoys)
        is ServerMessage.CruiseInvite -> copy(invite = message)
        is ServerMessage.CruiseInvited -> copy(invited = invited + message.to)

        is ServerMessage.Chat -> copy(chat = chat + (message.room to mergeChat(chat[message.room].orEmpty(), listOf(message.message))))
    }

    private fun mergeChat(old: List<ConvoyMessage>, incoming: List<ConvoyMessage>): List<ConvoyMessage> {
        val seen = old.mapTo(HashSet()) { it.id }
        return (old + incoming.filter { it.id !in seen }).takeLast(100)
    }
}
