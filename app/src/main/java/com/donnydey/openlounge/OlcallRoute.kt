package com.donnydey.openlounge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Whether a "connect" op can run on the olcall engine. Pure (no Android calls) so it is unit-tested on the JVM.
 *
 * olcall (the :olcall module, tgcalls -> Go/Pion wss://.../v1/call, coturn relay) is the ONLY call engine. It runs when
 * the backend hands out CallMedia { provider: "olcall", url: wss://.../v1/call, token } and the phone is API 27+ (the
 * module's minSdk) on an arm ABI. Anything else fails the call ("olcall-unavailable"); there is no other engine to fall
 * back to. The relay list: olcall's joined frame carries the server's iceServers (primary); CallMedia.iceServers from
 * Railway is handed over as the secondary list ([appIceServers]); OlRtcServers.select picks between them.
 */
object OlcallRoute {
    const val MIN_SDK = 27

    /** ABIs libolcall_tgcalls.so is built for (the APK ships only these). */
    val ABIS = setOf("arm64-v8a", "armeabi-v7a")

    fun deviceSupported(sdkInt: Int, abis: List<String> = ABIS.toList()): Boolean =
        sdkInt >= MIN_SDK && abis.firstOrNull() in ABIS

    fun eligible(media: JSONObject?, sdkInt: Int, abis: List<String> = ABIS.toList()): Boolean =
        media != null && deviceSupported(sdkInt, abis) &&
            media.optString("provider") == "olcall" &&
            media.optString("url").let { it.startsWith("wss://") || it.startsWith("ws://") } &&
            media.optString("token").isNotEmpty()

    /** CallMedia.iceServers (TurnIceServer[] from src/lib/types.ts) or null; never logged with credentials. */
    fun appIceServers(media: JSONObject?): JSONArray? = media?.optJSONArray("iceServers")?.takeIf { it.length() > 0 }
}
