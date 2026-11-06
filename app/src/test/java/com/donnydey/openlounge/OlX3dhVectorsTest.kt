package com.donnydey.openlounge

import com.donnydey.openlounge.ringrtc.OlBundle
import com.donnydey.openlounge.ringrtc.OlCrypto
import com.donnydey.openlounge.ringrtc.OlIdentity
import com.donnydey.openlounge.ringrtc.OlSession
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** OLX3DH-v1 parity with web olx3dh.ts / node:crypto vectors (tests/calls/olx3dh-vectors.json in aud-openlounge). */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class OlX3dhVectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("olx3dh-vectors.json").readText())
    private fun hx(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun priv(n: String) = hx(v.getJSONObject("privHex").getString(n))
    private fun pub(n: String) = hx(v.getJSONObject("pubHex").getString(n))

    @Test fun publicKeysMatch() { for (n in listOf("ika", "eka", "ikb", "spkb", "opkb")) assertArrayEquals(pub(n), OlCrypto.DhKey(priv(n)).pub) }

    @Test fun respondOpensWebVector() {
        val me = OlIdentity(OlCrypto.DhKey(priv("ikb")), OlCrypto.genSig())
        val pre = JSONObject().put("ikDh", OlCrypto.b64(pub("ika"))).put("ek", OlCrypto.b64(pub("eka"))).put("spkId", 1).put("opkId", 1)
        val s = OlSession.respond(me, priv("spkb"), priv("opkb"), pre)
        val m = v.getJSONObject("msg0")
        assertEquals(m.getString("plaintext"), String(s.open(JSONObject().put("v", 1).put("n", 0).put("ct", m.getString("ctB64u")))))
        assertThrows(SecurityException::class.java) { s.open(JSONObject().put("v", 1).put("n", 0).put("ct", m.getString("ctB64u"))) }
    }

    @Test fun roundTripOutOfOrderAndSignedPrekey() {
        val a = OlIdentity(OlCrypto.genDh(), OlCrypto.genSig()); val b = OlIdentity(OlCrypto.genDh(), OlCrypto.genSig())
        val spk = OlCrypto.genDh(); val opk = OlCrypto.genDh()
        val bundle = OlBundle("devB", b.dh.pub, b.sig.pub, 7, spk.pub, OlCrypto.sign(b.sig, spk.pub), 3, opk.pub)
        val sa = OlSession.initiate(a, bundle)
        val e0 = sa.seal("offer".toByteArray()); val e1 = sa.seal("ice".toByteArray())
        val sb = OlSession.respond(b, spk.priv, opk.priv, e0.getJSONObject("pre"))
        assertEquals("ice", String(sb.open(e1))); assertEquals("offer", String(sb.open(e0)))
        assertEquals("answer", String(sa.open(sb.seal("answer".toByteArray()))))
        assertThrows(SecurityException::class.java) { OlSession.initiate(a, bundle.copy(spk = opk.pub)) }
    }
}
