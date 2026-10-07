package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Map;

/** window.OpenLoungeNative contract + Keystore encryption + 0.2.3 plaintext migration, on a real device/emulator. */
@RunWith(AndroidJUnit4.class)
public class SessionBridgeTest {
    private static final String TOKEN =
            "{\"v\":1,\"refresh\":\"r_test_refresh_token_0123456789abcdef\",\"access\":\"a_test\",\"accessExp\":0}";
    private Context ctx;

    @Before
    public void reset() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.deleteSharedPreferences(SecureSessionStore.SECURE_PREFS);
        ctx.deleteSharedPreferences(SecureSessionStore.LEGACY_PREFS);
    }

    private SessionBridge trustedBridge() {
        SessionBridge b = new SessionBridge(ctx);
        b.setCurrentUrl("https://openlounge.app/");
        return b;
    }

    private File prefsFile(String name) {
        return new File(ctx.getApplicationInfo().dataDir, "shared_prefs/" + name + ".xml");
    }

    private String secureRaw() {
        Map<String, ?> all = ctx.getSharedPreferences(SecureSessionStore.SECURE_PREFS, Context.MODE_PRIVATE).getAll();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : all.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue());
        return sb.toString();
    }

    @Test
    public void setGetClear_roundTrip_survivesNewInstance() {
        SessionBridge b = trustedBridge();
        assertNull(b.getSession());
        b.setSession(TOKEN);
        assertEquals(TOKEN, b.getSession());
        assertTrue(b.hasStoredSession());
        // a fresh instance (no in-memory cache) decrypts from disk
        assertEquals(TOKEN, trustedBridge().getSession());
        b.clearSession();
        assertNull(b.getSession());
        assertNull(trustedBridge().getSession());
        assertFalse(b.hasStoredSession());
    }

    @Test
    public void ciphertextOnly_noPlaintextOnDisk() {
        trustedBridge().setSession(TOKEN);
        String raw = secureRaw();
        assertTrue(raw.startsWith("session_v1="));
        assertFalse(raw.contains("refresh"));
        assertFalse(raw.contains("r_test_refresh_token"));
        assertFalse(prefsFile(SecureSessionStore.LEGACY_PREFS).exists());
    }

    @Test
    public void untrustedOrigin_isRefused() {
        trustedBridge().setSession(TOKEN);
        SessionBridge evil = new SessionBridge(ctx);
        evil.setCurrentUrl("https://evil.example/");
        assertNull(evil.getSession());
        evil.setSession(TOKEN.replace("r_test", "r_evil"));
        evil.clearSession();
        evil.setCurrentUrl("http://openlounge.app/"); // not https
        assertNull(evil.getSession());
        assertEquals(TOKEN, trustedBridge().getSession());
    }

    @Test
    public void invalidValues_ignored() {
        SessionBridge b = trustedBridge();
        b.setSession(TOKEN);
        b.setSession("short");
        b.setSession(null);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 8200; i++) big.append('x');
        b.setSession(big.toString());
        assertEquals(TOKEN, b.getSession());
    }

    @Test
    public void migratesLegacyPlaintext_thenDeletesIt() {
        SharedPreferences legacy = ctx.getSharedPreferences(SecureSessionStore.LEGACY_PREFS, Context.MODE_PRIVATE);
        assertTrue(legacy.edit().putString(SecureSessionStore.LEGACY_KEY, TOKEN).commit());
        assertTrue(prefsFile(SecureSessionStore.LEGACY_PREFS).exists());

        SessionBridge b = trustedBridge(); // migration happens on construction
        assertEquals(TOKEN, b.getSession());
        assertFalse("plaintext file must be deleted", prefsFile(SecureSessionStore.LEGACY_PREFS).exists());
        assertNull(ctx.getSharedPreferences(SecureSessionStore.LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(SecureSessionStore.LEGACY_KEY, null));
        assertFalse(secureRaw().contains("r_test_refresh_token"));

        // next launch: still signed in, nothing to migrate
        assertEquals(TOKEN, trustedBridge().getSession());
        // and the web app can rotate / sign out as before
        String rotated = TOKEN.replace("r_test_refresh", "r_rotated_refresh");
        b.setSession(rotated);
        assertEquals(rotated, trustedBridge().getSession());
        b.clearSession();
        assertNull(trustedBridge().getSession());
    }

    @Test
    public void legacyPlaintext_neverOverridesNewerEncryptedValue() {
        trustedBridge().setSession(TOKEN);
        String stale = TOKEN.replace("r_test_refresh", "r_stale_refresh");
        ctx.getSharedPreferences(SecureSessionStore.LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit().putString(SecureSessionStore.LEGACY_KEY, stale).commit();
        assertEquals(TOKEN, trustedBridge().getSession());
        assertFalse(prefsFile(SecureSessionStore.LEGACY_PREFS).exists());
    }

    @Test
    public void seedIfEmpty_onlyWhenEmpty() {
        SessionBridge b = trustedBridge();
        b.seedIfEmpty(TOKEN);
        assertEquals(TOKEN, b.getSession());
        b.seedIfEmpty(TOKEN.replace("r_test", "r_other"));
        assertEquals(TOKEN, trustedBridge().getSession());
    }

    @Test
    public void undecryptableRecord_isDiscarded() {
        ctx.getSharedPreferences(SecureSessionStore.SECURE_PREFS, Context.MODE_PRIVATE)
                .edit().putString("session_v1", "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit();
        SessionBridge b = trustedBridge();
        assertNull(b.getSession());
        assertFalse(secureRaw().contains("session_v1"));
        b.setSession(TOKEN);
        assertNotNull(trustedBridge().getSession());
    }
}
