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
import com.donnydey.openlounge.pjsip.OLSimulcastEncoder
import com.donnydey.openlounge.pjsip.OLVideoLayer
import com.donnydey.openlounge.pjsip.PjsipEngine

/**
 * In-app call video (pjsip-rebuild, matches iOS OLCallVideo): SFU "video" PlainTransport leg in ol_sfu_video.c.
 * Receive: nativeSfuVideoPoll() Annex-B AUs -> MediaCodec H.264 decoder rendering straight into [remote].
 * Send: Camera2 -> 2 simulcast hardware H.264 encoders (180p/15, 720p/30) + [local] preview -> nativeSfuVideoSend.
 * Camera2 guarantees 3 PRIV outputs on every hardware level, hence 2 layers + preview.
 */
object NativeCallVideo {
    val LAYERS = listOf(OLVideoLayer(320, 180, 150_000, 15), OLVideoLayer(1280, 720, 1_500_000, 30))
    var remote: TextureView? = null
    var local: TextureView? = null
    var onFirstRemoteFrame: (() -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var rx: Thread? = null
    private var dec: MediaCodec? = null
    private var enc: OLSimulcastEncoder? = null
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
        val e = try { OLSimulcastEncoder(layersOverride = LAYERS, forceH264 = true).apply {
            onAnnexB = { layer, au, k, ts -> if (running) { PjsipEngine.nativeSfuVideoSend(layer, au, au.size, ts, k); if (layer == LAYERS.size - 1) txFrames++ } }
            start() } } catch (x: Exception) { return done(false, "encoder: ${x.message}") }
        enc = e
        val t = HandlerThread("ol-camera").also { it.start() }; camThread = t
        val h = Handler(t.looper)
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { cam = d
                    val outs = ArrayList(e.surfaces)
                    local?.surfaceTexture?.let { st -> st.setDefaultBufferSize(1280, 720); outs += Surface(st) }
                    @Suppress("DEPRECATION")
                    d.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { session = s
                            val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                outs.forEach { addTarget(it) }
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(15, 30))
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) }
                            runCatching { s.setRepeatingRequest(rq.build(), null, h) }
                            main.post { done(true, null) } }
                        override fun onConfigureFailed(s: CameraCaptureSession) { main.post { done(false, "camera session"); onCameraError?.invoke("session") } }
                    }, h)
                }
                override fun onDisconnected(d: CameraDevice) { d.close(); cam = null }
                override fun onError(d: CameraDevice, err: Int) { d.close(); cam = null; main.post { onCameraError?.invoke("camera error $err") } }
            }, h)
        } catch (x: Exception) { stopCamera(); done(false, "camera: ${x.message}") }
    }

    private fun stopCamera() {
        runCatching { session?.close() }; session = null
        runCatching { cam?.close() }; cam = null
        runCatching { enc?.stop() }; enc = null
        camThread?.quitSafely(); camThread = null
    }

    fun stats(): IntArray = runCatching { PjsipEngine.nativeSfuVideoStats() }.getOrDefault(IntArray(0))

    fun stop() {
        running = false; stopCamera()
        rx?.join(500); rx = null
        runCatching { PjsipEngine.nativeSfuVideoStop() }
    }
}
