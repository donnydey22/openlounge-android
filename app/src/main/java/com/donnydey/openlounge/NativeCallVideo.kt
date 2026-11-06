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
import com.donnydey.openlounge.pjsip.PjsipEngine

/**
 * In-app call video (pjsip-rebuild, matches iOS OLCallVideo): SFU "video" PlainTransport leg in ol_sfu_video.c.
 * Receive: nativeSfuVideoPoll() Annex-B AUs -> MediaCodec H.264 decoder rendering straight into [remote].
 * Send (47): ONE Camera2 output at an advertised size -> [OLCameraFanout] (EGL) -> 3 simulcast hardware H.264
 * encoders (180p/15, 720p/30, 1080p/30) + [local] preview drawn from the same frame -> nativeSfuVideoSend.
 * Camera2 output-combination support depends on hardware level/sizes, so the camera gets exactly one target.
 */
object NativeCallVideo {
    // SFU android ladder (media-sfu/src/plain.mjs:13, checked at :29) takes exactly 3 SSRCs: 180p/720p/1080p
    val LAYERS = listOf(OLVideoLayer(320, 180, 120_000, 15), OLVideoLayer(1280, 720, 600_000, 30), OLVideoLayer(1920, 1080, 1_600_000, 30))
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
    private var camThread: HandlerThread? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var ctx: Context? = null
    @Volatile var rxFrames = 0; @Volatile var txFrames = 0
    var facing = "user"; private set

    /** Starts the native leg and the receive/decode loop. 0 on success (ol_sfu_video_start result). */
    fun start(c: Context, ip: String, port: Int, txKey: String, rxKey: String, ssrcs: LongArray, pt: Int): Int {
        ctx = c.applicationContext
        val r = PjsipEngine.nativeSfuVideoStart(ip, port, txKey, rxKey, ssrcs, pt)
        if (r != 0) return r
        running = true; rxFrames = 0; txFrames = 0
        rx = Thread({ recvLoop() }, "ol-video-recv").also { it.start() }
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

    /** Camera on/off/flip. Off keeps the leg (and the remote video) running. */
    @SuppressLint("MissingPermission")
    fun camera(on: Boolean, face: String = facing, done: (Boolean, String?) -> Unit) {
        stopCamera()
        if (!on) return done(true, null)
        facing = if (face == "environment") "environment" else "user"
        val c = ctx ?: return done(false, "no context")
        val mgr = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (facing == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: mgr.cameraIdList.firstOrNull() ?: return done(false, "no camera")
        val top = LAYERS.last()
        val size = runCatching { cameraSize(mgr.getCameraCharacteristics(id), top.width, top.height) }.getOrNull()
            ?: return done(false, "no camera size")
        val e = try { OLSimulcastEncoder(layersOverride = LAYERS, forceH264 = true, requireHardware = true).apply {
            onAnnexB = { layer, au, k, ts -> if (running) { PjsipEngine.nativeSfuVideoSend(layer, au, au.size, ts, k); if (layer == LAYERS.size - 1) txFrames++ } }
            start() } } catch (x: Exception) { return done(false, "encoder: ${x.message}") }
        enc = e
        val f = try { OLCameraFanout(size.width, size.height, e.fanoutTargets()).also { it.start() } }
            catch (x: Exception) { stopCamera(); return done(false, "fanout: ${x.message}") }
        fan = f
        local?.surfaceTexture?.let { st -> st.setDefaultBufferSize(1280, 720); f.setPreview(Surface(st), 1280, 720) }
        val gen = ++camGen
        var reported = false
        fun finish(ok: Boolean, why: String?) = main.post { if (gen != camGen || reported) return@post; reported = true
            if (!ok) stopCamera(); done(ok, why); if (!ok) onCameraError?.invoke(why ?: "camera") }
        val t = HandlerThread("ol-camera").also { it.start() }; camThread = t
        val h = Handler(t.looper)
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != camGen) { d.close(); return }
                    cam = d
                    val outs = listOf(f.cameraSurface) // exactly one camera target; encoders + preview are fed by EGL
                    @Suppress("DEPRECATION")
                    try { d.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (gen != camGen) { runCatching { s.close() }; return }
                            session = s
                            try {
                                val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(f.cameraSurface)
                                    fpsRange(mgr.getCameraCharacteristics(id))?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                    val stab = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                                    if (stab?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true)
                                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) }
                                s.setRepeatingRequest(rq.build(), null, h)
                                finish(true, null)
                            } catch (x: Exception) { finish(false, "capture request: ${x.message}") }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { finish(false, "camera session") }
                    }, h) } catch (x: Exception) { finish(false, "camera session: ${x.message}") }
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); if (gen == camGen) { cam = null; finish(false, "camera disconnected") } }
                override fun onError(d: CameraDevice, err: Int) { d.close(); if (gen == camGen) { cam = null; finish(false, "camera error $err") } }
            }, h)
        } catch (x: Exception) { stopCamera(); done(false, "camera: ${x.message}") }
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
    }

    private fun stopCamera() {
        camGen++ // invalidate callbacks from the camera being stopped
        runCatching { session?.close() }; session = null
        runCatching { cam?.close() }; cam = null
        runCatching { fan?.release() }; fan = null // detach EGL targets before the codec input Surfaces go away
        runCatching { enc?.stop() }; enc = null
        camThread?.quitSafely(); camThread = null
    }


    fun stop() {
        running = false; stopCamera()
        rx?.join(500); rx = null
        runCatching { PjsipEngine.nativeSfuVideoStop() }
    }
}
