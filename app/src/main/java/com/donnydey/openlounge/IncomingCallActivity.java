package com.donnydey.openlounge;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-screen incoming call (shown over the lock screen, turns the screen on): caller name + photo on a blurred
 * backdrop, Decline / Accept like the web ring screen. Accept → native CallSignalClient call:accept (no WebView
 * answers instantly); Decline → POST /api/calls/<id>/decline. Finishes on call_cancel and at expiresAt.
 */
public class IncomingCallActivity extends Activity {
    static final String ACTION_ACCEPT = "com.donnydey.openlounge.CALL_ACCEPT";
    private static WeakReference<IncomingCallActivity> current = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private String callId;
    private final Runnable expire = this::finishQuietly;

    static Intent intent(Context ctx, String callId, String name, String photo, boolean video, long expiresAt) {
        return new Intent(ctx, IncomingCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(CallNotifications.EXTRA_CALL_ID, callId)
                .putExtra(CallNotifications.EXTRA_CALLER, name)
                .putExtra(CallNotifications.EXTRA_PHOTO, photo)
                .putExtra(CallNotifications.EXTRA_VIDEO, video)
                .putExtra(CallNotifications.EXTRA_EXPIRES, expiresAt);
    }

    /** call_cancel / answered elsewhere: close the ring screen for that call if it's up. */
    static void dismiss(String callId) {
        MAIN.post(() -> {
            IncomingCallActivity a = current.get();
            if (a != null && !a.isFinishing() && (callId == null || callId.equals(a.callId))) a.finishQuietly();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent i) {
        String id = i == null ? null : i.getStringExtra(CallNotifications.EXTRA_CALL_ID);
        if (id == null || !id.matches(CallApi.CALL_ID_RE)) { finish(); return; }
        long expiresAt = i.getLongExtra(CallNotifications.EXTRA_EXPIRES, 0);
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            CallNotifications.cancel(this, id);
            finish();
            return;
        }
        callId = id;
        current = new WeakReference<>(this);
        if (ACTION_ACCEPT.equals(i.getAction())) { accept(); return; }
        String name = i.getStringExtra(CallNotifications.EXTRA_CALLER);
        boolean video = i.getBooleanExtra(CallNotifications.EXTRA_VIDEO, false);
        buildUi(name == null ? getString(R.string.app_name) : name, video, i.getStringExtra(CallNotifications.EXTRA_PHOTO));
        MAIN.removeCallbacks(expire);
        if (expiresAt > 0) MAIN.postDelayed(expire, expiresAt - System.currentTimeMillis());
    }

    private void accept() {
        String id = callId;
        CallNotifications.cancel(this, id);
        // Android 12+: the call FGS must be up before media; starting it from this user tap (visible
        // activity) is exempt from background-start limits and lets Android 14+ grant the microphone type.
        CallService.start(getApplicationContext(), id, getIntent().getStringExtra(CallNotifications.EXTRA_CALLER), false, true);
        // Linphone cut: native owns accept signaling — no /call/<id>?accept=1 WebView path.
        boolean nativeOk = NativeCallMedia.acceptIncoming(getApplicationContext(), id);
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ol_native_accept_call_id", id)
                .putExtra("ol_native_accept_ok", nativeOk);
        if (Build.VERSION.SDK_INT >= 26) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                // locked phone: ask for unlock, then open the call (MainActivity isn't shown over the lock screen)
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { open(open); }
                    // FX-062: never open the call page behind the lock screen. MainActivity is not shown over the
                    // keyguard, so its mic/camera dialogs could not be tapped. Stay on the call screen; Accept retries.
                    @Override public void onDismissCancelled() { }
                    @Override public void onDismissError() { open(open); }
                });
                return;
            }
        }
        open(open);
    }

    private boolean opened;

    private void open(Intent open) {
        if (opened) return;
        opened = true;
        startActivity(open);
        finishQuietly();
    }

    private void decline() {
        String id = callId;
        CallNotifications.cancel(this, id);
        CallActionReceiver.declineAsync(getApplicationContext(), id, null);
        finishQuietly();
    }

    private void finishQuietly() {
        MAIN.removeCallbacks(expire);
        if (current.get() == this) current = new WeakReference<>(null);
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(expire);
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    // ---------- UI (programmatic: dark backdrop, avatar, name, two round buttons) ----------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi(String name, boolean video, String photoUrl) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B1220"));

        ImageView backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0.55f);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(96), dp(24), dp(64));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setCornerRadius(dp(47));
        circle.setColor(Color.parseColor("#64748B"));
        avatar.setBackground(circle);
        avatar.setClipToOutline(true);
        TextView initial = new TextView(this);
        initial.setText(initials(name));
        initial.setTextColor(Color.WHITE);
        initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        initial.setGravity(Gravity.CENTER);
        avatar.addView(initial, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(photo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(avatar, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(12);
        col.addView(title, tl);

        TextView sub = new TextView(this);
        sub.setText(getString(video ? R.string.call_incoming_video : R.string.call_incoming_audio));
        sub.setTextColor(0xCCFFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(4);
        col.addView(sub, sl);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(button(getString(R.string.call_decline), "#EF4444", R.drawable.ol_call_phone_off, v -> decline()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(button(getString(R.string.call_accept), "#10B981", video ? R.drawable.ol_call_video : R.drawable.ol_call_phone, v -> accept()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        loadPhoto(photoUrl, photo, backdrop);
    }

    private View button(String label, String color, int iconRes, View.OnClickListener onClick) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout disc = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.parseColor(color));
        disc.setBackground(g);
        disc.setElevation(dp(8));
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        disc.addView(ic, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        disc.setContentDescription(label);
        disc.setOnClickListener(onClick);
        b.addView(disc, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xD9FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.addView(t, lp);
        return b;
    }

    /** web utils initials(): one word -> first two, 2+ words -> first + last. */
    private static String initials(String n) {
        String[] w = n.trim().split("\\s+");
        if (n.trim().isEmpty()) return "?";
        if (w.length == 1) return w[0].substring(0, w[0].offsetByCodePoints(0, Math.min(2, w[0].codePointCount(0, w[0].length())))).toUpperCase(java.util.Locale.getDefault());
        return (w[0].substring(0, w[0].offsetByCodePoints(0, 1)) + w[w.length - 1].substring(0, w[w.length - 1].offsetByCodePoints(0, 1))).toUpperCase(java.util.Locale.getDefault());
    }

    /** https photo only, ≤ 2 MB, decoded downsampled off the main thread. */
    private void loadPhoto(String url, ImageView photo, ImageView backdrop) {
        if (url == null || !url.startsWith("https://") || url.length() > 2048) return;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                if (c.getResponseCode() != 200) return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 2_000_000) return;
                    }
                }
                byte[] data = out.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / s > 512) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                if (bmp == null) return;
                MAIN.post(() -> {
                    if (isFinishing()) return;
                    photo.setImageBitmap(bmp);
                    backdrop.setImageBitmap(bmp);
                    if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(36), dp(36), Shader.TileMode.CLAMP));
                    else backdrop.setAlpha(0.15f);
                });
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }, "ol-call-photo").start();
    }
}
