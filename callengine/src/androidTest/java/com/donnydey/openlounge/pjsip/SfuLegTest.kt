package com.donnydey.openlounge.pjsip

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Matrix harness: the real :callengine native engine (ol_sfu.c) on the emulator, connecting straight to a CI mediasoup
 *  PlainTransport. Params come from tests/pjsip-matrix/run-phone.mjs (PHONE=android) via `am instrument -e`. */
@RunWith(AndroidJUnit4::class)
class SfuLegTest {
    @Test fun audioLeg() {
        val a = InstrumentationRegistry.getArguments(); val ins = InstrumentationRegistry.getInstrumentation()
        val ip = a.getString("ip") ?: return // not invoked by the matrix runner -> no-op
        assertEquals(0, PjsipEngine.start())
        val r = PjsipEngine.nativeSfuAudioStart(ip, a.getString("port")!!.toInt(), a.getString("tx")!!, a.getString("rx")!!,
            a.getString("ssrc")!!.toLong(), a.getString("txPt")!!.toInt(), a.getString("rxPt")!!.toInt(), 440, true)
        val secs = a.getString("seconds")!!.toInt(); var sum = 0L; var n = 0; var max = 0
        if (r == 0) for (i in 0 until secs * 10) { Thread.sleep(100); val s = PjsipEngine.nativeSfuStats(); if (i > 20) { sum += s[3]; n++; if (s[3] > max) max = s[3] } }
        val s = if (r == 0) PjsipEngine.nativeSfuStats() else intArrayOf(0, 0, 0, 0)
        if (r == 0) PjsipEngine.nativeSfuStop()
        val json = if (r != 0) "{\"error\":$r}" else "{\"rxPkt\":${s[0]},\"rxLost\":${s[1]},\"txPkt\":${s[2]},\"avgLevel\":${if (n > 0) sum.toDouble() / n else 0.0},\"maxLevel\":$max}"
        ins.sendStatus(0, Bundle().apply { putString("olresult", json) })
        assertEquals(0, r)
    }

    /** Video spec v2 cell leg: audio (tone) + video on its own transport. A moving synthetic picture is drawn onto the
     *  PRODUCT encoder's input Surfaces (OLSimulcastEncoder, MediaCodec, 3 layers) and what the SFU forwards is decoded. */
    @Test fun videoLeg() {
        val a = InstrumentationRegistry.getArguments(); val ins = InstrumentationRegistry.getInstrumentation()
        val ip = a.getString("ip") ?: return; val vip = a.getString("vip") ?: return
        assertEquals(0, PjsipEngine.start())
        val r = PjsipEngine.nativeSfuAudioStart(ip, a.getString("port")!!.toInt(), a.getString("tx")!!, a.getString("rx")!!,
            a.getString("ssrc")!!.toLong(), a.getString("txPt")!!.toInt(), a.getString("rxPt")!!.toInt(), 440, true)
        val ssrcs = a.getString("vssrcs")!!.split(",").map { it.toLong() }.toLongArray()
        val vr = PjsipEngine.nativeSfuVideoStart(vip, a.getString("vport")!!.toInt(), a.getString("vtx")!!, a.getString("vrx")!!, ssrcs, a.getString("vpt")!!.toInt())
        var videoError = "null"; val enc = OLSimulcastEncoder()
        enc.onAnnexB = { layer, au, key, ts -> PjsipEngine.nativeSfuVideoSend(layer, au, au.size, ts, key) }
        try { enc.start() } catch (e: VideoConfigException) { videoError = "\"${e.message?.replace("\"", "'")}\"" }
        val dec = OLVideoDecoder(); val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val dt = Thread { while (running.get()) { val p = PjsipEngine.nativeSfuVideoPoll(); if (p == null) { Thread.sleep(2); continue }
            val ts = ((p[1].toLong() and 0xff) shl 24) or ((p[2].toLong() and 0xff) shl 16) or ((p[3].toLong() and 0xff) shl 8) or (p[4].toLong() and 0xff)
            dec.decode(p.copyOfRange(5, p.size), p[0].toInt() == 1, ts * 100 / 9) } }.also { it.start() }
        val secs = a.getString("seconds")!!.toInt(); var sum = 0L; var n = 0; var max = 0; var frame = 0
        val paint = android.graphics.Paint()
        val t0 = System.currentTimeMillis()
        if (r == 0 && vr == 0) while (System.currentTimeMillis() - t0 < secs * 1000L) {
            if (videoError == "null") for ((i, sf) in enc.surfaces.withIndex()) runCatching {
                val l = enc.layers[i]; val cv = sf.lockHardwareCanvas()
                val band = l.height / 8; for (k in 0 until 8) { val v = 40 + ((k * 23 + frame * 3) % 180); paint.color = android.graphics.Color.rgb(v, 255 - v, (v * 2) % 255)
                    cv.drawRect(0f, (k * band).toFloat(), l.width.toFloat(), ((k + 1) * band).toFloat(), paint) }
                sf.unlockCanvasAndPost(cv) }
            frame++
            if (frame % 3 == 0) { val s = PjsipEngine.nativeSfuStats(); if (System.currentTimeMillis() - t0 > 2000) { sum += s[3]; n++; if (s[3] > max) max = s[3] } }
            val next = t0 + frame * 1000L / 30; val d = next - System.currentTimeMillis(); if (d > 0) Thread.sleep(d)
        }
        enc.stop(); running.set(false); dt.join(1000)
        val s = if (r == 0) PjsipEngine.nativeSfuStats() else intArrayOf(0, 0, 0, 0)
        val v = if (vr == 0) PjsipEngine.nativeSfuVideoStats() else IntArray(12)
        PjsipEngine.nativeSfuVideoStop(); if (r == 0) PjsipEngine.nativeSfuStop(); dec.close()
        val decFps = if (dec.firstAtMs > 0) dec.decoded * 1000.0 / maxOf(500L, System.currentTimeMillis() - dec.firstAtMs) else 0.0
        val json = if (r != 0 || vr != 0) "{\"error\":$r,\"videoError\":${if (vr != 0) "$vr" else videoError}}" else
            "{\"rxPkt\":${s[0]},\"rxLost\":${s[1]},\"txPkt\":${s[2]},\"avgLevel\":${if (n > 0) sum.toDouble() / n else 0.0},\"maxLevel\":$max," +
            "\"codec\":\"${if (enc.mime.contains("av01")) "AV1" else "H264"}\",\"encoder\":\"${enc.encoderName}\",\"videoError\":$videoError," +
            "\"vRxPkt\":${v[0]},\"rxAu\":${v[1]},\"rxKey\":${v[2]},\"rxGap\":${v[3]},\"vTxPkt\":${v[4]},\"txAu\":[${v[5]},${v[6]},${v[7]}],\"txKey\":[${v[8]},${v[9]},${v[10]}],\"qDrop\":${v[11]}," +
            "\"decoded\":${dec.decoded},\"decFps\":$decFps,\"decErr\":${dec.errors},\"nonBlack\":${dec.nonBlack},\"srcFrames\":$frame}"
        ins.sendStatus(0, Bundle().apply { putString("olresult", json) })
        assertEquals(0, r); assertEquals(0, vr)
    }
}
