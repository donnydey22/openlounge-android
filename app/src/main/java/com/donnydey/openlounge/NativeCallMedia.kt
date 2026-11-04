package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.pjsip.PjsipEngine
import org.json.JSONObject

/**
 * Native call media — PJSIP only (WebRTC removed, pjsip-rebuild). Same host surface as before so MainActivity /
 * SessionBridge stay unchanged; the WebView never captures or plays call audio. SDP/SRTP signaling over the
 * existing TLS WebSocket (M4): 'open' starts the SRTP audio leg to the mediasoup PlainTransport; 'close' tears down fully.
 */
object NativeCallMedia {
    interface Host {
        fun relay(id: String, event: String, dataJson: String): Boolean
        fun emit(detailJson: String)
        fun nativeCallAudio(on: Boolean)
    }

    @JvmStatic @Volatile var host: Host? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false

    @JvmStatic fun init(ctx: Context) {}

    @JvmStatic fun handle(cmdJson: String, done: (Boolean, String?, String?) -> Unit) {
        val op = runCatching { JSONObject(cmdJson).optString("op") }.getOrDefault("")
        when (op) {
            "close" -> { closeAll(); done(true, "true", null) }
            "prewarm" -> done(true, JSONObject().put("engine", "pjsip").put("start", PjsipEngine.start()).toString(), null)
            "open" -> main.post {
                val c = JSONObject(cmdJson)
                val leg = Leg(c.getString("ip"), c.getInt("port"), c.getString("txKey"), c.getString("rxKey"),
                    c.optLong("ssrc", 1111L), c.optInt("txPt", 111), c.optInt("rxPt", 111))
                val r = startLeg(leg)
                if (r == 0) { active = true; host?.nativeCallAudio(true); done(true, JSONObject().put("engine", "pjsip").toString(), null) }
                else done(false, null, "pjsip audio leg start failed: $r")
            }
            "stats" -> { val s = runCatching { PjsipEngine.nativeSfuStats() }.getOrNull()
                done(true, JSONObject().put("rxPkt", s?.getOrNull(0)).put("rxLost", s?.getOrNull(1)).put("txPkt", s?.getOrNull(2)).toString(), null) }
            else -> done(false, null, "unknown op: $op")
        }
    }

    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {}
    private data class Leg(val ip: String, val port: Int, val tx: String, val rx: String, val ssrc: Long, val txPt: Int, val rxPt: Int)
    @Volatile private var leg: Leg? = null
    private fun startLeg(l: Leg): Int { PjsipEngine.start(); PjsipEngine.openSound(); leg = l
        return PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, false) }

    /** Network changed (Wi-Fi <-> cellular): re-bind the RTP socket to the new interface, same SRTP keys/SSRC. */
    @JvmStatic fun onNetworkChange() = main.post {
        val l = leg ?: return@post
        if (!active) return@post
        PjsipEngine.nativeSfuStop()
        val r = PjsipEngine.nativeSfuAudioStart(l.ip, l.port, l.tx, l.rx, l.ssrc, l.txPt, l.rxPt, 0, false)
        host?.emit(JSONObject().put("type", "pjsip:netchange").put("restart", r).toString())
    }
    @JvmStatic fun closeAll() = main.post { leg = null; runCatching { PjsipEngine.nativeSfuStop() }; PjsipEngine.teardown(); if (active) { active = false; host?.nativeCallAudio(false) } }
    @JvmStatic fun isActive(): Boolean = active
    @JvmStatic fun releaseIdle() { PjsipEngine.teardown() }
}
