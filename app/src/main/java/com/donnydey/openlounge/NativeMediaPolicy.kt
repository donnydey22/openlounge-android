package com.donnydey.openlounge

/**
 * Pure decisions for native call media (no Android/WebRTC types, so they are JVM unit-tested):
 * what a page 'connect' does, the recovery ladder and its backoff, and when the transports count as up.
 * Mirrors the iOS controller (ios-r2 src/nativeMedia.ts, 071d489 connect {rebuild:true}).
 */
object NativeMediaPolicy {
    enum class ConnectAction { ATTACH, REBUILD_IN_PLACE, NEW_SESSION }
    enum class RecoveryStep { ICE_RESTART, REBUILD }

    val BACKOFF_MS = longArrayOf(500, 1_000, 2_000, 4_000, 8_000, 15_000)
    const val ICE_RESTARTS_BEFORE_REBUILD = 2

    /**
     * Same call + same path + live session: a plain connect is a page reload → re-attach; connect {rebuild:true} is
     * the page's outage recovery (its ICE restarts did not bring media back) → fresh transports on the same
     * nativeConn, mic kept. Anything else (no session, other call, UDP ⇄ TCP path change) → new session.
     */
    fun connectAction(hasLive: Boolean, curCallId: String?, curTcpOnly: Boolean, callId: String, tcpOnly: Boolean, rebuild: Boolean): ConnectAction {
        if (!hasLive || curCallId != callId || curTcpOnly != tcpOnly) return ConnectAction.NEW_SESSION
        return if (rebuild) ConnectAction.REBUILD_IN_PLACE else ConnectAction.ATTACH
    }

    /** attempt is 1-based: ICE restart ×2, then full transport rebuilds for as long as the call lives */
    fun recoveryStep(attempt: Int): RecoveryStep = if (attempt <= ICE_RESTARTS_BEFORE_REBUILD) RecoveryStep.ICE_RESTART else RecoveryStep.REBUILD

    fun backoffMs(attempt: Int): Long = BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]

    /** 'failed'/'closed' skips straight to rebuilds; 'disconnected' tries ICE restarts first */
    fun startAttempt(state: String): Int = if (state == "disconnected") 0 else ICE_RESTARTS_BEFORE_REBUILD

    fun isUp(state: String?): Boolean = state == "connected" || state == "completed"

    /** media is up when send is up and recv is up (or nothing is being received yet) */
    fun transportsUp(send: String?, recv: String?, hasConsumers: Boolean): Boolean = isUp(send) && (isUp(recv) || !hasConsumers)

    fun isLoss(state: String): Boolean = state == "disconnected" || state == "failed" || state == "closed"

    fun validLiveKitUrl(url: String?): Boolean = url != null && (url.startsWith("wss://") || url.startsWith("https://"))
}
