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
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.animation.PathInterpolator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * FSI trampoline (Claude native-UI first cut): lock-screen ring chrome. Accept → native CallSignalClient
 * call:accept, then forward into CallActivity (active phase, no WebView call paint). Decline → POST
 * /api/calls/<id>/decline. Finishes on call_cancel and at expiresAt.
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
        String name = i.getStringExtra(CallNotifications.EXTRA_CALLER);
        boolean video = i.getBooleanExtra(CallNotifications.EXTRA_VIDEO, false);
        if (ACTION_ACCEPT.equals(i.getAction())) {
            OlCallPermissions.Action action = video
                    ? OlCallPermissions.Action.START_OR_ACCEPT_VIDEO
                    : OlCallPermissions.Action.START_OR_ACCEPT_AUDIO;
            if (OlCallPermissions.decide(action, OlCallPermissionPrompt.granted(this))
                    == OlCallPermissions.Decision.PROCEED) {
                acceptAfterPermission();
                return;
            }
            // Need a prompt (or a prior silent deny): show the ring so denial isn't a blank screen.
            buildUi(name == null ? getString(R.string.app_name) : name, video, i.getStringExtra(CallNotifications.EXTRA_PHOTO));
            MAIN.removeCallbacks(expire);
            if (expiresAt > 0) MAIN.postDelayed(expire, expiresAt - System.currentTimeMillis());
            accept();
            return;
        }
        buildUi(name == null ? getString(R.string.app_name) : name, video, i.getStringExtra(CallNotifications.EXTRA_PHOTO));
        MAIN.removeCallbacks(expire);
        if (expiresAt > 0) MAIN.postDelayed(expire, expiresAt - System.currentTimeMillis());
    }

    private void accept() {
        boolean video = getIntent().getBooleanExtra(CallNotifications.EXTRA_VIDEO, false);
        OlCallPermissions.Action action = video
                ? OlCallPermissions.Action.START_OR_ACCEPT_VIDEO
                : OlCallPermissions.Action.START_OR_ACCEPT_AUDIO;
        // Same rule as an outgoing tap: mic on accept, mic+camera together for a video call.
        // Denial fails this accept silently (no rationale, no re-prompt). The ring stays up.
        OlCallPermissionPrompt.ensure(this, action, ok -> {
            if (!ok) return;
            acceptAfterPermission();
        });
    }

    private void acceptAfterPermission() {
        String id = callId;
        CallNotifications.cancel(this, id);
        boolean video = getIntent().getBooleanExtra(CallNotifications.EXTRA_VIDEO, false);
        // Android 12+: the call FGS must be up before media; starting it from this user tap (visible
        // activity) is exempt from background-start limits and lets Android 14+ grant the microphone type.
        CallService.start(getApplicationContext(), id, getIntent().getStringExtra(CallNotifications.EXTRA_CALLER), false, true);
        // Linphone cut: native owns accept signaling — no /call/<id>?accept=1 WebView path.
        boolean nativeOk = CallMediaCoordinator.acceptIncoming(getApplicationContext(), id, video);
        // Native-UI first cut: CallActivity owns in-app call chrome (no WebView call path).
        startActivity(CallActivity.intent(this, id,
                getIntent().getStringExtra(CallNotifications.EXTRA_CALLER),
                getIntent().getStringExtra(CallNotifications.EXTRA_PHOTO),
                video,
                CallActivity.PHASE_ACTIVE));
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
    protected void onResume() {
        super.onResume();
        OlCallPermissionPrompt.attach(this);
    }

    @Override
    protected void onPause() {
        OlCallPermissionPrompt.detach(this);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!OlCallPermissionPrompt.onResult(requestCode, permissions, grantResults))
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(expire);
        MAIN.removeCallbacks(presenceTick);
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    // ---------- UI: first Open Lounge call screen (web 101e00a, 2026-10-07 MST) ----------
    // Full-screen #0b1220 stage, drifting blobs + blurred photo, squircle 112dp avatar with the
    // 101e00a / PresenceAvatar online dot (16dp + 3dp ring, bottom-right). Even 56dp Decline /
    // Message / Accept discs (24dp icon, 12sp label). Pulsing emerald Accept with swipe-up.
    // Quick-reply sheet declines the call. Accept / Decline / permission paths above this line
    // are unchanged.

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private final java.util.ArrayList<View> rings = new java.util.ArrayList<>();
    private View acceptDisc;
    private View haloA;
    private View haloB;
    private View replies;
    private View presenceDot;
    private float acceptDragY;
    private Float acceptStartY;
    private final Runnable presenceTick = this::peekPresence;
    private ImageView photoView;
    private ImageView backdrop;
    private TextView initialView;

    private void buildUi(String name, boolean video, String photoUrl) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        root.setClipChildren(false);
        int vmax = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);

        // drifting colour blobs (peer slate + indigo #6366F1) + blurred photo + shade
        FrameLayout bg = new FrameLayout(this);
        bg.addView(blob(0xFF64748B, Gravity.TOP | Gravity.START, 0.18f, 0.14f, vmax));
        bg.addView(blob(0xFF6366F1, Gravity.BOTTOM | Gravity.END, -0.16f, -0.12f, vmax));
        ImageView backdrop = new ImageView(this);
        this.backdrop = backdrop;
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setScaleX(1.15f);
        backdrop.setScaleY(1.15f);
        backdrop.setAlpha(0f);
        if (Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(36), dp(36), Shader.TileMode.CLAMP));
        }
        bg.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x40000000, 0x0D000000, 0x8C000000}));
        bg.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        int padTop = dp(24) + (Build.VERSION.SDK_INT >= 35 ? 0 : 0);
        col.setPadding(dp(24), dp(96), dp(24), dp(32));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout ringBox = new FrameLayout(this);
        ringBox.setClipChildren(false);
        rings.clear();
        for (int i = 0; i < 3; i++) {
            View rv = new View(this);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(dp(2), 0x59FFFFFF);
            rv.setBackground(ring);
            rv.setAlpha(0f);
            rings.add(rv);
            ringBox.addView(rv, new FrameLayout.LayoutParams(dp(112), dp(112)));
        }
        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable squircle = new GradientDrawable();
        squircle.setCornerRadius(dp(47));
        squircle.setColor(Color.parseColor("#64748B"));
        avatar.setBackground(squircle);
        avatar.setClipToOutline(true);
        TextView initial = new TextView(this);
        initialView = initial;
        initial.setText(initials(name));
        initial.setTextColor(Color.WHITE);
        initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        initial.setGravity(Gravity.CENTER);
        avatar.addView(initial, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView photo = new ImageView(this);
        photoView = photo;
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(photo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ringBox.addView(avatar, new FrameLayout.LayoutParams(dp(112), dp(112)));
        presenceDot = new View(this);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(CallPresence.COLOR_ONLINE);
        dot.setStroke(dp(3), CallPresence.RING);
        presenceDot.setBackground(dot);
        presenceDot.setVisibility(View.GONE);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END | Gravity.BOTTOM);
        dlp.rightMargin = dp(4);
        dlp.bottomMargin = dp(4);
        ringBox.addView(presenceDot, dlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(112), dp(112));
        rlp.bottomMargin = dp(12);
        col.addView(ringBox, rlp);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(-0.01f);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        row.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        row.setClipChildren(false);
        row.addView(roundAction(getString(R.string.call_decline), 0xFFEF4444, R.drawable.ol_call_phone_off, v -> decline()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(roundAction(getString(R.string.call_message), 0x26FFFFFF, R.drawable.ol_call_message, v -> showReplies(true)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(acceptButton(video), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        replies = buildReplies();
        replies.setVisibility(View.GONE);
        root.addView(replies, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        loadPhoto(photoUrl, photo, backdrop);
        for (int i = 0; i < rings.size(); i++) pulseRing(rings.get(i), i * 800L);
        pulseAccept();
        applyPresence(CallPresence.of(getIntent() != null ? getIntent().getStringExtra("presence") : null));
        startPresencePeek();
    }

    private View blob(int color, int gravity, float dx, float dy, int vmax) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(vmax * 0.35f);
        g.setColors(new int[]{(0x8C << 24) | (color & 0xFFFFFF), color & 0xFFFFFF});
        v.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (vmax * 0.7), (int) (vmax * 0.7), gravity);
        if ((gravity & Gravity.START) == Gravity.START) {
            lp.leftMargin = (int) (-vmax * 0.25);
            lp.topMargin = (int) (-vmax * 0.2);
        } else {
            lp.rightMargin = (int) (-vmax * 0.3);
            lp.bottomMargin = (int) (-vmax * 0.25);
        }
        v.setLayoutParams(lp);
        v.animate().translationX(dx * vmax).translationY(dy * vmax).setDuration(14000).start();
        return v;
    }

    private View roundAction(String label, int color, int iconRes, View.OnClickListener onClick) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout disc = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        disc.setBackground(g);
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        if (color == 0x26FFFFFF) ic.setColorFilter(Color.WHITE);
        disc.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        disc.setContentDescription(label);
        disc.setOnClickListener(onClick);
        b.addView(disc, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xD9FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.addView(t, lp);
        return b;
    }

    private View acceptButton(boolean video) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setClipChildren(false);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        haloA = halo();
        haloB = halo();
        wrap.addView(haloA, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        wrap.addView(haloB, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        FrameLayout disc = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF10B981);
        disc.setBackground(g);
        disc.setElevation(dp(8));
        ImageView ic = new ImageView(this);
        ic.setImageResource(video ? R.drawable.ol_call_video : R.drawable.ol_call_phone);
        ic.setColorFilter(Color.WHITE);
        disc.addView(ic, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        String label = getString(R.string.call_accept);
        disc.setContentDescription(video ? "Accept video call" : label);
        disc.setOnClickListener(v -> accept());
        disc.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    acceptStartY = ev.getRawY();
                    acceptDragY = 0;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (acceptStartY != null) {
                        acceptDragY = Math.min(0, ev.getRawY() - acceptStartY);
                        float dy = Math.max(-120, acceptDragY);
                        disc.setTranslationY(dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (acceptDragY < -dp(60)) accept();
                    else if (ev.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(acceptDragY) < dp(8)) accept();
                    acceptStartY = null;
                    acceptDragY = 0;
                    disc.animate().translationY(0).setDuration(140).start();
                    return true;
            }
            return false;
        });
        acceptDisc = disc;
        wrap.addView(disc, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        b.addView(wrap, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView swipe = new TextView(this);
        swipe.setText(getString(R.string.call_swipe_up));
        swipe.setTextColor(0x99FFFFFF);
        swipe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        swipe.setGravity(Gravity.CENTER);
        // place the hint above the button visually: add it first then move via index — keep below for compactness on short screens
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xD9FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.addView(t, lp);
        swipeHint(swipe);
        // insert swipe hint as a sibling above the disc column by wrapping? first UI has hint above the button.
        // We add it as translation animation on a small label above: rebuild order — hint is already animated; put it in wrap top.
        TextView hint = swipe;
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        hlp.topMargin = -dp(22);
        wrap.addView(hint, hlp);
        return b;
    }

    private View halo() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x7334D399);
        v.setBackground(g);
        v.setAlpha(0.7f);
        return v;
    }

    private void pulseAccept() {
        if (acceptDisc == null || isFinishing()) return;
        ObjectAnimator a = ObjectAnimator.ofFloat(acceptDisc, View.SCALE_X, 1f, 1.08f, 1f);
        ObjectAnimator b = ObjectAnimator.ofFloat(acceptDisc, View.SCALE_Y, 1f, 1.08f, 1f);
        a.setDuration(1400);
        b.setDuration(1400);
        a.setRepeatCount(ValueAnimator.INFINITE);
        b.setRepeatCount(ValueAnimator.INFINITE);
        a.start();
        b.start();
        pulseHalo(haloA, 0);
        pulseHalo(haloB, 700);
    }

    private void pulseHalo(View v, long delay) {
        if (v == null) return;
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(0.7f);
        v.animate().setStartDelay(delay).scaleX(1.8f).scaleY(1.8f).alpha(0f).setDuration(1400)
                .withEndAction(() -> { if (!isFinishing()) pulseHalo(v, 0); }).start();
    }

    private void pulseRing(View rv, long delay) {
        if (isFinishing()) return;
        rv.setScaleX(1f);
        rv.setScaleY(1f);
        rv.setAlpha(0.8f);
        rv.animate().setStartDelay(delay).scaleX(1.9f).scaleY(1.9f).alpha(0f).setDuration(2400)
                .setInterpolator(new PathInterpolator(0.2f, 0.6f, 0.35f, 1f))
                .withEndAction(() -> { if (!isFinishing()) pulseRing(rv, 0); }).start();
    }

    private void swipeHint(View v) {
        v.setAlpha(0.5f);
        v.animate().translationY(-dp(6)).alpha(1f).setDuration(800)
                .withEndAction(() -> v.animate().translationY(0).alpha(0.5f).setDuration(800)
                        .withEndAction(() -> { if (!isFinishing()) swipeHint(v); }).start())
                .start();
    }

    private View buildReplies() {
        FrameLayout sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x66000000);
        sheet.setOnClickListener(v -> showReplies(false));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF217171A);
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        col.setBackground(bg);
        col.setPadding(dp(12), dp(12), dp(12), dp(24));
        col.setOnClickListener(v -> { /* swallow */ });
        View handle = new View(this);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0x33FFFFFF);
        hg.setCornerRadius(dp(2));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(8);
        col.addView(handle, hlp);
        int[] ids = {R.string.call_quick_1, R.string.call_quick_2, R.string.call_quick_3, R.string.call_quick_4};
        for (int id : ids) {
            TextView row = new TextView(this);
            row.setText(getString(id));
            row.setTextColor(Color.WHITE);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setOnClickListener(v -> decline()); // same decline path; canned text is not posted (native decline has no message field)
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView cancel = new TextView(this);
        cancel.setText(getString(R.string.call_cancel));
        cancel.setTextColor(0xFF34D399);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(16), dp(14), dp(16), dp(14));
        cancel.setOnClickListener(v -> showReplies(false));
        col.addView(cancel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sheet.addView(col, lp);
        return sheet;
    }

    private void showReplies(boolean on) {
        if (replies == null) return;
        replies.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** web utils initials(): one word -> first two, 2+ words -> first + last. */
    private static String initials(String n) {
        String[] w = n.trim().split("\\s+");
        if (n.trim().isEmpty()) return "?";
        if (w.length == 1) return w[0].substring(0, w[0].offsetByCodePoints(0, Math.min(2, w[0].codePointCount(0, w[0].length())))).toUpperCase(java.util.Locale.getDefault());
        return (w[0].substring(0, w[0].offsetByCodePoints(0, 1)) + w[w.length - 1].substring(0, w[w.length - 1].offsetByCodePoints(0, 1))).toUpperCase(java.util.Locale.getDefault());
    }

    private void startPresencePeek() {
        MAIN.removeCallbacks(presenceTick);
        peekPresence();
    }

    private void peekPresence() {
        if (isFinishing() || presenceDot == null) return;
        MainActivity.peekLivePresence(raw -> {
            if (isFinishing() || presenceDot == null) return;
            String t = CallPresence.rawFromJs(raw);
            if (t == null || t.isEmpty()) t = raw == null ? "" : raw.trim();
            if (!t.isEmpty()) applyPresence(CallPresence.of(t));
            if (photoView != null && photoView.getDrawable() == null) {
                MainActivity.peekLivePhoto(photoRaw -> {
                    if (isFinishing() || photoView == null) return;
                    String u = CallPresence.rawFromJs(photoRaw);
                    if (u == null || u.isEmpty()) u = photoRaw == null ? "" : photoRaw.trim();
                    if (u.startsWith("http://") || u.startsWith("https://")) loadPhoto(u, photoView, IncomingCallActivity.this.backdrop);
                });
            }
            if (!isFinishing()) MAIN.postDelayed(presenceTick, 2000);
        });
    }

    private void applyPresence(String status) {
        if (presenceDot == null) return;
        String s = CallPresence.of(status);
        if (s == null) {
            presenceDot.setVisibility(View.GONE);
            presenceDot.setContentDescription(null);
        } else {
            android.graphics.drawable.GradientDrawable g = (android.graphics.drawable.GradientDrawable) presenceDot.getBackground();
            g.setColor(CallPresence.color(s));
            g.setStroke(dp(3), CallPresence.RING);
            presenceDot.setVisibility(View.VISIBLE);
            presenceDot.setContentDescription(s);
        }
    }

    /** http(s) photo, or file:// under this app's cache/files; ≤ 2 MB, decoded off the main thread. */
    private void loadPhoto(String url, ImageView photo, ImageView backdrop) {
        if (!CallPresence.allowedPhotoUrl(url, getCacheDir(), getFilesDir()) || photo == null) return;
        final String src = url;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                byte[] data;
                if (src.startsWith("file:")) {
                    java.io.File f = new java.io.File(new java.net.URI(src).getPath());
                    if (!f.isFile() || f.length() > 2_000_000) return;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = new java.io.FileInputStream(f)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    data = out.toByteArray();
                } else {
                    c = (HttpURLConnection) new URL(src).openConnection();
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
                    data = out.toByteArray();
                }
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
                    if (initialView != null) initialView.setText("");
                    if (backdrop != null) {
                        backdrop.setImageBitmap(bmp);
                        backdrop.animate().alpha(1f).setDuration(200).start();
                        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(36), dp(36), Shader.TileMode.CLAMP));
                        else backdrop.setAlpha(0.15f);
                    }
                });
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }, "ol-call-photo").start();
    }
}
