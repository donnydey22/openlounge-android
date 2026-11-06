package com.donnydey.openlounge.ringrtc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local OLX3DH-v1 key store. Private keys are AES-GCM wrapped by a non-exportable AndroidKeyStore key and kept
 * in app-private SharedPreferences (no backup). Publishes public bundle via rc:keys:publish; one-time prekeys are
 * deleted on use.
 */
class OlKeyStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("ol_rc_keys", Context.MODE_PRIVATE)
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrapKey(): SecretKey = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        generateKey() }
    private fun putSecret(k: String, v: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        p.edit().putString(k, OlCrypto.b64(c.iv) + "." + OlCrypto.b64(c.doFinal(v))).apply()
    }
    private fun getSecret(k: String): ByteArray? {
        val (iv, ct) = (p.getString(k, null) ?: return null).split(".").let { it[0] to it[1] }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, OlCrypto.unb64(iv))) }.doFinal(OlCrypto.unb64(ct))
    }

    @Synchronized fun deviceId(): String = p.getString("dev", null) ?: OlCrypto.b64(java.security.SecureRandom().generateSeed(16)).also { p.edit().putString("dev", it).apply() }

    @Synchronized fun identity(): OlIdentity {
        val dh = getSecret("ik.dh"); val sg = getSecret("ik.sig")
        if (dh != null && sg != null) return OlIdentity(OlCrypto.DhKey(dh), OlCrypto.SigKey(sg))
        val d = OlCrypto.genDh(); val s = OlCrypto.genSig()
        putSecret("ik.dh", d.priv); putSecret("ik.sig", s.priv)
        return OlIdentity(d, s)
    }

    /** rotate signed prekey + top up one-time prekeys; returns the rc:keys:publish payload */
    @Synchronized fun publishable(batch: Int = 20): JSONObject {
        val id = identity()
        val spkId = p.getInt("spkNext", 1); val spk = OlCrypto.genDh()
        putSecret("spk.$spkId", spk.priv); p.edit().putInt("spkNext", spkId + 1).apply()
        var next = p.getInt("opkNext", 1); val opks = JSONArray()
        repeat(batch) { val k = OlCrypto.genDh(); putSecret("opk.$next", k.priv); opks.put(JSONObject().put("id", next).put("pub", OlCrypto.b64(k.pub))); next++ }
        p.edit().putInt("opkNext", next).apply()
        return JSONObject().put("deviceId", deviceId()).put("ikDh", OlCrypto.b64(id.dh.pub)).put("ikSig", OlCrypto.b64(id.sig.pub))
            .put("spkId", spkId).put("spk", OlCrypto.b64(spk.pub)).put("spkSig", OlCrypto.b64(OlCrypto.sign(id.sig, spk.pub))).put("opks", opks)
    }

    @Synchronized fun spkPriv(id: Int): ByteArray = getSecret("spk.$id") ?: throw SecurityException("olx3dh: unknown spk")
    @Synchronized fun takeOpk(id: Int?): ByteArray? {
        if (id == null) return null
        val k = getSecret("opk.$id") ?: throw SecurityException("olx3dh: opk used or unknown")
        p.edit().remove("opk.$id").apply()
        return k
    }

    companion object { private const val ALIAS = "ol_rc_wrap_v1" }
}
