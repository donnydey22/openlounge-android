package com.donnydey.openlounge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.InputStream;

/** The incoming ring is Open Lounge's own tone (res/raw/ol_ring_facetime_style.ogg) on the "calls_v2" channel. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CallRingChannelTest {
    private final Context ctx = RuntimeEnvironment.getApplication();
    private final NotificationManager nm = ctx.getSystemService(NotificationManager.class);

    @Test public void ringUriNamesTheBundledTone() {
        Uri u = CallNotifications.ringtone(ctx);
        assertEquals("android.resource://com.donnydey.openlounge/raw/ol_ring_facetime_style", u.toString());
        // the name in the Uri resolves back to the resource the app ships
        assertEquals(R.raw.ol_ring_facetime_style,
                ctx.getResources().getIdentifier(u.getPathSegments().get(1), u.getPathSegments().get(0), u.getAuthority()));
    }

    @Test public void bundledToneIsOgg() throws Exception {
        byte[] head = new byte[4];
        try (InputStream in = ctx.getResources().openRawResource(R.raw.ol_ring_facetime_style)) {
            assertEquals(4, in.read(head));
        }
        assertArrayEquals(new byte[] {'O', 'g', 'g', 'S'}, head);
    }

    @Test public void ringChannelUsesTheToneAsARingtone() {
        CallNotifications.ensureChannels(ctx);
        assertEquals("calls_v2", CallNotifications.CHANNEL_CALLS);
        NotificationChannel c = nm.getNotificationChannel(CallNotifications.CHANNEL_CALLS);
        assertNotNull(c);
        assertEquals(CallNotifications.ringtone(ctx), c.getSound());
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, c.getAudioAttributes().getUsage());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, c.getImportance());
        assertTrue(c.shouldVibrate());
    }

    @Test public void startupDeletesTheChannelThatRangWithTheSystemRingtone() {
        nm.createNotificationChannel(new NotificationChannel("calls", "Calls", NotificationManager.IMPORTANCE_HIGH));
        assertNotNull(nm.getNotificationChannel("calls"));
        CallNotifications.ensureChannels(ctx);
        assertNull(nm.getNotificationChannel("calls"));
        assertNotNull(nm.getNotificationChannel(CallNotifications.CHANNEL_CALLS));
        assertNotNull(nm.getNotificationChannel(CallNotifications.CHANNEL_ONGOING)); // untouched
        CallNotifications.ensureChannels(ctx); // again: nothing to delete, same channels
        assertNull(nm.getNotificationChannel("calls"));
        assertNotNull(nm.getNotificationChannel(CallNotifications.CHANNEL_CALLS));
    }

    @Test public void incomingRingIsPostedOnTheNewChannel() {
        CallNotifications.show(ctx, "c_0123456789abcdef", "Ada", null, false, System.currentTimeMillis() + 30_000);
        Notification n = Shadows.shadowOf(nm).getNotification("c_0123456789abcdef", CallNotifications.NOTIF_ID);
        assertNotNull(n);
        assertEquals(CallNotifications.CHANNEL_CALLS, n.getChannelId());
        assertTrue((n.flags & Notification.FLAG_INSISTENT) != 0);
    }
}
