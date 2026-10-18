package com.donnydey.openlounge;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service (type phoneCall, + microphone when RECORD_AUDIO is granted) for the length of a call, so the
 * OS doesn't stop the WebView's audio when the app goes to the background. Ongoing "Open Lounge call · Tap to
 * return" notification with Hang up. Service started on call:outgoing and call:connected; communication audio mode only on call:connected. Stopped on call:ended.
 */
public class CallService extends Service {
    private static final String TAG = "OLCalls";
    static final int NOTIF_ID = 7002;
    static final String EXTRA_NAME = "name";
    static final String EXTRA_CONNECTED = "connected";
    static final String EXTRA_USER_VISIBLE = "user_visible";

    static boolean start(Context ctx, String callId, String name, boolean connected) {
        return start(ctx, callId, name, connected, MainActivity.isVisible());
    }

    /** userVisible: started from a visible activity or a user tap (Accept), which lets Android 14+ grant the mic type */
    static boolean start(Context ctx, String callId, String name, boolean connected, boolean userVisible) {
        Intent i = new Intent(ctx, CallService.class)
                .putExtra(CallNotifications.EXTRA_CALL_ID, callId == null ? "" : callId)
                .putExtra(EXTRA_NAME, name == null ? "" : name)
                .putExtra(EXTRA_CONNECTED, connected)
                .putExtra(EXTRA_USER_VISIBLE, userVisible);
        try {
            ContextCompat.startForegroundService(ctx, i);
            return true;
        } catch (RuntimeException e) {
            // Android 12+: not allowed from the background (e.g. connected while the app was away) → no FGS
            Log.w(TAG, "call foreground service not started: " + e.getClass().getSimpleName());
            return false;
        }
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CallService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String callId = intent == null ? "" : intent.getStringExtra(CallNotifications.EXTRA_CALL_ID);
        String name = intent == null ? "" : intent.getStringExtra(EXTRA_NAME);
        boolean connected = intent != null && intent.getBooleanExtra(EXTRA_CONNECTED, true);
        Notification n = build(callId, name, connected);
        boolean userVisible = intent != null && intent.getBooleanExtra(EXTRA_USER_VISIBLE, false);
        boolean mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        int type = CallFgsPolicy.types(Build.VERSION.SDK_INT, mic, userVisible);
        while (true) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, n, type);
                Log.i(TAG, "call foreground service up (type " + type + ")");
                break;
            } catch (RuntimeException e) {
                // Android 14+: a background "microphone" start is refused; keep the phoneCall FGS instead of dying
                Log.w(TAG, "startForeground failed (type " + type + "): " + e.getClass().getSimpleName() + " " + e.getMessage());
                int next = CallFgsPolicy.fallback(type);
                if (next == 0) { stopSelf(); break; }
                type = next;
            }
        }
        return START_NOT_STICKY; // never resurrect a call after the process died
    }

    private Notification build(String callId, String name, boolean connected) {
        CallNotifications.ensureChannels(this);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 7002, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hangPi = CallActionReceiver.pending(this, CallActionReceiver.ACTION_HANGUP, callId);
        String who = (name == null || name.trim().isEmpty()) ? getString(R.string.app_name) : name.trim();
        Person p = new Person.Builder().setName(who).build();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CallNotifications.CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(this, R.color.openlounge_accent))
                .setContentTitle(getString(R.string.call_ongoing_title))
                .setContentText(getString(connected ? R.string.call_ongoing_text : R.string.call_calling_text))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        try {
            b.setStyle(NotificationCompat.CallStyle.forOngoingCall(p, hangPi));
            return b.build();
        } catch (RuntimeException e) {
            return b.setStyle(null).addAction(0, getString(R.string.call_hang_up), hangPi).build();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // app swiped away: the WebView (and the call) are gone
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
