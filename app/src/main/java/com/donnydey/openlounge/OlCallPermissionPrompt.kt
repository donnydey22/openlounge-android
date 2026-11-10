package com.donnydey.openlounge

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Shows one system permission dialog for [OlCallPermissions] and never a rationale or follow-up nag.
 * Activities call [attach] in onResume so a connect / accept / camera toggle can prompt on the
 * visible screen (IncomingCallActivity when accepting from a ring, CallActivity mid-call, else MainActivity).
 *
 * MainActivity uses [ActivityResultContracts.RequestMultiplePermissions] via [Launcher]; other
 * activities use Activity.requestPermissions with the same permission list (one combined dialog).
 */
object OlCallPermissionPrompt {
    const val REQUEST_CODE = 0x0C51

    interface Launcher {
        fun launchCallPermissions(permissions: Array<String>)
    }

    fun interface Callback {
        fun onResult(granted: Boolean)
    }

    @Volatile private var foreground: Activity? = null
    private var pending: ((Map<String, Boolean>) -> Unit)? = null

    @JvmStatic fun attach(activity: Activity) { foreground = activity }
    @JvmStatic fun detach(activity: Activity) { if (foreground === activity) foreground = null }

    @JvmStatic fun granted(ctx: Context): Set<String> = buildSet {
        if (ContextCompat.checkSelfPermission(ctx, OlCallPermissions.MIC) == PackageManager.PERMISSION_GRANTED)
            add(OlCallPermissions.MIC)
        if (ContextCompat.checkSelfPermission(ctx, OlCallPermissions.CAMERA) == PackageManager.PERMISSION_GRANTED)
            add(OlCallPermissions.CAMERA)
    }

    @JvmStatic
    fun ensure(activity: Activity, action: OlCallPermissions.Action, done: Callback) {
        ensure(action, activity, activity) { done.onResult(it) }
    }

    @JvmStatic
    fun ensure(action: OlCallPermissions.Action, ctx: Context, done: Callback) {
        ensure(action, ctx, foreground) { done.onResult(it) }
    }

    private fun ensure(
        action: OlCallPermissions.Action,
        ctx: Context,
        activity: Activity?,
        done: (Boolean) -> Unit,
    ) {
        val held = granted(ctx)
        when (OlCallPermissions.decide(action, held)) {
            OlCallPermissions.Decision.PROCEED -> done(true)
            OlCallPermissions.Decision.DENY_SILENT -> done(false)
            OlCallPermissions.Decision.REQUEST -> {
                val act = activity
                if (act == null || act.isFinishing) { done(false); return }
                val missing = OlCallPermissions.missing(action, held).toTypedArray()
                if (missing.isEmpty()) { done(true); return }
                pending = { result ->
                    done(OlCallPermissions.decide(action, held, result) == OlCallPermissions.Decision.PROCEED)
                }
                if (act is Launcher) act.launchCallPermissions(missing)
                else act.requestPermissions(missing, REQUEST_CODE)
            }
        }
    }

    @JvmStatic
    fun onResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val result = permissions.indices.associate { i ->
            permissions[i] to (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED)
        }
        deliver(result)
        return true
    }

    @JvmStatic
    fun onLaunchResult(result: Map<String, Boolean>) { deliver(result) }

    private fun deliver(result: Map<String, Boolean>) {
        val cb = pending
        pending = null
        cb?.invoke(result)
    }
}
