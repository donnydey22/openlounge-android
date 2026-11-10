package com.donnydey.openlounge

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.donnydey.openlounge.olcall.OlCallKit
import com.donnydey.openlounge.olcall.OlCallSession
import com.donnydey.openlounge.olcall.OlCallUi
import org.json.JSONObject

/**
 * One olcall (tgcalls) call driven by [CallMediaCoordinator] when [OlcallRoute.eligible] (the only call engine).
 * Maps OlCallSession.UiState onto the coordinator's host events (connected / failed / remote-video / audio route).
 */
internal class OlcallCall(
    ctx: Context,
    private val id: String,
    media: JSONObject,
    video: Boolean,
    peerId: String,
    private val onConnected: () -> Unit,
    private val onRemoteVideo: () -> Unit,
    /** route label (speaker/earpiece/wired/usb/bluetooth), speaker on */
    private val onRoute: (route: String, speaker: Boolean) -> Unit,
    /** reason; connectedBefore = the call had reached InCall */
    private val onEnded: (reason: String, connectedBefore: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var connected = false
    private var ended = false
    private var remoteVideo = false
    private var route: String? = null
    private val session: OlCallSession

    init {
        OlCallKit.init(ctx.applicationContext)
        val ui = OlCallUi { s ->
            if (ended) return@OlCallUi
            if (s.phase == OlCallSession.Phase.InCall && !connected) { connected = true; onConnected() }
            if (s.remoteVideoOn && !remoteVideo) { remoteVideo = true; onRemoteVideo() }
            if (s.audioRoute != route) { route = s.audioRoute; onRoute(s.audioRoute, s.speakerOn) }
            if (s.phase == OlCallSession.Phase.Ended) { ended = true; onEnded(s.endReason ?: "ended", connected) }
        }
        session = OlCallSession(ctx.applicationContext, media.optString("url"), media.optString("token"),
            OlCallSession.Peer(peerId, "", null), video, ui,
            appIceServers = OlcallRoute.appIceServers(media),
            callLog = OlCallLogs.recorder(id, media.optString("token"), video))
        val (remote, local) = NativeCallVideo.olcallRenderers(ctx)
        session.attachRenderers(remote, local)
    }

    fun start() = session.start()
    fun setMuted(m: Boolean) = session.setMuted(m)
    fun setSpeaker(on: Boolean) = session.setSpeaker(on)
    private var front = true
    fun camera(on: Boolean, facing: String) {
        session.setCameraEnabled(on)
        val wantFront = facing != "environment"
        if (on && wantFront != front) { front = wantFront; session.switchCamera() }
    }
    fun hangup() { if (!ended) runCatching { session.hangup() } }
}
