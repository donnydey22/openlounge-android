package com.donnydey.openlounge.olcall

/**
 * In-call output choice for an olcall call. Pure, so it is unit-tested on the JVM.
 *
 *   explicit pick from the call screen (Speaker / Phone) wins while that device exists;
 *   otherwise a headset wins: Bluetooth, then USB / wired;
 *   otherwise video calls go to the loudspeaker and audio calls to the earpiece (OL behaviour).
 * A phone without an earpiece (tablet) uses the loudspeaker.
 */
object OlAudioRouting {
    enum class Kind { SPEAKER, EARPIECE, WIRED, USB, BLUETOOTH }

    fun choose(available: Set<Kind>, video: Boolean, speakerPick: Boolean?): Kind {
        when (speakerPick) {
            true -> if (Kind.SPEAKER in available) return Kind.SPEAKER
            false -> {
                headset(available)?.let { return it }
                if (Kind.EARPIECE in available) return Kind.EARPIECE
            }
            null -> {}
        }
        headset(available)?.let { return it }
        if (video && Kind.SPEAKER in available) return Kind.SPEAKER
        if (Kind.EARPIECE in available) return Kind.EARPIECE
        return Kind.SPEAKER
    }

    private fun headset(available: Set<Kind>): Kind? = when {
        Kind.BLUETOOTH in available -> Kind.BLUETOOTH
        Kind.USB in available -> Kind.USB
        Kind.WIRED in available -> Kind.WIRED
        else -> null
    }

    fun label(k: Kind): String = k.name.lowercase()
}
