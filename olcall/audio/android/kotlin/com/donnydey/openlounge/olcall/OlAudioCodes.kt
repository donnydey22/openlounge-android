package com.donnydey.openlounge.olcall

/**
 * Audio error codes. RULE: codes are reported verbatim with their domain;
 * nothing here maps a code from one domain to another.
 *
 * - 420003 = PJMEDIA_EAUD_INIT of the RETIRED PJSIP stack
 *   (pj-openlounge-android 8cb35eb NativeCallMedia.kt:111-112; PJMEDIA
 *   audiodev errno start 420000 + 3). It cannot be produced by the new
 *   engine; it exists only in legacy logs and the regression fixture.
 * - 4200003 = the code the owner reported for the Samsung Note 10+ openSound
 *   failure. It is not present anywhere in the OL repositories or their git
 *   history (searched 2026-11-06). Most likely a transcription of 420003, but
 *   that is UNVERIFIED and is not assumed in code. It is kept only as a label
 *   on the replay fixture.
 * - New engine: AAudio aaudio_result_t (e.g. AAUDIO_ERROR_INTERNAL = -896) or
 *   OpenSL ES SLresult, as returned by the backend, inside the open report
 *   JSON produced by OlAudioDeviceModule.
 */
object OlAudioCodes {
    const val PJMEDIA_EAUD_INIT_LEGACY = 420003
    const val OWNER_REPORTED_NOTE10_CODE_UNVERIFIED = 4200003

    /** Human label for logs; returns the verbatim code, never a substitute. */
    fun describe(domain: String, status: Int): String = when {
        domain == "pjsip" && status == PJMEDIA_EAUD_INIT_LEGACY -> "pjsip:$status (PJMEDIA_EAUD_INIT, legacy stack)"
        domain == "aaudio" && status == -896 -> "aaudio:$status (AAUDIO_ERROR_INTERNAL)"
        domain == "aaudio" && status == -889 -> "aaudio:$status (AAUDIO_ERROR_UNAVAILABLE)"
        domain == "aaudio" && status == -899 -> "aaudio:$status (AAUDIO_ERROR_DISCONNECTED)"
        else -> "$domain:$status"
    }
}
