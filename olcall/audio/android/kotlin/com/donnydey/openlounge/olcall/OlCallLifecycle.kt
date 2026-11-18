package com.donnydey.openlounge.olcall

import org.json.JSONObject

/**
 * The server-owned call lifecycle (olcall/server/states.go), as received in {t:"call:state", lifecycle:{...}}.
 *
 * Single-writer: the olcall server is the only writer of this state. This phone never computes, advances or
 * caches its own copy; it holds the latest snapshot the server sent and nothing else. Wire values are pinned
 * to Go by the shared golden fixture olcall/integration/testdata/lifecycle.json.
 */
data class OlCallLifecycle(
    val callId: String,
    val leg: String,
    val state: State,
    /** Non-null only in [State.Terminal]. */
    val reason: Reason?,
    val seq: Long,
    val event: String,
) {
    /** BaoTime CallLifecycle's seven states, in lifecycle order. [wire] matches Go's CallState.String(). */
    enum class State(val wire: String) {
        Idle("idle"), Ringing("ringing"), Answering("answering"), JoiningUrl("joining_url"),
        Connecting("connecting"), InRoom("in_room"), Terminal("terminal");
        companion object { fun of(w: String): State? = values().firstOrNull { it.wire == w } }
    }

    enum class Reason(val wire: String) {
        Declined("declined"), Missed("missed"), Disconnected("disconnected"), Expired("expired"),
        CancelledByConfig("cancelled_by_config"), Failed("failed"), Completed("completed");
        companion object { fun of(w: String): Reason? = values().firstOrNull { it.wire == w } }
    }

    companion object {
        /** Parses the "lifecycle" object of a call:state frame; null if it is not a valid snapshot. */
        fun parse(o: JSONObject?): OlCallLifecycle? {
            if (o == null) return null
            val state = State.of(o.optString("state")) ?: return null
            val r = o.optString("reason")
            val reason = if (r.isEmpty()) null else (Reason.of(r) ?: return null)
            if ((state == State.Terminal) != (reason != null)) return null
            val seq = o.optLong("seq", -1)
            if (seq < 1) return null
            return OlCallLifecycle(o.optString("callId"), o.optString("leg"), state, reason, seq, o.optString("event"))
        }

        /** Observer rule: take the server's snapshot only if it is newer than the one held (seq order). */
        fun newer(held: OlCallLifecycle?, incoming: OlCallLifecycle): Boolean = held == null || incoming.seq > held.seq
    }
}
