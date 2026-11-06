package com.donnydey.openlounge.audio

/**
 * Shared typed failure contract (plan §0): permission/device/session/codec/network/signaling.
 * Original platform/engine codes are retained verbatim. Only NETWORK may ever trigger ICE restart / relay.
 */
enum class CallFailureDomain { PERMISSION, DEVICE, SESSION, CODEC, NETWORK, SIGNALING }

data class CallFailure(val domain: CallFailureDomain, val step: String, val code: Int, val message: String) {
    /** Local failures (everything except NETWORK) must NEVER become relay/TURN/ICE-restart attempts. */
    val relayEligible: Boolean get() = domain == CallFailureDomain.NETWORK
    fun toJson(): String = org.json.JSONObject()
        .put("domain", domain.name.lowercase()).put("step", step).put("status", code)
        .put("networkRelated", relayEligible).put("allowRelay", relayEligible).put("message", message).toString()

    companion object {
        /** Legacy PJSIP fixtures (420003 PJMEDIA_EAUD_INIT, -201) classify as local device failures, never relay-worthy. */
        @JvmStatic fun classifyLegacy(code: Int): CallFailure =
            CallFailure(CallFailureDomain.DEVICE, "legacy-audio-open", code, "legacy audio-device status=$code")
    }
}
