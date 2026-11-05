package com.donnydey.openlounge

/**
 * Page call message -> Telegram VoIPService state (DrKLO/Telegram@3de37c8 messenger/voip/VoIPService.java,
 * ui/VoIPFragment.java updateViewState). Pure, so it is unit-tested on the JVM. Nothing else is in the flow.
 */
object NativeCallFlow {
    enum class State { WAITING, WAIT_INIT, ESTABLISHED, ENDED }
    @JvmStatic fun stateFor(type: String): State? = when (type) {
        "call:outgoing" -> State.WAITING      // STATE_WAITING (WhatsApp wording "Calling…")
        "call:answering" -> State.WAIT_INIT   // STATE_WAIT_INIT -> R.string.VoipConnecting
        "call:connected" -> State.ESTABLISHED // STATE_ESTABLISHED: engine reported connected -> showTimer
        "call:ended" -> State.ENDED           // STATE_ENDED: no rating -> windowView.finish()
        else -> null
    }
    @JvmStatic fun statusText(s: State?): String = when (s) { State.WAITING -> "Calling…"; State.WAIT_INIT -> "Connecting…"; else -> "" }
    @JvmStatic fun timer(sec: Long): String = String.format("%d:%02d", sec / 60, sec % 60)
    /** VoIPFragment.updateButtons slot 0: frontal-camera action while my video is on, else speaker. */
    @JvmStatic fun firstButtonIsFlip(camOn: Boolean) = camOn
}
