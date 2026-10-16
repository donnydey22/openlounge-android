package com.donnydey.openlounge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** SessionBridge: the origin gate every bridge call (incl. nativeMediaCmd / nativeMsReply) goes through, and the
 *  OpenLoungeNativeMedia shim contract the web page relies on (same shape as iOS window.OpenLoungeNative). */
public class SessionBridgeNativeMediaTest {
    private static final String STG = "openlounge-staging-native-media-staging.up.railway.app";
    @Test public void prodOriginsTrustedInEveryFlavor() {
        for (boolean staging : new boolean[]{false, true}) {
            assertTrue(Prefs.isTrustedOrigin("https", "openlounge.app", staging, STG));
            assertTrue(Prefs.isTrustedOrigin("HTTPS", "WWW.OpenLounge.app", staging, STG));
            assertTrue(Prefs.isTrustedOrigin("https", "openlounge-production.up.railway.app", staging, STG));
        }
    }
    @Test public void stagingHostOnlyInStagingFlavor() {
        assertFalse(Prefs.isTrustedOrigin("https", STG, false, STG));
        assertTrue(Prefs.isTrustedOrigin("https", STG, true, STG));
    }
    @Test public void untrustedOriginsRejected() {
        assertFalse(Prefs.isTrustedOrigin("http", "openlounge.app", true, STG));
        assertFalse(Prefs.isTrustedOrigin("https", "openlounge.app.evil.com", true, STG));
        assertFalse(Prefs.isTrustedOrigin("https", "evil.up.railway.app", true, STG));
        assertFalse(Prefs.isTrustedOrigin("https", null, true, STG));
        assertFalse(Prefs.isTrustedOrigin(null, "openlounge.app", true, STG));
    }
    @Test public void nativeMediaShimContract() {
        String s = SessionBridge.NATIVE_MEDIA_SHIM;
        assertTrue(s.contains("window.OpenLoungeNativeMedia=Object.freeze("));
        assertTrue(s.contains("capabilities:Object.freeze(['nativeMedia'])"));
        assertTrue(s.contains("platform:'android'"));
        assertTrue(s.contains("N.nativeMediaCmd(id,JSON.stringify(cmd||{}))"));
        assertTrue(s.contains("N.nativeMsReply(String(id),ok===true,"));
        assertTrue(s.contains("window.__olNativeMediaResolve=function(id,ok,r,e)"));
        // idempotent: never re-installs over an existing shim, and only when the bridge exposes nativeMediaCmd
        assertTrue(s.contains("if(!N||typeof N.nativeMediaCmd!=='function'||window.OpenLoungeNativeMedia)return;"));
    }
}
