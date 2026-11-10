package com.donnydey.openlounge

import org.json.JSONObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPresenceTest {
    @Test fun parseTokens() {
        assertEquals("online", CallPresence.of("online"))
        assertEquals("online", CallPresence.of("\"online\""))
        assertEquals("away", CallPresence.of("Away"))
        assertEquals("online", CallPresence.of("true"))
        assertNull(CallPresence.of("offline"))
        assertNull(CallPresence.of(""))
        assertNull(CallPresence.of(null as String?))
    }

    @Test fun fromMessageAndJs() {
        val on = JSONObject().put("presence", "online")
        assertTrue(CallPresence.hasField(on))
        assertEquals("online", CallPresence.fromMessage(on))
        val off = JSONObject().put("presence", "offline")
        assertTrue(CallPresence.hasField(off))
        assertNull(CallPresence.fromMessage(off))
        val flag = JSONObject().put("online", true)
        assertEquals("online", CallPresence.fromMessage(flag))
        assertFalse(CallPresence.hasField(JSONObject().put("callId", "x")))
        assertEquals("online", CallPresence.rawFromJs("\"online\""))
        assertEquals("", CallPresence.rawFromJs("\"\""))
        assertEquals("", CallPresence.rawFromJs("null"))
    }

    @Test fun failureMapping() {
        assertTrue(CallPresence.isFailure("failed", null))
        assertTrue(CallPresence.isFailure("", "Can't connect the call on this network. Try another Wi-Fi or mobile data."))
        assertFalse(CallPresence.isFailure("", "Call ended"))
        assertEquals(CallPresence.FAIL_MSG, CallPresence.failureText(null))
        val m = JSONObject().put("reason", "failed").put("endedText", "  nope  ")
        assertEquals("nope", CallPresence.messageFromEnd(m))
    }

    @Test fun allowedPhotoUrl() {
        val cache = File.createTempFile("olcache", "").apply { delete(); mkdirs() }
        val files = File.createTempFile("olfiles", "").apply { delete(); mkdirs() }
        val ok = File(cache, "avatar-placeholder.jpg").apply { writeText("x") }
        assertTrue(CallPresence.allowedPhotoUrl("https://cdn.example/a.jpg", cache, files))
        assertTrue(CallPresence.allowedPhotoUrl(ok.toURI().toString(), cache, files))
        assertFalse(CallPresence.allowedPhotoUrl("file:///etc/passwd", cache, files))
        assertFalse(CallPresence.allowedPhotoUrl("", cache, files))
        cache.deleteRecursively(); files.deleteRecursively()
    }
}
