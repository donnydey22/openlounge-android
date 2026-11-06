package com.donnydey.openlounge

/**
 * Native 1:1 call phases (Claude native-UI scope: incoming / outgoing / active / ended).
 * Page call:* messages still map in for this first cut; CallController will re-source later.
 */
object NativeCallFlow {
    enum class State { INCOMING, WAITING, WAIT_INIT, ESTABLISHED, ENDED }
    @JvmStatic fun stateFor(type: String): State? = when (type) {
        "call:incoming" -> State.INCOMING
        "call:outgoing" -> State.WAITING      // outgoing "Calling…"
        "call:answering" -> State.WAIT_INIT   // "Connecting…"
        "call:connected" -> State.ESTABLISHED // active
        "call:ended" -> State.ENDED
        else -> null
    }
    @JvmStatic fun statusText(s: State?): String = when (s) {
        State.INCOMING -> "Incoming call"
        State.WAITING -> "Calling…"
        State.WAIT_INIT -> "Connecting…"
        State.ENDED -> "Call ended"
        else -> ""
    }
    @JvmStatic fun timer(sec: Long): String = String.format("%d:%02d", sec / 60, sec % 60)
    @JvmStatic fun firstButtonIsFlip(camOn: Boolean) = camOn
}
