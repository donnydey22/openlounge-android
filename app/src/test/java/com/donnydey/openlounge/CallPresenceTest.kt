package com.donnydey.openlounge

import org.json.JSONObject
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
}
