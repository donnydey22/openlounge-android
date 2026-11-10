package com.donnydey.openlounge.olcall

import com.donnydey.openlounge.olcall.OlAudioRouting.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class OlAudioRoutingTest {
    private val phone = setOf(Kind.SPEAKER, Kind.EARPIECE)

    @Test fun defaultsAudioEarpieceVideoSpeaker() {
        assertEquals(Kind.EARPIECE, OlAudioRouting.choose(phone, video = false, speakerPick = null))
        assertEquals(Kind.SPEAKER, OlAudioRouting.choose(phone, video = true, speakerPick = null))
    }

    @Test fun headsetWinsAutomatically() {
        assertEquals(Kind.BLUETOOTH, OlAudioRouting.choose(phone + Kind.BLUETOOTH + Kind.WIRED, video = true, speakerPick = null))
        assertEquals(Kind.WIRED, OlAudioRouting.choose(phone + Kind.WIRED, video = false, speakerPick = null))
        assertEquals(Kind.USB, OlAudioRouting.choose(phone + Kind.USB + Kind.WIRED, video = false, speakerPick = null))
    }

    @Test fun explicitPickWins() {
        assertEquals(Kind.SPEAKER, OlAudioRouting.choose(phone + Kind.BLUETOOTH, video = false, speakerPick = true))
        assertEquals(Kind.EARPIECE, OlAudioRouting.choose(phone, video = true, speakerPick = false))
        // "Phone" with a headset connected goes back to the headset, not the earpiece
        assertEquals(Kind.BLUETOOTH, OlAudioRouting.choose(phone + Kind.BLUETOOTH, video = true, speakerPick = false))
    }

    @Test fun noEarpieceUsesSpeaker() {
        assertEquals(Kind.SPEAKER, OlAudioRouting.choose(setOf(Kind.SPEAKER), video = false, speakerPick = null))
        assertEquals(Kind.SPEAKER, OlAudioRouting.choose(setOf(Kind.SPEAKER), video = false, speakerPick = false))
    }
}
