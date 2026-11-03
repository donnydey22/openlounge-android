// Video spec v2 (Donny Nov 3 6:51 AM, PLAN §14). Android: MediaCodec H.264 everywhere; AV1 only on Tensor G3+ with a
// hardware AV1 encoder. 3 layers at 120/600/1600 kbps, top layer capped at 720p on mid-range chips. CBR, keyframe
// every 1 s, B-frames off, Surface input, realtime priority. If MediaCodec fails to configure, the call fails.
package com.donnydey.openlounge.pjsip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.view.Surface

class VideoConfigException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)

data class OLVideoLayer(val width: Int, val height: Int, val bps: Int, val fps: Int)

object OLVideoSpecV2 {
  const val KEYFRAME_S = 1
  private val BPS = intArrayOf(120_000, 600_000, 1_600_000)

  fun isTensorG3OrNewer(): Boolean {
    if (Build.VERSION.SDK_INT < 31) return false
    val m = Regex("Tensor G(\\d+)").find(Build.SOC_MODEL ?: "") ?: return false
    return m.groupValues[1].toInt() >= 3
  }

  /** Mid-range = no hardware encoder for this mime that supports 1080p30. */
  fun supports1080(mime: String): Boolean = hwEncoders(mime).any { it.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(1920, 1080, 30.0) == true }

  fun hwEncoders(mime: String): List<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
    it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } && (Build.VERSION.SDK_INT < 29 || (it.isHardwareAccelerated && !it.isSoftwareOnly))
  }

  fun mime(): String = if (isTensorG3OrNewer() && hwEncoders(MediaFormat.MIMETYPE_VIDEO_AV1).isNotEmpty()) MediaFormat.MIMETYPE_VIDEO_AV1 else MediaFormat.MIMETYPE_VIDEO_AVC

  fun layers(mime: String): List<OLVideoLayer> {
    val top = if (supports1080(mime)) OLVideoLayer(1920, 1080, BPS[2], 30) else OLVideoLayer(1280, 720, BPS[2], 30)
    return listOf(OLVideoLayer(320, 180, BPS[0], 15), OLVideoLayer(1280, 720, BPS[1], 30).let { if (top.height == 720) OLVideoLayer(640, 360, BPS[1], 30) else it }, top)
  }
}

/** One hardware MediaCodec per layer, each with its own input Surface. Throws VideoConfigException -> fail the call. */
class OLSimulcastEncoder(val onEncoded: (layer: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) -> Unit) {
  val mime = OLVideoSpecV2.mime()
  val layers = OLVideoSpecV2.layers(mime)
  private val codecs = ArrayList<MediaCodec>()
  val surfaces = ArrayList<Surface>()
  private var drain: Thread? = null
  @Volatile private var running = false

  fun start() {
    val hw = OLVideoSpecV2.hwEncoders(mime).firstOrNull() ?: throw VideoConfigException("no hardware $mime encoder")
    try {
      for (l in layers) {
        val f = MediaFormat.createVideoFormat(mime, l.width, l.height).apply {
          setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
          setInteger(MediaFormat.KEY_BIT_RATE, l.bps)
          setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
          setInteger(MediaFormat.KEY_FRAME_RATE, l.fps)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OLVideoSpecV2.KEYFRAME_S)
          setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
          if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
          setInteger("max-bframes", 0)
          if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
          if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
        }
        val c = MediaCodec.createByCodecName(hw.name)
        codecs += c
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surfaces += c.createInputSurface()
        c.start()
      }
    } catch (e: Exception) { stop(); throw VideoConfigException("MediaCodec configure failed: ${e.message}", e) }
    running = true
    drain = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // high-priority send
      val info = MediaCodec.BufferInfo()
      while (running) for ((i, c) in codecs.withIndex()) {
        val ix = try { c.dequeueOutputBuffer(info, 2_000) } catch (_: IllegalStateException) { -1 }
        if (ix >= 0) { c.getOutputBuffer(ix)?.let { onEncoded(i, it, info) }; c.releaseOutputBuffer(ix, false) }
      }
    }, "ol-video-send").also { it.start() }
  }

  /** Teardown on every call end. */
  fun stop() {
    running = false; drain?.join(500); drain = null
    for (c in codecs) runCatching { c.stop() }.also { runCatching { c.release() } }
    for (s in surfaces) runCatching { s.release() }
    codecs.clear(); surfaces.clear()
  }
}
