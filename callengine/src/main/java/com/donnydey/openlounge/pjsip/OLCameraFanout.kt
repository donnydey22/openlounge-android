// Open Lounge call video, build 47: ONE Camera2 output -> EGL fan-out -> the 3 hardware H.264 encoder input Surfaces
// (+ optional native local preview drawn from the same frame). Written against documented Android EGL14/GLES20/
// SurfaceTexture APIs only; no third-party code. Replaces the old "camera writes 3-4 PRIV targets" configuration.
package com.donnydey.openlounge.pjsip

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [encoderTargets]: codec input Surfaces in layer order, with each layer's max fps for per-layer pacing.
 * [cameraSurface] is the single Surface handed to Camera2. All GL work runs on the private "ol-fanout" thread.
 * Every frame goes to every target with the SAME source timestamp (eglPresentationTimeANDROID). Pre-encode work is
 * bounded: SurfaceTexture holds one pending image, so a slow frame is replaced, never queued.
 */
class OLCameraFanout(private val camW: Int, private val camH: Int, private val encoderTargets: List<Pair<Surface, Int>>) {
  private val thread = HandlerThread("ol-fanout").also { it.start() }
  private val h = Handler(thread.looper)
  private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
  private var cfg: EGLConfig? = null
  private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
  private val encSurf = ArrayList<EGLSurface>()
  private var lastNs = LongArray(encoderTargets.size)
  private var preview: EGLSurface = EGL14.EGL_NO_SURFACE
  private var previewW = 0; private var previewH = 0
  private var prog = 0; private var tex = 0; private var aPos = 0; private var aTex = 0; private var uMtx = 0
  private var st: SurfaceTexture? = null
  @Volatile private var released = false
  private val mtx = FloatArray(16)
  lateinit var cameraSurface: Surface; private set
  @Volatile var frames = 0; private set

  /** Builds EGL + targets on the fan-out thread. Throws VideoConfigException on any failure (caller ends cleanly). */
  fun start() {
    var err: Throwable? = null
    val l = CountDownLatch(1)
    h.post { try { setup() } catch (t: Throwable) { err = t }; l.countDown() }
    if (!l.await(2, TimeUnit.SECONDS)) err = IllegalStateException("fan-out setup timeout")
    err?.let { release(); throw VideoConfigException("EGL fan-out: ${it.message}", it) }
  }

