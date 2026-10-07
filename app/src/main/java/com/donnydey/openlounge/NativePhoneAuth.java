package com.donnydey.openlounge;

import android.app.Activity;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * Native Firebase phone verification for the web AuthFlow (window.OpenLoungeNative.phoneAuthStart/Confirm).
 * Uses the Firebase Auth Android SDK: app verification via Play Integrity (no reCAPTCHA) — requires the app's
 * SHA-256 fingerprints registered on the Android app in the Firebase project. If Play Integrity can't attest the
 * device, the SDK itself falls back to a reCAPTCHA in a Custom Tab (still not inside the WebView).
 * Results go back to the page as window.__olPhoneAuth({id, ok, sent?, idToken?, code?, error?}); the idToken is
 * then posted by the web layer to the same server endpoint as web Firebase verification (auth:verifyFirebase).
 * The native Firebase user is signed out immediately after minting the token: Firebase is only the verifier.
 */
final class NativePhoneAuth {
    private static final String TAG = "NativePhoneAuth";
    private final Activity activity;
    private final WebView webView;
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private String lastPhone;

    NativePhoneAuth(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        auth.useAppLanguage();
    }

    void start(String reqId, String e164) {
        activity.runOnUiThread(() -> {
            PhoneAuthOptions.Builder b = PhoneAuthOptions.newBuilder(auth)
                    .setPhoneNumber(e164)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(activity)
                    .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                        private boolean done;

                        @Override
                        public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                            // instant verification / SMS auto-retrieval
                            if (done) return;
                            done = true;
                            signIn(reqId, credential);
                        }

                        @Override
                        public void onVerificationFailed(@NonNull FirebaseException e) {
                            if (done) return;
                            done = true;
                            fail(reqId, e);
                        }

                        @Override
                        public void onCodeSent(@NonNull String id, @NonNull PhoneAuthProvider.ForceResendingToken token) {
                            if (done) return;
                            done = true;
                            verificationId = id;
                            resendToken = token;
                            JSONObject o = base(reqId, true);
                            try { o.put("sent", true); } catch (Exception ignored) {}
                            deliver(o);
                        }
                    });
            if (resendToken != null && e164.equals(lastPhone)) b.setForceResendingToken(resendToken);
            lastPhone = e164;
            PhoneAuthProvider.verifyPhoneNumber(b.build());
        });
    }

    void confirm(String reqId, String code) {
        activity.runOnUiThread(() -> {
            if (verificationId == null) {
                deliver(error(reqId, "auth/missing-verification-id", "Request a new code and try again."));
                return;
            }
            signIn(reqId, PhoneAuthProvider.getCredential(verificationId, code));
        });
    }

    private void signIn(String reqId, PhoneAuthCredential credential) {
        auth.signInWithCredential(credential)
                .addOnSuccessListener(res -> res.getUser().getIdToken(true)
                        .addOnSuccessListener(t -> {
                            JSONObject o = base(reqId, true);
                            try { o.put("idToken", t.getToken()); } catch (Exception ignored) {}
                            auth.signOut();
                            verificationId = null;
                            deliver(o);
                        })
                        .addOnFailureListener(e -> fail(reqId, e)))
                .addOnFailureListener(e -> fail(reqId, e));
    }

    private void fail(String reqId, Exception e) {
        Log.w(TAG, "phone auth failed", e);
        String code = "auth/unknown";
        if (e instanceof FirebaseAuthException) {
            // ERROR_INVALID_VERIFICATION_CODE → auth/invalid-verification-code (web codes, so the page maps them)
            code = "auth/" + ((FirebaseAuthException) e).getErrorCode().toLowerCase().replaceFirst("^error_", "").replace('_', '-');
        } else if (e instanceof com.google.firebase.FirebaseTooManyRequestsException) {
            code = "auth/too-many-requests";
        }
        deliver(error(reqId, code, null));
    }

    private static JSONObject base(String reqId, boolean ok) {
        JSONObject o = new JSONObject();
        try { o.put("id", reqId); o.put("ok", ok); } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject error(String reqId, String code, String message) {
        JSONObject o = base(reqId, false);
        try { o.put("code", code); if (message != null) o.put("error", message); } catch (Exception ignored) {}
        return o;
    }

    private void deliver(JSONObject o) {
        String js = "window.__olPhoneAuth&&window.__olPhoneAuth(" + JSONObject.quote(o.toString()) + ")";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }
}
