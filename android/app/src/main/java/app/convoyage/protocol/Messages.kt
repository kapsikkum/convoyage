package app.convoyage.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Hand-written mirror of protocol/convoy.v1.schema.json (and server/src/convoy/messages.ts).
 *
 * TODO(codegen): generate these from the JSON Schema once the protocol package has a Kotlin generator.
 * Until then, keep in step with the schema by hand; ProtocolTest round-trips every message.
 *
 * Every frame is one JSON object with a `t` discriminator. Client and server both use some of the same
 * `t` values (`pos`), so they are separate sealed hierarchies.
 */

@Serializable
data class Destination(val lat: Double, val lng: Double, val label: String = "")

/** Ordered driving stops as [lng, lat]. A circuit repeats its first stop at the end. */
@Serializable
data class ConvoyRoute(val name: String = "", val stops: List<List<Double>> = emptyList())

@Serializable
data class AutomaticRegroup(val enabled: Boolean = false, val separationM: Double = 3000.0)

@Serializable
data class Position(
    val lat: Double,
    val lng: Double,
    val heading: Double? = null,
    /** Metres per second. */
    val speed: Double? = null,
    /** Server receive time, ms since the epoch. */
    val ts: Long = 0,
)

@Serializable
data class Member(
    val id: String,
    val username: String = "",
    val avatar: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0,
    val position: Position? = null,
    /** The member's primary vehicle, for their avatar badge. Absent or null when they have none or the account is private. */
    val vehicle: app.convoyage.api.VehicleBadge? = null,
)

@Serializable
data class ConvoyMessage(
    val id: String = "",
    val clientId: String = "",
    val userId: String,
    val username: String = "",
    val text: String,
    val ts: Long = 0,
)

@Serializable
data class Room(
    val messages: List<ConvoyMessage> = emptyList(),
    /** Invite code, also the room id. */
    val code: String,
    val name: String = "",
    val eventId: String? = null,
    /** Open convoy: Open Cruise drivers nearby can see and join it. */
    val open: Boolean = false,
    val leader: String = "",
    val destination: Destination? = null,
    val resting: Boolean = false,
    val route: ConvoyRoute? = null,
    /** Road-following route to the destination, polyline with 6 decimals. Optional: older servers never send it. */
    val routeGeometry: String? = null,
    val automaticRegroup: AutomaticRegroup = AutomaticRegroup(),
    val members: List<Member> = emptyList(),
)

/**
 * A road hazard. The same object comes from REST (`GET /v1/hazards`, with vote counts and [myAction]) and from
 * the socket's `hazard` message (no vote counts, no reporter), so everything but the core fields has a default.
 * [kind] stays a string so a kind added later by the server does not break parsing.
 */