  private fun setup() {
    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1)) { "eglInitialize" }
    val attr = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
    val cs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
    check(EGL14.eglChooseConfig(dpy, attr, 0, cs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
    cfg = cs[0]
    ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
    check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
    pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
    for ((s, _) in encoderTargets) {
      val es = EGL14.eglCreateWindowSurface(dpy, cfg, s, intArrayOf(EGL14.EGL_NONE), 0)
      check(es != EGL14.EGL_NO_SURFACE) { "encoder surface ${encSurf.size}" }; encSurf += es
    }
    check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)) { "eglMakeCurrent" }
    prog = program(); aPos = GLES20.glGetAttribLocation(prog, "aPos"); aTex = GLES20.glGetAttribLocation(prog, "aTex")
    uMtx = GLES20.glGetUniformLocation(prog, "uMtx")
    val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    val s = SurfaceTexture(tex).apply { setDefaultBufferSize(camW, camH); setOnFrameAvailableListener({ onFrame() }, h) }
    st = s; cameraSurface = Surface(s)
  }

  /** Flip only rebinds Camera2 to this same input Surface; codec/EGL outputs survive. */
  fun resizeCameraInput(w: Int, h0: Int) {
    val done = CountDownLatch(1)
    var error: Throwable? = null
    h.post { try { check(!released); st?.setDefaultBufferSize(w, h0) } catch (t: Throwable) { error = t } finally { done.countDown() } }
    check(done.await(2, TimeUnit.SECONDS)) { "camera input resize timeout" }
    error?.let { throw IllegalStateException("camera input resize", it) }
  }

  /** Attach/detach the native local preview independently of capture and encoders (null = detach). */
  fun setPreview(surface: Surface?, w: Int, h0: Int) = h.post {
    if (released) return@post
    if (preview != EGL14.EGL_NO_SURFACE) { EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx); EGL14.eglDestroySurface(dpy, preview); preview = EGL14.EGL_NO_SURFACE }
    if (surface != null && surface.isValid) {
      preview = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
      previewW = w; previewH = h0
    }
  }

  private fun onFrame() {
    if (released) return
    val s = st ?: return
    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
    s.updateTexImage(); s.getTransformMatrix(mtx)
    val ts = s.timestamp
    frames++
    for ((i, es) in encSurf.withIndex()) {
      val fps = encoderTargets[i].second
      if (lastNs[i] != 0L && ts - lastNs[i] < 1_000_000_000L / fps - 2_000_000L) continue // per-layer cadence
      lastNs[i] = ts
      if (!EGL14.eglMakeCurrent(dpy, es, es, ctx)) continue
      draw(widthOf(es), heightOf(es))
      EGLExt.eglPresentationTimeANDROID(dpy, es, ts)
      EGL14.eglSwapBuffers(dpy, es)
    }
    if (preview != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(dpy, preview, preview, ctx)) {
      draw(previewW, previewH); EGL14.eglSwapBuffers(dpy, preview)
    }
  }

  private fun widthOf(s: EGLSurface): Int { val v = IntArray(1); EGL14.eglQuerySurface(dpy, s, EGL14.EGL_WIDTH, v, 0); return v[0] }
  private fun heightOf(s: EGLSurface): Int { val v = IntArray(1); EGL14.eglQuerySurface(dpy, s, EGL14.EGL_HEIGHT, v, 0); return v[0] }

  private fun draw(w: Int, h0: Int) {
    GLES20.glViewport(0, 0, w, h0)
    GLES20.glUseProgram(prog)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
    GLES20.glUniformMatrix4fv(uMtx, 1, false, mtx, 0)
    QUAD.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, QUAD); GLES20.glEnableVertexAttribArray(aPos)
    QUAD.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, QUAD); GLES20.glEnableVertexAttribArray(aTex)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
  }

  private fun program(): Int {
    fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
      val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0); check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(it)}" } }
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, VS)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, FS)); GLES20.glLinkProgram(p)
    val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0) { "link" }
    return p
  }

  /** Idempotent. Call AFTER the camera session is closed and BEFORE the encoder releases its input Surfaces. */
  fun release() {
    if (released) return
    val l = CountDownLatch(1)
    h.post {
      released = true
      runCatching { st?.setOnFrameAvailableListener(null) }
      if (dpy != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        for (es in encSurf) EGL14.eglDestroySurface(dpy, es); encSurf.clear()
        if (preview != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, preview); preview = EGL14.EGL_NO_SURFACE
        if (pbuf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, pbuf)
        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
        EGL14.eglReleaseThread(); EGL14.eglTerminate(dpy)
      }
      dpy = EGL14.EGL_NO_DISPLAY; ctx = EGL14.EGL_NO_CONTEXT
      runCatching { if (::cameraSurface.isInitialized) cameraSurface.release() }
      runCatching { st?.release() }; st = null
      l.countDown()
    }
    l.await(1, TimeUnit.SECONDS)
    thread.quitSafely()
  }

  companion object {
    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val VS = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uMtx; varying vec2 vTex;\n" +
      "void main() { gl_Position = aPos; vTex = (uMtx * aTex).xy; }"
    private const val FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTex;\n" +
      "uniform samplerExternalOES sTex; void main() { gl_FragColor = texture2D(sTex, vTex); }"
    private val QUAD: java.nio.FloatBuffer = ByteBuffer.allocateDirect(4 * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
      .apply { put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0) }
  }
}
