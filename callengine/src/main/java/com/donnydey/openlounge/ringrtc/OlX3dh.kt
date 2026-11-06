package com.donnydey.openlounge.ringrtc

import android.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OL clean-room X3DH + symmetric-ratchet call session ("OLX3DH-v1") — the libsignal session *model* (identity key,
 * signed prekey, one-time prekey, X3DH, per-message keys), NOT libsignal (AGPL). Byte-identical to web olx3dh.ts and
 * iOS OLX3DH.swift; see docs/calling/RINGRTC-SESSION.md + vectors. Crypto: BouncyCastle (MIT) X25519/Ed25519 so
 * API 26+ works (platform X25519 is API 33+); HMAC/AES-GCM from the platform.
 */
object OlCrypto {
    private val rnd = SecureRandom()
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    class DhKey(val priv: ByteArray) { val pub = ByteArray(32).also { X25519.generatePublicKey(priv, 0, it, 0) } }
    fun genDh() = DhKey(ByteArray(32).also { X25519.generatePrivateKey(rnd, it) })
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw SecurityException("olx3dh: low-order point")
        return out
    }
    class SigKey(val priv: ByteArray) { val pub: ByteArray = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded }
    fun genSig() = SigKey(ByteArray(32).also { rnd.nextBytes(it) })
    fun sign(k: SigKey, msg: ByteArray): ByteArray = Ed25519Signer().run { init(true, Ed25519PrivateKeyParameters(k.priv, 0)); update(msg, 0, msg.size); generateSignature() }
    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = runCatching {
        Ed25519Signer().run { init(false, Ed25519PublicKeyParameters(pub, 0)); update(msg, 0, msg.size); verifySignature(sig) } }.getOrDefault(false)

    fun hkdf(ikm: ByteArray, info: String, len: Int): ByteArray = ByteArray(len).also {
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, ByteArray(32), info.toByteArray())) }.generateBytes(it, 0, len) }
    fun hmac(key: ByteArray, b: Int): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(byteArrayOf(b.toByte())) }
    fun gcm(enc: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run { init(if (enc) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(data) }
}

class OlIdentity(val dh: OlCrypto.DhKey, val sig: OlCrypto.SigKey)

data class OlBundle(val deviceId: String, val ikDh: ByteArray, val ikSig: ByteArray, val spkId: Int, val spk: ByteArray, val spkSig: ByteArray, val opkId: Int?, val opk: ByteArray?) {
    companion object {
        fun from(j: JSONObject) = OlBundle(j.getString("deviceId"), OlCrypto.unb64(j.getString("ikDh")), OlCrypto.unb64(j.getString("ikSig")), j.getInt("spkId"),
            OlCrypto.unb64(j.getString("spk")), OlCrypto.unb64(j.getString("spkSig")), if (j.has("opkId")) j.getInt("opkId") else null, j.optString("opk").takeIf { it.isNotEmpty() }?.let(OlCrypto::unb64))
    }
}

class OlSession private constructor(private var sendCk: ByteArray, private var recvCk: ByteArray, private val ad: ByteArray, private var pre: JSONObject?) {
    private var sendN = 0; private var recvN = 0
    private val skipped = LinkedHashMap<Int, ByteArray>()

    companion object {
        private val FF = ByteArray(32) { 0xFF.toByte() }
        private fun cat(vararg a: ByteArray) = a.fold(ByteArray(0)) { x, y -> x + y }

        fun initiate(me: OlIdentity, b: OlBundle): OlSession {
            if (!OlCrypto.verify(b.ikSig, b.spk, b.spkSig)) throw SecurityException("olx3dh: bad signed prekey signature")
            val ek = OlCrypto.genDh()
            var ikm = cat(FF, OlCrypto.dh(me.dh.priv, b.spk), OlCrypto.dh(ek.priv, b.ikDh), OlCrypto.dh(ek.priv, b.spk))
            if (b.opk != null) ikm += OlCrypto.dh(ek.priv, b.opk)
            val ch = OlCrypto.hkdf(OlCrypto.hkdf(ikm, "OLX3DH-v1", 32), "OLCallChain-v1", 64)
            val pre = JSONObject().put("ikDh", OlCrypto.b64(me.dh.pub)).put("ikSig", OlCrypto.b64(me.sig.pub)).put("ek", OlCrypto.b64(ek.pub)).put("spkId", b.spkId)
            if (b.opk != null && b.opkId != null) pre.put("opkId", b.opkId)
            return OlSession(ch.copyOfRange(0, 32), ch.copyOfRange(32, 64), cat(me.dh.pub, b.ikDh), pre)
        }

        fun respond(me: OlIdentity, spkPriv: ByteArray, opkPriv: ByteArray?, pre: JSONObject): OlSession {
            val ikA = OlCrypto.unb64(pre.getString("ikDh")); val ek = OlCrypto.unb64(pre.getString("ek"))
            var ikm = cat(FF, OlCrypto.dh(spkPriv, ikA), OlCrypto.dh(me.dh.priv, ek), OlCrypto.dh(spkPriv, ek))
            if (opkPriv != null) ikm += OlCrypto.dh(opkPriv, ek)
            val ch = OlCrypto.hkdf(OlCrypto.hkdf(ikm, "OLX3DH-v1", 32), "OLCallChain-v1", 64)
            return OlSession(ch.copyOfRange(32, 64), ch.copyOfRange(0, 32), cat(ikA, me.dh.pub), null)
        }
        internal fun header(n: Int): ByteArray = ByteBuffer.allocate(5).put(1).putInt(n).array()
    }

    fun confirmed() { pre = null }

    fun seal(plain: ByteArray): JSONObject {
        val n = sendN++
        val mk = OlCrypto.hmac(sendCk, 1); val nonce = OlCrypto.hmac(sendCk, 3).copyOf(12)
        sendCk = OlCrypto.hmac(sendCk, 2)
        val ct = OlCrypto.gcm(true, mk, nonce, ad + header(n), plain)
        return JSONObject().put("v", 1).put("n", n).put("ct", OlCrypto.b64(ct)).also { e -> pre?.let { e.put("pre", it) } }
    }

    fun open(e: JSONObject): ByteArray {
        val n = e.optInt("n", -1)
        if (e.optInt("v") != 1 || n < 0) throw SecurityException("olx3dh: bad envelope")
        val ck: ByteArray
        if (n < recvN) ck = skipped.remove(n) ?: throw SecurityException("olx3dh: replay")
        else {
            if (n - recvN > 256) throw SecurityException("olx3dh: too far ahead")
            while (recvN < n) { skipped[recvN++] = recvCk; recvCk = OlCrypto.hmac(recvCk, 2) }
            ck = recvCk; recvCk = OlCrypto.hmac(recvCk, 2); recvN++
            while (skipped.size > 256) skipped.remove(skipped.keys.first())
        }
        val mk = OlCrypto.hmac(ck, 1); val nonce = OlCrypto.hmac(ck, 3).copyOf(12)
        return OlCrypto.gcm(false, mk, nonce, ad + header(n), OlCrypto.unb64(e.getString("ct")))
    }
}
