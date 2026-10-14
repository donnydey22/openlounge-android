package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ForegroundNotificationTest {
    private static Map<String, String> dm() {
        Map<String, String> d = new HashMap<>();
        d.put("type", "dm");
        d.put("title", "Maya");
        d.put("body", "Are you around?");
        d.put("tag", "dm:u_maya");
        d.put("url", "/messages/u_maya");
        d.put("channel", "messages");
        d.put("badge", "3");
        d.put("peerId", "u_maya");
        return d;
    }

    @Test public void acceptsBoundedInAppDm() {
        ForegroundNotification n = ForegroundNotification.from(dm());
        assertNotNull(n);
        assertEquals("dm", n.type);
        assertEquals("/messages/u_maya", n.url);
        assertEquals("messages", n.channel);
        assertEquals("3", n.badge);
        assertEquals("u_maya", n.peerId);
        assertFalse(n.clear);
    }

    @Test public void acceptsTagOnlyClearAndZeroBadge() {
        Map<String, String> d = dm();
        d.put("type", "clear");
        d.put("title", "");
        d.put("body", "");
        d.put("url", "");
        d.put("clear", "1");
        d.put("badge", "0");
        ForegroundNotification n = ForegroundNotification.from(d);
        assertNotNull(n);
        assertTrue(n.clear);
        assertEquals("0", n.badge);
    }

    @Test public void rejectsExternalAndMalformedDisplayRoutes() {
        Map<String, String> d = dm();
        d.put("url", "https://example.test");
        assertNull(ForegroundNotification.from(d));
        d = dm();
        d.put("url", "//example.test");
        assertNull(ForegroundNotification.from(d));
        d = dm();
        d.put("title", "");
        assertNull(ForegroundNotification.from(d));
    }
}
