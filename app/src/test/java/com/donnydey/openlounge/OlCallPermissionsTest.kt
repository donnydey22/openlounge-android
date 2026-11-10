package com.donnydey.openlounge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Signal / WhatsApp call-permission gate: what to request, when, and silent deny with no re-prompt. */
class OlCallPermissionsTest {
    private val mic = OlCallPermissions.MIC
    private val cam = OlCallPermissions.CAMERA
    private val audio = OlCallPermissions.Action.START_OR_ACCEPT_AUDIO
    private val video = OlCallPermissions.Action.START_OR_ACCEPT_VIDEO
    private val toggle = OlCallPermissions.Action.TOGGLE_VIDEO_ON

    @Before fun reset() { OlCallPermissions.resetForTests() }

    @Test fun audioCallRequestsMicOnly() {
        assertEquals(listOf(mic), OlCallPermissions.required(audio))
        assertEquals(listOf(mic), OlCallPermissions.missing(audio, emptySet()))
        assertTrue(cam !in OlCallPermissions.required(audio))
    }

    @Test fun videoCallRequestsMicAndCameraTogether() {
        // One combined RequestMultiplePermissions list: mic then camera, never camera alone at start.
        assertEquals(listOf(mic, cam), OlCallPermissions.required(video))
        assertEquals(listOf(mic, cam), OlCallPermissions.missing(video, emptySet()))
    }

    @Test fun midCallToggleRequestsCameraOnly() {
        assertEquals(listOf(cam), OlCallPermissions.required(toggle))
        assertEquals(listOf(cam), OlCallPermissions.missing(toggle, setOf(mic)))
        assertFalse(mic in OlCallPermissions.required(toggle))
    }

    @Test fun alreadyGrantedProceedsWithNoRequest() {
        assertEquals(OlCallPermissions.Decision.PROCEED, OlCallPermissions.decide(audio, setOf(mic)))
        assertEquals(OlCallPermissions.Decision.PROCEED, OlCallPermissions.decide(video, setOf(mic, cam)))
        assertEquals(OlCallPermissions.Decision.PROCEED, OlCallPermissions.decide(toggle, setOf(cam)))
        assertTrue(OlCallPermissions.missing(audio, setOf(mic)).isEmpty())
        assertTrue(OlCallPermissions.missing(video, setOf(mic, cam)).isEmpty())
    }

    @Test fun missingPermissionsAreRequestedOnce() {
        assertEquals(OlCallPermissions.Decision.REQUEST, OlCallPermissions.decide(audio, emptySet()))
        assertEquals(OlCallPermissions.Decision.REQUEST, OlCallPermissions.decide(video, emptySet()))
        assertEquals(OlCallPermissions.Decision.REQUEST, OlCallPermissions.decide(toggle, setOf(mic)))
        // Video start with mic already held: only camera is still requested (system dialog for the missing one).
        assertEquals(listOf(cam), OlCallPermissions.missing(video, setOf(mic)))
        assertEquals(OlCallPermissions.Decision.REQUEST, OlCallPermissions.decide(video, setOf(mic)))
    }

    @Test fun videoCombinedGrantProceeds() {
        val d = OlCallPermissions.decide(video, emptySet(), mapOf(mic to true, cam to true))
        assertEquals(OlCallPermissions.Decision.PROCEED, d)
    }

    @Test fun videoPartialGrantFailsSilentAndDoesNotStart() {
        // Mic yes, camera no in the combined dialog: the video call does not start, no second prompt.
        val d = OlCallPermissions.decide(video, emptySet(), mapOf(mic to true, cam to false))
        assertEquals(OlCallPermissions.Decision.DENY_SILENT, d)
        assertEquals(OlCallPermissions.Decision.DENY_SILENT, OlCallPermissions.decide(video, setOf(mic)))
    }

    @Test fun audioDeniedFailsSilentWithNoReprompt() {
        assertEquals(
            OlCallPermissions.Decision.DENY_SILENT,
            OlCallPermissions.decide(audio, emptySet(), mapOf(mic to false)),
        )
        assertEquals(
            "second tap must not re-prompt",
            OlCallPermissions.Decision.DENY_SILENT,
            OlCallPermissions.decide(audio, emptySet()),
        )
    }

    @Test fun toggleDeniedFailsSilentCallKeepsGoing() {
        // Camera denied mid-call: only the toggle fails. Audio start would still be allowed (mic not denied).
        assertEquals(
            OlCallPermissions.Decision.DENY_SILENT,
            OlCallPermissions.decide(toggle, setOf(mic), mapOf(cam to false)),
        )
        assertEquals(OlCallPermissions.Decision.DENY_SILENT, OlCallPermissions.decide(toggle, setOf(mic)))
        assertEquals(OlCallPermissions.Decision.PROCEED, OlCallPermissions.decide(audio, setOf(mic)))
    }

    @Test fun laterGrantInSettingsProceedsWithoutPrompt() {
        OlCallPermissions.decide(audio, emptySet(), mapOf(mic to false))
        assertEquals(OlCallPermissions.Decision.DENY_SILENT, OlCallPermissions.decide(audio, emptySet()))
        assertEquals(OlCallPermissions.Decision.PROCEED, OlCallPermissions.decide(audio, setOf(mic)))
    }

    @Test fun calleeAcceptUsesTheSameRules() {
        // Accept is the same Action as start: mic for audio, mic+camera for video.
        assertEquals(OlCallPermissions.required(audio), listOf(mic))
        assertEquals(OlCallPermissions.required(video), listOf(mic, cam))
        assertEquals(OlCallPermissions.Decision.REQUEST, OlCallPermissions.decide(audio, emptySet()))
        assertEquals(
            OlCallPermissions.Decision.DENY_SILENT,
            OlCallPermissions.decide(video, emptySet(), mapOf(mic to false, cam to false)),
        )
    }

    @Test fun missingKeyInResultCountsAsDenied() {
        assertEquals(
            OlCallPermissions.Decision.DENY_SILENT,
            OlCallPermissions.decide(video, emptySet(), mapOf(mic to true)),
        )
    }
}
