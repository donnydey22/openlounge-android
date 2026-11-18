package com.donnydey.openlounge.olcall

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pinned to the Go writer (olcall/server/states.go) through the shared golden fixture lifecycle.json. */
class OlCallLifecycleTest {
    private fun fixture(): JSONArray {
        val f = listOf("lifecycle.json", "../../olcall/integration/testdata/lifecycle.json")
            .map { File(it) }.firstOrNull { it.exists() }
            ?: javaClass.classLoader!!.getResource("lifecycle.json")?.let { File(it.toURI()) }
            ?: error("lifecycle.json fixture not found")
        return JSONArray(f.readText())
    }

    @Test fun sevenStatesMatchGoWireValues() {
        assertEquals(listOf("idle", "ringing", "answering", "joining_url", "connecting", "in_room", "terminal"),
            OlCallLifecycle.State.values().map { it.wire })
    }

    @Test fun parsesEveryGoldenFrame() {
        val cases = fixture()
        assertTrue(cases.length() >= 3)
        for (i in 0 until cases.length()) {
            val frames = cases.getJSONObject(i).getJSONArray("frames")
            var held: OlCallLifecycle? = null
            for (j in 0 until frames.length()) {
                val o = frames.getJSONObject(j)
                val l = OlCallLifecycle.parse(o) ?: error("unparsed ${o}")
                assertEquals(o.getString("state"), l.state.wire)
                assertEquals(o.optString("reason"), l.reason?.wire ?: "")
                assertEquals(o.getLong("seq"), l.seq)
                assertEquals(o.getString("event"), l.event)
                assertTrue(OlCallLifecycle.newer(held, l))
                held = l
            }
        }
    }

    @Test fun rejectsInvalidSnapshots() {
        assertNull(OlCallLifecycle.parse(null))
        assertNull(OlCallLifecycle.parse(JSONObject("""{"state":"reconnecting","seq":1}""")))
        assertNull(OlCallLifecycle.parse(JSONObject("""{"state":"terminal","seq":2}""")))
        assertNull(OlCallLifecycle.parse(JSONObject("""{"state":"in_room","reason":"completed","seq":2}""")))
        assertNull(OlCallLifecycle.parse(JSONObject("""{"state":"in_room","seq":0}""")))
    }

    @Test fun staleOrDuplicateSnapshotIsIgnored() {
        val a = OlCallLifecycle.parse(JSONObject("""{"state":"in_room","seq":3,"event":"peer:connected"}"""))!!
        val old = OlCallLifecycle.parse(JSONObject("""{"state":"connecting","seq":2,"event":"joined"}"""))!!
        assertFalse(OlCallLifecycle.newer(a, old))
        assertFalse(OlCallLifecycle.newer(a, a))
    }
}
