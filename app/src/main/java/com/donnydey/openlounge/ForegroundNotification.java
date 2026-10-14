package com.donnydey.openlounge;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounded, WebView-safe subset of a server FCM payload. Firebase data is not trusted at the native-to-WebView
 * boundary: only a valid relative in-app route may be forwarded while the activity is visibly foregrounded.
 */
final class ForegroundNotification {
    private static final Pattern TYPE = Pattern.compile("[A-Za-z0-9:_-]{1,64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    final String type;
    final String title;
    final String body;
    final String tag;
    final String url;
    final String channel;
    final String badge;
    final String sentAt;
    final String peerId;
    final String groupId;
    final String roomId;
    final boolean clear;

    private ForegroundNotification(String type, String title, String body, String tag, String url, String channel,
                                   String badge, String sentAt, String peerId, String groupId, String roomId, boolean clear) {
        this.type = type;
        this.title = title;
        this.body = body;
        this.tag = tag;
        this.url = url;
        this.channel = channel;
        this.badge = badge;
        this.sentAt = sentAt;
        this.peerId = peerId;
        this.groupId = groupId;
        this.roomId = roomId;
        this.clear = clear;
    }

    static ForegroundNotification from(Map<String, String> data) {
        if (data == null) return null;
        String type = text(data.get("type"), 64);
        String title = text(data.get("title"), 140);
        String body = text(data.get("body"), 500);
        String tag = text(data.get("tag"), 160);
        String url = text(data.get("url"), 300);
        boolean clear = "1".equals(data.get("clear"));
        if (!TYPE.matcher(type).matches() || tag.isEmpty() || (!url.isEmpty() && !relativePath(url))) return null;
        // A display event requires content and an internal destination. A clear event only reconciles a collapsed tag.
        if (!clear && (title.isEmpty() || url.isEmpty())) return null;
        String channel = "messages".equals(data.get("channel")) || "rooms".equals(data.get("channel")) ? data.get("channel") : "social";
        String badge = text(data.get("badge"), 5);
        if (!badge.matches("[0-9]{1,5}")) badge = "";
        String sentAt = text(data.get("sentAt"), 14);
        if (!sentAt.matches("[0-9]{10,14}")) sentAt = "";
        return new ForegroundNotification(type, title, body, tag, url, channel, badge, sentAt,
                id(data.get("peerId")), id(data.get("groupId")), id(data.get("roomId")), clear);
    }

    private static String text(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String id(String value) {
        return value != null && ID.matcher(value).matches() ? value : "";
    }

    static boolean relativePath(String value) {
        return value.startsWith("/") && !value.startsWith("//") && !value.contains("\\") && value.length() <= 300;
    }
}
