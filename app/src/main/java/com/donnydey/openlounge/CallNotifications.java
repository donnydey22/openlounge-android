package com.donnydey.openlounge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;

import java.util.Map;

/**
 * Incoming call ring (NATIVE_SPEC.md §4): FCM data {type:"call_incoming", callId, callerId, callerName, callerPhoto,
 * video "0"|"1", expiresAt (epoch ms), url} → a ringing CATEGORY_CALL notification on the "calls" channel
 * (ringtone, insistent, vibration, public on the lock screen, times out at expiresAt) with a full-screen intent
 * (IncomingCallActivity) and Accept / Decline; CallStyle.forIncomingCall on Android 12+. {type:"call_cancel"}
 * removes it. Android 14+: if the full-screen intent permission was revoked it still rings as a heads-up (and
 * FullScreenIntentPrompt offers the setting once). 0.3.2: data.declineToken → CallApi (Decline without a session).
 */
final class CallNotifications {
    private static final String TAG = "OLCalls";
    static final String CHANNEL_CALLS = "calls";
    static final String CHANNEL_ONGOING = "call_ongoing";
    static final int NOTIF_ID = 7001;
    static final String EXTRA_CALL_ID = "call_id";
    static final String EXTRA_CALLER = "caller_name";
    static final String EXTRA_PHOTO = "caller_photo";
    static final String EXTRA_VIDEO = "video";
    static final String EXTRA_EXPIRES = "expires_at";
    static final long[] VIBRATION = {0, 800, 600, 800, 600, 800, 600, 800};

    private CallNotifications() {}

    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL_CALLS, ctx.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH);
        c.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        c.enableVibration(true);
        c.setVibrationPattern(VIBRATION);
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
        NotificationChannel o = new NotificationChannel(CHANNEL_ONGOING, ctx.getString(R.string.channel_call_ongoing), NotificationManager.IMPORTANCE_LOW);
        o.setShowBadge(false);
        o.setSound(null, null);
        o.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(o);
    }

    /** Handles an FCM call_incoming. Returns false if it was ignored (expired / malformed). */
    static boolean onIncoming(Context ctx, Map<String, String> d) {
        String callId = d.get("callId");
        if (callId == null || !callId.matches(CallApi.CALL_ID_RE)) return false;
        long expiresAt = parseLong(d.get("expiresAt"), System.currentTimeMillis() + 45_000);
        if (expiresAt <= System.currentTimeMillis()) return false; // stale push: never ring for an old call
        String name = d.get("callerName");
        if (name == null || name.trim().isEmpty()) name = ctx.getString(R.string.app_name);
        name = name.trim();
        if (name.length() > 80) name = name.substring(0, 80);
        String photo = d.get("callerPhoto");
        boolean video = "1".equals(d.get("video")) || "true".equals(d.get("video"));
        CallApi.rememberDeclineToken(ctx, callId, d.get("declineToken"), expiresAt); // 0.3.2: session-less Decline
        show(ctx, callId, name, photo, video, expiresAt);
        return true;
    }

    static void show(Context ctx, String callId, String name, String photo, boolean video, long expiresAt) {
        ensureChannels(ctx);
        Intent full = IncomingCallActivity.intent(ctx, callId, name, photo, video, expiresAt);
        PendingIntent fullPi = PendingIntent.getActivity(ctx, callId.hashCode(), full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent accept = IncomingCallActivity.intent(ctx, callId, name, photo, video, expiresAt).setAction(IncomingCallActivity.ACTION_ACCEPT);
        PendingIntent acceptPi = PendingIntent.getActivity(ctx, callId.hashCode() + 1, accept,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent declinePi = CallActionReceiver.pending(ctx, CallActionReceiver.ACTION_DECLINE, callId);

        Person caller = new Person.Builder().setName(name).setImportant(true).build();
        String text = ctx.getString(video ? R.string.call_incoming_video : R.string.call_incoming_audio);
        long timeout = Math.max(1_000, expiresAt - System.currentTimeMillis());
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(ctx, R.color.openlounge_accent))
                .setContentTitle(name)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(timeout)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                // < Android 8: no channels, so sound/vibration go on the notification itself
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioManager.STREAM_RING)
                .setVibrate(VIBRATION)
                .addPerson(caller);
        Notification n;
        try {
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, acceptPi).setIsVideo(video));
            n = b.build();
        } catch (RuntimeException e) {
            // CallStyle refused on this device: plain actions instead
            b.setStyle(null)
                    .addAction(0, ctx.getString(R.string.call_decline), declinePi)
                    .addAction(0, ctx.getString(R.string.call_accept), acceptPi);
            n = b.build();
        }
        n.flags |= Notification.FLAG_INSISTENT; // keep ringing until answered / declined / cancelled
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                Log.i(TAG, "full-screen intent not allowed: heads-up ring only");
                FullScreenIntentPrompt.markRangWithoutFullScreen(ctx); // one more explainer after a real missed lock-screen ring
            }
        }
        try {
            NotificationManagerCompat.from(ctx).notify(callId, NOTIF_ID, n);
            Log.i(TAG, "incoming call notification posted");
        } catch (SecurityException e) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted: no native ring");
        }
    }

    static void cancel(Context ctx, String callId) {
        if (callId == null) return;
        NotificationManagerCompat.from(ctx).cancel(callId, NOTIF_ID);
        IncomingCallActivity.dismiss(callId);
    }

    static Uri ringtone() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    static long parseLong(String s, long dflt) {
        try {
            return s == null ? dflt : (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
