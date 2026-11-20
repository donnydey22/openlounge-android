package com.donnydey.openlounge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.donnydey.openlounge.olcall.OlCallLifecycle
import org.json.JSONObject

/**
 * Native 1:1 call screen (outgoing / connecting / active audio / active video / end), drawn above the WebView.
 *
 * Flow + state: the page's call engine stays the single owner of call:* on openlounge.app (no server or page change).
 * It already posts call:outgoing / call:answering / call:connected / call:ended / call:muted to the app, and accepts
 * call:hangup / call:mute back (openlounge:native-call, CallProvider.tsx). This screen only maps those to Telegram's
 * VoIPService states and draws them like Telegram's VoIPFragment:
 *   call:outgoing  -> STATE_WAITING        (VoIPFragment.updateViewState: status text)  [WhatsApp wording "Calling…"]
 *   call:answering -> STATE_WAIT_INIT      ("Connecting…")
 *   call:connected -> STATE_ESTABLISHED    (showTimer; = engine reported connected, VoIPService.onConnectionStateChanged)
 *   call:ended     -> STATE_ENDED          (no rating -> windowView.finish(): screen removed)
 * Placement: audio in-call [Speaker] [Mute] [End call]; video stage adds Flip / Camera; pre-answer is Speaker / Mute / End call
 * so a video call can still switch lenses (the first commit listed flip as an in-call control).
 * Look = the first Open Lounge call screen (web CallOverlay.tsx / calls.module.css at 101e00a, 2026-10-07 MST).
 * Refs: DrKLO/Telegram@3de37c8 TMessagesProj/.../ui/VoIPFragment.java, .../messenger/voip/VoIPService.java.
 *
 * Native-UI first cut (Claude step 5): this screen owns all four phases — never hands the call paint back to the
 * WebView. Missing native video tiles keep the avatar + "Connecting…" chrome; no 3 s web-video fallback.
 * Kill switch: Prefs native_call_ui.
 */
class NativeCallScreen(private val act: Activity, private val root: FrameLayout, private val actions: Actions) {
    companion object {
        private val mediaUp = mutableSetOf<String>()
        private val screens = java.util.Collections.newSetFromMap(java.util.WeakHashMap<NativeCallScreen, Boolean>())
        /** Latest server lifecycle snapshot per call (seq-ordered). The server is the only writer; this is a cache of what it sent. */
        private val latest = HashMap<String, OlCallLifecycle>()

        /** Main thread. Native media for [id] is flowing (olcall onConnected): enables Speaker / Mute. Never moves the screen. */
        @JvmStatic fun onNativeMediaFlowing(id: String) {
            if (id.isEmpty()) return
            mediaUp.add(id)
            screens.toList().forEach { it.onMediaUp(id) }
        }
        @JvmStatic fun onNativeMediaEnded(id: String) { mediaUp.remove(id); remoteCam.remove(id); remoteMic.remove(id) }
        private val remoteMic = mutableSetOf<String>()
        /** Main thread. The other person's mic turned off / on (their tgcalls audio state): web's mute badge. */
        @JvmStatic fun onRemoteMuted(id: String, muted: Boolean) {
            if (id.isEmpty()) return
            if (muted) remoteMic.add(id) else remoteMic.remove(id)
            screens.toList().forEach { it.onRemoteMic(id, muted) }
        }
        private val remoteCam = mutableSetOf<String>()
        /** Main thread. The other person's camera turned on / off (olcall remote video state). Never moves the state. */
        @JvmStatic fun onRemoteVideo(id: String, on: Boolean) {
            if (id.isEmpty()) return
            if (on) remoteCam.add(id) else remoteCam.remove(id)
            screens.toList().forEach { it.onRemoteCam(id, on) }
        }

        /**
         * Main thread. A server call:state snapshot, from either route: the olcall leg socket (OlcallCall.onLifecycle)
         * or the backend relay forwarded verbatim by the page (call:lifecycle). Older or duplicate seqs are dropped.
         */
        @JvmStatic fun onServerLifecycle(l: OlCallLifecycle) {
            if (l.callId.isEmpty() || !l.callId.matches(Regex(CallApi.CALL_ID_RE))) return
            if (!OlCallLifecycle.newer(latest[l.callId], l)) return
            latest[l.callId] = l
            if (latest.size > 16) latest.keys.firstOrNull { it != l.callId }?.let { latest.remove(it) }
            CallDiskLog.mark("lifecycle", "${l.state.wire} seq=${l.seq}${l.reason?.let { " reason=" + it.wire } ?: ""}")
            screens.toList().forEach { it.applyLifecycle(l) }
            NativeCallFlow.pageStateJson(l)?.let { MainActivity.dispatchNativeCall(it) } // seven states back to the page
        }
        @JvmStatic fun latestFor(id: String): OlCallLifecycle? = latest[id]
    }
    init { screens.add(this) }

