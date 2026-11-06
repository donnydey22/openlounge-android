package com.donnydey.openlounge

import android.content.Context
import android.view.SurfaceView
import com.donnydey.openlounge.rtc.OlVideo
import org.webrtc.SurfaceViewRenderer

/**
 * In-app call video views (matches iOS OLRtcVideo): camera + remote video ride the call's one WebRTC PeerConnection
 * (callengine rtc/OlVideo). This object only builds/binds MainActivity's renderers (typed as SurfaceView so the Java
 * side never touches org.webrtc classes). PJSIP video (ol_sfu_video, OLCameraFanout, MediaCodec simulcast) is gone.
 */
object NativeCallVideo {
    @JvmStatic fun renderer(ctx: Context, overlay: Boolean): SurfaceView =
        SurfaceViewRenderer(ctx).also { if (overlay) it.setZOrderMediaOverlay(true) }
    @JvmStatic fun bind(remote: SurfaceView, local: SurfaceView) {
        OlVideo.remoteView = remote as? SurfaceViewRenderer
        OlVideo.localView = local as? SurfaceViewRenderer
    }
    var onFirstRemoteFrame: (() -> Unit)?
        get() = OlVideo.onFirstRemoteFrame
        set(v) { OlVideo.onFirstRemoteFrame = v }
    val facing: String get() = OlVideo.facing

    fun camera(on: Boolean, face: String = OlVideo.facing, done: (Boolean, String?) -> Unit) = OlVideo.camera(on, face, done)
    fun stop() = OlVideo.end()
}