@Serializable
data class Hazard(
    val id: String,
    val kind: String,
    val subkind: String = "",
    val lat: Double,
    val lng: Double,
    /** Direction of travel of the reporter when reporting, degrees. */
    val heading: Double? = null,
    /** ISO-8601 instants. */
    val createdAt: String = "",
    val expiresAt: String = "",
    val confirmations: Int = 0,
    val dismissals: Int = 0,
    /** "confirm" | "dismiss" | null: my own vote (REST only). */
    val myAction: String? = null,
) {
    /** Expiry as ms since the epoch, or null when absent/unparseable. */
    fun expiresAtMs(): Long? = parseInstantMs(expiresAt)

    val isPolice: Boolean get() = kind == "police"

    companion object {
        fun parseInstantMs(iso: String): Long? = try {
            if (iso.isBlank()) null else java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

/** An Open Cruise driver nearby (position rounded to ~100 m by the server). */
@Serializable
data class NearbyDriver(
    val id: String,
    val username: String = "",
    val avatar: String = "",
    val vehicle: app.convoyage.api.VehicleBadge? = null,
    val lat: Double,
    val lng: Double,
    val distanceM: Double = 0.0,
    val canInvite: Boolean = false,
)

/** An open convoy nearby: where its members are on average. */
@Serializable
data class NearbyConvoy(
    val code: String,
    val name: String = "",
    val members: Int = 0,
    val lat: Double,
    val lng: Double,
    val distanceM: Double = 0.0,
    val destination: String? = null,
)

// ---- Client -> server --------------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed interface ClientMessage {
    @Serializable @SerialName("ping")
    data object Ping : ClientMessage

    @Serializable @SerialName("pos.stop")
    data object PosStop : ClientMessage

    /** heading 0..360, speed 0..120 m/s; null = the device does not know. */
    @Serializable @SerialName("pos")
    data class Pos(val lat: Double, val lng: Double, val heading: Double? = null, val speed: Double? = null) : ClientMessage

    @Serializable @SerialName("convoy.create")
    data class ConvoyCreate(val name: String = "", val automaticRegroup: AutomaticRegroup? = null) : ClientMessage

    @Serializable @SerialName("convoy.join")
    data class ConvoyJoin(val code: String) : ClientMessage

    @Serializable @SerialName("convoy.cruise")
    data class ConvoyCruise(val eventId: String) : ClientMessage

    @Serializable @SerialName("convoy.leave")
    data class ConvoyLeave(val room: String) : ClientMessage

    @Serializable @SerialName("convoy.dest")
    data class ConvoyDest(val room: String, val destination: Destination?) : ClientMessage

    /** Leader: rename the convoy (max 40 characters). */
    @Serializable @SerialName("convoy.rename")
    data class ConvoyRename(val room: String, val name: String) : ClientMessage

    /** Leader: automatic regroup on/off and how far apart the convoy may spread, metres. */
    @Serializable @SerialName("convoy.regroup")
    data class ConvoyRegroup(val room: String, val enabled: Boolean, val separationM: Double) : ClientMessage

    /** action: "find" | "continue" */
    @Serializable @SerialName("convoy.rest")
    data class ConvoyRest(val room: String, val action: String) : ClientMessage

    @Serializable @SerialName("convoy.route")
    data class ConvoyRouteSet(val room: String, val route: ConvoyRoute?) : ClientMessage

    @Serializable @SerialName("convoy.lead")
    data class ConvoyLead(val room: String, val to: String) : ClientMessage

    @Serializable @SerialName("convoy.remove")
    data class ConvoyRemove(val room: String, val id: String) : ClientMessage

    /** status: "stopping" | "moving" | "sos" (note up to 140 chars). The server adds our position to an sos. */
    @Serializable @SerialName("convoy.status")
    data class ConvoyStatus(val status: String, val note: String? = null) : ClientMessage

    @Serializable @SerialName("convoy.message")
    data class ConvoyChat(val room: String, val text: String, val clientId: String) : ClientMessage

    /** Leader: open the convoy to Open Cruise drivers nearby, or close it. */
    @Serializable @SerialName("convoy.open")
    data class ConvoyOpen(val room: String, val open: Boolean) : ClientMessage

    /** Open Cruise on or off. */
    @Serializable @SerialName("cruise.open")
    data class CruiseOpen(val on: Boolean) : ClientMessage

    /** Invite an Open Cruise driver within 500 m. */
    @Serializable @SerialName("cruise.invite")
    data class CruiseInvite(val to: String) : ClientMessage

    /** Join an open convoy nearby, or one we were invited to, without its code. */
    @Serializable @SerialName("cruise.join")
    data class CruiseJoin(val code: String) : ClientMessage
}

// ---- Server -> client --------------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed interface ServerMessage {
    @Serializable @SerialName("pong")
    data object Pong : ServerMessage

    @Serializable @SerialName("hello")
    data class Hello(val me: String, val rooms: List<Room> = emptyList()) : ServerMessage

    @Serializable @SerialName("room")
    data class RoomUpdate(val room: Room) : ServerMessage

    @Serializable @SerialName("left")
    data class Left(val room: String, val removed: Boolean = false) : ServerMessage

    @Serializable @SerialName("pos")
    data class PosUpdate(
        val room: String,
        val id: String,
        val lat: Double,
        val lng: Double,
        val heading: Double? = null,
        val speed: Double? = null,
        val ts: Long = 0,
    ) : ServerMessage

    @Serializable @SerialName("error")
    data class Error(val error: String, @SerialName("in") val inMessage: String? = null) : ServerMessage

    @Serializable @SerialName("convoy.message")
    data class Chat(val room: String, val message: ConvoyMessage) : ServerMessage

    /** A member sent convoy.status. [position] is only present for sos. Sent to every member, the sender included. */
    @Serializable @SerialName("status")
    data class Status(val room: String, val id: String, val status: String, val note: String? = null, val position: Position? = null) : ServerMessage

    /** A member reported a hazard (POST /v1/hazards) while in this convoy. Sent to every member, the reporter included. */
    /** A notification (follow, comment, like, SOS, hazard) sent down the socket instead of a push. [data] "type" names it. */
    @Serializable @SerialName("notify")
    data class Notify(val title: String, val body: String, val data: Map<String, String> = emptyMap()) : ServerMessage

    @Serializable @SerialName("hazard")
    data class HazardReported(val room: String, val hazard: Hazard) : ServerMessage

    @Serializable @SerialName("cruise.state")
    data class CruiseState(val on: Boolean) : ServerMessage

    @Serializable @SerialName("cruise.nearby")
    data class CruiseNearby(val drivers: List<NearbyDriver> = emptyList(), val convoys: List<NearbyConvoy> = emptyList()) : ServerMessage

    /** Somebody nearby invited us. Answer with [ClientMessage.CruiseJoin] before [expiresAt]. */
    @Serializable @SerialName("cruise.invite")
    data class CruiseInvite(val from: String, val fromName: String = "", val code: String, val name: String = "", val expiresAt: Long = 0) : ServerMessage

    @Serializable @SerialName("cruise.invited")
    data class CruiseInvited(val to: String, val code: String) : ServerMessage
}

/** Shared codec. Unknown fields are ignored (the server may add fields within v1); nulls are omitted on the wire. */
@OptIn(ExperimentalSerializationApi::class)
object ProtocolJson {
    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    /** Returns null for frames that are not valid ServerMessages (unknown `t`, malformed JSON). */
    fun decodeServer(text: String): ServerMessage? =
        try {
            json.decodeFromString(ServerMessage.serializer(), text)
        } catch (_: Exception) {
            null
        }

    fun decodeClient(text: String): ClientMessage? =
        try {
            json.decodeFromString(ClientMessage.serializer(), text)
        } catch (_: Exception) {
            null
        }
}
