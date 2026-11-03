package app.convoyage.data

import app.convoyage.protocol.ConvoyMessage
import app.convoyage.protocol.Position
import app.convoyage.protocol.Room
import app.convoyage.protocol.ServerMessage

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
) {
    val active: Room? get() = activeCode?.let { rooms[it] }

    /** Folds one server message into the state. */
    fun apply(message: ServerMessage): ConvoyState = when (message) {
        ServerMessage.Pong -> this

        is ServerMessage.Hello -> {
            val map = message.rooms.associateBy { it.code }
            copy(
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
                rooms = rooms + (room.code to room),
                // A room we were not in is the answer to create/join: it becomes the active one.
                activeCode = if (room.code in rooms) activeCode ?: room.code else room.code,
                chat = chat + (room.code to mergeChat(chat[room.code].orEmpty(), room.messages)),
            )
        }

        is ServerMessage.Left -> copy(
            rooms = rooms - message.room,
            chat = chat - message.room,
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

        is ServerMessage.Error -> copy(error = message.error)

        is ServerMessage.Chat -> copy(chat = chat + (message.room to mergeChat(chat[message.room].orEmpty(), listOf(message.message))))
    }

    private fun mergeChat(old: List<ConvoyMessage>, incoming: List<ConvoyMessage>): List<ConvoyMessage> {
        val seen = old.mapTo(HashSet()) { it.id }
        return (old + incoming.filter { it.id !in seen }).takeLast(100)
    }
}
