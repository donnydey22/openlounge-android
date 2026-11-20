package com.donnydey.openlounge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the native call screen (Open Lounge web look, Telegram flow/placement) per state and checks the controls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NativeCallScreenRenderTest {
    private val sent = mutableListOf<String>()
    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView && v.visibility == View.VISIBLE && isShown(v)) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }
    private fun isShown(v: View): Boolean { var p: View? = v; while (p != null) { if (p.visibility != View.VISIBLE) return false; p = p.parent as? View }; return true }
    private fun snap(root: View, name: String): Bitmap {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        val b = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); root.draw(Canvas(b))
        File("build/ncu-shots").apply { mkdirs() }.resolve("$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }

    /** Backdrop bands with no chrome (above the avatar, between the status and the row): after a σ 36dp blur no
     *  luminance jump over 4dp exceeds a few levels; the unblurred v3 photo jumped ~45-50 (face edges). */
    private fun assertBlurred(b: Bitmap, state: String) {
        fun l(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        var worst = 0.0
        for ((y0, y1) in listOf(360 to 720, 1450 to 1900)) for (y in y0 until y1 - 12 step 2) for (x in 60 until 1020 - 12 step 2) {
            val p = l(b.getPixel(x, y))
            worst = maxOf(worst, Math.abs(p - l(b.getPixel(x + 12, y))), Math.abs(p - l(b.getPixel(x, y + 12))))
        }
        assertTrue("$state backdrop is blurred (max 4dp luminance step $worst)", worst < 12.0)
    }

    private fun ringBoxOf(v: View): View? {
        if (v is ViewGroup && v.childCount >= 4 && v.width == v.height && v.width > 300 && v.width < 360) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) ringBoxOf(v.getChildAt(i))?.let { return it }
        return null
    }
    private fun avatarTop(root: View): Int {
        val rb = ringBoxOf(root)!!; val loc = IntArray(2); rb.getLocationInWindow(loc)
        val r0 = IntArray(2); root.getLocationInWindow(r0); return loc[1] - r0[1]
    }
    /** SwiftUI CallScreen: block (112 avatar, 12, name, 16, status) centered between the 56pt bar and the 24 + 76 row. */
    private fun expectedAvatarTop(act: Activity, root: View): Int {
        val d = act.resources.displayMetrics.density
        val rb = ringBoxOf(root)!!
        val block = (rb.parent as ViewGroup).height
        val bar = Math.round(56 * d); val reserve = Math.round(100 * d)
        return bar + (root.height - bar - reserve - block) / 2 + rb.top
    }

    /** Same hallway the iOS sheet panel draws: header + three DM rows. */
    private fun hallway(act: Activity): View {
        fun dpx(v: Int) = dp(act, v)
        val col = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0B0D.toInt()) }
        col.addView(TextView(act).apply { text = "Open Lounge"; setTextColor(Color.WHITE); textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false; setPadding(dpx(16), dpx(48), dpx(16), dpx(12)) })
        val photo = BitmapFactory.decodeFile(fixtureFile(act, "avatar-placeholder.jpg").absolutePath)
        for ((name, preview, tint) in listOf(Triple("Ada Lovelace", "Hey — are you free?", 0), Triple("The Lounge", "3 people speaking", 0xFF6366F1.toInt()), Triple("Jamie Cole", "See you at 4", 0xFF10B981.toInt()))) {
            val row = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dpx(16), dpx(10), dpx(16), dpx(10))
                background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(0x12FFFFFF), android.graphics.drawable.ColorDrawable(0xFF0B0B0D.toInt()))).apply { setLayerInset(1, 0, 1, 0, 0) } }
            val av: View = if (tint == 0) ImageView(act).apply { setImageBitmap(CallAvatar.squircle(photo, dpx(44), dpx(18).toFloat())); scaleType = ImageView.ScaleType.FIT_XY }
                else TextView(act).apply { text = if (name == "The Lounge") "JM" else "JC"; setTextColor(Color.WHITE); textSize = 14f; typeface = CallChrome.semibold(); gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dpx(18).toFloat(); setColor(tint) } }
            row.addView(av, android.widget.LinearLayout.LayoutParams(dpx(44), dpx(44)).apply { marginEnd = dpx(12) })
            val txt = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = name; setTextColor(Color.WHITE); textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false })
            txt.addView(TextView(act).apply { text = preview; setTextColor(0xFFAAAAAA.toInt()); textSize = 12f; includeFontPadding = false; setPadding(0, dpx(3), 0, 0) })
            row.addView(txt)
            col.addView(row)
        }
        return col
    }
    private fun findClickable(v: View, label: String): View? {
        if (v is ViewGroup) { for (i in 0 until v.childCount) { val c = v.getChildAt(i); if (c is TextView && c.text == label && v.hasOnClickListeners()) return v; findClickable(c, label)?.let { return it } } }
        return null
    }
    private fun findDesc(v: View, d: String): View? {
        if (v.contentDescription == d) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findDesc(v.getChildAt(i), d)?.let { return it }
        return null
    }
    private fun enabled(root: View, label: String): Boolean = findClickable(root, label)?.isEnabled == true
    private fun discWidth(root: View, label: String): Int {
        val box = findClickable(root, label) ?: return -1
        if (box is ViewGroup && box.childCount > 0) return box.getChildAt(0).width
        return box.width
    }


    private fun fixtureFile(act: Activity, name: String): File {
        val dest = File(act.cacheDir, name)
        dest.outputStream().use { out ->
            javaClass.classLoader!!.getResourceAsStream(name)!!.copyTo(out)
        }
        return dest
    }
    private fun fixturePhoto(act: Activity): String = fixtureFile(act, "avatar-placeholder.jpg").toURI().toString()
    private fun dp(act: Activity, v: Int) = Math.round(v * act.resources.displayMetrics.density)
    private fun idlePhoto() {
        // file:// photos decode on the main thread; nothing to idle.
    }
    private fun hasPhoto(v: View): Boolean {
        if (v is android.widget.ImageView && v.drawable != null && v.alpha > 0f && v.width > 0) return true
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (hasPhoto(v.getChildAt(i))) return true
        return false
    }
    /** The 112dp avatar photo was cover-cropped into a circle (corners and mid-edge are transparent). */
    private fun circlePhoto(v: View): Boolean {
        if (v is ImageView && v.drawable != null && v.width == v.height && v.width > 0) {
            val d = v.drawable
            val b = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (b != null && b.width > 8 && b.height == b.width) {
                val a00 = Color.alpha(b.getPixel(0, 0))
                val mid = Color.alpha(b.getPixel(b.width / 2, b.height / 2))
                // y=0 at the squircle's flat top (47/112) is opaque on a squircle and outside a circle.
                val side = Color.alpha(b.getPixel(b.width * 47 / 112, 0))
                if (a00 < 16 && mid > 200 && side < 16) return true
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (circlePhoto(v.getChildAt(i))) return true
        return false
    }

    /** A real server call:state snapshot (the olcall states.go wire shape), delivered the way the page relays it. */
    private var seqs = HashMap<String, Long>()
    private fun lc(s: NativeCallScreen, id: String, state: String, reason: String? = null, viaPage: Boolean = true) {
        val seq = (seqs[id] ?: 0L) + 1; seqs[id] = seq
        val l = JSONObject().put("callId", id).put("leg", "caller").put("state", state).put("seq", seq).put("event", "test")
        if (reason != null) l.put("reason", reason)
        if (viaPage) s.onPageCall("call:lifecycle", JSONObject().put("type", "call:lifecycle").put("callId", id).put("lifecycle", l))
        else NativeCallScreen.onServerLifecycle(com.donnydey.openlounge.olcall.OlCallLifecycle.parse(l)!!)
    }

    /** t6093u/t6094u: a link join opens the same native live room; alone = "Waiting for <first>…", camera toggle always there. */
    @Test fun linkLiveRoom() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        root.addView(hallway(act), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val s = NativeCallScreen(act, root, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean = true
            override fun speaker(on: Boolean) {}
            override fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit) { done(true) }
        })
        s.onPageCall("call:answering", JSONObject().put("callId", "L1").put("name", "Jordan Ellis").put("video", false).put("photo", fixturePhoto(act)).put("link", true))
        idlePhoto()
        lc(s, "L1", "connecting", viaPage = false)
        NativeCallScreen.onNativeMediaFlowing("L1")
        var t = texts(root); snap(root, "L1-link-waiting")
        assertTrue("link alone: Waiting for Jordan…, never Connecting…: $t", t.contains("Waiting for Jordan…") && !t.contains("Connecting…"))
        assertTrue("camera toggle present in the link room: $t", t.contains("Video") || t.contains("Camera"))
        lc(s, "L1", "in_room", viaPage = false)
        t = texts(root); snap(root, "L2-link-live-avatar")
        assertTrue("in_room: timer, no Waiting/Connecting: $t", t.any { Regex("^\\d+:\\d\\d$").matches(it) } && !t.contains("Connecting…") && !t.any { it.startsWith("Waiting for") })
        assertTrue("controls: Speaker / Video / Mute / End call: $t", t.containsAll(listOf("Speaker", "Mute", "End call")) && t.contains("Video"))
        findClickable(root, "Video")!!.performClick()
        s.onNativeVideo(true)
        t = texts(root); snap(root, "L3-link-camera-on")
        assertTrue("camera on: Camera + Flip: $t", t.contains("Camera") && t.contains("Flip"))
        NativeCallScreen.onRemoteVideo("L1", true); snap(root, "L4-link-remote-video-on")
        NativeCallScreen.onRemoteVideo("L1", false); t = texts(root); snap(root, "L5-link-remote-camera-off")
        assertTrue("their camera off: their name/avatar tile again: $t", t.contains("Jordan Ellis"))
    }

    @Test fun audioCallFlow() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        // Stand-in for the real app (WebView): the same hallway list the iOS sheet panel shows, so
        // minimized is the green bar over app content, never a blank panel.
        val app = hallway(act)
        root.addView(app, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val remote = ImageView(act).apply {
            setImageBitmap(BitmapFactory.decodeFile(fixtureFile(act, "remote-video-placeholder.jpg").absolutePath))
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(remote, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val pip = ImageView(act).apply {
            val raw = BitmapFactory.decodeFile(fixtureFile(act, "local-pip-placeholder.jpg").absolutePath)
            setImageBitmap(CallAvatar.rounded(raw, dp(act, 100), dp(act, 150), dp(act, 18).toFloat()))
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
        }
        val s = NativeCallScreen(act, root, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean { sent += detailJson; return true }
            override fun speaker(on: Boolean) {}
            override fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit) { done(true) }
        })
        val photo = fixturePhoto(act)
        s.onPageCall("call:outgoing", JSONObject().put("callId", "t1").put("name", "Ada Lovelace").put("video", false).put("photo", photo).put("presence", "online"))
        idlePhoto()
        var t = texts(root)
        assertTrue("no server frame yet: no state wording invented locally: $t", t.none { it in listOf("Calling…", "Ringing…", "Connecting…") })
        // server: callee leg Ringing (strict ring gate) -> BaoTime's one Ringing state
        lc(s, "t1", "ringing")
        t = texts(root); val shot1 = snap(root, "1-ringing")
        assertBlurred(shot1, "ringing")
        assertEquals("BaoTime ringing tint", "INITIATING", s.tintForTest)
        val avTop = avatarTop(root)
        assertEquals("avatar centered between the 56dp bar and the row (SwiftUI frame)", expectedAvatarTop(act, root), avTop)
        assertTrue(t.toString(), t.containsAll(listOf("Ada Lovelace", "Ringing…", "Speaker", "Mute", "End call")))
        assertTrue("pre-answer card (57, iOS 133 parity): Speaker / Mute / End call, no Cancel / Video / Flip / Camera: $t", t.none { it in listOf("Cancel", "Phone", "Video", "Flip", "Camera") })
        assertTrue("pre-answer Speaker/Mute disabled until this phone's olcall leg is up", !enabled(root, "Speaker") && !enabled(root, "Mute") && enabled(root, "End call"))
        assertTrue("no minimize before answer", findDesc(root, "Minimize call")?.let { isShown(it) } != true)
        assertTrue("profile photo is painted (initials only as a fallback)", hasPhoto(root))
        assertTrue("avatar is a 112dp circle (transparent corners, not a squircle)", circlePhoto(root))
        assertTrue("online presence dot on the calling avatar", findDesc(root, "online") != null)
        val expect = Math.round(56f * act.resources.displayMetrics.density)
        assertEquals("56dp End call disc", expect, discWidth(root, "End call"))
        assertEquals("56dp Speaker disc", expect, discWidth(root, "Speaker"))
        // legacy page messages no longer move the screen (new path supersedes the old one)
        for (old in listOf("call:ringing", "call:answered", "call:connected", "call:mediaFlowing", "call:ended")) s.onPageCall(old, JSONObject().put("callId", "t1"))
        assertTrue("legacy page messages are ignored", texts(root).contains("Ringing…"))
        // server: answered -> Answering ("Connecting…", fast pulse), no timer
        lc(s, "t1", "answering")
        t = texts(root); assertBlurred(snap(root, "2a-answering"), "answering")
        assertTrue("answering -> Connecting…, no timer: $t", t.contains("Connecting…") && t.contains("End call") && !t.contains("0:00"))
        assertEquals("answering avatar does not move", avTop, avatarTop(root))
        // server: connecting (via the olcall leg socket, not the page)
        lc(s, "t1", "connecting", viaPage = false)
        t = texts(root); snap(root, "2b-connecting")
        assertTrue("connecting -> Connecting…: $t", t.contains("Connecting…") && !t.contains("0:00"))
        assertEquals("connecting pulse is the fast 1.1 s ring", 1100L, s.pulseForTest)
        // this phone's leg up: Speaker / Mute enable, still not active until the server says in_room
        NativeCallScreen.onNativeMediaFlowing("t1")
        assertTrue("leg up alone does not start the timer", !texts(root).contains("0:00"))
        // server: in_room -> active
        lc(s, "t1", "in_room", viaPage = false)
        assertEquals("BaoTime active tint", "CONNECTED", s.tintForTest)
        t = texts(root)
        assertTrue("media flowing -> Speaker / Mute / Video / End call (one A/V room: camera toggle always present): $t", t.containsAll(listOf("Speaker", "Mute", "End call")) && !t.contains("Cancel") && !t.contains("Phone") && (t.contains("Video") || t.contains("Camera")))
        assertTrue("timer starts on in_room: $t", t.contains("0:00"))
        assertTrue("live: Speaker/Mute enabled", enabled(root, "Speaker") && enabled(root, "Mute"))
        assertTrue("camera off: no Flip until the camera is on", !t.contains("Flip"))
        assertBlurred(snap(root, "2c-active"), "active")
        assertEquals("live avatar does not move", avTop, avatarTop(root))
        val sizes = listOf("Speaker", "Mute", "End call").map { discWidth(root, it) }
        assertTrue("even in-call discs: $sizes", sizes.all { it == sizes[0] && it == expect })
        lc(s, "t1", "in_room") // duplicate state from the other route: seq-newer, same surface
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        t = texts(root); snap(root, "3-active"); assertTrue(t.toString(), t.any { Regex("^\\d+:\\d\\d$").matches(it) })
        findClickable(root, "Speaker")!!.performClick(); t = texts(root); assertTrue(t.contains("Speaker")); assertBlurred(snap(root, "4-speaker"), "in-call audio")
        assertEquals("in-call audio avatar does not move", avTop, avatarTop(root))
        findDesc(root, "Minimize call")!!.performClick()
        t = texts(root); assertTrue("minimized bar shows the peer and Tap to return", t.any { it.contains("Ada Lovelace") && it.contains("Tap to return") })
        assertTrue("controls hidden while minimized", !t.contains("End call"))
        assertTrue("real app stays visible under the bar (no blank stage)", t.containsAll(listOf("Open Lounge", "The Lounge", "Jamie Cole")))
        snap(root, "5-minimized")
        fun findBar(v: View): View? {
            if (v is android.widget.TextView && v.text.toString().contains("Tap to return") && (v.parent as? View)?.hasOnClickListeners() == true) return v.parent as View
            if (v is ViewGroup) for (i in 0 until v.childCount) findBar(v.getChildAt(i))?.let { return it }
            return null
        }
        findBar(root)!!.performClick()
        t = texts(root); assertTrue("restored controls", t.contains("End call")); snap(root, "6-restored")
        // one A/V room: the in-call camera toggle turns the local camera on (native video track alone no longer flips it)
        findClickable(root, "Video")!!.performClick()
        s.onNativeVideo(true)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        t = texts(root)
        assertTrue("short Camera label when the local camera is on", t.contains("Camera"))
        assertTrue("Flip appears once the camera is on", t.contains("Flip"))
        assertTrue("Stop video is not used (it overflowed the even row)", !t.contains("Stop video"))
        val videoSizes = listOf("Speaker", "Flip", "Camera", "Mute", "End call").map { discWidth(root, it) }
        assertTrue("even in-call video discs: $videoSizes", videoSizes.all { it == videoSizes[0] && it == expect })
        app.visibility = View.GONE
        remote.visibility = View.VISIBLE
        pip.visibility = View.VISIBLE
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        s.releasePipForTest(1080f, 2340f, 0f, 0f) // place now that the stage has a size (nearest = bottom-right)
        val touch = findDesc(root, "Self view")!!
        // Telegram slot: bottom-right, 12dp from the side, row reserve + 12dp from the bottom (controls shown)
        val d = act.resources.displayMetrics.density
        assertEquals("PiP default corner", CallPip.Corner.BOTTOM_RIGHT, s.pipCornerForTest)
        assertEquals("PiP x", 1080f - 12 * d - 100 * d, touch.translationX, 1.5f)
        assertEquals("PiP y", 2340f - (CallChrome.ROW_RESERVE + 12) * d - 150 * d, touch.translationY, 1.5f)
        // the stand-in renderer sits exactly where NativeCallVideo.placeLocal puts the SurfaceView
        if (pip.parent == null) root.addView(pip, FrameLayout.LayoutParams(dp(act, 100), dp(act, 150), android.view.Gravity.TOP or android.view.Gravity.START))
        pip.translationX = touch.translationX; pip.translationY = touch.translationY
        assertTrue("controls overlay the video on Telegram's shadows", texts(root).contains("End call"))
        snap(root, "7-video")
        // fling up-left from the bottom-right slot: Telegram's projection (pos - v / (1000 ln 0.99)) picks top-left
        s.releasePipForTest(touch.translationX + 50 * d, touch.translationY + 75 * d, -9000f, -16000f)
        assertEquals(CallPip.Corner.TOP_LEFT, s.pipCornerForTest)
        assertEquals("PiP top-left x", 12 * d, touch.translationX, 1.5f)
        assertEquals("PiP top-left y", 60 * d, touch.translationY, 1.5f)
        // slow release in the lower half stays bottom: nearest inset corner
        s.releasePipForTest(1080f * 0.3f, 2340f * 0.7f, 0f, 0f)
        assertEquals(CallPip.Corner.BOTTOM_LEFT, s.pipCornerForTest)
        pip.translationX = touch.translationX; pip.translationY = touch.translationY
        snap(root, "7b-video-pip-moved")
        s.releasePipForTest(1080f, 2340f, 0f, 0f)
        app.visibility = View.VISIBLE
        remote.visibility = View.GONE
        pip.visibility = View.GONE
        findClickable(root, "End call")!!.performClick()
        assertEquals("call:hangup", JSONObject(sent.last()).getString("type"))
        lc(s, "t1", "terminal", "completed", viaPage = false)
        assertEquals("BaoTime ended tint", "ENDED", s.tintForTest)
        t = texts(root)
        assertTrue("end card: name and Call ended · duration, no row: $t", t.contains("Ada Lovelace") && t.any { Regex("^Call ended · \\d+:\\d\\d$").matches(it) } && t.none { it == "End call" || it == "Cancel" })
        assertTrue("end card has the avatar (not the video stage)", findDesc(root, "Self view")?.visibility != View.VISIBLE)
        assertBlurred(snap(root, "9-ended"), "end card")
        assertEquals("end card avatar = calling avatar", avTop, avatarTop(root))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1900, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("end card leaves after 1.8 s", texts(root).none { it.startsWith("Call ended") })

        // callee path: accepted -> Answering -> Connecting -> InRoom, then declined/missed/expired reason cards
        s.onPageCall("call:answering", JSONObject().put("callId", "t3").put("name", "Ada Lovelace").put("photo", photo).put("presence", "online"))
        idlePhoto()
        lc(s, "t3", "answering"); lc(s, "t3", "connecting", viaPage = false)
        t = texts(root); assertBlurred(snap(root, "2-connecting"), "connecting")
        assertTrue("connecting: Connecting… + Speaker / Mute / End call: $t", t.contains("Connecting…") && t.containsAll(listOf("Speaker", "Mute", "End call")) && !t.contains("Cancel"))
        assertEquals("connecting avatar matches calling", avTop, avatarTop(root))
        lc(s, "t3", "in_room", viaPage = false)
        assertTrue("callee goes active on in_room", texts(root).any { Regex("^\\d+:\\d\\d$").matches(it) })
        lc(s, "t3", "connecting") // server leg reset back to connecting (seq-newer) is shown as is
        assertTrue("server reset back to connecting is shown", texts(root).contains("Connecting…"))
        lc(s, "t3", "terminal", "disconnected")
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1900, java.util.concurrent.TimeUnit.MILLISECONDS)

        for ((cid, reason, text) in listOf(Triple("t4", "declined", "Declined"), Triple("t5", "missed", "No answer"), Triple("t6", "expired", "Link expired"))) {
            s.onPageCall("call:outgoing", JSONObject().put("callId", cid).put("name", "Ada Lovelace").put("video", false).put("photo", photo))
            idlePhoto(); lc(s, cid, if (cid == "t6") "joining_url" else "ringing")
            if (cid == "t6") assertTrue("link door: Joining…", texts(root).contains("Joining…"))
            lc(s, cid, "terminal", reason)
            assertTrue("$reason end card says $text: ${texts(root)}", texts(root).contains(text))
            snap(root, "9-$reason")
            org.robolectric.shadows.ShadowLooper.idleMainLooper(1900, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        s.onPageCall("call:outgoing", JSONObject().put("callId", "t2").put("name", "Ada Lovelace").put("video", false).put("photo", photo).put("presence", "online"))
        idlePhoto()
        lc(s, "t2", "ringing"); lc(s, "t2", "terminal", "failed", viaPage = false)
        assertEquals("BaoTime failed tint", "FAILED", s.tintForTest)
        t = texts(root)
        assertTrue("Failed screen offers Call again: $t", t.contains("Call again"))
        assertTrue(t.toString(), t.any { it.contains("Can't connect the call on this network") })
        assertTrue("failure screen has no bottom row", !t.contains("End call"))
        assertTrue("Close control is in the top-right", findDesc(root, "Close") != null)
        assertBlurred(snap(root, "8-failed"), "failure")
        // two-line copy: the block stays centered in the same frame (as SwiftUI), so it rises by half the extra line
        assertEquals("failure block centered in the SwiftUI frame", expectedAvatarTop(act, root), avatarTop(root))
        findDesc(root, "Close")!!.performClick()
        assertTrue("X dismisses the failure screen", texts(root).none { it.contains("Can't connect the call") })
    }

    /** 4:34 PM recording: the outgoing screen flashed "KD" before the photo arrived. Initials are the no-photo
     *  fallback only, so they are not painted while a profile photo URL is still downloading. */
    @Test fun noInitialsWhilePhotoLoads() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act); act.setContentView(root)
        val s = NativeCallScreen(act, root, object : NativeCallScreen.Actions {
            override fun pageCall(detailJson: String): Boolean = true
            override fun speaker(on: Boolean) {}
            override fun camera(on: Boolean, facing: String, done: (Boolean) -> Unit) { done(true) }
        })
        s.onPageCall("call:outgoing", JSONObject().put("callId", "t9").put("name", "Keiko Dey").put("video", false).put("photo", "https://127.0.0.1:9/p.jpg"))
        assertTrue("no initials while the photo loads", "KD" !in texts(root))
        s.onPageCall("call:outgoing", JSONObject().put("callId", "t10").put("name", "Keiko Dey").put("video", false).put("photo", ""))
        assertTrue("no photo: initials are the fallback", "KD" in texts(root))
    }

    @Test fun incomingMatchesCallScreen() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val f = File(ctx.cacheDir, "avatar-placeholder.jpg"); f.outputStream().use { o -> javaClass.classLoader!!.getResourceAsStream("avatar-placeholder.jpg")!!.copyTo(o) }
        val intent = IncomingCallActivity.intent(ctx, "inc1", "Ada Lovelace", f.toURI().toString(), false, 0L).putExtra("presence", "online")
        val act = Robolectric.buildActivity(IncomingCallActivity::class.java, intent).setup().get()
        val root = (act.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        val t = texts(root)
        assertTrue(t.toString(), t.containsAll(listOf("Ada Lovelace", "Decline", "Message", "Accept")))
        val shot = snap(root, "0-incoming")
        assertBlurred(shot, "incoming")
        assertTrue("incoming avatar is the circle photo", circlePhoto(root))
        assertTrue("online dot on the incoming avatar", findDesc(root, "online") != null)
        val d = act.resources.displayMetrics.density
        val rb = ringBoxOf(root)!!
        val loc = IntArray(2); rb.getLocationInWindow(loc); val r0 = IntArray(2); root.getLocationInWindow(r0)
        val block = Math.round(112 * d) + Math.round(12 * d) + (rb.parent as ViewGroup).let { c -> (1 until c.childCount).map { c.getChildAt(it) }.filter { it is TextView }.sumOf { it.height + (it.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin } }
        val bar = Math.round(56 * d); val reserve = Math.round(100 * d)
        val expect = bar + (root.height - bar - reserve - block) / 2
        assertTrue("incoming avatar top ${loc[1] - r0[1]} vs SwiftUI frame $expect", Math.abs(loc[1] - r0[1] - expect) <= 2)
    }
}
