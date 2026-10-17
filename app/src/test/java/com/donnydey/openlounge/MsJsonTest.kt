package com.donnydey.openlounge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MsJsonTest {
    private val consumer = JSONObject("""{"id":"c1","producerId":"p1","kind":"audio","rtpParameters":{"codecs":[{"mimeType":"audio/opus","payloadType":100,"clockRate":48000,"channels":2,"parameters":{"minptime":10,"useinbandfec":1},"rtcpFeedback":[{"type":"transport-cc","parameter":""}]}],"headerExtensions":[{"uri":"urn:ietf:params:rtp-hdrext:sdes:mid","id":1,"encrypt":false,"parameters":{}}],"encodings":[{"ssrc":1234,"dtx":false}],"rtcp":{"cname":"abc","reducedSize":true},"mid":"0"}}""")

    @Test fun consumeAppDataMissingBecomesEmptyObject() {
        assertEquals("{}", MsJson.appData(consumer.opt("appData")).toString())
        assertEquals("{}", MsJson.appData(JSONObject.NULL).toString())
        assertEquals("{}", MsJson.appData(5).toString())
        assertEquals("{}", MsJson.appData(JSONArray("[1]")).toString())
        assertEquals("""{"a":1}""", MsJson.appData(JSONObject("""{"a":1}""")).toString())
    }

    @Test fun realConsumerRtpParametersPassUnchanged() {
        val r = MsJson.rtpParameters(consumer.opt("rtpParameters"))
        assertEquals(JSONObject(consumer.getJSONObject("rtpParameters").toString()).toString().length, r.toString().length)
        assertEquals("abc", r.getJSONObject("rtcp").getString("cname"))
    }

    @Test fun wrongTypesAreRepairedOrRejected() {
        val p = JSONObject("""{"codecs":[{"mimeType":"audio/opus","payloadType":100,"clockRate":48000,"parameters":7,"rtcpFeedback":[3,{"type":"nack"}]}, 4],"encodings":[{"ssrc":1,"rtx":9}],"rtcp":"x"}""")
        val r = MsJson.rtpParameters(p)
        assertTrue(r.getJSONArray("codecs").getJSONObject(0).get("parameters") is JSONObject)
        assertEquals(1, r.getJSONArray("codecs").length())
        assertEquals(1, r.getJSONArray("codecs").getJSONObject(0).getJSONArray("rtcpFeedback").length())
        assertFalse(r.getJSONArray("encodings").getJSONObject(0).has("rtx"))
        assertTrue(r.get("rtcp") is JSONObject)
        assertThrows(MsJson.BadShape::class.java) { MsJson.rtpParameters(42) }
        assertThrows(MsJson.BadShape::class.java) { MsJson.rtpParameters(JSONObject("""{"codecs":[],"encodings":[]}""")) }
        assertThrows(MsJson.BadShape::class.java) { MsJson.obj(JSONArray(), "iceParameters") }
    }

    @Test fun iceCandidatesKeepOnlyObjects() {
        val a = MsJson.objArray(JSONArray("""[{"foundation":"udp","ip":"1.2.3.4","port":40000,"priority":1,"protocol":"udp","type":"host"},"x",3]"""), "iceCandidates")
        assertEquals(1, a.length())
    }

    @Test fun routerCapabilities() {
        val c = MsJson.rtpCapabilities(JSONObject("""{"codecs":[{"kind":"audio","mimeType":"audio/opus","clockRate":48000,"channels":2,"preferredPayloadType":100}],"headerExtensions":[]}"""))
        assertTrue(c.getJSONArray("codecs").getJSONObject(0).get("parameters") is JSONObject)
        assertTrue(c.getJSONArray("codecs").getJSONObject(0).get("rtcpFeedback") is JSONArray)
    }

    @Test fun deterministicErrors() {
        assertTrue(MsJson.isDeterministic(RuntimeException("consume: [json.exception.type_error.305] cannot use operator[] with a string argument with number")))
        assertTrue(MsJson.isDeterministic(RuntimeException("x", MsJson.BadShape("y"))))
        assertFalse(MsJson.isDeterministic(RuntimeException("native media connect timed out")))
        assertFalse(MsJson.isDeterministic(null))
    }
}
