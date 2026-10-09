package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppBadgeTest {
    @Test public void absentOrMalformedValuesAreIgnored() {
        assertEquals(-1, AppBadge.parse(null));
        assertEquals(-1, AppBadge.parse(""));
        assertEquals(-1, AppBadge.parse("not-a-number"));
        assertEquals(-1, AppBadge.parse("-1"));
    }

    @Test public void validCountsAreBounded() {
        assertEquals(0, AppBadge.parse("0"));
        assertEquals(7, AppBadge.parse("7"));
        assertEquals(9999, AppBadge.parse("10000"));
    }
}
