package app.convoyage

import app.convoyage.protocol.AutomaticRegroup
import app.convoyage.protocol.ClientMessage
import app.convoyage.protocol.ConvoyMessage
import app.convoyage.protocol.ConvoyRoute
import app.convoyage.protocol.Destination
import app.convoyage.protocol.Member
import app.convoyage.protocol.Position
import app.convoyage.protocol.ProtocolJson
import app.convoyage.protocol.Room
import app.convoyage.protocol.ServerMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun encodeServer(m: ServerMessage): String = ProtocolJson.json.encodeToString(ServerMessage.serializer(), m)

    private val room = Room(
        messages = listOf(ConvoyMessage("m1", "c1", "u1", "Alex", "hello", 1_700_000_000_000)),
        code = "K7QXM2PR",
        name = "Sunday Run",
        eventId = null,
        leader = "u1",
        destination = Destination(-37.8, 144.9, "Fitzroy Gardens"),
        resting = false,
        route = ConvoyRoute("Loop", listOf(listOf(144.9, -37.8), listOf(145.0, -37.9))),
        automaticRegroup = AutomaticRegroup(true, 3000.0),
        members = listOf(
            Member("u1", "Alex", "", true, 5, Position(-37.8, 144.9, 90.0, 12.5, 100)),
            Member("u2", "Mia", "", false, 3, null),
        ),
    )

    // ---- client -> server: wire shape + round trip -------------------------------------------------

    @Test fun clientPingIsJustT() {
        assertEquals("""{"t":"ping"}""", ProtocolJson.encode(ClientMessage.Ping))
    }

    @Test fun clientPosWireShape() {
        val o = obj(ProtocolJson.encode(ClientMessage.Pos(-37.81, 144.96, 270.0, 13.4)))
        assertEquals("pos", o["t"]!!.jsonPrimitive.content)
        assertEquals(-37.81, o["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(144.96, o["lng"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(270.0, o["heading"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(13.4, o["speed"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun clientPosOmitsUnknownHeadingAndSpeed() {
        val o = obj(ProtocolJson.encode(ClientMessage.Pos(1.0, 2.0)))
        assertFalse("heading" in o)
        assertFalse("speed" in o)
    }

    @Test fun everyClientMessageRoundTrips() {
        val messages = listOf<ClientMessage>(
            ClientMessage.Ping,
            ClientMessage.PosStop,
            ClientMessage.Pos(-37.8, 144.9, 12.0, 3.0),
            ClientMessage.Pos(-37.8, 144.9, null, null),
            ClientMessage.ConvoyCreate("Sunday Run", AutomaticRegroup(true, 5000.0)),
            ClientMessage.ConvoyCreate("Plain", null),
            ClientMessage.ConvoyJoin("K7QXM2PR"),
            ClientMessage.ConvoyCruise("event-1"),
            ClientMessage.ConvoyLeave("K7QXM2PR"),
            ClientMessage.ConvoyDest("K7QXM2PR", Destination(1.0, 2.0, "Here")),
            ClientMessage.ConvoyDest("K7QXM2PR", null),
            ClientMessage.ConvoyRest("K7QXM2PR", "find"),
            ClientMessage.ConvoyRouteSet("K7QXM2PR", ConvoyRoute("R", listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)))),
            ClientMessage.ConvoyRouteSet("K7QXM2PR", null),
            ClientMessage.ConvoyLead("K7QXM2PR", "u2"),
            ClientMessage.ConvoyRemove("K7QXM2PR", "u3"),
            ClientMessage.ConvoyChat("K7QXM2PR", "on my way", "client-1"),
        )
        for (m in messages) {
            val text = ProtocolJson.encode(m)
            assertEquals("round trip of $text", m, ProtocolJson.decodeClient(text))
        }
    }

    @Test fun clientDiscriminatorsMatchSchema() {
        val expected = mapOf(
            "ping" to ClientMessage.Ping,
            "pos.stop" to ClientMessage.PosStop,
            "convoy.join" to ClientMessage.ConvoyJoin("K7QXM2PR"),
            "convoy.cruise" to ClientMessage.ConvoyCruise("e"),
            "convoy.leave" to ClientMessage.ConvoyLeave("r"),
            "convoy.rest" to ClientMessage.ConvoyRest("r", "continue"),
            "convoy.lead" to ClientMessage.ConvoyLead("r", "u"),
            "convoy.remove" to ClientMessage.ConvoyRemove("r", "u"),
            "convoy.message" to ClientMessage.ConvoyChat("r", "t", "c"),
            "convoy.create" to ClientMessage.ConvoyCreate("n"),
            "convoy.dest" to ClientMessage.ConvoyDest("r", null),
            "convoy.route" to ClientMessage.ConvoyRouteSet("r", null),
        )
        for ((t, m) in expected) assertEquals(t, obj(ProtocolJson.encode(m))["t"]!!.jsonPrimitive.content)
    }

    @Test fun nullDestinationIsOmittedWhichTheServerReadsAsNull() {
        // messages.ts treats an absent destination the same as null (clear it).
        val o = obj(ProtocolJson.encode(ClientMessage.ConvoyDest("K7QXM2PR", null)))
        assertEquals("K7QXM2PR", o["room"]!!.jsonPrimitive.content)
        assertFalse("destination" in o)
    }

    // ---- server -> client ----------------------------------------------------------------------------

    @Test fun decodePong() {
        assertEquals(ServerMessage.Pong, ProtocolJson.decodeServer("""{"t":"pong"}"""))
    }

    @Test fun decodeHelloWithRoom() {
        val json = """
        {"t":"hello","me":"u1","rooms":[{
          "messages":[{"id":"m1","clientId":"c1","userId":"u1","username":"Alex","text":"hi","ts":1}],
          "code":"K7QXM2PR","name":"Sunday Run","eventId":null,"leader":"u1",
          "destination":{"lat":-37.8,"lng":144.9,"label":"Fitzroy"},"resting":false,
          "route":{"name":"Loop","stops":[[144.9,-37.8],[145.0,-37.9]]},
          "automaticRegroup":{"enabled":false,"separationM":3000},
          "members":[
            {"id":"u1","username":"Alex","avatar":"","online":true,"lastSeen":10,
             "position":{"lat":-37.8,"lng":144.9,"heading":null,"speed":null,"ts":9}},
            {"id":"u2","username":"Mia","avatar":"","online":false,"lastSeen":3,"position":null}
          ]}]}
        """.trimIndent()
        val hello = ProtocolJson.decodeServer(json) as ServerMessage.Hello
        assertEquals("u1", hello.me)
        val r = hello.rooms.single()
        assertEquals("K7QXM2PR", r.code)
        assertNull(r.eventId)
        assertEquals("Fitzroy", r.destination!!.label)
        assertEquals(listOf(144.9, -37.8), r.route!!.stops.first())
        assertNull(r.members[0].position!!.heading)
        assertNull(r.members[1].position)
        assertFalse(r.members[1].online)
    }

    @Test fun serverRoomRoundTrips() {
        val m = ServerMessage.RoomUpdate(room)
        val text = encodeServer(m)
        assertEquals("room", obj(text)["t"]!!.jsonPrimitive.content)
        assertEquals(m, ProtocolJson.decodeServer(text))
    }

    @Test fun everyServerMessageRoundTrips() {
        val messages = listOf<ServerMessage>(
            ServerMessage.Pong,
            ServerMessage.Hello("u1", listOf(room)),
            ServerMessage.RoomUpdate(room),
            ServerMessage.Left("K7QXM2PR", removed = true),
            ServerMessage.Left("K7QXM2PR"),
            ServerMessage.PosUpdate("K7QXM2PR", "u2", -37.8, 144.9, 45.0, 10.0, 99),
            ServerMessage.PosUpdate("K7QXM2PR", "u2", -37.8, 144.9, null, null, 99),
            ServerMessage.Error("No convoy with that code", "convoy.join"),
            ServerMessage.Error("Bad"),
            ServerMessage.Chat("K7QXM2PR", ConvoyMessage("m2", "c2", "u2", "Mia", "stopped for fuel", 5)),
        )
        for (m in messages) {
            val text = encodeServer(m)
            assertEquals("round trip of $text", m, ProtocolJson.decodeServer(text))
        }
    }

    @Test fun errorUsesInAsFieldName() {
        val e = ProtocolJson.decodeServer("""{"t":"error","error":"nope","in":"convoy.join"}""") as ServerMessage.Error
        assertEquals("nope", e.error)
        assertEquals("convoy.join", e.inMessage)
        assertEquals("convoy.join", obj(encodeServer(e))["in"]!!.jsonPrimitive.content)
    }

    @Test fun serverPosFrameWithUnknownFieldsIsTolerated() {
        val m = ProtocolJson.decodeServer(
            """{"t":"pos","room":"K7QXM2PR","id":"u2","lat":-37.8,"lng":144.9,"heading":10,"speed":2.5,"ts":123,"future":true}""",
        ) as ServerMessage.PosUpdate
        assertEquals(10.0, m.heading!!, 0.0)
        assertEquals(123L, m.ts)
    }

    @Test fun unknownOrBrokenFramesDecodeToNull() {
        assertNull(ProtocolJson.decodeServer("""{"t":"mystery"}"""))
        assertNull(ProtocolJson.decodeServer("not json"))
        assertNull(ProtocolJson.decodeServer("""{"no":"t"}"""))
        assertTrue(ProtocolJson.decodeServer("""{"t":"pong"}""") != null)
    }
}
