package com.donnydey.openlounge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import com.donnydey.openlounge.pjsip.OLCameraFanout
import com.donnydey.openlounge.pjsip.OLSimulcastEncoder
import com.donnydey.openlounge.pjsip.OLVideoLayer
import com.donnydey.openlounge.pjsip.OLVideoSpecV2
import com.donnydey.openlounge.pjsip.PjsipEngine

/**
 * In-app call video (pjsip-rebuild, matches iOS OLCallVideo): SFU "video" PlainTransport leg in ol_sfu_video.c.
 * Receive: nativeSfuVideoPoll() Annex-B AUs -> MediaCodec H.264 decoder rendering straight into [remote].
 * Send (47): ONE Camera2 output at an advertised size -> [OLCameraFanout] (EGL) -> 3 simulcast hardware H.264
 * encoders (capability ladder; 1080p on capable HW, lower on A11/weak) + [local] preview from the same frame
 * -> nativeSfuVideoSend. Camera2 gets exactly one target. Mitigations: per-SSRC keyframes, MediaCodec
 * invalidate rebuild (-12903 analogue), off-main setup, flip without rebuild, empty-c watch, bg resume,
 * teardown/burst cleanup. Video mitigations do not claim to fix audio -201.
 */
object NativeCallVideo {
    // SFU still expects 3 SSRCs; sizes from OLVideoSpecV2.layersForDevice (weak HW incl. A11 -> 720p top).
    val LAYERS: List<OLVideoLayer> get() = activeLayers
    @Volatile private var activeLayers: List<OLVideoLayer> = OLVideoSpecV2.layersForDevice()
    var remote: TextureView? = null
    var local: TextureView? = null
    var onFirstRemoteFrame: (() -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var rx: Thread? = null
    private var dec: MediaCodec? = null
    private var enc: OLSimulcastEncoder? = null
    private var fan: OLCameraFanout? = null
    @Volatile private var camGen = 0 // generation guard: callbacks from a stopped camera are ignored
    // Serialized ownership for camera, codec and EGL setup/teardown; never initialize them on main.
    private val camThread = HandlerThread("ol-camera").also { it.start() }
    private val cameraHandler = Handler(camThread.looper)
    @Volatile private var closing = false
    private var pendingCameraDone: ((Boolean, String?) -> Unit)? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var ctx: Context? = null
    @Volatile var rxFrames = 0; @Volatile var txFrames = 0
    var facing = "user"; private set
    @Volatile private var cameraDesired = false
    private var emptyWatch: Runnable? = null
    private val lifecycleCbs = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: android.app.Activity) {
            if (!running || !cameraDesired || closing) return
            // Without multitasking camera access, Camera2 often freezes in background; reopen or IDR on resume.
            if (cam == null || session == null) {
                CallDiskLog.mark("video-bg-resume", "reopen facing=$facing")
                camera(true, facing) { ok, e -> if (!ok) CallDiskLog.mark("video-bg-resume-fail", e ?: "") }
            } else enc?.requestKeyframes()
        }
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivityPaused(a: android.app.Activity) {}
        override fun onActivityStopped(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    }
    @Volatile private var lifecycleRegistered = false

    /** Starts the native leg and the receive/decode loop. 0 on success (ol_sfu_video_start result). */
    fun start(c: Context, ip: String, port: Int, txKey: String, rxKey: String, ssrcs: LongArray, pt: Int): Int {
        if (closing || running) return -20 // do not reuse native state while a previous teardown is draining
        ctx = c.applicationContext
        activeLayers = OLVideoSpecV2.layersForDevice()
        CallDiskLog.mark("video-layers", activeLayers.joinToString { "${it.width}x${it.height}@${it.fps}" } +
            " weak=${OLVideoSpecV2.weakHardware()}")
        val r = PjsipEngine.nativeSfuVideoStart(ip, port, txKey, rxKey, ssrcs, pt)
        if (r != 0) return r
        running = true; rxFrames = 0; txFrames = 0
        rx = Thread({ recvLoop() }, "ol-video-recv").also { it.start() }
        if (!lifecycleRegistered) {
            (c.applicationContext as? android.app.Application)?.let {
                runCatching { it.registerActivityLifecycleCallbacks(lifecycleCbs); lifecycleRegistered = true }
            }
        }
        return 0
    }

    private fun remoteSurface(): Surface? {
        val tv = remote ?: return null
        val st: SurfaceTexture = tv.surfaceTexture ?: return null
        return Surface(st)
    }

