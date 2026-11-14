package com.kidslauncher.mdm.assistant

import android.content.Context
import android.util.Log

/**
 * Design 98 §3: the assistant step of "Fullfør oppsett" and the role's changes. [roleMaybeChanged] asks for one sync
 * whenever the role held differs from the one last reported (§3.6: no public broadcast for a role change - it is read
 * on every apply(), on Kid Settings' resume and at the service's shutdown).
 */
object AssistantSetup {
    private const val PREFS = "airplane_mode"
    private const val KEY_ROLE_SEEN = "assistant_role_seen"

    /** The role just became ours (`VibbAssistantService.onReady`). */
    fun roleBecameOurs(context: Context) {
        roleMaybeChanged(context)
    }

    /** §3.6: a change of the role held asks for one sync (once per change). Any thread. */
    fun roleMaybeChanged(context: Context) {
        try {
            val held = AirplaneRuntime.roleHeld(context)
            val prefs = context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = if (prefs.contains(KEY_ROLE_SEEN)) prefs.getBoolean(KEY_ROLE_SEEN, false) else null
            if (seen == held) return
            prefs.edit().putBoolean(KEY_ROLE_SEEN, held).apply()
            Log.i("AssistantSetup", "The assistant role is ${if (held) "ours" else "not ours"}: a report")
            AirplaneRuntime.changed()
            com.kidslauncher.mdm.push.SyncRunner.request(context, "assistant_role")
        } catch (e: Exception) {
            Log.w("AssistantSetup", "Role check failed: ${e.javaClass.simpleName}")
        }
    }
}
