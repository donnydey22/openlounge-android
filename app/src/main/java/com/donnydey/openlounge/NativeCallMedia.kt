package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.pjsip.PjsipEngine
import org.json.JSONObject

/**
 * Native call media — PJSIP only (WebRTC removed, pjsip-rebuild). Same host surface as before so MainActivity /
 * SessionBridge stay unchanged; the WebView never captures or plays call audio. SDP/SRTP signaling over the
 * existing TLS WebSocket lands in M4; until then 'open' reports the engine state and 'close' tears down fully.
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
            else -> done(false, null, "pjsip signaling not wired yet (M4)")
        }
    }

    @JvmStatic fun onMsReply(id: String, ok: Boolean, resultJson: String?, error: String?) {}
    @JvmStatic fun onNetworkChange() {}
    @JvmStatic fun closeAll() = main.post { PjsipEngine.teardown(); if (active) { active = false; host?.nativeCallAudio(false) } }
    @JvmStatic fun isActive(): Boolean = active
    @JvmStatic fun releaseIdle() { PjsipEngine.teardown() }
}