    interface Actions {
        fun pageCall(detailJson: String): Boolean
        fun speaker(on: Boolean)
        fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit)
        /** Read-only peek of the page's existing call-screen presence (data-presence). */
        fun peekCallPresence(done: (String?) -> Unit) { done(null) }
        /** Read-only peek of the page call-screen profile photo src, when the message had none. */
        fun peekCallPhoto(done: (String?) -> Unit) { done(null) }
        /** Read-only peek of the page call-screen display name (the person's current profile name). */
        fun peekCallName(done: (String?) -> Unit) { done(null) }
        /** When minimized from a host with no WebView, bring the real app forward. No hang-up. */
        fun revealAppWhenMinimized() {}
    }


    var state: NativeCallFlow.Screen? = null; private set
    /** Last server snapshot applied to this screen. */
    var lifecycle: OlCallLifecycle? = null; private set
    private var callId: String? = null
    private var video = false
    private var nativeVideo = false
    private var muted = false
    private var speakerOn = false
    private var camOn = false
    private var facing = "user"
    private var establishedAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    private lateinit var name: TextView
    private lateinit var status: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (state != NativeCallFlow.Screen.ACTIVE) return
            val s = (SystemClock.elapsedRealtime() - establishedAt) / 1000
            status.text = NativeCallFlow.timer(s)
            ui.postDelayed(this, 1000)
        }
    }

    /**
     * Page -> app call message (MainActivity.onPageCall). Metadata only: call:outgoing / call:answering open the
     * surface with name, photo and video; call:lifecycle carries the backend's relayed server snapshot verbatim.
     * No page message changes the state; only server lifecycle snapshots do (applyLifecycle).
     */
    fun onPageCall(type: String, m: JSONObject) {
        val id = m.optString("callId", "")
        when (type) {
            "call:incoming" -> if (id.isNotEmpty()) { incoming = id to m.optString("name", ""); incomingPhoto = id to m.optString("photo", ""); if (CallPresence.hasField(m)) incomingPresence = id to CallPresence.fromMessage(m) }
            "call:outgoing" -> if (id.isNotEmpty() && callId != id) { link = false; begin(id, m.optString("name", ""), m.optBoolean("video"), m.optString("photo", ""), if (CallPresence.hasField(m)) CallPresence.fromMessage(m) else null) }
            "call:answering" -> if (id.isNotEmpty() && callId != id) { link = m.optBoolean("link", false); begin(id, m.optString("name", "").ifEmpty { incoming?.takeIf { it.first == id }?.second.orEmpty() }, m.optBoolean("video"), m.optString("photo", "").ifEmpty { incomingPhoto?.takeIf { it.first == id }?.second.orEmpty() }, if (CallPresence.hasField(m)) CallPresence.fromMessage(m) else incomingPresence?.takeIf { it.first == id }?.second); if (m.optBoolean("link", false)) { link = true; render() } }
            "call:lifecycle" -> OlCallLifecycle.parse(m.optJSONObject("lifecycle"))?.let { onServerLifecycle(it) }
            "call:muted" -> { muted = m.optBoolean("muted"); render() }
            "call:audioRoute" -> if (m.has("speaker")) { speakerOn = m.optBoolean("speaker"); render() }
        }
        if (view != null && CallPresence.hasField(m) && (id.isEmpty() || id == callId)) applyPresence(CallPresence.fromMessage(m))
    }

    private fun onMediaUp(id: String) { if (view != null && id == callId) { media = true; render() } }
    private fun onRemoteMic(id: String, muted: Boolean) { if (view != null && id == callId) { remoteMuted = muted; render() } }
    private var remoteMuted = false
    private lateinit var muteBadge: android.widget.ImageView
    private fun onRemoteCam(id: String, on: Boolean) { if (view != null && id == callId) { remoteOn = on; render() } }
    /** One path (t6093u): a call link (no ring). Alone with this phone's leg up reads "Waiting for <name>…". */
    private var link = false
    /** The other person's camera is on (their video is the big tile); off = their avatar tile. */
    private var remoteOn = false
    /** Video stage: in the room with a camera on either side (no cameras = the plain avatar screen). */
    private val stageOn get() = nativeVideo && !failed && live && (remoteOn || camOn)
    /**
     * Web parity (t6106u): their camera off on the video stage = their avatar tile on the opaque stage backdrop.
     * The window used to go fully transparent here with the remote renderer hidden, so the stage showed whatever
     * was under it (clear / black). Only the self-view PiP stays a hole onto its renderer (StageBackdrop.hole).
     */
    private val avatarTile get() = stageOn && !remoteOn

    /** A server snapshot for this screen's call drives the surface. Snapshots for other calls are kept in [latest]. */
    private fun applyLifecycle(l: OlCallLifecycle) {
        if (view == null || l.callId != callId) return
        if (!OlCallLifecycle.newer(lifecycle, l)) return
        lifecycle = l
        when (val sc = NativeCallFlow.screenFor(l)) {
            null -> Unit
            NativeCallFlow.Screen.FAILED -> showFailed(CallPresence.FAIL_MSG)
            NativeCallFlow.Screen.ENDED -> showEndCard(l.reason)
            NativeCallFlow.Screen.ACTIVE -> { set(sc); if (hidden) { hidden = false; view?.visibility = View.VISIBLE } }
            else -> set(sc)
        }
    }

    /** MainActivity.nativeCallVideo: native tiles are up (remote full screen, self-view PiP) -> transparent screen. */
    fun onNativeVideo(on: Boolean) { nativeVideo = on; if (on) { video = true; if (hidden) { hidden = false; view?.visibility = View.VISIBLE } }; render() }
    private var hidden = false
    /** This phone's olcall leg is up (enables Speaker / Mute before InRoom). Not a state. */
    private var media = false
    /** The full call UI (row, timer, video, minimize) shows only in the server's in_room state. */
    private val live get() = state == NativeCallFlow.Screen.ACTIVE
    private lateinit var endBtn: Btn
    private var incoming: Pair<String, String>? = null
    private var incomingPhoto: Pair<String, String>? = null
    private var incomingPresence: Pair<String, String?>? = null

    private fun begin(id: String, peer: String, v: Boolean, photo: String = "", presence: String? = null) {
        val keepLink = link; dismiss(); link = keepLink; callId = id; remoteOn = id in remoteCam; remoteMuted = id in remoteMic; video = v; camOn = v; muted = false; speakerOn = v; facing = "user"; failed = false; failedText = null; media = id in mediaUp; lifecycle = null; endedReason = null
        if (!Prefs.nativeCallUi(act)) { callId = null; return }
        photoLoading = null
        build(); name.text = peer.ifEmpty { act.getString(R.string.app_name) }; loadPhoto(id, photo)
        applyPresence(presence ?: incomingPresence?.takeIf { it.first == id }?.second)
        startPresencePeek()
        // Paint name / avatar now; the status line stays blank (no local state) until the server's first snapshot.
        latest[id]?.let { applyLifecycle(it) } ?: render()
    }

    private fun set(s: NativeCallFlow.Screen) { if (view == null) return; state = s
        if (s == NativeCallFlow.Screen.ACTIVE && establishedAt == 0L) { establishedAt = SystemClock.elapsedRealtime(); ui.post(tick) }
        render() }

    private var failed = false
    private var failedText: String? = null

    private fun showFailed(text: String) {
        failed = true
        failedText = text
        minimized = false
        nativeVideo = false
        hidden = false
        ui.removeCallbacks(tick)
        set(NativeCallFlow.Screen.FAILED)
        if (view == null) return
        view?.visibility = View.VISIBLE
        render()
    }

    private val endLinger = Runnable { dismiss() }
    private var endedReason: OlCallLifecycle.Reason? = null
    private fun showEndCard(reason: OlCallLifecycle.Reason?) {
        if (view == null) return
        endedReason = reason
        endedSecs = if (establishedAt > 0L) (SystemClock.elapsedRealtime() - establishedAt) / 1000 else -1L
        endedCard = true; minimized = false; nativeVideo = false; hidden = false
        ui.removeCallbacks(tick)
        state = NativeCallFlow.Screen.ENDED
        view?.visibility = View.VISIBLE
        render()
        ui.postDelayed(endLinger, 1800) // web engine.ts:93 ENDED_LINGER_MS
    }

    private fun dismiss() { ui.removeCallbacks(endLinger); endedCard = false; ui.removeCallbacks(tick); ui.removeCallbacks(hideCtl); ui.removeCallbacks(presenceTick); ui.removeCallbacks(quickPeek); view?.let { root.removeView(it) }; view = null; state = null; lifecycle = null; endedReason = null; callId = null; hidden = false; establishedAt = 0L; nativeVideo = false; minimized = false; failed = false; failedText = null; link = false; remoteOn = false }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    // Open Lounge call screen look (web CallOverlay.tsx / calls.module.css / Avatar.tsx): #0b1220 stage + shade,
    // xl initials avatar, 28sp semibold name, 15sp white/80 status, RoundBtn 56dp white/15 (active = white, zinc-900
    // icon), End call 56dp red-500, 12sp white/85 label under each button. lucide vectors ol_call_* (same set as iOS).
    private class Btn(val box: LinearLayout, val disc: FrameLayout, val icon: android.widget.ImageView, val label: TextView)
    private fun btn(danger: Boolean, click: () -> Unit): Btn {
        val size = dp(56)
        val disc = FrameLayout(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (danger) 0xFFEF4444.toInt() else 0x26FFFFFF) } }
        val icon = android.widget.ImageView(act)
        disc.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        val label = TextView(act).apply { setTextColor(0xD9FFFFFF.toInt()); textSize = 12f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { click() } }
        box.addView(disc, LinearLayout.LayoutParams(size, size))
        box.addView(label, LinearLayout.LayoutParams(dp(76), -2).apply { topMargin = dp(6) })
        box.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        return Btn(box, disc, icon, label)
    }
    private fun Btn.set(text: String, iconRes: Int, active: Boolean = false, enabled: Boolean = true) {
        label.text = text; icon.setImageResource(iconRes)
        icon.setColorFilter(if (active) 0xFF18181B.toInt() else Color.WHITE)
        (disc.background as GradientDrawable).setColor(if (active) Color.WHITE else 0x26FFFFFF)
        box.alpha = if (enabled) 1f else 0.4f; box.isEnabled = enabled
    }

    private fun initials(n: String): String {
        val parts = n.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && Character.isLetterOrDigit(it.codePointAt(0)) }
        if (parts.isEmpty()) return n.trim().take(1).ifEmpty { "?" }
        if (parts.size == 1) return parts[0].take(2).uppercase()
        return (parts.first().take(1) + parts.last().take(1)).uppercase()
    }

    private lateinit var avatar: TextView
    private lateinit var avatarBox: FrameLayout
    private lateinit var photoView: android.widget.ImageView
    /** URL being fetched. Initials are the no-photo fallback only: they are not painted while the photo is on its way. */
    private var photoLoading: String? = null
    private lateinit var presenceDot: View

    /** web AvatarPhoto.tsx: initials stay underneath, the photo fades in once decoded, a broken URL keeps the initials.
     *  http(s) profile URLs, or a file:// under this app's cache/files (Robolectric fixtures). */
    private fun loadPhoto(id: String, url: String) {
        if (!CallPresence.allowedPhotoUrl(url, act.cacheDir, act.filesDir)) return
        // Local cache files are small and already on disk — decode on this thread so tests
        // (and a just-written cache file) show the photo without waiting on a background post.
        if (url.startsWith("file:")) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(java.net.URI(url).path) ?: return
                applyDecodedPhoto(id, bmp)
            } catch (_: Throwable) {}
            return
        }
        if (photoLoading == url) return
        photoLoading = url
        if (view != null) avatar.text = ""
        Thread {
            val bmp = try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }.also { c.disconnect() }
            } catch (_: Throwable) { null }
            ui.post {
                if (photoLoading != url) return@post
                photoLoading = null
                if (bmp != null) applyDecodedPhoto(id, bmp)
                else if (callId == id && view != null && photoView.alpha < 1f) avatar.text = initials(name.text?.toString().orEmpty())
            }
        }.start()
    }

    private fun applyDecodedPhoto(id: String, bmp: android.graphics.Bitmap) {
        if (callId != id || view == null) return
        CallAvatar.setPhoto(photoView, bmp)
        avatar.text = ""
        // Same pixels on every Android version and in Robolectric (software pre-blur, no RenderEffect).
        CallBackdrop.attach(bgPhoto, bmp)
        bgPhoto.alpha = 1f
        // Photo present: first-design backdrop is the dark blurred portrait, not the indigo blobs.
        blobs.forEach { it.visibility = android.view.View.GONE }
        bgPhoto.animate().alpha(1f).setDuration(200).start()
    }
    private lateinit var bFirst: Btn
    private lateinit var bFlip: Btn
    private val rings = ArrayList<View>()
    private lateinit var top: LinearLayout
    private lateinit var bg: FrameLayout
    private lateinit var bgPhoto: android.widget.ImageView
    private val blobs = ArrayList<View>()
    private lateinit var bVid: Btn
    private lateinit var bMic: Btn

    private fun build() {
        val v = StageBackdrop(act).apply { isClickable = true } // the page under it never gets touches while shown; StageBackdrop: self-view hole drawn last
        bg = FrameLayout(act)
        val dm = act.resources.displayMetrics; val vmax = maxOf(dm.widthPixels, dm.heightPixels)
        fun blob(color: Int, gravity: Int, dx: Float, dy: Float): View = View(act).apply {
            background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = vmax * 0.35f; colors = intArrayOf((0x8C shl 24) or (color and 0xFFFFFF), color and 0xFFFFFF) }
            layoutParams = FrameLayout.LayoutParams((vmax * 0.7).toInt(), (vmax * 0.7).toInt(), gravity).apply { if (gravity and Gravity.START == Gravity.START) { leftMargin = (-vmax * 0.25).toInt(); topMargin = (-vmax * 0.2).toInt() } else { rightMargin = (-vmax * 0.3).toInt(); bottomMargin = (-vmax * 0.25).toInt() } }
            animate().translationX(dx * vmax).translationY(dy * vmax).setDuration(14000).start()
        }
        blobs.clear()
        blobs.add(blob(0xFF64748B.toInt(), Gravity.TOP or Gravity.START, 0.18f, 0.14f))
        blobs.add(blob(0xFF6366F1.toInt(), Gravity.BOTTOM or Gravity.END, -0.16f, -0.12f))
        blobs.forEach { bg.addView(it) }
        // iOS CallBackdrop parameters (1.15× zoom, σ 36, saturation 1.3, brightness −0.15) via CallBackdrop.attach.
        bgPhoto = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY; alpha = 0f }
        bg.addView(bgPhoto, FrameLayout.LayoutParams(-1, -1))
        v.addView(bg, FrameLayout.LayoutParams(-1, -1))
        // BaoTime state colour layer (web calls.module.css tint_*), under the shade, recoloured on every server state.
        tint = View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0, 0)); contentDescription = null }
        bg.addView(tint, FrameLayout.LayoutParams(-1, -1))
        v.addView(View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0x0D000000, 0x8C000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        // Video stage: controls sit over the full-bleed remote video on Telegram's two 160dp shadows
        // (DrKLO 3de37c8 VoIPFragment.java:917-923: top black 40% -> clear, bottom clear -> black 50%).
        topShadow = View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, Color.TRANSPARENT)); visibility = View.GONE }
        bottomShadow = View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0x80000000.toInt())); visibility = View.GONE }
        v.addView(topShadow, FrameLayout.LayoutParams(-1, dp(160), Gravity.TOP))
        v.addView(bottomShadow, FrameLayout.LayoutParams(-1, dp(160), Gravity.BOTTOM))
        // Self-view PiP touch target (the renderer is a SurfaceView under this window, NativeCallVideo.placeLocal).
        pipTouch = android.widget.ImageView(act).apply { contentDescription = "Self view"; visibility = View.GONE; setOnTouchListener(PipDrag()); scaleType = android.widget.ImageView.ScaleType.CENTER }
        v.addView(pipTouch, FrameLayout.LayoutParams(dp(CallPip.WIDTH), dp(CallPip.HEIGHT), Gravity.TOP or Gravity.START))
        top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), 0) }
        avatar = TextView(act).apply { setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        // 112dp circle avatar, clipped so the photo takes the same shape
        avatarBox = FrameLayout(act).apply {
            background = GradientDrawable().apply { cornerRadius = dp(47).toFloat(); setColor(0xFF64748B.toInt()) }
            CallAvatar.applyOutline(this, dp(47).toFloat())
        }
        photoView = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        avatarBox.addView(avatar, FrameLayout.LayoutParams(-1, -1)); avatarBox.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        name = TextView(act).apply { setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = CallChrome.semibold(); letterSpacing = -0.01f; includeFontPadding = false }
        status = TextView(act).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER; fontFeatureSettings = "tnum"; includeFontPadding = false }
        val ringBox = FrameLayout(act).apply { clipChildren = false }
        rings.clear()
        repeat(3) { i -> val rv = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), 0x59FFFFFF) }; alpha = 0f }
            rings.add(rv); ringBox.addView(rv, FrameLayout.LayoutParams(dp(112), dp(112))) }
        ringBox.addView(avatarBox, FrameLayout.LayoutParams(dp(112), dp(112)))
        // 101e00a Avatar presence / later call-screen PresenceAvatar: 16dp + 3dp stage ring, bottom-right 13% inset
        presenceDot = View(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(CallPresence.COLOR_ONLINE); setStroke(dp(3), CallPresence.RING) }
            visibility = View.GONE
        }
        ringBox.addView(presenceDot, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(4); bottomMargin = dp(4) })
        top.clipChildren = false; v.clipChildren = false
        top.addView(ringBox, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(12) })
        top.addView(name, LinearLayout.LayoutParams(-1, -2)); top.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        // web mute badge (their mic off): mic-off glyph in a white/15 circle under their name / status
        muteBadge = android.widget.ImageView(act).apply { contentDescription = "Muted"; setImageResource(R.drawable.ol_call_mic_off); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6)); background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) }; visibility = View.GONE }
        top.addView(muteBadge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL })
        // Centered between the 56dp top bar and the bottom row (SwiftUI CallScreen / IncomingCallScreen).
        // FrameLayout CENTER offsets by (topMargin - bottomMargin), so these put the block in the middle of the space between them.
        v.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = 0; bottomMargin = dp(CallChrome.CENTER_LIFT) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER or Gravity.BOTTOM; setPadding(dp(16), 0, dp(16), 0) }
        bFirst = btn(false) { speakerOn = !speakerOn; actions.speaker(speakerOn); render() }
        bFlip = btn(false) { facing = if (facing == "user") "environment" else "user"; actions.camera(true, facing) { } }
        bVid = btn(false) {
            val want = !camOn
            if (!want) { camOn = false; actions.camera(false, facing) { }; render(); return@btn }
            // camera permission is requested here (toggle on), not up front; a denial leaves the button off
            actions.camera(true, facing) { ok -> if (ok) { camOn = true; render() } }
        }
        bMic = btn(false) { actions.pageCall(JSONObject().put("type", "call:mute").put("muted", !muted).toString()) }
        val end = btn(true) { actions.pageCall(JSONObject().put("type", "call:hangup").toString()) }
        end.label.text = "End call"; end.icon.setImageResource(R.drawable.ol_call_end); endBtn = end
        row.addView(bFirst.box); row.addView(bFlip.box); row.addView(bVid.box); row.addView(bMic.box); row.addView(end.box)
        v.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(CallChrome.ROW_BOTTOM) })
        this.row = row
        // minimize (chevron, first UI) — UI-only: collapses to the green top bar; does not hang up
        minBtn = FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1AFFFFFF) }
            setOnClickListener { setMinimized(true) }
            contentDescription = "Minimize call"
        }
        minBtn.addView(android.widget.ImageView(act).apply { setImageResource(R.drawable.ol_call_chevron_down); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        v.addView(minBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply { topMargin = dp(8); leftMargin = dp(12) })
        closeBtn = FrameLayout(act).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x1AFFFFFF) }
            setOnClickListener { dismiss() }
            contentDescription = "Close"
            visibility = View.GONE
        }
        closeBtn.addView(android.widget.ImageView(act).apply { setImageResource(R.drawable.ol_call_close); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        v.addView(closeBtn, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(12) })
        failBanner = TextView(act).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            includeFontPadding = false; setLineSpacing(0f, 1.15f)
            visibility = View.GONE
        }
        v.addView(failBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2, Gravity.TOP).apply { topMargin = dp(64); leftMargin = dp(16); rightMargin = dp(16) })
        // Failed screen (server terminal/failed): Cancel + Call again, same pair as web CallOverlay call-close / call-retry.
        retryRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE }
        val cancel = btn(false) { dismiss() }
        cancel.set("Cancel", R.drawable.ol_call_close)
        val again = btn(false) { val id = callId; dismiss(); actions.pageCall(JSONObject().put("type", "call:retry").put("callId", id ?: "").toString()) }
        again.set("Call again", R.drawable.ol_call_phone)
        retryRow.addView(cancel.box); retryRow.addView(again.box)
        v.addView(retryRow, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(CallChrome.ROW_BOTTOM) })
        miniBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF10B981.toInt())
            visibility = View.GONE
            setOnClickListener { setMinimized(false) }
        }
        miniDot = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        miniLabel = TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = CallChrome.semibold()
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniBar.addView(miniDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        miniBar.addView(miniLabel, LinearLayout.LayoutParams(-2, -2))
        v.addView(miniBar, FrameLayout.LayoutParams(-1, dp(28), Gravity.TOP))
        v.setOnClickListener { if (nativeVideo) pokeControls() }
        root.addView(v, FrameLayout.LayoutParams(-1, -1)); view = v
    }

    private lateinit var row: LinearLayout
    private lateinit var topShadow: View
    private lateinit var bottomShadow: View
    private lateinit var pipTouch: View
    private var pipCorner = CallPip.Corner.BOTTOM_RIGHT
    /** Normal hang-up after a live call: the end card (avatar, name, "Call ended · m:ss"), web ENDED_LINGER_MS. */
    private var endedCard = false
    private var endedSecs = 0L

    private fun pipInsets(): CallPip.Insets {
        val d = act.resources.displayMetrics.density
        return CallPip.insets(showCtl, 0f, CallChrome.ROW_RESERVE * d, d)
    }

    /** Put the PiP (touch target + renderer) in its corner slot. */
    private fun placePip(animate: Boolean) {
        val v = view ?: return
        if (v.width == 0) { v.post { placePip(false) }; return }
        val pw = dp(CallPip.WIDTH).toFloat(); val ph = dp(CallPip.HEIGHT).toFloat()
        val (x, y) = CallPip.origin(pipCorner, v.width.toFloat(), v.height.toFloat(), pw, ph, pipInsets())
        if (animate) pipTouch.animate().translationX(x).translationY(y).setDuration(150).setUpdateListener { syncRenderer() }.start()
        else { pipTouch.translationX = x; pipTouch.translationY = y; syncRenderer() }
    }
    private fun syncRenderer() {
        selfView(stageOn)
        NativeCallVideo.placeLocal(pipTouch.translationX.toInt(), pipTouch.translationY.toInt(), dp(CallPip.WIDTH), dp(CallPip.HEIGHT), dp(CallPip.RADIUS).toFloat())
    }

    /**
     * Self view (web selfPip): camera on = a hole in the stage backdrop onto the local renderer (only needed on the
     * avatar tile; over their video the backdrop is hidden). Camera off = the camera-off tile, never an empty surface.
     */
    private fun selfView(stage: Boolean) {
        if (view == null) return
        val p = pipTouch as android.widget.ImageView
        val off = stage && !camOn
        p.background = if (off) GradientDrawable().apply { cornerRadius = dp(CallPip.RADIUS).toFloat(); setColor(0xFF27272A.toInt()) } else null
        p.setImageResource(if (off) R.drawable.ol_call_video_off else 0)
        p.contentDescription = if (off) "Self view, camera off" else "Self view"
        val root = view as? StageBackdrop ?: return
        root.holeRadius = dp(CallPip.RADIUS).toFloat()
        root.hole = if (stage && camOn && avatarTile) android.graphics.RectF(p.translationX, p.translationY, p.translationX + dp(CallPip.WIDTH), p.translationY + dp(CallPip.HEIGHT)) else null
    }

    /** Drag the self-view anywhere; on release snap to the corner Telegram's fling projection picks. */
    private inner class PipDrag : View.OnTouchListener {
        private var vt: android.view.VelocityTracker? = null
        private var sx = 0f; private var sy = 0f; private var tx = 0f; private var ty = 0f
        override fun onTouch(t: View, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    t.animate().cancel(); vt?.recycle(); vt = android.view.VelocityTracker.obtain()
                    sx = e.rawX; sy = e.rawY; tx = t.translationX; ty = t.translationY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    t.translationX = tx + e.rawX - sx; t.translationY = ty + e.rawY - sy; syncRenderer()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val v = view
                    if (v != null) pipCorner = CallPip.snap(t.translationX + t.width / 2f, t.translationY + t.height / 2f,
                        vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f, v.width.toFloat(), v.height.toFloat(), pipInsets())
                    vt?.recycle(); vt = null
                    placePip(true)
                }
            }
            vt?.let { val m = android.view.MotionEvent.obtain(e); m.setLocation(e.rawX, e.rawY); it.addMovement(m); m.recycle() }
            return true
        }
    }
    /** Test hook: release the PiP at centre (cx, cy) with velocity (vx, vy) px/s. */
    internal fun releasePipForTest(cx: Float, cy: Float, vx: Float, vy: Float) {
        val v = view ?: return
        pipCorner = CallPip.snap(cx, cy, vx, vy, v.width.toFloat(), v.height.toFloat(), pipInsets()); placePip(false)
    }
    internal val pipCornerForTest get() = pipCorner
    private lateinit var minBtn: FrameLayout
    private lateinit var closeBtn: FrameLayout
    private lateinit var failBanner: TextView
    private lateinit var retryRow: LinearLayout
    @get:androidx.annotation.VisibleForTesting internal val tintForTest: String? get() = if (view == null) null else tint.tag as? String
    @get:androidx.annotation.VisibleForTesting internal val pulseForTest: Long get() = if (failed) 0L else NativeCallFlow.pulseMs(state)
    private lateinit var tint: View
    private lateinit var miniBar: LinearLayout
    private lateinit var miniDot: View
    private lateinit var miniLabel: TextView
    private var minimized = false
    private var showCtl = true
    private val hideCtl = Runnable { showCtl = false; view?.let { applyChrome(it) } }

    fun setMinimized(on: Boolean) {
        minimized = on
        if (on) {
            showCtl = true
            actions.revealAppWhenMinimized()
        }
        view?.let { applyChrome(it) }
    }

    private fun pokeControls() {
        showCtl = true
        ui.removeCallbacks(hideCtl)
        if (nativeVideo) ui.postDelayed(hideCtl, 4000)
        view?.let { applyChrome(it) }
    }

    private fun applyChrome(v: ViewGroup) {
        val incomingOrEnded = state == NativeCallFlow.Screen.ENDED || failed
        val lp = v.layoutParams as? FrameLayout.LayoutParams
        if (minimized && !incomingOrEnded) {
            // First UI MiniBar: only the green bar, over the real app — no blank full-screen stage.
            miniBar.visibility = View.VISIBLE
            miniLabel.text = "${name.text}  ${status.text}  · Tap to return"
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                c.visibility = if (c === miniBar) View.VISIBLE else View.GONE
            }
            v.setBackgroundColor(Color.TRANSPARENT)
            v.isClickable = false
            v.isFocusable = false
            if (lp != null) {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = dp(28)
                lp.gravity = Gravity.TOP
                v.layoutParams = lp
            }
            // Hide tiles only (engine keeps running).
            NativeCallVideo.show(false)
            pulseMiniDot()
            return
        }
        if (lp != null) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            v.layoutParams = lp
        }
        v.isClickable = true
        v.setBackgroundColor(if (stageOn) Color.TRANSPARENT else 0xFF0B1220.toInt())
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            c.visibility = if (c === miniBar) View.GONE else View.VISIBLE
        }
        val stage = stageOn
        bg.visibility = if (stage && !avatarTile) View.GONE else View.VISIBLE
        val hide = stage && !showCtl
        topShadow.visibility = if (stage && !hide) View.VISIBLE else View.GONE
        bottomShadow.visibility = topShadow.visibility
        pipTouch.visibility = if (stage) View.VISIBLE else View.GONE
        if (stage) placePip(false)
        selfView(stage)
        row.visibility = if (hide || failed || endedCard) View.GONE else View.VISIBLE
        minBtn.visibility = if (hide || incomingOrEnded || !live) View.GONE else View.VISIBLE
        closeBtn.visibility = if (failed || endedCard) View.VISIBLE else View.GONE
        retryRow.visibility = if (failed && !hide) View.VISIBLE else View.GONE
        failBanner.visibility = if (failed) View.VISIBLE else View.GONE
        top.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        // Pre-answer card is opaque: tiles stay hidden (engine keeps running) until the answered event.
        if (nativeVideo) { NativeCallVideo.show(stage); if (stage && !remoteOn) NativeCallVideo.showRemote(false); if (stage && !camOn) NativeCallVideo.showLocal(false) }
        if (stage && showCtl) {
            ui.removeCallbacks(hideCtl)
            ui.postDelayed(hideCtl, 4000)
        }
    }

    private fun pulseMiniDot() {
        if (miniBar.visibility != View.VISIBLE) return
        miniDot.animate().alpha(0.35f).setDuration(600).withEndAction {
            miniDot.animate().alpha(1f).setDuration(600).withEndAction { pulseMiniDot() }.start()
        }.start()
    }

    private fun render() {
        val v = view ?: return
        v.setBackgroundColor(if (stageOn) Color.TRANSPARENT else 0xFF0B1220.toInt())
        val videoStage = stageOn
        // Meet-style (t6093u): their camera off -> their avatar in the middle of the stage, your camera stays the PiP
        val avatarTile = this.avatarTile
        avatarBox.visibility = if (videoStage && !avatarTile) View.GONE else View.VISIBLE
        bg.visibility = if (videoStage && !avatarTile) View.GONE else View.VISIBLE
        (avatarBox.parent as View).visibility = avatarBox.visibility
        val period = if (failed) 0L else NativeCallFlow.pulseMs(state)
        rings.forEachIndexed { i, rv -> if (period > 0 && rv.tag != period) { rv.tag = period; rv.animate().cancel(); pulse(rv, i * period / 3) } else if (period == 0L) { rv.tag = null; rv.animate().cancel(); rv.alpha = 0f } }
        NativeCallFlow.tone(if (failed) NativeCallFlow.Screen.FAILED else state).let { t -> tint.tag = t.name; (tint.background as GradientDrawable).colors = intArrayOf((0x8C shl 24) or (t.from and 0xFFFFFF), (0x8C shl 24) or (t.to and 0xFFFFFF)) }
        name.textSize = if (videoStage) 20f else 28f
        (top.layoutParams as FrameLayout.LayoutParams).apply { gravity = if (videoStage) Gravity.TOP else Gravity.CENTER; topMargin = if (videoStage) dp(64) else 0; bottomMargin = if (videoStage) 0 else dp(CallChrome.CENTER_LIFT) }
        top.requestLayout()
        if (photoView.alpha < 1f && photoLoading == null) avatar.text = initials(name.text?.toString().orEmpty())
        if (failed) {
            status.text = failedText ?: CallPresence.FAIL_MSG
            failBanner.text = failedText ?: CallPresence.FAIL_MSG
        } else if (endedCard) {
            // end card: same structure as the calling screen (avatar, name, status), status = duration
            val t = NativeCallFlow.endedText(endedReason)
            status.text = if (endedSecs >= 0) "$t · " + NativeCallFlow.timer(endedSecs) else t
        } else if (state != NativeCallFlow.Screen.ACTIVE) {
            status.text = NativeCallFlow.statusText(state, lifecycle?.reason, link && media, name.text?.toString().orEmpty())
        } else if (establishedAt > 0L) {
            // answered: the timer shows at once (0:00), not only from the first tick
            status.text = NativeCallFlow.timer((SystemClock.elapsedRealtime() - establishedAt) / 1000)
        }
        // Pre-answer (calling / ringing / connecting): the in-call row's look, same as iOS 133 CallScreen.controlRow:
        // Speaker / Mute / End call. Speaker and Mute run the very same in-call actions, enabled only once this
        // phone's olcall leg is up (media = olcall onConnected, iOS engineUp), so they never act on a leg that does
        // not exist yet; End call is the same end action Cancel ran.
        // Audio in-call (Donny's design): Speaker (white when on), Mute, End call. Video row only on the video stage.
        val pre = !live && !failed && !endedCard
        // One path (t6093u): every live call is the same audio + video room, so the camera toggle is always in the row.
        // Once this phone's olcall leg is up (media) the camera works too: alone in a link ("Waiting for <name>…") and the
        // video-ring caller ("Ringing…", already in the live room) get the toggle before the other side is in.
        val videoRow = (!pre || media) && !failed && !endedCard
        endBtn.label.text = "End call"
        bFirst.box.visibility = View.VISIBLE
        bMic.box.visibility = View.VISIBLE
        bFlip.box.visibility = if (camOn && videoRow) View.VISIBLE else View.GONE
        // Same lucide glyphs as iOS (Assets.xcassets ol_call_*): speaker without/with second wave, plain hung-up handset.
        bFlip.set("Flip", R.drawable.ol_call_flip)
        bFirst.set("Speaker", if (speakerOn) R.drawable.ol_call_speaker else R.drawable.ol_call_speaker_off, active = speakerOn, enabled = !pre || media)
        bVid.box.visibility = if (videoRow) View.VISIBLE else View.GONE
        bVid.set(if (camOn) "Camera" else "Video", if (camOn) R.drawable.ol_call_video else R.drawable.ol_call_video_off, active = camOn)
        bMic.set(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ol_call_mic_off else R.drawable.ol_call_mic, active = muted, enabled = !pre || media)
        muteBadge.visibility = if (remoteMuted && live && !failed && !endedCard) View.VISIBLE else View.GONE
        applyChrome(v)
    }

    /** One read-only look at the page call screen: presence, the current profile name, and the photo if none yet. */
    private fun peekOnce() {
        if (view == null) return
        actions.peekCallPresence { raw ->
            val t = CallPresence.rawFromJs(raw).ifBlank { raw?.trim().orEmpty() }
            if (t.isNotEmpty()) applyPresence(CallPresence.of(t))
        }
        actions.peekCallName { raw ->
            if (view == null || endedCard) return@peekCallName
            val n = CallPresence.nameFromPeek(CallPresence.rawFromJs(raw), name.text?.toString().orEmpty()) ?: return@peekCallName
            name.text = n
            if (photoView.alpha < 1f && photoLoading == null) avatar.text = initials(n)
        }
        if (view != null && photoView.alpha < 1f) {
            actions.peekCallPhoto { raw ->
                val t = CallPresence.rawFromJs(raw).ifBlank { raw?.trim().orEmpty() }
                if (t.startsWith("http://") || t.startsWith("https://")) callId?.let { loadPhoto(it, t) }
            }
        }
    }

    private val presenceTick = object : Runnable {
        override fun run() {
            if (view == null) return
            peekOnce()
            if (view != null) ui.postDelayed(this, 2000)
        }
    }
    /** The page paints its call screen a moment after call:outgoing: look again soon instead of waiting 2 s. */
    private val quickPeek = Runnable { peekOnce() }

    private fun startPresencePeek() {
        ui.removeCallbacks(presenceTick)
        ui.removeCallbacks(quickPeek)
        ui.post(presenceTick)
        ui.postDelayed(quickPeek, 300)
        ui.postDelayed(quickPeek, 1000)
    }

    private fun applyPresence(status: String?) {
        if (view == null || !::presenceDot.isInitialized) return
        val s = CallPresence.of(status)
        if (s == null) {
            presenceDot.visibility = View.GONE
            presenceDot.contentDescription = null
        } else {
            (presenceDot.background as GradientDrawable).apply { setColor(CallPresence.color(s)); setStroke(dp(3), CallPresence.RING) }
            presenceDot.visibility = View.VISIBLE
            presenceDot.contentDescription = s
        }
    }

    private fun pulse(rv: View, delay: Long) {
        if (rv.tag == null || view == null) return
        rv.scaleX = 1f; rv.scaleY = 1f; rv.alpha = 0.8f
        rv.animate().setStartDelay(delay).scaleX(1.9f).scaleY(1.9f).alpha(0f).setDuration((rv.tag as? Long) ?: 2400L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.6f, 0.35f, 1f)).withEndAction { pulse(rv, 0) }.start()
    }

    val showing: Boolean get() = view != null && !hidden
}

/** Call stage backdrop; [hole] is cleared to transparent so the self-view SurfaceView under the window shows there. */
internal class StageBackdrop(ctx: android.content.Context) : FrameLayout(ctx) {
    var hole: android.graphics.RectF? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var holeRadius = 0f
    private val clear = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
    init { setWillNotDraw(false) }
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        hole?.let { canvas.drawRoundRect(it, holeRadius, holeRadius, clear) }
    }
}
