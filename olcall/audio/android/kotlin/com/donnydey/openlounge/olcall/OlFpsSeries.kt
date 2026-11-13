package com.donnydey.openlounge.olcall

/**
 * Logs as armor (t5614u target 3; logging only): 1 s video fps samples for the call log.
 * in = far-side frames rendered here, out = local camera frames. Shipped as "video_fps" batches of 5 samples
 * plus one "video_fps_summary" (n/min/max/avg/p50 per direction) at the end. Same shape as iOS OLFpsSeries.
 */
class OlFpsSeries(private val batch: Int = 5, private val maxSamples: Int = 7200) {
    val inSamples = ArrayList<Int>()
    val outSamples = ArrayList<Int>()
    private val pendIn = ArrayList<Int>()
    private val pendOut = ArrayList<Int>()

    /** One 1 s sample; returns a full batch (in, out) to log, or null. */
    fun add(inFps: Int, outFps: Int): Pair<List<Int>, List<Int>>? {
        if (inSamples.size < maxSamples) { inSamples.add(inFps); outSamples.add(outFps) }
        pendIn.add(inFps); pendOut.add(outFps)
        return if (pendIn.size >= batch) flush() else null
    }

    fun flush(): Pair<List<Int>, List<Int>>? {
        if (pendIn.isEmpty()) return null
        val r = Pair(pendIn.toList(), pendOut.toList()); pendIn.clear(); pendOut.clear(); return r
    }

    data class Stats(val n: Int, val min: Int, val max: Int, val avg: Double, val p50: Int)

    companion object {
        fun stats(s: List<Int>): Stats? {
            if (s.isEmpty()) return null
            val sorted = s.sorted()
            return Stats(s.size, sorted.first(), sorted.last(), Math.round(s.sum() * 10.0 / s.size) / 10.0, sorted[(sorted.size - 1) / 2])
        }
        /** Longest run (samples = seconds) strictly below [threshold]. */
        fun longestBelow(s: List<Int>, threshold: Int): Int {
            var best = 0; var cur = 0
            for (v in s) { cur = if (v < threshold) cur + 1 else 0; if (cur > best) best = cur }
            return best
        }
    }

    fun summary(): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        stats(inSamples)?.let { m["in"] = mapOf("n" to it.n, "min" to it.min, "max" to it.max, "avg" to it.avg, "p50" to it.p50) }
        stats(outSamples)?.let { m["out"] = mapOf("n" to it.n, "min" to it.min, "max" to it.max, "avg" to it.avg, "p50" to it.p50) }
        m["inBelow20MaxRunSec"] = longestBelow(inSamples, 20)
        m["outBelow20MaxRunSec"] = longestBelow(outSamples, 20)
        return m
    }
}
