package app.convoyage

import app.convoyage.data.AppLink
import app.convoyage.data.Chat
import app.convoyage.data.DeepLink
import app.convoyage.protocol.ConvoyMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkTest {
    @Test fun parsesTheAppScheme() {
        assertEquals(AppLink.Join("K7QXM2PR"), DeepLink.parse("convoyage://join/K7QXM2PR"))
    }

    @Test fun parsesTheHttpsFallback() {
        assertEquals(AppLink.Join("K7QXM2PR"), DeepLink.parse("https://convoyage.app/join/K7QXM2PR"))
        assertEquals(AppLink.Join("K7QXM2PR"), DeepLink.parse("https://convoyage.app/join/k7qxm2pr/?utm=x#y"))
    }

    @Test fun forgivesCaseAndWhitespace() {
        assertEquals(AppLink.Join("K7QXM2PR"), DeepLink.parse("  convoyage://join/k7qx-m2pr "))
    }

    @Test fun rejectsBadCodes() {
        assertNull(DeepLink.parse("convoyage://join/SHORT"))
        assertNull(DeepLink.parse("convoyage://join/K7QXM2P0")) // 0 is not in the alphabet
        assertNull(DeepLink.parse("convoyage://join/K7QXM2PRX"))
        assertNull(DeepLink.parse("convoyage://join/"))
    }

    @Test fun rejectsOtherHostsAndSchemes() {
        assertNull(DeepLink.parse("https://evil.example/join/K7QXM2PR"))
        assertNull(DeepLink.parse("http://convoyage.app/join/K7QXM2PR"))
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
    }

    @Test fun magicLinksStillRouteToSignIn() {
        val link = "convoyage://auth/magic?token=abc"
        assertEquals(AppLink.Auth(link), DeepLink.parse(link))
    }

    @Test fun shareTextHasBothLinksAndTheCode() {
        val t = DeepLink.shareText("K7QXM2PR", "Sunday Run")
        assertTrue(t.contains("convoyage://join/K7QXM2PR"))
        assertTrue(t.contains("https://convoyage.app/join/K7QXM2PR"))
        assertTrue(t.contains("Code: K7QXM2PR"))
        assertTrue(t.contains("Sunday Run"))
    }

    private fun msg(id: String, user: String, text: String, ts: Long) = ConvoyMessage(id, id, user, user, text, ts)

    @Test fun unreadCountsOnlyOthersAfterTheReadMark() {
        val list = listOf(msg("1", "a", "hi", 100), msg("2", "me", "yo", 200), msg("3", "a", "SOS: crash", 300), msg("4", "b", "ok", 400))
        assertEquals(3, Chat.unread(list, "me", 0))
        assertEquals(2, Chat.unread(list, "me", 100))
        assertEquals(0, Chat.unread(list, "me", 400))
        assertEquals(400L, Chat.latestTs(list))
        assertEquals("3", Chat.pendingSos(list, "me", 0)?.id)
        assertNull(Chat.pendingSos(list, "me", 300))
    }

    @Test fun statusMessagesRoundTrip() {
        val out = app.convoyage.protocol.ProtocolJson.encode(app.convoyage.protocol.ClientMessage.ConvoyStatus("sos", "note"))
        assertTrue(out.contains("\"t\":\"convoy.status\"") && out.contains("\"status\":\"sos\""))
        val m = app.convoyage.protocol.ProtocolJson.decodeServer(
            """{"t":"status","room":"ABCDEFGH","id":"u1","status":"sos","note":"n","position":{"lat":-33.4,"lng":149.5,"heading":null,"speed":0,"ts":5}}""",
        ) as app.convoyage.protocol.ServerMessage.Status
        assertEquals("sos", m.status)
        assertEquals(-33.4, m.position!!.lat, 0.0)
        val st = app.convoyage.data.ConvoyState(rooms = mapOf("ABCDEFGH" to app.convoyage.protocol.Room(code = "ABCDEFGH"))).apply(m)
        assertTrue(st.statuses["ABCDEFGH"]!!["u1"]!!.isSos)
    }

    @Test fun sosNoteFitsTheStatusLimit() {
        val n = app.convoyage.data.SosMessage.note("x".repeat(200), -33.4, 149.5)
        assertTrue(n.length <= 140)
    }
}
