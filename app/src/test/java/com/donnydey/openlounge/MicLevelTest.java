package com.donnydey.openlounge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MicLevelTest {
    private static byte[] pcm(short... s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) { b[2 * i] = (byte) s[i]; b[2 * i + 1] = (byte) (s[i] >> 8); }
        return b;
    }
    @Test public void silence() { assertEquals(0f, MicLevel.rms16(pcm((short) 0, (short) 0), 4), 1e-6); }
    @Test public void fullScaleSquare() { assertEquals(1f, MicLevel.rms16(pcm((short) -32768, (short) -32768), 4), 1e-4); }
    @Test public void halfScale() { assertEquals(0.5f, MicLevel.rms16(pcm((short) 16384, (short) -16384), 4), 1e-3); }
    @Test public void emptyAndNull() { assertEquals(0f, MicLevel.rms16(null, 10), 0); assertEquals(0f, MicLevel.rms16(new byte[1], 1), 0); }
    @Test public void medianBounded() {
        MicLevel m = new MicLevel(3);
        m.add(9f); m.add(1f); m.add(2f); m.add(3f); // 9 rolled out
        assertEquals(2f, m.median(), 0);
        m.reset(); assertEquals(0f, m.median(), 0);
    }
}
