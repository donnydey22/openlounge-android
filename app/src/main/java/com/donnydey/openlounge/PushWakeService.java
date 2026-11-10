package com.donnydey.openlounge;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * ol-call-wake: keeps the process alive for the few seconds a push-woken ring needs (socket reconnect + "received" +
 * server confirmation), like Telegram's KeepAliveJob. Type phoneCall (manifest; MANAGE_OWN_CALLS is declared, which
 * Android 14+ requires for that type); started from a high-priority FCM message, which Android 12+ exempts from the
 * background-start ban. Best effort: if the start is refused the wake still runs inside onMessageReceived.
 * Always calls startForeground first and stops itself (never stopService before that — a pending FGS start that is
 * stopped early crashes the app on some Android versions). Hard cap [MAX_MS].
 */
public class PushWakeService extends Service {
    private static final String TAG = "OLCalls";
    private static final int NOTIF_ID = 7004;
    private static final long MAX_MS = 15_000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static PushWakeService running;
    private static volatile boolean released;

    static void start(Context ctx, String callId) {
        released = false;
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, PushWakeService.class).putExtra(CallNotifications.EXTRA_CALL_ID, callId));
            CallDiskLog.mark("wake-fgs-start", "callId=" + callId);
        } catch (RuntimeException e) {
            Log.w(TAG, "push-wake FGS not started: " + e.getClass().getSimpleName());
            CallDiskLog.mark("wake-fgs-refused", e.getClass().getSimpleName());
        }
    }

    /** the wake is done: stop once the service is in the foreground (immediately if it already is) */
    static void release(Context ctx, String callId) {
        released = true;
        main.post(() -> { if (running != null) running.done("released"); });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        CallNotifications.ensureChannels(this);
        Notification n = new NotificationCompat.Builder(this, CallNotifications.CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Incoming call…")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build();
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL : 0);
            CallDiskLog.mark("wake-fgs-up", "");
        } catch (RuntimeException e) {
            Log.w(TAG, "push-wake startForeground failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            CallDiskLog.mark("wake-fgs-fail", e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }
        running = this;
        main.removeCallbacksAndMessages(null);
        if (released) main.post(() -> done("released-early"));
        main.postDelayed(() -> done("max"), MAX_MS);
        return START_NOT_STICKY;
    }

    private void done(String why) {
        if (running != this) return;
        running = null;
        main.removeCallbacksAndMessages(null);
        CallDiskLog.mark("wake-fgs-stop", "why=" + why);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
