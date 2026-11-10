package com.donnydey.openlounge.olcall

/**
 * tgcalls version selection for the olcall server. Pure (no native code) so it
 * is unit-testable; the iOS client (OLCallSession.swift) applies the same rule.
 */
object OlVersions {
    /**
     * Versions the olcall server terminates, newest first (olcall/translator;
     * every one passed the Linux libtgcalls<->Pion matrix 2026-11-09,
     * out/tgpeer-20261109T193633Z.json). Must match olcall-tgpeer -versions.
     */
    val SERVER: List<String> = listOf("13.0.0", "12.0.0", "9.0.0", "8.0.0", "7.0.0")

    /** Newest version both this tgcalls build and the server support. */
    fun pick(local: List<String>): String =
        SERVER.firstOrNull { it in local }
            ?: throw IllegalStateException("this tgcalls build supports none of $SERVER (has $local)")
}
