package com.donnydey.openlounge

import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup

/**
 * In-app call video views. MainActivity lays out two slots (remote full-screen, local PiP) under the transparent
 * WebView; every call is an olcall (tgcalls) call, which renders through Telegram's relocated WebRTC (org.olwrtc), so
 * each call gets a fresh org.olwrtc renderer pair placed exactly in those slots (same parent, index, layout params).
 * The slots themselves are plain placeholders that stay hidden.
 */
object NativeCallVideo {
    @JvmStatic fun renderer(ctx: Context, overlay: Boolean): SurfaceView =
        SurfaceView(ctx).also { if (overlay) it.setZOrderMediaOverlay(true) }
    private var remoteSlot: SurfaceView? = null
    private var localSlot: SurfaceView? = null
    @JvmStatic fun bind(remote: SurfaceView, local: SurfaceView) { remoteSlot = remote; localSlot = local }

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
    /** Remove the call's renderers. */
    fun endOlcall() {
        listOfNotNull(olRemote, olLocal).forEach { v -> (v.parent as? ViewGroup)?.removeView(v) }
        olRemote = null; olLocal = null
    }
    /** Self-view frame (px, top-left in the parent) with the PiP's rounded corners; slot and renderer move together. */
    fun placeLocal(x: Int, y: Int, w: Int, h: Int, radius: Float) {
        listOfNotNull(localSlot, olLocal).forEach { v ->
            val lp = (v.layoutParams as? android.widget.FrameLayout.LayoutParams) ?: return@forEach
            lp.width = w; lp.height = h; lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.setMargins(0, 0, 0, 0); v.layoutParams = lp
            v.translationX = x.toFloat(); v.translationY = y.toFloat()
            v.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, radius) }
            }
            v.clipToOutline = true
        }
    }
    /** MainActivity.nativeCallVideo: show/hide the current call's renderer pair (slots stay hidden). */
    @JvmStatic fun show(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        remoteSlot?.visibility = View.GONE; localSlot?.visibility = View.GONE
        olRemote?.visibility = v; olLocal?.visibility = v
    }
}
