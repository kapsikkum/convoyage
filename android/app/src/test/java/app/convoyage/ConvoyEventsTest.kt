package app.convoyage

import app.convoyage.data.ConvoyEvents
import app.convoyage.data.ConvoyState
import app.convoyage.data.Link
import app.convoyage.data.StatusEvent
import app.convoyage.protocol.ConvoyMessage
import app.convoyage.protocol.ConvoyRoute
import app.convoyage.protocol.Destination
import app.convoyage.protocol.Member
import app.convoyage.protocol.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyEventsTest {
    private val me = Member("me", "Riley", online = true)
    private val sam = Member("sam", "Sam", online = true)
    private val jo = Member("jo", "Jo", online = true)
    private fun state(room: Room, chat: List<ConvoyMessage> = emptyList(), statuses: Map<String, StatusEvent> = emptyMap()) =
        ConvoyState(link = Link.Online, meId = "me", rooms = mapOf(room.code to room), activeCode = room.code, chat = mapOf(room.code to chat), statuses = mapOf(room.code to statuses))
    private val base = Room(code = "ABCDEFGH", name = "Bathurst loop", leader = "me", members = listOf(me, sam))

    private fun texts(a: ConvoyState, b: ConvoyState) = ConvoyEvents.between(a, b).map { it.text }

    @Test fun joinsLeavesAndReconnects() {
        val a = state(base)
        assertEquals(listOf("Jo joined the convoy", "Sam left the convoy"), texts(a, state(base.copy(members = listOf(me, jo)))))
        assertEquals(listOf("Sam disconnected"), texts(a, state(base.copy(members = listOf(me, sam.copy(online = false))))))
    }

    @Test fun leaderDestinationRestAndRoute() {
        val a = state(base)
        assertEquals(listOf("Sam is now leading"), texts(a, state(base.copy(leader = "sam"))))
        assertEquals(listOf("Destination: Mount Panorama"), texts(a, state(base.copy(destination = Destination(-33.4, 149.5, "Mount Panorama")))))
        assertEquals(listOf("Rest stop: Kelso servo"), texts(a, state(base.copy(resting = true, destination = Destination(-33.4, 149.6, "Kelso servo")))))
        assertEquals(listOf("Route shared: Bathurst loop"), texts(a, state(base.copy(route = ConvoyRoute("Bathurst loop", listOf(listOf(149.5, -33.4), listOf(149.6, -33.5)))))))
    }

    @Test fun statusesAndChatFromOthersOnly() {
        val a = state(base)
        val b = state(base, chat = listOf(ConvoyMessage("1", "c1", "sam", "Sam", "Fuel at Kelso"), ConvoyMessage("2", "c2", "me", "Riley", "ok")),
            statuses = mapOf("sam" to StatusEvent("sam", "stopping", null, null, 10)))
        val n = ConvoyEvents.between(a, b)
        assertEquals(listOf("Sam is stopping", "Sam says: Fuel at Kelso"), n.map { it.text })
        assertTrue(n.last().message)
        assertEquals(emptyList<String>(), texts(b, b))
    }

    @Test fun firstHelloIsQuiet() {
        assertEquals(emptyList<String>(), texts(ConvoyState(), state(base)))
        val online = ConvoyState(link = Link.Online, meId = "me")
        assertEquals(listOf("Joined Bathurst loop"), texts(online, state(base)))
    }
}
