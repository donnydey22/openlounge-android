package com.donnydey.openlounge.ringrtc

/**
 * RingRTC CallManager session model (2.74.0 CallState / CallEvent / HangupType), Android. One active call; a second
 * incoming call gets busy. Drives ConnectionService/Telecom state through [listener]; media = rtc.RtcCallSession
 * (BSD WebRTC M152) over [RcSealedTransport].
 */
enum class RcCallState { IDLE, STARTING, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED }
enum class RcHangup { NORMAL, ACCEPTED, DECLINED, BUSY, NEED_PERMISSION, TIMEOUT, FAILED }
enum class RcCallEvent { LOCAL_RINGING, REMOTE_RINGING, LOCAL_CONNECTED, RECONNECTING, RECONNECTED, LOCAL_HANGUP, REMOTE_HANGUP, REMOTE_BUSY,
    ENDED_TIMEOUT, ENDED_CONNECTION_FAILURE, ENDED_DEVICE_FAILURE, ENDED_SIGNALING_FAILURE }

interface RcCallListener {
    fun onState(callId: String, s: RcCallState, ev: RcCallEvent?)
    fun onEnded(callId: String, h: RcHangup, detail: String?)
}

object RcCallManager {
    const val RING_TIMEOUT_MS = 60_000L
    var listener: RcCallListener? = null
    @Volatile var callId: String? = null; private set
    @Volatile var state = RcCallState.IDLE; private set

    /** @return false = busy (RingRTC: auto-reply busy to a second call) */
    @Synchronized fun begin(id: String, outgoing: Boolean): Boolean {
        if (callId != null && callId != id && state != RcCallState.ENDED && state != RcCallState.IDLE) return false
        callId = id; move(if (outgoing) RcCallState.STARTING else RcCallState.RINGING, if (outgoing) RcCallEvent.REMOTE_RINGING else RcCallEvent.LOCAL_RINGING)
        return true
    }
    @Synchronized fun connecting(id: String) { if (id == callId) move(RcCallState.CONNECTING, null) }
    @Synchronized fun connected(id: String) { if (id == callId) move(RcCallState.CONNECTED, if (state == RcCallState.RECONNECTING) RcCallEvent.RECONNECTED else RcCallEvent.LOCAL_CONNECTED) }
    @Synchronized fun reconnecting(id: String) { if (id == callId && state == RcCallState.CONNECTED) move(RcCallState.RECONNECTING, RcCallEvent.RECONNECTING) }
    @Synchronized fun end(id: String, h: RcHangup, ev: RcCallEvent, detail: String? = null) {
        if (id != callId || state == RcCallState.ENDED) return
        move(RcCallState.ENDED, ev); listener?.onEnded(id, h, detail); callId = null; state = RcCallState.IDLE
    }
    private fun move(s: RcCallState, ev: RcCallEvent?) { state = s; callId?.let { listener?.onState(it, s, ev) } }
}
