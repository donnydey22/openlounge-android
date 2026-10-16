package com.donnydey.openlounge;

import android.content.Context;
import android.net.Uri;
import android.webkit.JavascriptInterface;

/**
 * window.OpenLoungeNative — the durable native token store the web app mirrors its session into
 * (see src/lib/session.ts for the contract: getSession() → string|null, setSession(string), clearSession()).
 *
 * Since 0.2.4 the value is encrypted at rest with an Android Keystore AES-GCM key (SecureSessionStore); a
 * plaintext session left by 0.2.3 or earlier is migrated on first use and the plaintext copy deleted. Writes are
 * commit()ed, so a rotated refresh token is on disk before the call returns: it survives app kills, WebView
 * storage eviction and origin changes (openlounge.app vs *.up.railway.app have separate localStorage).
 *
 * Only pages from Open Lounge's own origins may use it: the WebView's current origin is tracked on the
 * UI thread (setCurrentUrl) and checked on every call (JavascriptInterface methods run on a binder thread,
 * so store access is synchronized).
 */
public final class SessionBridge {
    public static final String JS_NAME = "OpenLoungeNative";

    /** Activity-side actions for the extra bridge methods (push, share, contacts, haptics). */
    public interface Host {
        String pushToken();
        void share(String text, String url);
        void requestContacts();
        String contactsPermission();
        void openAppSettings();
        void requestNotificationPermission();
        void haptic(String type);
        void phoneAuthStart(String reqId, String e164);
        void phoneAuthConfirm(String reqId, String code);
        void setBadge(int count);
        void openAuth(String url);
        boolean biometricsAvailable();
        boolean biometricsEnabled();
        void biometricsSetEnabled(String reqId, boolean on);
        /** 0.3.0: one-off prompt (quick sign-in with screen name); result true only on success. */
        void biometricsAuthenticate(String reqId);
        /** 0.3.0: light|medium|heavy|success|warning|error|selection (HAPTICS_BRIDGE.md). */
        void haptics(String type);
        /** 0.3.0: external link → Custom Tab; openlounge.app → in the WebView (BROWSER_BRIDGE.md). */
        void openBrowser(String url);
        /** 0.3.1: page → native call messages {type:"call:*"} (NATIVE_SPEC.md §5). */
        void callMessage(String json);
        /** 0.3.16: answer a page nativeMedia(cmd) promise (resultJson is a JSON literal) */
        void nativeMediaResolve(String reqId, boolean ok, String resultJson, String error);
    }

    private final SecureSessionStore store;
    private volatile String currentUrl = "";
    private final Host host;
    private static final Host NOOP = new Host() {
        public String pushToken() { return ""; }
        public void share(String t, String u) {}
        public void requestContacts() {}
        public String contactsPermission() { return "denied"; }
        public void openAppSettings() {}
        public void requestNotificationPermission() {}
        public void haptic(String type) {}
        public void phoneAuthStart(String id, String p) {}
        public void phoneAuthConfirm(String id, String c) {}
        public void setBadge(int n) {}
        public void openAuth(String u) {}
        public boolean biometricsAvailable() { return false; }
        public boolean biometricsEnabled() { return false; }
        public void biometricsSetEnabled(String id, boolean on) {}
        public void biometricsAuthenticate(String id) {}
        public void haptics(String type) {}
        public void openBrowser(String url) {}
        public void callMessage(String json) {}
        public void nativeMediaResolve(String id, boolean ok, String r, String e) {}
    };

    public SessionBridge(Context ctx) {
        this(ctx, null);
    }

    public SessionBridge(Context ctx, Host host) {
        store = new SecureSessionStore(ctx);
        this.host = host != null ? host : NOOP;
    }

    /** Called from the UI thread whenever the WebView navigates. */
    public void setCurrentUrl(String url) {
        currentUrl = url == null ? "" : url;
    }

    private boolean trusted() {
        return Prefs.isTrustedOrigin(Uri.parse(currentUrl));
    }

    @JavascriptInterface
    public String getSession() {
        if (!trusted()) return null;
        synchronized (store) {
            return store.get();
        }
    }

    @JavascriptInterface
    public void setSession(String value) {
        if (!trusted() || value == null || value.length() < 20 || value.length() > 8192) return;
        synchronized (store) {
            store.put(value);
        }
    }

    @JavascriptInterface
    public void clearSession() {
        if (!trusted()) return;
        synchronized (store) {
            store.clear();
        }
    }

    // ---- 0.2.5: push token, share sheet, contacts (opt-in), haptics ----