    private fun recvLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        var surf: Surface? = null; var gotKey = false
        val info = MediaCodec.BufferInfo()
        while (running) {
            val a = runCatching { PjsipEngine.nativeSfuVideoPoll() }.getOrNull()
            if (a == null || a.size < 6) { drain(info); Thread.sleep(4); continue }
            val key = a[0].toInt() != 0
            val ts = ((a[1].toLong() and 0xff) shl 24) or ((a[2].toLong() and 0xff) shl 16) or ((a[3].toLong() and 0xff) shl 8) or (a[4].toLong() and 0xff)
            if (dec == null) {
                if (!key) continue
                if (surf == null) surf = remoteSurface() ?: continue
                dec = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
                    if (android.os.Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    configure(f, surf, null, 0); start() } }.getOrNull() ?: continue
            }
            if (key) gotKey = true
            if (!gotKey) continue
            val d = dec!!
            try {
                val ix = d.dequeueInputBuffer(10_000)
                if (ix >= 0) { d.getInputBuffer(ix)!!.apply { clear(); put(a, 5, a.size - 5) }
                    d.queueInputBuffer(ix, 0, a.size - 5, ts * 1000 / 90, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
                drain(info)
            } catch (e: Exception) { // codec died (surface lost / reconfig): rebuild on the next keyframe
                runCatching { d.release() }; dec = null; gotKey = false
            }
        }
        dec?.let { runCatching { it.stop() }; runCatching { it.release() } }; dec = null
        surf?.release()
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        val d = dec ?: return
        while (true) {
            val ox = try { d.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            if (ox < 0) return
            d.releaseOutputBuffer(ox, info.size > 0) // render to the remote TextureView
            if (info.size > 0 && rxFrames++ == 0) main.post { onFirstRemoteFrame?.invoke() }
        }
    }

    /** Camera commands are ordered on main, but all expensive setup runs on ol-camera. */
    fun camera(on: Boolean, face: String = facing, done: (Boolean, String?) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { camera(on, face, done) }; return }
        pendingCameraDone?.invoke(false, "camera superseded")
        pendingCameraDone = null
        val gen = ++camGen
        cameraDesired = on
        if (!on) { emptyWatch?.let { main.removeCallbacks(it) }; emptyWatch = null
            cameraHandler.post { stopCamera() }; done(true, null); return }
        if (!running || closing) { done(false, "video leg not running"); return }
        pendingCameraDone = done
        facing = if (face == "environment") "environment" else "user"
        val selectedFacing = facing
        val preview = local?.surfaceTexture?.let { Surface(it) }
        cameraHandler.post {
            if (gen != camGen) { preview?.release(); return@post }
            stopCapture() // flip changes only Camera2; keep encoder/EGL state and RTP SSRCs intact
            setupCamera(gen, selectedFacing, preview)
        }
    }

