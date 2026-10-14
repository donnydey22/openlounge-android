package com.donnydey.openlounge;

/**
 * Pure helpers for App Lock ↔ incoming calls (unit-tested).
 * While App Lock is up the shell reports not-visible so the server sends FCM CallStyle;
 * Accept /call/…?accept=1 deep links bypass the lock overlay.
 */
final class CallLockPolicy {
    private CallLockPolicy() {}

    /** Report presence:visible to the page only when the activity is foregrounded and unlocked. */
    static boolean shouldReportVisible(boolean activityForeground, boolean locked) {
        return activityForeground && !locked;
    }

    /** /call/<id> or /call/<id>?accept=1 (and accept=1 in the query) — unlock so the call UI is reachable. */
    static boolean isCallAcceptPath(String path) {
        if (path == null || !path.startsWith("/call/")) return false;
        if (path.contains("accept=1")) return true;
        int q = path.indexOf('?');
        String base = q < 0 ? path : path.substring(0, q);
        return base.length() > "/call/".length() && base.indexOf('/', "/call/".length()) < 0;
    }
}
