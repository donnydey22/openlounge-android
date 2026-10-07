package com.donnydey.openlounge;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Builds notifications from the server's data-only FCM messages (server/push/sender.ts):
 * data = { type, title, body, channel: messages|rooms|social, tag, url, clear? }.
 * Same tag replaces the previous notification (per DM thread, "followers", per room); clear=1 removes it
 * (DM read on another device). Tapping opens the deep link inside the WebView.
 * 0.2.7: optional data.badge (total unread = unread DMs + unread notifications) → NotificationCompat.setNumber
 * (launchers that read the notification count) and ShortcutBadger (Samsung One UI, etc.) via AppBadge.
 */
public class OpenLoungeMessagingService extends FirebaseMessagingService {
    public static final String EXTRA_OPEN_PATH = "open_path";

    @Override
    public void onNewToken(@NonNull String token) {
        PushTokens.save(getApplicationContext(), token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> d = message.getData();
        String tag = d.containsKey("tag") ? d.get("tag") : "openlounge";
        int badge = AppBadge.parse(d.get("badge"));
        if (badge >= 0) AppBadge.set(this, badge);
        if ("1".equals(d.get("clear"))) {
            NotificationManagerCompat.from(this).cancel(tag, 0);
            return;
        }
        String title = d.get("title");
        String body = d.get("body");
        if (title == null && message.getNotification() != null) {
            title = message.getNotification().getTitle();
            body = message.getNotification().getBody();
        }
        if (title == null) return;
        show(this, d.get("channel"), tag, title, body == null ? "" : body, d.get("url"), badge);
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel("messages", ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(channel("rooms", ctx.getString(R.string.channel_rooms), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(channel("social", ctx.getString(R.string.channel_social), NotificationManager.IMPORTANCE_DEFAULT));
    }

    @androidx.annotation.RequiresApi(26)
    private static NotificationChannel channel(String id, String name, int importance) {
        NotificationChannel c = new NotificationChannel(id, name, importance);
        c.setShowBadge(true); // default is true; explicit so app icon badges/dots stay on
        return c;
    }

    static void show(Context ctx, String channel, String tag, String title, String body, String path, int badge) {
        ensureChannels(ctx);
        if (!"messages".equals(channel) && !"rooms".equals(channel)) channel = "social";
        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_PATH, path == null ? "/" : path);
        PendingIntent pi = PendingIntent.getActivity(ctx, tag.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(ctx, R.color.openlounge_accent))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory("messages".equals(channel) ? NotificationCompat.CATEGORY_MESSAGE : NotificationCompat.CATEGORY_SOCIAL)
                .setPriority("social".equals(channel) ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_HIGH);
        if (badge > 0) b.setNumber(badge).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL);
        try {
            NotificationManagerCompat.from(ctx).notify(tag, 0, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted (Android 13+)
        }
    }
}