    private fun reportCamera(gen: Int, ok: Boolean, why: String?) {
        main.post {
            if (gen != camGen) return@post
            val cb = pendingCameraDone; pendingCameraDone = null
            cb?.invoke(ok, why)
            if (!ok) onCameraError?.invoke(why ?: "camera")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupCamera(gen: Int, face: String, preview: Surface?) {
        val c = ctx
        if (c == null) { preview?.release(); reportCamera(gen, false, "no context"); return }
        try {
            val mgr = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (face == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: mgr.cameraIdList.firstOrNull() ?: error("no camera")
            val ch = mgr.getCameraCharacteristics(id)
            val top = LAYERS.last()
            val size = cameraSize(ch, top.width, top.height) ?: error("empty camera sizes")
            var e = enc
            var f = fan
            if (e == null || f == null) {
                activeLayers = OLVideoSpecV2.layersForDevice()
                e = OLSimulcastEncoder(layersOverride = activeLayers, forceH264 = true, requireHardware = true)
                enc = e // own partially-initialized instances too
                e.onAnnexB = label@{ layer, au, k, ts ->
                    if (!running || closing || au.isEmpty()) return@label // empty-c: never send zero-length
                    PjsipEngine.nativeSfuVideoSend(layer, au, au.size, ts, k)
                    if (layer == activeLayers.size - 1) txFrames++
                }
                e.onInvalidated = { // MediaCodec died (VT -12903 analogue): rebuild all layers via full reopen
                    CallDiskLog.mark("video-enc-invalidated", "rebuild")
                    main.post {
                        if (!running || !cameraDesired || closing) return@post
                        camera(true, facing) { ok, err -> CallDiskLog.mark("video-enc-rebuild", "ok=$ok err=${err ?: ""}") }
                    }
                }
                e.start()
                f = OLCameraFanout(size.width, size.height, e.fanoutTargets())
                fan = f; f.start()
            } else {
                f.resizeCameraInput(size.width, size.height)
                e.requestKeyframes() // flip: decoder refresh, not codec rebuild
            }
            val captureFan = f
            captureFan.setPreview(preview, 1280, 720)
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != camGen) { d.close(); return }
                    cam = d
                    @Suppress("DEPRECATION")
                    try { d.createCaptureSession(listOf(captureFan.cameraSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (gen != camGen) { s.close(); return }
                            session = s
                            try {
                                val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(captureFan.cameraSurface)
                                    fpsRange(ch)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                }
                                s.setRepeatingRequest(rq.build(), null, cameraHandler)
                                armEmptyWatch(gen)
                                reportCamera(gen, true, null)
                            } catch (x: Exception) { cameraFailure(gen, "capture request: ${x.message}") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { s.close(); cameraFailure(gen, "camera session") }
                    }, cameraHandler) } catch (x: Exception) { cameraFailure(gen, "camera session: ${x.message}") }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); cameraFailure(gen, "camera disconnected") }
                override fun onError(d: CameraDevice, err: Int) { d.close(); cameraFailure(gen, "camera error $err") }
            }, cameraHandler)
        } catch (x: Exception) {
            preview?.release()
            cameraFailure(gen, "camera setup: ${x.message}")
        }
    }

    private fun cameraFailure(gen: Int, why: String) {
        if (gen != camGen) return
        stopCamera()
        CallDiskLog.mark("video-camera-fail", why)
        reportCamera(gen, false, why)
    }

    /** Largest advertised SurfaceTexture size with the top layer's aspect, not above it; else the closest aspect. */
    private fun cameraSize(ch: CameraCharacteristics, w: Int, h: Int): android.util.Size? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val all = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val same = all.filter { it.width * h == it.height * w && it.width <= w }
        return same.maxByOrNull { it.width } ?: all.filter { it.width <= w }.maxByOrNull { it.width * it.height }
    }

    /** Advertised AE range covering 30 fps (prefer 15-30, else fixed 30, else the widest with max 30). */
    private fun fpsRange(ch: CameraCharacteristics): android.util.Range<Int>? {
        val r = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return r.firstOrNull { it.lower == 15 && it.upper == 30 } ?: r.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: r.filter { it.upper == 30 }.minByOrNull { it.lower }
            ?: r.filter { it.upper >= 24 }.minByOrNull { it.lower }
    }

    /** ol-camera only. Close the producer before the EGL consumers and codec input surfaces. */
    private fun stopCapture() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }; session = null
        runCatching { cam?.close() }; cam = null
    }

    private fun stopCamera() {
        stopCapture()
        runCatching { fan?.release() }; fan = null
        runCatching { enc?.stop() }; enc = null
    }

    /** empty-c: if fan-out / TX stalls for ~2s while camera desired, force IDR; after ~4s reopen. */
    private fun armEmptyWatch(gen: Int) {
        main.post {
            emptyWatch?.let { main.removeCallbacks(it) }
            val startFan = fan?.frames ?: 0
            val startTx = txFrames
            val r = object : Runnable {
                var tick = 0
                override fun run() {
                    if (gen != camGen || !cameraDesired || !running || closing) return
                    val frames = (fan?.frames ?: 0) - startFan
                    val tx = txFrames - startTx
                    if (frames == 0 || tx == 0) {
                        tick++
                        CallDiskLog.mark("video-empty-c", "tick=$tick fan=$frames tx=$tx")
                        enc?.requestKeyframes()
                        if (tick >= 2) {
                            CallDiskLog.mark("video-empty-c-reopen", "facing=$facing")
                            camera(true, facing) { _, _ -> }
                            return
                        }
                        main.postDelayed(this, 2000L)
                    }
                }
            }
            emptyWatch = r
            main.postDelayed(r, 2000L)
        }
    }

    fun stop() {
        running = false; cameraDesired = false; closing = true; camGen++
        emptyWatch?.let { main.removeCallbacks(it) }; emptyWatch = null
        PjsipEngine.cancelVideoBurst()
        main.post { pendingCameraDone?.invoke(false, "video stopped"); pendingCameraDone = null }
        cameraHandler.post {
            stopCamera()
            rx?.join(); rx = null // poll is non-blocking; never free native state under the receive loop
            runCatching { PjsipEngine.nativeSfuVideoStop() }
            closing = false
        }
    }
}
