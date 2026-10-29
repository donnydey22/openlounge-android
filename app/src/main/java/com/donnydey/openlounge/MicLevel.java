package com.donnydey.openlounge;

import java.util.Arrays;

/**
 * Read-only mic level meter for native calls (Oct 17: Keiko median 0.004 vs Donny 0.026). Computes the RMS of each
 * 16-bit PCM capture buffer (0..1 full scale) and keeps a bounded ring of recent values so the median can be
 * reported with call stats. Never modifies audio. Pure logic, unit-tested.
 */
final class MicLevel {
    private final float[] ring;
    private int count;
    private int pos;

    MicLevel(int capacity) {
        ring = new float[Math.max(1, capacity)];
    }

    static float rms16(byte[] pcm, int len) {
        int n = Math.min(len, pcm == null ? 0 : pcm.length) / 2;
        if (n <= 0) return 0f;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)); // little-endian
            double v = s / 32768.0;
            sum += v * v;
        }
        return (float) Math.sqrt(sum / n);
    }

    synchronized void add(float level) {
        ring[pos] = level;
        pos = (pos + 1) % ring.length;
        if (count < ring.length) count++;
    }

    synchronized float median() {
        if (count == 0) return 0f;
        float[] c = Arrays.copyOf(ring, count);
        Arrays.sort(c);
        return count % 2 == 1 ? c[count / 2] : (c[count / 2 - 1] + c[count / 2]) / 2f;
    }

    synchronized void reset() { count = 0; pos = 0; }
}
