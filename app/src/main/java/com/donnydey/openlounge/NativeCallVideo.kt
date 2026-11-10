package com.donnydey.openlounge

import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
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
    private var remoteSlot: SurfaceView? = null
    private var localSlot: SurfaceView? = null
    @JvmStatic fun bind(remote: SurfaceView, local: SurfaceView) {
        remoteSlot = remote; localSlot = local
        OlVideo.remoteView = remote as? SurfaceViewRenderer
        OlVideo.localView = local as? SurfaceViewRenderer
    }

    // ---- olcall (tgcalls) calls render through Telegram's relocated WebRTC (org.olwrtc), so they get their own
    // renderer pair placed exactly where MainActivity put the org.webrtc pair (same parent, index, layout params).
    private var olRemote: org.olwrtc.SurfaceViewRenderer? = null
    private var olLocal: org.olwrtc.SurfaceViewRenderer? = null
    /** Fresh org.olwrtc renderers for one olcall call (OlCallSession inits/releases them); null pair if no slots. */
    fun olcallRenderers(ctx: Context): Pair<org.olwrtc.SurfaceViewRenderer?, org.olwrtc.SurfaceViewRenderer?> {
        endOlcall()
        val r = remoteSlot; val l = localSlot
        val parent = r?.parent as? ViewGroup ?: return null to null
        val c = r.context ?: ctx
        val rem = org.olwrtc.SurfaceViewRenderer(c)
        val loc = org.olwrtc.SurfaceViewRenderer(c).also { it.setZOrderMediaOverlay(true) }
        parent.addView(rem, parent.indexOfChild(r) + 1, r.layoutParams)
        if (l != null && l.parent === parent) parent.addView(loc, parent.indexOfChild(l) + 1, l.layoutParams)
        rem.visibility = View.GONE; loc.visibility = View.GONE
        olRemote = rem; olLocal = loc
        return rem to loc
    }
    /** Remove the olcall renderers; the org.webrtc pair becomes the active one again. */
    fun endOlcall() {
        listOfNotNull(olRemote, olLocal).forEach { v -> (v.parent as? ViewGroup)?.removeView(v) }
        olRemote = null; olLocal = null
    }
    /** MainActivity.nativeCallVideo: show/hide whichever renderer pair the current call uses. */
    @JvmStatic fun show(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        val ol = olRemote
        if (ol != null) {
            remoteSlot?.visibility = View.GONE; localSlot?.visibility = View.GONE
            ol.visibility = v; olLocal?.visibility = v
        } else {
            remoteSlot?.visibility = v; localSlot?.visibility = v
        }
    }
    var onFirstRemoteFrame: (() -> Unit)?
        get() = OlVideo.onFirstRemoteFrame
        set(v) { OlVideo.onFirstRemoteFrame = v }
    val facing: String get() = OlVideo.facing

    fun camera(on: Boolean, face: String = OlVideo.facing, done: (Boolean, String?) -> Unit) = OlVideo.camera(on, face, done)
    fun stop() = OlVideo.end()
}