    @JavascriptInterface
    public String getPlatform() { return "android"; }

    @JavascriptInterface
    public String getPushToken() { return trusted() ? host.pushToken() : ""; }

    @JavascriptInterface
    public void share(String text, String url) {
        if (trusted()) host.share(text == null ? "" : text, url == null ? "" : url);
    }

    @JavascriptInterface
    public void requestContacts() { if (trusted()) host.requestContacts(); }

    @JavascriptInterface
    public String contactsPermission() { return trusted() ? host.contactsPermission() : "denied"; }

    @JavascriptInterface
    public void openAppSettings() { if (trusted()) host.openAppSettings(); }

    @JavascriptInterface
    public void requestNotificationPermission() { if (trusted()) host.requestNotificationPermission(); }

    @JavascriptInterface
    public void haptic(String type) { if (trusted()) host.haptic(type == null ? "light" : type); }

    // ---- 0.3.0: haptics + in-app browser contracts (see /workspace/native/*_BRIDGE.md) ----
    @JavascriptInterface
    public boolean haptics(String type) {
        if (!trusted() || !NativeHaptics.isKnown(type)) return false;
        host.haptics(type);
        return true;
    }

    @JavascriptInterface
    public boolean openBrowser(String url) {
        if (!trusted() || url == null || url.length() > 4096) return false;
        host.openBrowser(url);
        return true;
    }

    /** Feature list for the page (JSON array string; Android JS interfaces can't return arrays). */
    @JavascriptInterface
    public String capabilities() {
        return "[\"session\",\"haptic\",\"haptics\",\"browser\",\"camera\",\"openAuth\",\"push\",\"share\",\"contacts\",\"badge\",\"biometrics\",\"phoneAuth\",\"calls\",\"nativeMedia\"]";
    }

    @JavascriptInterface
    public String getVersion() { return BuildConfig.VERSION_NAME; }

    @JavascriptInterface
    public String getBuildNumber() { return String.valueOf(BuildConfig.VERSION_CODE); }

    // ---- 0.3.1: call messages (engine.ts postNative → OpenLoungeNative.postMessage(JSON string)) ----
    @JavascriptInterface
    public void postMessage(String json) {
        if (!trusted() || json == null || json.length() > 4096) return;
        host.callMessage(json);
    }

    // ---- 0.3.16: native call media (NativeCallMedia.kt). The page talks to it through window.OpenLoungeNativeMedia
    // (NATIVE_MEDIA_SHIM: Promise-based nativeMedia(cmd) + msReply(id, ok, result, error), capabilities ["nativeMedia"]),
    // the same contract as the iOS app's window.OpenLoungeNative; these flat string methods are its transport.
    @JavascriptInterface
    public void nativeMediaCmd(String reqId, String cmdJson) {
        if (reqId == null || reqId.length() > 100) return;
        if (!trusted() || cmdJson == null || cmdJson.length() > 65536) {
            host.nativeMediaResolve(reqId, false, "null", "nativeMedia unavailable");
            return;
        }
        NativeCallMedia.handle(cmdJson, (ok, result, error) -> {
            host.nativeMediaResolve(reqId, ok, result, error);
            return kotlin.Unit.INSTANCE;
        });
    }

    @JavascriptInterface
    public void nativeMsReply(String id, boolean ok, String resultJson, String error) {
        if (!trusted() || id == null || id.length() > 100 || (resultJson != null && resultJson.length() > 262144)) return;
        NativeCallMedia.onMsReply(id, ok, resultJson, error);
    }

    public static final String NATIVE_MEDIA_SHIM = "(function(){try{var N=window.OpenLoungeNative;"
            + "if(!N||typeof N.nativeMediaCmd!=='function'||window.OpenLoungeNativeMedia)return;"
            + "var p={},s=0;window.__olNativeMediaResolve=function(id,ok,r,e){var f=p[id];if(!f)return;delete p[id];"
            + "ok?f[0](r):f[1](new Error(e||'native media error'))};"
            + "window.OpenLoungeNativeMedia=Object.freeze({capabilities:Object.freeze(['nativeMedia']),platform:'android',"
            + "nativeMedia:function(cmd){return new Promise(function(res,rej){var id='nm'+Date.now().toString(36)+(++s);"
            + "p[id]=[res,rej];try{N.nativeMediaCmd(id,JSON.stringify(cmd||{}))}catch(e){delete p[id];rej(e)}})},"
            + "msReply:function(id,ok,result,error){try{N.nativeMsReply(String(id),ok===true,JSON.stringify(result===undefined?null:result),"
            + "error==null?'':String(error).slice(0,200))}catch(e){}}})}catch(e){}})();";

