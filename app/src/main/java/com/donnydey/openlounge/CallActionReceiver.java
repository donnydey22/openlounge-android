package com.donnydey.openlounge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification buttons that must not open the app: Decline (incoming ring) and Hang up (ongoing call). */
public class CallActionReceiver extends BroadcastReceiver {
    static final String ACTION_DECLINE = "com.donnydey.openlounge.CALL_DECLINE";
    static final String ACTION_HANGUP = "com.donnydey.openlounge.CALL_HANGUP";

    static PendingIntent pending(Context ctx, String action, String callId) {
        Intent i = new Intent(ctx, CallActionReceiver.class).setAction(action)
                .putExtra(CallNotifications.EXTRA_CALL_ID, callId == null ? "" : callId);
        return PendingIntent.getBroadcast(ctx, (action + callId).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Context app = context.getApplicationContext();
        String callId = intent.getStringExtra(CallNotifications.EXTRA_CALL_ID);
        if (ACTION_DECLINE.equals(intent.getAction())) {
            CallNotifications.cancel(app, callId);
            declineAsync(app, callId, goAsync());
        } else if (ACTION_HANGUP.equals(intent.getAction())) {
            // the page hangs up and then posts call:ended, which stops CallService; no page → stop it here
            if (!MainActivity.dispatchNativeCall("{\"type\":\"call:hangup\"}")) CallService.stop(app);
        }
    }

    /** POST decline natively; if that wasn't possible, let a live page decline over its socket. */
    static void declineAsync(Context app, String callId, PendingResult pr) {
        new Thread(() -> {
            try {
                int st = CallApi.decline(app, callId);
                if (st < 200 || st >= 300) MainActivity.dispatchNativeCall("{\"type\":\"call:decline\"}");
            } finally {
                if (pr != null) pr.finish();
            }
        }, "ol-decline").start();
    }
}
