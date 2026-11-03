package app.convoyage

import app.convoyage.data.ConvoyState
import app.convoyage.data.Fix
import app.convoyage.data.Geo
import app.convoyage.data.Link
import app.convoyage.data.Roster
import app.convoyage.data.Side
import app.convoyage.design.MemberState
import app.convoyage.net.devSocketUrl
import app.convoyage.protocol.Member
import app.convoyage.protocol.Position
import app.convoyage.protocol.Room
import app.convoyage.protocol.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterTest {
    private fun member(id: String, lat: Double, lng: Double, speed: Double?, online: Boolean = true) =
        Member(id, id.uppercase(), "", online, 0, Position(lat, lng, 0.0, speed, 1))

    private val me = Fix(-37.80, 144.96, 0.0, 15.0, 5f, 0L) // heading north
    private val room = Room(
        code = "K7QXM2PR", leader = "lead",
        members = listOf(
            member("lead", -37.78, 144.96, 15.0), // ~2.2 km north (ahead)
            member("me", -37.80, 144.96, 15.0),
            member("slow", -37.81, 144.96, 0.0), // behind and stopped
            member("gone", -37.9, 144.96, 5.0, online = false),
        ),
    )

    @Test fun statesAreDerivedFromRoomAndSpeed() {
        val byId = room.members.associateBy { it.id }
        assertEquals(MemberState.Lead, Roster.stateOf(byId.getValue("lead"), room))
        assertEquals(MemberState.Online, Roster.stateOf(byId.getValue("me"), room))
        assertEquals(MemberState.Stopped, Roster.stateOf(byId.getValue("slow"), room))
        assertEquals(MemberState.Offline, Roster.stateOf(byId.getValue("gone"), room))
    }

    @Test fun aheadAndBehindFollowMyHeading() {
        val e = Roster.build(room, "me", me).associateBy { it.id }
        assertEquals(Side.Ahead, e.getValue("lead").side)
        assertEquals(Side.Behind, e.getValue("slow").side)
        assertTrue(e.getValue("lead").distanceM!! in 2000.0..2400.0)
        assertTrue(e.getValue("me").isMe)
    }

    @Test fun leaderSortsFirst() {
        assertEquals("lead", Roster.build(room, "me", me).first().id)
    }

    @Test fun stoppedMemberRaisesAnAlert() {
        val entries = Roster.build(room, "me", me)
        val alert = Roster.alert(room, entries, "me")
        assertNotNull(alert)
        assertTrue(alert!!.title.startsWith("SLOW stopped"))
        val without = room.copy(members = room.members.filter { it.id != "slow" })
        assertNull(Roster.alert(without, entries.filter { it.id != "slow" }, "me"))
    }

    @Test fun initials() {
        assertEquals("AL", Roster.initials("Alex Lee"))
        assertEquals("MI", Roster.initials("mia"))
        assertEquals("?", Roster.initials("  "))
    }

    @Test fun distanceFormatting() {
        assertEquals("600 m", Geo.formatDistance(600.0))
        assertEquals("1.8 km", Geo.formatDistance(1800.0))
        assertEquals("12 km", Geo.formatDistance(12_340.0))
    }

    @Test fun geoDistanceAndBearing() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 200.0)
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 0.0)
    }

    // ---- state reducer ------------------------------------------------------------------------------

    @Test fun helloThenPosUpdatesAMember() {
        var s = ConvoyState().apply(ServerMessage.Hello("me", listOf(room)))
        assertEquals(Link.Online, s.link)
        assertEquals("K7QXM2PR", s.activeCode)
        s = s.apply(ServerMessage.PosUpdate("K7QXM2PR", "slow", -37.5, 144.5, 90.0, 20.0, 77))
        val slow = s.active!!.members.first { it.id == "slow" }
        assertEquals(-37.5, slow.position!!.lat, 0.0)
        assertEquals(20.0, slow.position!!.speed!!, 0.0)
    }

    @Test fun joiningANewRoomActivatesItAndLeftClearsIt() {
        var s = ConvoyState().apply(ServerMessage.Hello("me", emptyList()))
        assertNull(s.active)
        s = s.apply(ServerMessage.RoomUpdate(room))
        assertEquals("K7QXM2PR", s.activeCode)
        s = s.apply(ServerMessage.Left("K7QXM2PR", removed = true))
        assertNull(s.active)
        assertEquals("K7QXM2PR", s.removedFrom)
    }

    @Test fun errorIsKept() {
        val s = ConvoyState().apply(ServerMessage.Error("No convoy with that code", "convoy.join"))
        assertEquals("No convoy with that code", s.error)
    }

    @Test fun devSocketUrlCarriesIdentity() {
        val url = devSocketUrl("http://10.0.2.2:8080/", "u-1", "Alex Lee")!!
        assertEquals("/ws", url.encodedPath)
        assertEquals("u-1", url.queryParameter("dev_user"))
        assertEquals("Alex Lee", url.queryParameter("name"))
        assertEquals(8080, url.port)
    }
}
