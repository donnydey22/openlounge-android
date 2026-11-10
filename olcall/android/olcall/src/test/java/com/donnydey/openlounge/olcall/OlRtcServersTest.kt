package com.donnydey.openlounge.olcall

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pinned to the Go reference (olcall/tgrtc) through the shared golden fixture. */
class OlRtcServersTest {
    private fun fixture(): JSONArray {
        val f = listOf("rtcservers.json", "../../olcall/integration/testdata/rtcservers.json")
            .map { File(it) }.firstOrNull { it.exists() }
            ?: javaClass.classLoader!!.getResource("rtcservers.json")?.let { File(it.toURI()) }
            ?: error("rtcservers.json fixture not found")
        return JSONArray(f.readText())
    }

    private fun expect(arr: JSONArray?): List<OlRtcServers.Server> =
        if (arr == null) emptyList() else (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            OlRtcServers.Server(o.getString("host"), o.getInt("port"), o.getString("login"), o.getString("password"),
                o.getBoolean("isTurn"), o.getBoolean("isTcp"))
        }

    @Test fun matchesGoGoldenFixture() {
        val cases = fixture()
        assertTrue(cases.length() >= 3)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = OlRtcServers.fromIceServers(c.optJSONArray("iceServers"))
            assertEquals(c.getString("name"), expect(c.optJSONArray("expect")), got)
        }
    }

    @Test fun liveShapeIsDirectFirstRelayFallback() {
        val live = fixture().getJSONObject(0).getJSONArray("iceServers")
        val got = OlRtcServers.fromIceServers(live)
        assertEquals("stun=1 turnUdp=1 turnTcp=1", OlRtcServers.summary(got))
        // tgcalls has no TLS TURN: turns:5349 must never become a plain TURN entry
        assertTrue(got.none { it.port == 5349 })
    }

    @Test fun joinedWinsOverAppListAndAppIsFallback() {
        val joined = JSONArray().put(JSONObject().put("urls", JSONArray().put("turn:a.example:3478")).put("username", "1:u").put("credential", "c"))
        val app = JSONArray().put(JSONObject().put("urls", "stun:b.example:3478"))
        assertEquals("joined", OlRtcServers.select(joined, app).second)
        val (l, src) = OlRtcServers.select(JSONArray(), app)
        assertEquals("app", src); assertEquals("b.example", l.single().host)
        assertEquals("none", OlRtcServers.select(null, null).second)
    }

    @Test fun toStringNeverLeaksCredential() {
        val s = OlRtcServers.Server("h", 3478, "1:u", "SECRETCRED", isTurn = true, isTcp = false)
        assertTrue(!s.toString().contains("SECRETCRED") && !s.toString().contains("1:u"))
    }
}
