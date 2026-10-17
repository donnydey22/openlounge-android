package com.donnydey.openlounge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shapes every JSON value handed to libmediasoupclient (libmediasoup-android 0.21.0) into the exact types it indexes.
 * libmediasoupclient uses nlohmann::json operator[] with string keys; any value that arrives as a number, string or
 * array where an object is expected throws "[json.exception.type_error.305] cannot use operator[] with a string
 * argument with <type>". Pure org.json, so it is JVM unit-tested.
 */
object MsJson {
    class BadShape(msg: String) : IllegalArgumentException(msg)

    fun obj(v: Any?, what: String): JSONObject = v as? JSONObject ?: throw BadShape("$what must be a JSON object")

    /** array whose entries are all objects (iceCandidates, codecs, encodings, headerExtensions, rtcpFeedback) */
    fun objArray(v: Any?, what: String): JSONArray {
        val a = v as? JSONArray ?: throw BadShape("$what must be a JSON array")
        val out = JSONArray()
        for (i in 0 until a.length()) (a.opt(i) as? JSONObject)?.let { out.put(it) }
        return out
    }

    /** appData is always an object: {} when absent/null or not an object */
    fun appData(v: Any?): JSONObject = (v as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()

    private fun codec(c: JSONObject): JSONObject {
        val o = JSONObject(c.toString())
        if (o.opt("parameters") !is JSONObject) o.put("parameters", JSONObject())
        o.put("rtcpFeedback", if (o.has("rtcpFeedback")) objArray(o.opt("rtcpFeedback"), "rtcpFeedback") else JSONArray())
        return o
    }

    private fun codecs(v: Any?): JSONArray {
        val out = JSONArray()
        val a = objArray(v, "codecs")
        for (i in 0 until a.length()) out.put(codec(a.getJSONObject(i)))
        return out
    }

    /** router RTP capabilities for Device.load */
    fun rtpCapabilities(v: Any?): JSONObject {
        val o = JSONObject(obj(v, "routerRtpCapabilities").toString())
        o.put("codecs", codecs(o.opt("codecs")))
        o.put("headerExtensions", if (o.has("headerExtensions")) objArray(o.opt("headerExtensions"), "headerExtensions") else JSONArray())
        return o
    }

    /** consumer RTP parameters for RecvTransport.consume */
    fun rtpParameters(v: Any?): JSONObject {
        val o = JSONObject(obj(v, "rtpParameters").toString())
        o.put("codecs", codecs(o.opt("codecs")))
        o.put("headerExtensions", if (o.has("headerExtensions")) objArray(o.opt("headerExtensions"), "headerExtensions") else JSONArray())
        val enc = if (o.has("encodings")) objArray(o.opt("encodings"), "encodings") else JSONArray()
        if (enc.length() == 0) throw BadShape("rtpParameters.encodings empty")
        for (i in 0 until enc.length()) {
            val e = enc.getJSONObject(i)
            if (e.has("rtx") && e.opt("rtx") !is JSONObject) e.remove("rtx")
        }
        o.put("encodings", enc)
        if (o.opt("rtcp") !is JSONObject) o.put("rtcp", JSONObject())
        return o
    }

    /** same input → same failure: a libmediasoupclient JSON/type error or a bad shape. Retrying mediasoup won't help. */
    fun isDeterministic(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t is BadShape) return true
            val m = t.message ?: ""
            if (m.contains("[json.exception") || m.contains("must be a JSON object")) return true
            t = t.cause
        }
        return false
    }
}
