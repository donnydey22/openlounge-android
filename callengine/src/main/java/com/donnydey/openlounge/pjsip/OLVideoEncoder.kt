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

  /** H.264 runs on EVERY device (spec): hardware encoder preferred, else the platform MediaCodec H.264 encoder.
   *  AV1 is hardware-only (Tensor G3+). */
  fun encoders(mime: String): List<MediaCodecInfo> = if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) hwEncoders(mime).ifEmpty {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
      .sortedByDescending { score(it, mime) } } else hwEncoders(mime)

  /** Prefer encoders whose advertised caps match the spec (CBR + Surface input), e.g. c2.android.avc.encoder over OMX.google. */
  fun score(ci: MediaCodecInfo, mime: String): Int { val caps = ci.getCapabilitiesForType(mime)
    var s = 0
    if (caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) s += 2
    if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) s += 1
    return s }

  /** Constrained Baseline when advertised, else Baseline (same no-B-frame/CAVLC constraints for our use). */
  fun avcProfile(ci: MediaCodecInfo): Int { val pl = ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels
    return if (pl.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline }) MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
      else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }

  /** Mid-range = no encoder for this mime that supports 1080p30 (hardware first). */
  fun supports1080(mime: String): Boolean = encoders(mime).any { it.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(1920, 1080, 30.0) == true }

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
class OLSimulcastEncoder(val onEncoded: (layer: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) -> Unit = { _, _, _ -> }) {
  /** Annex-B AU per layer (SPS/PPS prepended on keyframes), 90 kHz ts -> PjsipEngine.nativeSfuVideoSend. */
  var onAnnexB: ((layer: Int, au: ByteArray, key: Boolean, ts90k: Long) -> Unit)? = null
  private val csd = arrayOfNulls<ByteArray>(3)
  val encoderName: String get() = OLVideoSpecV2.encoders(mime).firstOrNull()?.name ?: "none"
  val mime = OLVideoSpecV2.mime()
  val layers = OLVideoSpecV2.layers(mime)
  private val codecs = ArrayList<MediaCodec>()
  val surfaces = ArrayList<Surface>()
  private var drain: Thread? = null
  @Volatile private var running = false
  /** 3 = all encoder hints accepted; lower = hints the encoder rejected were dropped (reported by the harness). */
  var hintLevel = 3

  fun start() {
    val hw = OLVideoSpecV2.encoders(mime).firstOrNull() ?: throw VideoConfigException("no $mime encoder")
    try {
      for ((li, l) in layers.withIndex()) {
        // Spec keys (bitrate, keyframe interval, no B-frames, Surface input) are always set. Latency/priority/profile are
        // encoder hints: some encoders (OMX.google.h264 on API 30) reject them with 0x80001001, so drop hints step by step.
        var last: Exception? = null; var ok = false
        for (hints in 3 downTo 0) {
          val f = MediaFormat.createVideoFormat(mime, l.width, l.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, l.bps)
            // CBR per spec wherever the encoder advertises it; encoders without CBR keep default rate control at the same target.
            if (OLVideoSpecV2.score(hw, mime) >= 2) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, l.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, OLVideoSpecV2.KEYFRAME_S)
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (hints >= 3 && Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (hints >= 2) setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (hints >= 1 && mime == MediaFormat.MIMETYPE_VIDEO_AVC) setInteger(MediaFormat.KEY_PROFILE, OLVideoSpecV2.avcProfile(hw))
          }
          val c = MediaCodec.createByCodecName(hw.name)
          try { c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) } catch (e: Exception) { last = e; c.release(); continue }
          codecs += c; surfaces += c.createInputSurface(); c.start(); hintLevel = minOf(hintLevel, hints); ok = true; break
        }
        if (!ok) throw IllegalStateException("layer $li ${l.width}x${l.height}: ${last?.message}", last)
      }
    } catch (e: Exception) { stop(); throw VideoConfigException("MediaCodec configure failed (${hw.name} cbr=${OLVideoSpecV2.score(hw, mime) >= 2}): ${e.message}", e) }
    running = true
    drain = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // high-priority send
      val info = MediaCodec.BufferInfo()
      while (running) for ((i, c) in codecs.withIndex()) {
        val ix = try { c.dequeueOutputBuffer(info, 2_000) } catch (_: IllegalStateException) { -1 }
        if (ix >= 0) { c.getOutputBuffer(ix)?.let { onEncoded(i, it, info); annexB(i, it, info) }; c.releaseOutputBuffer(ix, false) }
      }
    }, "ol-video-send").also { it.start() }
  }

  private fun annexB(i: Int, b: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
    val cb = onAnnexB ?: return; if (info.size <= 0) return
    val d = ByteArray(info.size); b.position(info.offset); b.get(d, 0, info.size)
    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { csd[i] = d; return } // MediaCodec AVC output is Annex-B
    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    cb(i, if (key && csd[i] != null) csd[i]!! + d else d, key, info.presentationTimeUs * 9 / 100)
  }

  /** Teardown on every call end. */
  fun stop() {
    running = false; drain?.join(500); drain = null
    for (c in codecs) runCatching { c.stop() }.also { runCatching { c.release() } }
    for (s in surfaces) runCatching { s.release() }
    codecs.clear(); surfaces.clear()
  }
}

/** H.264 decode of Annex-B AUs via MediaCodec (ByteBuffer output). Counts frames, errors and non-black frames. */
class OLVideoDecoder {
  private var c: MediaCodec? = null
  @Volatile var decoded = 0; @Volatile var errors = 0; @Volatile var nonBlack = 0; @Volatile var firstAtMs = 0L
  private var gotKey = false
  fun decode(au: ByteArray, key: Boolean, ptsUs: Long) {
    if (c == null) { if (!key) return
      try { c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720), null, null, 0); start() } }
      catch (e: Exception) { errors++; return } }
    if (key) gotKey = true; if (!gotKey) return
    val d = c!!
    try {
      val ix = d.dequeueInputBuffer(10_000); if (ix >= 0) { d.getInputBuffer(ix)!!.apply { clear(); put(au) }; d.queueInputBuffer(ix, 0, au.size, ptsUs, 0) }
      val info = MediaCodec.BufferInfo()
      while (true) { val ox = d.dequeueOutputBuffer(info, 0); if (ox < 0) break
        if (info.size > 0) { decoded++; if (firstAtMs == 0L) firstAtMs = System.currentTimeMillis()
          if (decoded % 10 == 1) { val ob = d.getOutputBuffer(ox)!!; var sum = 0L; var n = 0; val lim = minOf(info.size, 1280 * 720)
            var k = info.offset; while (k < info.offset + lim) { sum += (ob.get(k).toInt() and 0xff); n++; k += 97 }; if (n > 0 && sum / n > 16) nonBlack++ } }
        d.releaseOutputBuffer(ox, false) }
    } catch (e: Exception) { errors++ }
  }
  fun close() { c?.let { runCatching { it.stop() }; runCatching { it.release() } }; c = null }
}
