package com.donnydey.openlounge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Durable per-call disk log: every line is appended and fsync'd BEFORE the step it records runs (audio session
 * change, call message, engine step, page reload), so a process death leaves the last step on disk.
 * On the next launch the previous process's ApplicationExitInfo (API 30+) is appended, and the page receives the
 * tail of the log as a pjsip:calllog event (it rides the existing diag upload). No call audio/content, no secrets.
 */
object CallDiskLog {
    private const val MAX_FILES = 20
    private const val MAX_BYTES = 256 * 1024
    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    private val lock = Any()

    @JvmStatic fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "calllogs").apply { mkdirs() }
        dir = d
        file = File(d, "current.log")
        recordLastExit(ctx)
    }

    /** New call: rotate current.log to <callId>-<time>.log, keep the newest MAX_FILES. */
    @JvmStatic fun begin(callId: String?) = synchronized(lock) {
        val d = dir ?: return@synchronized
        val cur = File(d, "current.log")
        if (cur.exists() && cur.length() > 0) cur.renameTo(File(d, "call-${System.currentTimeMillis()}.log"))
        d.listFiles { f -> f.name.startsWith("call-") }?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
        file = cur
        mark("call-begin", "callId=${callId ?: ""}")
    }

    @JvmStatic fun mark(event: String, detail: String = "") = synchronized(lock) {
        val f = file ?: return@synchronized
        try {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "call-${System.currentTimeMillis()}-cont.log"))
            val line = "${System.currentTimeMillis()} +${SystemClock.elapsedRealtime()} pid=${Process.myPid()} " +
                "t=${Thread.currentThread().name} $event ${detail.replace('\n', ' ').take(500)}\n"
            FileOutputStream(f, true).use { o -> o.write(line.toByteArray()); o.flush(); o.fd.sync() }
        } catch (_: Throwable) { }
    }

    private fun recordLastExit(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val p = ctx.getSharedPreferences("ol_calllog", Context.MODE_PRIVATE)
            val seen = p.getLong("lastExitTs", 0L)
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5).filter { it.timestamp > seen }
            if (list.isEmpty()) return
            p.edit().putLong("lastExitTs", list.maxOf { it.timestamp }).apply()
            for (x in list.sortedBy { it.timestamp }) {
                var trace = ""
                if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == ApplicationExitInfo.REASON_ANR) {
                    trace = runCatching { x.traceInputStream?.use { s -> String(s.readBytes(64 * 1024)) } }.getOrNull().orEmpty()
                }
                mark("prev-exit", "ts=${x.timestamp} pid=${x.pid} reason=${x.reason} status=${x.status} imp=${x.importance} desc=${x.description ?: ""}")
                if (trace.isNotEmpty()) mark("prev-exit-trace", trace.take(4000).replace('\n', '|'))
            }
        } catch (_: Throwable) { }
    }

    private fun java.io.InputStream.readBytes(cap: Int): ByteArray { val b = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (b.size() < cap) { val n = read(buf); if (n <= 0) break; b.write(buf, 0, n) }; return b.toByteArray() }

    /** Tail of current + previous call log for upload through the page's diag channel. */
    @JvmStatic fun tailJson(maxLines: Int = 200): String = synchronized(lock) {
        val d = dir ?: return@synchronized "[]"
        val files = (d.listFiles { f -> f.name.endsWith(".log") }?.sortedBy { it.lastModified() } ?: emptyList()).takeLast(2)
        val lines = files.flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).map { "${f.name}: $it" } }.takeLast(maxLines)
        JSONArray(lines).toString()
    }
}
