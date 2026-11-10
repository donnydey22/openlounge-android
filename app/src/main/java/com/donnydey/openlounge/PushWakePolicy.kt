package com.donnydey.openlounge

import org.json.JSONObject

/**
 * ol-call-wake (Nov 10 2026, Telegram VoIPPreNotificationService): pure rules for the push-woken ring, JVM-tested.
 * Frames are the server's raw WebSocket protocol (server/ws.ts): {"t":"req","id":n,"ev":…,"data":…} →
 * {"t":"res","id":n,"data":…}, server events {"t":"evt","ev":…,"data":…}. Auth is session:resume with the same
 * stored access token the app session uses (CallApi.freshAccessToken) — no new keys.
 */
object PushWakePolicy {
    const val RESUME_ID = 1
    const val ACK_ID = 2

    enum class Verdict {
        /** the server confirmed the call is still ringing for us → ring */
        RING,
        /** the call is over / unknown / answered elsewhere → never ring (cancel a fallback ring) */
        DROP,
        /** no confirmation possible on this socket (session refused) → ring now without waiting */
        NO_SOCKET,
    }

    @JvmStatic fun resumeFrame(token: String): String =
        JSONObject().put("t", "req").put("id", RESUME_ID).put("ev", "session:resume")
            .put("data", JSONObject().put("token", token)).toString()

    @JvmStatic fun ackFrame(callId: String): String =
        JSONObject().put("t", "req").put("id", ACK_ID).put("ev", "call:ack")
            .put("data", JSONObject().put("callId", callId)).toString()

    /** What a server frame means for this call's ring; null = nothing to decide (other events, re-sent offers). */
    @JvmStatic fun onFrame(text: String, callId: String): Verdict? {
        val m = try { JSONObject(text) } catch (_: Exception) { return null }
        val data = m.optJSONObject("data")
        when (m.optString("t")) {
            "res" -> when (m.optInt("id", -1)) {
                RESUME_ID -> return if (data?.optBoolean("ok") == true) null else Verdict.NO_SOCKET
                ACK_ID -> {
                    if (data?.optBoolean("ok") != true) return Verdict.DROP // "No such call"
                    return phaseVerdict(data.optJSONObject("call"), callId) ?: Verdict.DROP
                }
            }
            "evt" -> if (m.optString("ev") == "call:state") {
                val v = phaseVerdict(data?.optJSONObject("call"), callId)
                return if (v == Verdict.DROP) v else null // only an end matters here; ringing is decided by the ack
            }
        }
        return null
    }

    private fun phaseVerdict(call: JSONObject?, callId: String): Verdict? {
        if (call == null || call.optString("id") != callId) return null
        return when (call.optString("phase")) {
            "calling", "ringing" -> Verdict.RING
            else -> Verdict.DROP // active (answered on another device) or ended
        }
    }

    /**
     * Ring deadline on THIS phone's clock from server timestamps only: the server's ring window (expiresAt − sentAt,
     * both server ms) counted from when the push arrived. A wrong phone clock can no longer drop or stretch a ring.
     * Pushes without sentAt (older servers) fall back to the absolute expiresAt.
     */
    @JvmStatic fun localDeadline(receivedAt: Long, expiresAt: Long, sentAt: Long): Long =
        if (sentAt > 0 && expiresAt > 0) receivedAt + (expiresAt - sentAt) else expiresAt

    /** Dedupe by call id: the first push for a call starts the wake; repeats (the ~5 s repush) are ignored. */
    class SeenCalls(private val keepMs: Long = 5 * 60_000L, private val max: Int = 32) {
        private val seen = LinkedHashMap<String, Long>()
        @Synchronized fun first(callId: String, now: Long): Boolean {
            seen.entries.removeAll { now - it.value > keepMs }
            if (seen.containsKey(callId)) return false
            seen[callId] = now
            while (seen.size > max) seen.remove(seen.keys.first())
            return true
        }
    }
}
