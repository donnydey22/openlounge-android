package com.donnydey.openlounge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Session token storage encrypted with a non-exportable AES-256-GCM key held in the Android Keystore.
 *
 * Why not androidx.security EncryptedSharedPreferences: the whole security-crypto library is deprecated
 * (1.1.0) and its Tink keysets are a known source of unrecoverable "keyset corrupted" crashes. This is the
 * same idea with no dependency: the Keystore key never leaves secure hardware / the keystore daemon, only
 * ciphertext reaches disk.
 *
 * On disk (shared_prefs/openlounge_session_secure.xml): "session_v1" = base64( 0x01 | iv(12) | ciphertext+tag ).
 * The record key and format version are bound as GCM AAD.
 *
 * One-time migration: 0.2.3 and earlier kept the token in plain shared_prefs/openlounge_session.xml
 * ("session"). On first use it is encrypted here, read back and compared, and only then is the plaintext copy
 * removed and its file deleted, so the update never signs anyone out. If the Keystore is unusable the
 * plaintext is left alone and the move is retried on the next launch.
 *
 * Not thread-safe by itself: SessionBridge serialises access.
 */
final class SecureSessionStore {
    private static final String TAG = "OLSecureStore";

    static final String SECURE_PREFS = "openlounge_session_secure";
    static final String LEGACY_PREFS = "openlounge_session";
    static final String LEGACY_KEY = "session";
    private static final String RECORD_KEY = "session_v1";
    private static final String KEY_ALIAS = "openlounge_session_aes_v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT_V1 = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "com.donnydey.openlounge/session_v1/1".getBytes(StandardCharsets.UTF_8);

    private final Context app;
    private final SharedPreferences secure;

    // decrypted value cached for the process lifetime (avoids a Keystore round trip on every bridge call)
    private boolean cacheValid = false;
    private String cache = null;
    // plaintext copy still on disk because the Keystore failed during migration
    private boolean legacyPending = false;

    SecureSessionStore(Context ctx) {
        app = ctx.getApplicationContext();
        secure = app.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE);
        migrateLegacyPlaintext();
    }

    /** @return the stored session, or null (also null if the ciphertext can no longer be decrypted). */
    String get() {
        if (cacheValid) return cache;
        String b64 = secure.getString(RECORD_KEY, null);
        if (b64 == null) {
            cache = null;
            cacheValid = true;
            return null;
        }
        try {
            cache = decrypt(b64);
            cacheValid = true;
            return cache;
        } catch (Exception e) {
            // key gone (data restored onto another device, keystore reset, lock-screen wipe…) or damaged record:
            // unrecoverable, so drop it — the web app then shows sign-in instead of failing forever.
            Log.w(TAG, "stored session unreadable, discarding: " + e.getClass().getSimpleName());
            secure.edit().remove(RECORD_KEY).commit();
            cache = null;
            cacheValid = true;
            return null;
        }
    }

    /** Durably store value (commit()). @return true if it is on disk encrypted. */
    boolean put(String value) {
        try {
            String b64 = encrypt(value);
            boolean ok = secure.edit().putString(RECORD_KEY, b64).commit();
            cache = value;
            cacheValid = true;
            if (!ok) Log.w(TAG, "commit failed; session held in memory only");
            if (legacyPending) {
                removeLegacyPlaintext(); // stale now that a newer value exists
                legacyPending = false;
            }
            return ok;
        } catch (Exception e) {
            // Keystore unusable on this device right now. Never fall back to plaintext on disk; keep the value for
            // this process, and drop the now-stale ciphertext so a rotated-away refresh token is never resurrected.
            Log.w(TAG, "encrypt failed; session held in memory only: " + e.getClass().getSimpleName());
            secure.edit().remove(RECORD_KEY).commit();
            removeLegacyPlaintext();
            legacyPending = false;
            cache = value;
            cacheValid = true;
            return false;
        }
    }

    void clear() {
        secure.edit().remove(RECORD_KEY).commit();
        removeLegacyPlaintext();
        legacyPending = false;
        cache = null;
        cacheValid = true;
    }

    // ---- migration ----

    private void migrateLegacyPlaintext() {
        SharedPreferences legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String plain = legacy.getString(LEGACY_KEY, null);
        if (plain == null) {
            if (!legacy.getAll().isEmpty()) legacy.edit().clear().commit();
            deleteLegacyFile();
            return;
        }
        boolean hasSecure = secure.contains(RECORD_KEY) && get() != null;
        if (!hasSecure) {
            if (plain.length() < 20) {
                removeLegacyPlaintext(); // junk, nothing worth keeping
                return;
            }
            try {
                String b64 = encrypt(plain);
                if (!plain.equals(decrypt(b64))) throw new IllegalStateException("round-trip mismatch");
                if (!secure.edit().putString(RECORD_KEY, b64).commit()) throw new IllegalStateException("commit failed");
                cache = plain;
                cacheValid = true;
            } catch (Exception e) {
                // keep the plaintext so the user stays signed in; retried next launch
                Log.w(TAG, "migration deferred: " + e.getClass().getSimpleName());
                legacyPending = true;
                cache = plain;
                cacheValid = true;
                return;
            }
        }
        // encrypted copy verified on disk (or a newer one already there): the plaintext copy goes
        removeLegacyPlaintext();
        Log.i(TAG, "migrated plaintext session to Keystore-encrypted storage");
    }

    private void removeLegacyPlaintext() {
        SharedPreferences legacy = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        if (!legacy.getAll().isEmpty()) legacy.edit().clear().commit();
        deleteLegacyFile();
    }

    private void deleteLegacyFile() {
        try {
            app.deleteSharedPreferences(LEGACY_PREFS); // API 24+, minSdk is 24
        } catch (Exception ignored) {
        }
    }

    // ---- crypto ----

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true) // Keystore picks a fresh IV per encryption
                .build());
        return kg.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, key());
        c.updateAAD(AAD);
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = c.getIV();
        if (iv == null || iv.length != IV_LEN) throw new IllegalStateException("unexpected IV");
        byte[] out = new byte[1 + IV_LEN + ct.length];
        out[0] = FORMAT_V1;
        System.arraycopy(iv, 0, out, 1, IV_LEN);
        System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(String b64) throws Exception {
        byte[] in = Base64.decode(b64, Base64.NO_WRAP);
        if (in.length < 1 + IV_LEN + TAG_BITS / 8 || in[0] != FORMAT_V1) throw new IllegalStateException("bad record");
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(in, 1, 1 + IV_LEN)));
        c.updateAAD(AAD);
        byte[] pt = c.doFinal(in, 1 + IV_LEN, in.length - 1 - IV_LEN);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
