package com.donnydey.openlounge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.HashMap;
import java.util.Map;

/**
 * DEBUG BUILDS ONLY (src/debug): simulates the FCM data messages for the emulator smoke test.
 *   adb shell am broadcast -n com.donnydey.openlounge/.DebugRingReceiver --es type call_incoming --es callId t1 --es callerName Ann
 *   adb shell am broadcast -n com.donnydey.openlounge/.DebugRingReceiver --es type call_cancel --es callId t1
 * 0.3.2: --es declineToken <token> (as in the real push) and --es apiOrigin http://10.0.2.2:8787 (local decline server;
 * --es apiOrigin "" resets to the real server).
 */
public class DebugRingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Map<String, String> d = new HashMap<>();
        for (String k : new String[]{"type", "callId", "callerId", "callerName", "callerPhoto", "video", "expiresAt", "declineToken"}) {
            String v = intent.getStringExtra(k);
            if (v != null) d.put(k, v);
        }
        if (intent.hasExtra("apiOrigin")) CallApi.setDebugOrigin(context, intent.getStringExtra("apiOrigin"));
        if (d.get("type") == null) return;
        if (!d.containsKey("expiresAt")) d.put("expiresAt", String.valueOf(System.currentTimeMillis() + 45_000));
        if ("call_cancel".equals(d.get("type"))) CallNotifications.cancel(context, d.get("callId"));
        else CallNotifications.onIncoming(context, d);
    }
}
