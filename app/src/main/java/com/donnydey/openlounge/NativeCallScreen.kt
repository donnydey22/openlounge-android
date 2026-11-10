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
 * Placement (first UI 101e00a): [Speaker] [Video] [Mute] [End call], plus Flip when the local camera is on
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
        /** Engine-branch "ringing acknowledged" page types (t5456). */
        val RINGING_ACK = setOf("call:ringing")
        /** olcall onConnected, relayed by MainActivity.emit: media up, but not proof of an answer. */
        const val MEDIA_NATIVE = "call:media-native"
        /** Engine-branch "media flowing" page types (posted after the answer), plus the native relay. */
        val MEDIA_FLOWING = setOf("call:mediaFlowing", MEDIA_NATIVE)
        private val mediaUp = mutableSetOf<String>()
        private val screens = java.util.Collections.newSetFromMap(java.util.WeakHashMap<NativeCallScreen, Boolean>())

        /** Main thread. Native media for [id] is flowing: tell every call screen (MainActivity and CallActivity). */
        @JvmStatic fun onNativeMediaFlowing(id: String) {
            if (id.isEmpty()) return
            mediaUp.add(id)
            screens.toList().forEach { it.onPageCall(MEDIA_NATIVE, JSONObject().put("callId", id)) }
        }
        @JvmStatic fun onNativeMediaEnded(id: String) { mediaUp.remove(id) }
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
        /** When minimized from a host with no WebView, bring the real app forward. No hang-up. */
        fun revealAppWhenMinimized() {}
    }


    var state: NativeCallFlow.State? = null; private set
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
            if (state != NativeCallFlow.State.ESTABLISHED) return
            val s = (SystemClock.elapsedRealtime() - establishedAt) / 1000
            status.text = NativeCallFlow.timer(s)
            ui.postDelayed(this, 1000)
        }
    }

    /** Page -> app call message (MainActivity.onPageCall). */
    fun onPageCall(type: String, m: JSONObject) {
        val id = m.optString("callId", "")
        when (type) {
            "call:incoming" -> if (id.isNotEmpty()) { incoming = id to m.optString("name", ""); incomingPhoto = id to m.optString("photo", ""); if (CallPresence.hasField(m)) incomingPresence = id to CallPresence.fromMessage(m) }
            "call:outgoing" -> if (id.isNotEmpty()) { begin(id, m.optString("name", ""), m.optBoolean("video"), m.optString("photo", ""), if (CallPresence.hasField(m)) CallPresence.fromMessage(m) else null); set(NativeCallFlow.State.WAITING) }
            "call:answering" -> if (id.isNotEmpty()) { if (callId != id) begin(id, m.optString("name", "").ifEmpty { incoming?.takeIf { it.first == id }?.second.orEmpty() }, m.optBoolean("video"), m.optString("photo", "").ifEmpty { incomingPhoto?.takeIf { it.first == id }?.second.orEmpty() }, if (CallPresence.hasField(m)) CallPresence.fromMessage(m) else incomingPresence?.takeIf { it.first == id }?.second); set(NativeCallFlow.State.WAIT_INIT) }
            // t5456 engine branch (ol-call-fixes): the callee's phone acknowledged the ring -> "Ringing…",
            // which stays until media flows.
            in RINGING_ACK -> if (view != null && (id.isEmpty() || id == callId) && !live) { ringingAck = true; render() }
            // Server-confirmed answer (call:answered; call:connected on engines that do not post it yet). Not live
            // on its own: the pre-answer card (avatar, name, Calling…/Ringing…, one Cancel) stays until media flows.
            "call:answered", "call:connected" -> if (view != null && (id.isEmpty() || id == callId)) {
                answered = true
                video = video || m.optBoolean("video")
                goLiveIfReady()
            }
            // Media flowing (engine mediaFlowing state, or olcall onConnected via MainActivity.emit). After answer it
            // switches to the full row (Video/Speaker/Mute/End) and starts the timer.
            in MEDIA_FLOWING -> if (view != null && (id.isEmpty() || id == callId)) {
                media = true
                if (type != MEDIA_NATIVE) answered = true // the engine posts mediaFlowing only after the answer
                goLiveIfReady()
            }
            "call:ended" -> if (id.isEmpty() || id == callId) {
                if (id.isNotEmpty()) mediaUp.remove(id)
                val text = CallPresence.messageFromEnd(m)
                if (CallPresence.isFailure(m.optString("reason", ""), text)) showFailed(CallPresence.failureText(text))
                else if (live && establishedAt > 0L && view != null) showEndCard()
                else { set(NativeCallFlow.State.ENDED); dismiss() }
            }
            "call:muted" -> { muted = m.optBoolean("muted"); render() }
            "call:audioRoute" -> if (m.has("speaker")) { speakerOn = m.optBoolean("speaker"); render() }
        }
        if (view != null && CallPresence.hasField(m) && (id.isEmpty() || id == callId)) applyPresence(CallPresence.fromMessage(m))
    }

    /** MainActivity.nativeCallVideo: native tiles are up (remote full screen, self-view PiP) -> transparent screen. */
    fun onNativeVideo(on: Boolean) { nativeVideo = on; if (on) { video = true; camOn = true; if (hidden) { hidden = false; view?.visibility = View.VISIBLE } }; render() }
    private var hidden = false
    /** Server answered (call:answered, or call:connected on engines that do not post it yet). */
    private var answered = false
    /** Media flowing (engine mediaFlowing / olcall onConnected). */
    private var media = false
    /** t5456: full call UI (row, timer, video, minimize) only once media flows after the answer. */
    private val live get() = answered && media

    private fun goLiveIfReady() {
        if (!live) { render(); return }
        set(NativeCallFlow.State.ESTABLISHED)
        // Native-UI first cut: never hide for WebView. Stay visible; avatar/status cover until native tiles up.
        if (hidden) { hidden = false; view?.visibility = View.VISIBLE }
    }
    private var ringingAck = false
    private lateinit var endBtn: Btn
    private var incoming: Pair<String, String>? = null
    private var incomingPhoto: Pair<String, String>? = null
    private var incomingPresence: Pair<String, String?>? = null

    private fun begin(id: String, peer: String, v: Boolean, photo: String = "", presence: String? = null) {
        dismiss(); callId = id; video = v; camOn = v; muted = false; speakerOn = v; facing = "user"; failed = false; failedText = null; answered = false; media = id in mediaUp; ringingAck = false
        if (!Prefs.nativeCallUi(act)) { callId = null; return }
        build(); name.text = peer.ifEmpty { act.getString(R.string.app_name) }; loadPhoto(id, photo)
        applyPresence(presence ?: incomingPresence?.takeIf { it.first == id }?.second)
        startPresencePeek()
    }

    private fun set(s: NativeCallFlow.State) { if (view == null) return; state = s
        if (s == NativeCallFlow.State.ESTABLISHED && establishedAt == 0L) { establishedAt = SystemClock.elapsedRealtime(); ui.post(tick) }
        render() }

    private var failed = false
    private var failedText: String? = null

    private fun showFailed(text: String) {
        failed = true
        failedText = text
        minimized = false
        nativeVideo = false
        hidden = false
        set(NativeCallFlow.State.ENDED)
        if (view == null) return
        view?.visibility = View.VISIBLE
        render()
    }

    private val endLinger = Runnable { dismiss() }
    private fun showEndCard() {
        endedSecs = (SystemClock.elapsedRealtime() - establishedAt) / 1000
        endedCard = true; minimized = false; nativeVideo = false; hidden = false
        ui.removeCallbacks(tick)
        state = NativeCallFlow.State.ENDED
        view?.visibility = View.VISIBLE
        render()
        ui.postDelayed(endLinger, 1800) // web engine.ts:93 ENDED_LINGER_MS
    }

    private fun dismiss() { ui.removeCallbacks(endLinger); endedCard = false; ui.removeCallbacks(tick); ui.removeCallbacks(hideCtl); ui.removeCallbacks(presenceTick); view?.let { root.removeView(it) }; view = null; state = null; callId = null; hidden = false; establishedAt = 0L; nativeVideo = false; minimized = false; failed = false; failedText = null }

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
        Thread {
            val bmp = try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }.also { c.disconnect() }
            } catch (_: Throwable) { null } ?: return@Thread
            ui.post { applyDecodedPhoto(id, bmp) }
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
        val v = FrameLayout(act).apply { isClickable = true } // the page under it never gets touches while shown
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
        v.addView(View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40000000, 0x0D000000, 0x8C000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        // Video stage: controls sit over the full-bleed remote video on Telegram's two 160dp shadows
        // (DrKLO 3de37c8 VoIPFragment.java:917-923: top black 40% -> clear, bottom clear -> black 50%).
        topShadow = View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, Color.TRANSPARENT)); visibility = View.GONE }
        bottomShadow = View(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0x80000000.toInt())); visibility = View.GONE }
        v.addView(topShadow, FrameLayout.LayoutParams(-1, dp(160), Gravity.TOP))
        v.addView(bottomShadow, FrameLayout.LayoutParams(-1, dp(160), Gravity.BOTTOM))
        // Self-view PiP touch target (the renderer is a SurfaceView under this window, NativeCallVideo.placeLocal).
        pipTouch = View(act).apply { contentDescription = "Self view"; visibility = View.GONE; setOnTouchListener(PipDrag()) }
        v.addView(pipTouch, FrameLayout.LayoutParams(dp(CallPip.WIDTH), dp(CallPip.HEIGHT), Gravity.TOP or Gravity.START))
        top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), 0) }
        avatar = TextView(act).apply { setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
        // squircle (web avatar-shape.ts, ~42% corner equivalent), clipped so the photo takes the same shape
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
        top.addView(name, LinearLayout.LayoutParams(-1, -2)); top.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
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
            maxWidth = dp(260); includeFontPadding = false; setLineSpacing(0f, 1.15f)
            visibility = View.GONE
        }
        v.addView(failBanner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })
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
        NativeCallVideo.placeLocal(pipTouch.translationX.toInt(), pipTouch.translationY.toInt(), dp(CallPip.WIDTH), dp(CallPip.HEIGHT), dp(CallPip.RADIUS).toFloat())
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
        val incomingOrEnded = state == NativeCallFlow.State.ENDED || failed
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
        v.setBackgroundColor(if (nativeVideo && live && !failed) Color.TRANSPARENT else 0xFF0B1220.toInt())
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            c.visibility = if (c === miniBar) View.GONE else View.VISIBLE
        }
        val stage = nativeVideo && !failed && live
        bg.visibility = if (stage) View.GONE else View.VISIBLE
        val hide = stage && !showCtl
        topShadow.visibility = if (stage && !hide) View.VISIBLE else View.GONE
        bottomShadow.visibility = topShadow.visibility
        pipTouch.visibility = if (stage) View.VISIBLE else View.GONE
        if (stage) placePip(false)
        row.visibility = if (hide || failed || endedCard) View.GONE else View.VISIBLE
        minBtn.visibility = if (hide || incomingOrEnded || !live) View.GONE else View.VISIBLE
        closeBtn.visibility = if (failed || endedCard) View.VISIBLE else View.GONE
        failBanner.visibility = if (failed) View.VISIBLE else View.GONE
        top.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        // Pre-answer card is opaque: tiles stay hidden (engine keeps running) until the answered event.
        if (nativeVideo) NativeCallVideo.show(stage)
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
        v.setBackgroundColor(if (nativeVideo && live && !failed) Color.TRANSPARENT else 0xFF0B1220.toInt())
        val videoStage = nativeVideo && !failed && live
        avatarBox.visibility = if (videoStage) View.GONE else View.VISIBLE
        bg.visibility = if (videoStage) View.GONE else View.VISIBLE
        (avatarBox.parent as View).visibility = avatarBox.visibility
        val ringing = state == NativeCallFlow.State.WAITING && !failed
        rings.forEachIndexed { i, rv -> if (ringing && rv.tag == null) { rv.tag = 1; pulse(rv, i * 800L) } else if (!ringing) { rv.tag = null; rv.animate().cancel(); rv.alpha = 0f } }
        name.textSize = if (videoStage) 20f else 28f
        (top.layoutParams as FrameLayout.LayoutParams).apply { gravity = if (videoStage) Gravity.TOP else Gravity.CENTER; topMargin = if (videoStage) dp(64) else 0; bottomMargin = if (videoStage) 0 else dp(CallChrome.CENTER_LIFT) }
        top.requestLayout()
        if (photoView.alpha < 1f) avatar.text = initials(name.text?.toString().orEmpty())
        if (failed) {
            status.text = failedText ?: CallPresence.FAIL_MSG
            failBanner.text = failedText ?: CallPresence.FAIL_MSG
        } else if (endedCard) {
            // end card: same structure as the calling screen (avatar, name, status), status = duration
            status.text = "Call ended · " + NativeCallFlow.timer(endedSecs)
        } else if (state != NativeCallFlow.State.ESTABLISHED) {
            status.text = if (!live && ringingAck && state == NativeCallFlow.State.WAITING) "Ringing…" else NativeCallFlow.statusText(state)
        } else if (establishedAt > 0L) {
            // answered: the timer shows at once (0:00), not only from the first tick
            status.text = NativeCallFlow.timer((SystemClock.elapsedRealtime() - establishedAt) / 1000)
        }
        // First UI always offers Video (audio→video upgrade). Flip is shown once the local camera is on.
        // "Camera" (not "Stop video") keeps five even 56dp labeled circles on one row.
        // Pre-answer (calling / ringing / connecting): one red Cancel. Video, Speaker, Mute, End after answered.
        val pre = !live && !failed && !endedCard
        endBtn.label.text = if (pre) "Cancel" else "End call"
        bFirst.box.visibility = if (pre) View.GONE else View.VISIBLE
        bMic.box.visibility = if (pre) View.GONE else View.VISIBLE
        bFlip.box.visibility = if (camOn && !failed && !pre) View.VISIBLE else View.GONE
        // Same lucide glyphs as iOS (Assets.xcassets ol_call_*): speaker without/with second wave, plain hung-up handset.
        bFlip.set("Flip", R.drawable.ol_call_flip)
        bFirst.set(if (speakerOn) "Speaker" else "Phone", if (speakerOn) R.drawable.ol_call_speaker else R.drawable.ol_call_speaker_off, active = speakerOn)
        bVid.box.visibility = if (failed || pre) View.GONE else View.VISIBLE
        bVid.set(if (camOn) "Camera" else "Video", if (camOn) R.drawable.ol_call_video else R.drawable.ol_call_video_off, active = camOn)
        bMic.set(if (muted) "Unmute" else "Mute", if (muted) R.drawable.ol_call_mic_off else R.drawable.ol_call_mic, active = muted)
        applyChrome(v)
    }

    private val presenceTick = object : Runnable {
        override fun run() {
            if (view == null) return
            actions.peekCallPresence { raw ->
                val t = CallPresence.rawFromJs(raw).ifBlank { raw?.trim().orEmpty() }
                if (t.isNotEmpty()) applyPresence(CallPresence.of(t))
            }
            if (view != null && photoView.alpha < 1f) {
                actions.peekCallPhoto { raw ->
                    val t = CallPresence.rawFromJs(raw).ifBlank { raw?.trim().orEmpty() }
                    if (t.startsWith("http://") || t.startsWith("https://")) callId?.let { loadPhoto(it, t) }
                }
            }
            if (view != null) ui.postDelayed(this, 2000)
        }
    }

    private fun startPresencePeek() {
        ui.removeCallbacks(presenceTick)
        ui.post(presenceTick)
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
        rv.animate().setStartDelay(delay).scaleX(1.9f).scaleY(1.9f).alpha(0f).setDuration(2400)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.6f, 0.35f, 1f)).withEndAction { pulse(rv, 0) }.start()
    }

    val showing: Boolean get() = view != null && !hidden
}
