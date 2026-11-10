package com.donnydey.openlounge

/**
 * Signal / WhatsApp-style call permission gate (pure: no Android framework calls, unit-tested on the JVM).
 *
 * - Never requested at app launch or onboarding. The caller asks only at the call action.
 * - Audio start / accept: RECORD_AUDIO only.
 * - Video start / accept: CAMERA + RECORD_AUDIO in one combined request.
 * - Mid-call video toggle: CAMERA only, at the moment of the toggle.
 * - A denial fails that call (or that toggle) silently: no rationale, no nag, no re-prompt this process.
 *   If the user later grants the permission in Settings, the next action proceeds.
 */
object OlCallPermissions {
    const val MIC = "android.permission.RECORD_AUDIO"
    const val CAMERA = "android.permission.CAMERA"

    enum class Action { START_OR_ACCEPT_AUDIO, START_OR_ACCEPT_VIDEO, TOGGLE_VIDEO_ON }
    enum class Decision { PROCEED, REQUEST, DENY_SILENT }

    private val deniedThisProcess = mutableSetOf<String>()

    /** Test hook: wipe process-local denial memory. */
    @JvmStatic fun resetForTests() { deniedThisProcess.clear() }

    /** Permissions this action requires, in the order a combined RequestMultiplePermissions should list them. */
    @JvmStatic fun required(action: Action): List<String> = when (action) {
        Action.START_OR_ACCEPT_AUDIO -> listOf(MIC)
        Action.START_OR_ACCEPT_VIDEO -> listOf(MIC, CAMERA)
        Action.TOGGLE_VIDEO_ON -> listOf(CAMERA)
    }

    @JvmStatic fun missing(action: Action, granted: Set<String>): List<String> =
        required(action).filter { it !in granted }

    @JvmStatic fun noteResult(result: Map<String, Boolean>) {
        result.forEach { (p, ok) -> if (ok) deniedThisProcess.remove(p) else deniedThisProcess.add(p) }
    }

    /**
     * @param granted permissions already held
     * @param justRequested result of the system dialog that just closed, or null if we have not prompted yet.
     *        Missing keys are treated as denied. After any prompt, the next decision is never REQUEST
     *        for still-missing permissions (no re-prompt loop).
     */
    @JvmStatic @JvmOverloads
    fun decide(action: Action, granted: Set<String>, justRequested: Map<String, Boolean>? = null): Decision {
        if (justRequested != null) noteResult(justRequested)
        val effective = granted + (justRequested?.filterValues { it }?.keys ?: emptySet())
        val need = missing(action, effective)
        if (need.isEmpty()) return Decision.PROCEED
        if (justRequested != null) return Decision.DENY_SILENT
        if (need.any { it in deniedThisProcess }) return Decision.DENY_SILENT
        return Decision.REQUEST
    }
}