    /** Document-start flag: the page only posts call:* messages to wrappers that set it (build 18 / 0.3.1). */
    public static final String CALLS_FLAG = "(function(){try{if(window.OpenLoungeNative&&typeof window.OpenLoungeNative.postMessage==='function'){window.__olNativeCalls=1;window.__olNativeRingback=1}}catch(e){}})();";

    // ---- 0.2.7: numeric app-icon badge (total unread); 0 clears ----
    @JavascriptInterface
    public void setBadge(int count) { if (trusted()) host.setBadge(Math.max(0, count)); }

    // ---- 0.2.7: Google sign-in in a Custom Tab (Google blocks OAuth in embedded WebViews); returns via openlounge://auth ----
    @JavascriptInterface
    public void openAuth(String url) { if (trusted() && url != null && url.length() < 2000) host.openAuth(url); }

    // ---- native-side helpers (not exposed to JS) ----

    public boolean hasStoredSession() {
        synchronized (store) {
            String v = store.get();
            return v != null && v.length() >= 20;
        }
    }

    /** Seed the store from a migrated value (only if empty). */
    public void seedIfEmpty(String value) {
        if (value == null || value.length() < 20) return;
        synchronized (store) {
            String v = store.get();
            if (v != null && v.length() >= 20) return;
            store.put(value);
        }
    }

    // ---- native phone verification (Firebase Auth SDK, no web reCAPTCHA) — see NativePhoneAuth ----
    @JavascriptInterface
    public String phoneAuthAvailable() {
        return trusted() ? "true" : "false";
    }

    @JavascriptInterface
    public void phoneAuthStart(String reqId, String e164) {
        if (!trusted() || reqId == null || reqId.length() > 100 || e164 == null || !e164.matches("\\+[0-9]{6,16}")) return;
        host.phoneAuthStart(reqId, e164);
    }

    @JavascriptInterface
    public void phoneAuthConfirm(String reqId, String code) {
        if (!trusted() || reqId == null || reqId.length() > 100 || code == null || !code.matches("[0-9]{4,8}")) return;
        host.phoneAuthConfirm(reqId, code);
    }

    // ---- biometric app lock (opt-in). JS sees window.OpenLoungeNative.biometrics = {isAvailable(), getEnabled(),
    // setEnabled(bool)} via BIOMETRICS_SHIM; these flat methods are its transport. setEnabled(true) only takes
    // effect after a successful prompt; the result arrives as window.__olBio(reqId, "true"|"false").
    @JavascriptInterface
    public String biometricsIsAvailable() { return trusted() && host.biometricsAvailable() ? "true" : "false"; }

    @JavascriptInterface
    public String biometricsGetEnabled() { return trusted() && host.biometricsEnabled() ? "true" : "false"; }

    @JavascriptInterface
    public void biometricsSetEnabled(String reqId, boolean on) {
        if (!trusted() || reqId == null || reqId.length() > 100) return;
        host.biometricsSetEnabled(reqId, on);
    }

    @JavascriptInterface
    public void biometricsAuthenticate(String reqId) {
        if (!trusted() || reqId == null || reqId.length() > 100) return;
        host.biometricsAuthenticate(reqId);
    }

    /** Promise-based window.OpenLoungeNative.biometrics; also mirrored as window.OpenLoungeBiometrics. */
    public static final String BIOMETRICS_SHIM = "(function(){try{var N=window.OpenLoungeNative;"
            + "if(!N||typeof N.biometricsIsAvailable!=='function'||window.OpenLoungeBiometrics)return;"
            + "var p={},s=0;window.__olBio=function(id,r){var f=p[id];if(f){delete p[id];f(r==='true'||r===true)}};"
            + "var api={isAvailable:function(){return Promise.resolve(N.biometricsIsAvailable()==='true')},"
            + "getEnabled:function(){return Promise.resolve(N.biometricsGetEnabled()==='true')},"
            + "setEnabled:function(on){return new Promise(function(res){var id='b'+Date.now().toString(36)+(++s);"
            + "p[id]=res;N.biometricsSetEnabled(id,!!on)})},"
            + "authenticate:function(){return new Promise(function(res){if(typeof N.biometricsAuthenticate!=='function')return res(false);"
            + "var id='a'+Date.now().toString(36)+(++s);p[id]=res;N.biometricsAuthenticate(id)})}};"
            + "window.OpenLoungeBiometrics=api;try{N.biometrics=api}catch(e){}}catch(e){}})();";
}
