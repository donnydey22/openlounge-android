package com.donnydey.openlounge.pjsip
// Open Lounge PJSIP engine (pjproject 2.17 prefab). One path; SRTP SDES mandatory (keys only in
// SDP a=crypto); adaptive pjmedia_jbuf (progressive discard) + Opus PLC/FEC; full teardown on every call end.
object PjsipEngine {
    @Volatile private var loaded = false
    @Volatile private var started = false
    private fun load() { if (!loaded) { System.loadLibrary("pjproject"); System.loadLibrary("olpjsip"); loaded = true } }
    @JvmStatic external fun nativeStart(jbMinPre: Int, jbMaxPre: Int, jbMax: Int): Int
    @JvmStatic external fun nativeOpenSound(): Int
    @JvmStatic external fun nativeTeardown()
    @JvmStatic external fun nativeVideoQuickHd(): Int
    @JvmStatic external fun nativeVideoSteady(): Int
    // Phone -> mediasoup PlainTransport audio leg (SDES keys base64, AES_CM_128_HMAC_SHA1_80). toneHz>0 = CI test tone.
    @JvmStatic external fun nativeSfuAudioStart(ip: String, port: Int, txKeyB64: String, rxKeyB64: String, ssrc: Long, txPt: Int, rxPt: Int, toneHz: Int, nullSnd: Boolean): Int
    @JvmStatic external fun nativeSfuStats(): IntArray // rxPkt, rxLost, txPkt, rxLevel
    @JvmStatic external fun nativeSfuStop()
    // Video spec v2 leg (ol_sfu_video.c): own "video" PlainTransport, own SRTP key, 3 simulcast SSRCs
    @JvmStatic external fun nativeSfuVideoStart(ip: String, port: Int, txKeyB64: String, rxKeyB64: String, ssrcs: LongArray, pt: Int): Int
    @JvmStatic external fun nativeSfuVideoSend(layer: Int, annexB: ByteArray, len: Int, ts90k: Long, key: Boolean): Int
    @JvmStatic external fun nativeSfuVideoPoll(): ByteArray? // [key, ts BE x4, annexB...]
    @JvmStatic external fun nativeSfuVideoStats(): IntArray // rxPkt, rxAu, rxKey, rxGap, txPkt, txAu0..2, txKey0..2, queueDrop
    @JvmStatic external fun nativeSfuVideoStop()
    @JvmStatic external fun nativeSfuVideoTakeKeyReq(): Int
    fun ensureLoaded() = load()
    /** QuickHD: 1080p/2.5 Mbps for the first 4 s of a video call, then 720p/1.2 Mbps. */
    @JvmStatic fun startVideoBurst() { start(); nativeVideoQuickHd()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ nativeVideoSteady() }, 4000) }
    /** AV1 only where a hardware decoder exists (negotiated per device via signaling). */
    @JvmStatic fun hardwareAv1(): Boolean = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
        .any { !it.isEncoder && (android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated) && it.supportedTypes.any { t -> t.equals("video/av01", true) } }
    @JvmStatic fun start(): Int { load(); if (started) return 0; val r = nativeStart(1, 16, 50); started = r == 0; return r }
    /** Called only once the ConnectionService call is active (Android's didActivate equivalent). */
    @JvmStatic fun openSound(): Int { start(); return nativeOpenSound() }
    @JvmStatic fun teardown() { if (started) nativeTeardown() }
}
