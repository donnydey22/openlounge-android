package com.donnydey.openlounge

import com.donnydey.openlounge.olcall.OlRtcServers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The "connect" op's engine choice + the relay list hand-off (joined frame wins, CallMedia.iceServers is the fallback). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OlcallRouteTest {
    private val ice = JSONArray("""[
        {"urls":["stun:turn.example:3478"]},
        {"urls":["turn:turn.example:3478?transport=udp","turn:turn.example:3478?transport=tcp","turns:turn.example:5349?transport=tcp"],
         "username":"1700000000:u1","credential":"c-app"}]""")
    private fun media(provider: String = "olcall", url: String = "wss://turn.example/v1/call", token: String = "t") =
        JSONObject().put("provider", provider).put("url", url).put("token", token).put("iceServers", ice)

    @Test fun olcallOnlyWhenBackendSaysSoOnApi27Plus() {
        assertTrue(OlcallRoute.eligible(media(), 27))
        assertTrue(OlcallRoute.eligible(media(), 35))
        assertFalse("today's p2p media keeps RtcCallSession", OlcallRoute.eligible(media(provider = "p2p"), 35))
        assertFalse("module minSdk is 27", OlcallRoute.eligible(media(), 26))
        assertFalse(OlcallRoute.eligible(media(url = "https://x/v1/call"), 35))
        assertFalse(OlcallRoute.eligible(media(token = ""), 35))
        assertFalse(OlcallRoute.eligible(null, 35))
        assertFalse("no x86 engine build (emulators keep RtcCallSession)", OlcallRoute.eligible(media(), 35, listOf("x86_64", "arm64-v8a")))
        assertTrue(OlcallRoute.eligible(media(), 35, listOf("armeabi-v7a", "armeabi")))
    }

    @Test fun appIceServersComeFromCallMedia() {
        assertEquals(2, OlcallRoute.appIceServers(media())!!.length())
        assertNull(OlcallRoute.appIceServers(JSONObject().put("iceServers", JSONArray())))
        assertNull(OlcallRoute.appIceServers(null))
    }

    @Test fun joinedFrameWinsAndAppListIsFallback() {
        val app = OlcallRoute.appIceServers(media())
        val joined = JSONArray("""[{"urls":["turn:165.232.137.71:3478"],"username":"1700000001:u2","credential":"c-joined"}]""")
        val (j, src) = OlRtcServers.select(joined, app)
        assertEquals("joined", src); assertEquals(1, j.size); assertTrue(j[0].isTurn)
        val (a, src2) = OlRtcServers.select(null, app)
        assertEquals("app", src2)
        // stun + turn/udp + turn/tcp; turns: (TLS) is skipped because tgcalls has no TLS TURN
        assertEquals(3, a.size)
        assertEquals(listOf(false to false, true to false, true to true), a.map { it.isTurn to it.isTcp })
        assertFalse(a.joinToString().contains("c-app"))
    }
}
