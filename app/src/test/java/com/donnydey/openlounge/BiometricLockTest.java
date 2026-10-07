package com.donnydey.openlounge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BiometricLockTest {
    private static final long T = BiometricLock.LOCK_AFTER_MS;

    @Test public void disabledNeverLocks() {
        assertFalse(BiometricLock.shouldLock(false, 0, 1_000_000, T));
        assertFalse(BiometricLock.shouldLock(false, 1, 1_000_000, T));
    }

    @Test public void coldStartLocksWhenEnabled() {
        assertTrue(BiometricLock.shouldLock(true, 0, 5, T));
    }

    @Test public void shortBackgroundDoesNotLock() {
        assertFalse(BiometricLock.shouldLock(true, 1_000, 1_000 + 30_000, T));
        assertFalse(BiometricLock.shouldLock(true, 1_000, 1_000 + 60_000, T));
    }

    @Test public void longBackgroundLocks() {
        assertTrue(BiometricLock.shouldLock(true, 1_000, 1_000 + 60_001, T));
    }
}
