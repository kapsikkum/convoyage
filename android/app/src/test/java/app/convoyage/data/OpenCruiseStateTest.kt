package app.convoyage.data

import app.convoyage.protocol.NearbyConvoy
import app.convoyage.protocol.Room
import app.convoyage.protocol.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCruiseStateTest {
    private val convoy = NearbyConvoy("K7QXM2PR", "Sunday Run", 5, -33.44, 149.57)

    @Test fun nearbyListsClearWhenTurnedOffOrReconnected() {
        var s = ConvoyState().apply(ServerMessage.CruiseState(true)).apply(ServerMessage.CruiseNearby(convoys = listOf(convoy)))
        assertTrue(s.openCruise)
        assertEquals(1, s.nearbyConvoys.size)
        val off = s.apply(ServerMessage.CruiseState(false))
        assertFalse(off.openCruise)
        assertTrue(off.nearbyConvoys.isEmpty())
        s = s.apply(ServerMessage.Hello("me"))
        assertFalse(s.openCruise)
        assertTrue(s.nearbyConvoys.isEmpty())
    }

    @Test fun inviteIsAnsweredByJoiningItsRoom() {
        val s = ConvoyState().apply(ServerMessage.CruiseInvite("u3", "Kai", "K7QXM2PR", "Kai's open convoy", 1))
        assertEquals("K7QXM2PR", s.invite?.code)
        val other = s.apply(ServerMessage.RoomUpdate(Room(code = "ZZZZZZZZ")))
        assertEquals("K7QXM2PR", other.invite?.code)
        assertNull(other.apply(ServerMessage.RoomUpdate(Room(code = "K7QXM2PR"))).invite)
    }
}
