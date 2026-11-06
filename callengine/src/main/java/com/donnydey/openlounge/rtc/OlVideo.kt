package com.donnydey.openlounge.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.donnydey.openlounge.audio.CallAudioEngine
import org.webrtc.*

/**
 * Call video on the ONE call PeerConnection (RingRTC session model; replaces the PJSIP ol_sfu_video leg, OLCameraFanout
 * and the MediaCodec simulcast encoder). Camera2Capturer -> VideoSource -> "ol-cam" track on the PC's video transceiver;
 * the remote video track renders into a SurfaceViewRenderer. Encode/decode: libwebrtc's default (HW first) factories on
 * the shared [egl] context. Turning the camera on/off is replaceTrack on the existing sender — no renegotiation.
 */
object OlVideo {
    val egl: EglBase by lazy { EglBase.create() }
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    @Volatile var localTrack: VideoTrack? = null; private set
    private var remoteTrack: VideoTrack? = null
    var localView: SurfaceViewRenderer? = null
        set(v) { field = v; v?.let { initView(it, mirror = true); localTrack?.addSink(it) } }
    var remoteView: SurfaceViewRenderer? = null
        set(v) { field = v; v?.let { initView(it, mirror = false); remoteTrack?.addSink(it) } }
    /** first decoded remote frame (main thread) */
    var onFirstRemoteFrame: (() -> Unit)? = null
    /** local track changed (null = camera off) -> OlPeerConnection swaps it onto the video sender */
    var onLocalTrack: ((VideoTrack?) -> Unit)? = null
    var facing = "user"; private set
    private var firstFrameSeen = false
    private val inited = HashSet<SurfaceViewRenderer>()

    fun init(c: Context) { ctx = c.applicationContext }

    fun encoderFactory(): VideoEncoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
    fun decoderFactory(): VideoDecoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)

    private fun initView(v: SurfaceViewRenderer, mirror: Boolean) {
        if (inited.add(v)) runCatching { v.init(egl.eglBaseContext, null) }
        v.setMirror(mirror)
        v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
    }

    private fun deviceFor(e: Camera2Enumerator, face: String): String? {
        val front = face != "environment"
        return e.deviceNames.firstOrNull { if (front) e.isFrontFacing(it) else e.isBackFacing(it) } ?: e.deviceNames.firstOrNull()
    }

    /** Camera on (or flip when already on) / off. done(ok, error) on the main thread. */
    fun camera(on: Boolean, face: String, done: (Boolean, String?) -> Unit) = main.post {
        if (!on) { stopCamera(); return@post done(true, null) }
        val f = CallAudioEngine.factory ?: return@post done(false, "engine not open")
        val c = ctx ?: return@post done(false, "no context")
        val cap = capturer
        if (cap != null) {
            if (face == facing) return@post done(true, null)
            cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(front: Boolean) { facing = if (front) "user" else "environment"; main.post { localView?.setMirror(front); done(true, null) } }
                override fun onCameraSwitchError(e: String?) { main.post { done(false, e ?: "switch failed") } }
            })
            return@post
        }
        runCatching {
            val e = Camera2Enumerator(c)
            val name = deviceFor(e, face) ?: error("no camera")
            val k = e.createCapturer(name, null) ?: error("capturer")
            val h = SurfaceTextureHelper.create("ol-cam", egl.eglBaseContext)
            val src = f.createVideoSource(false)
            k.initialize(h, c, src.capturerObserver)
            k.startCapture(1280, 720, 30)
            val t = f.createVideoTrack("ol-cam", src)
            capturer = k; helper = h; source = src; localTrack = t; facing = face
            localView?.let { t.addSink(it); it.setMirror(face != "environment") }
            onLocalTrack?.invoke(t)
        }.onFailure { Log.e(TAG, "camera", it); stopCamera(); return@post done(false, it.message ?: "camera failed") }
        done(true, null)
    }

    /** PeerConnection observer: remote video track arrived on the call PC. */
    fun setRemoteTrack(t: VideoTrack?) = main.post {
        remoteView?.let { v -> remoteTrack?.removeSink(v) }
        remoteTrack = t; firstFrameSeen = false
        val v = remoteView ?: return@post
        t?.addSink(VideoSink { fr -> v.onFrame(fr); if (!firstFrameSeen) { firstFrameSeen = true; main.post { onFirstRemoteFrame?.invoke() } } })
    }

    private fun stopCamera() {
        val t = localTrack
        localTrack = null
        if (t != null) { onLocalTrack?.invoke(null); localView?.let { t.removeSink(it) } }
        runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }; capturer = null
        runCatching { t?.dispose() }
        runCatching { source?.dispose() }; source = null
        runCatching { helper?.dispose() }; helper = null
        localView?.clearImage()
    }

    /** Call ended: camera off, remote detached (views stay initialised for the next call). */
    fun end() = main.post { stopCamera(); remoteView?.let { remoteTrack?.removeSink(it); it.clearImage() }; remoteTrack = null; firstFrameSeen = false }

    private const val TAG = "OLRtcVideo"
}
