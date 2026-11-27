package com.kidslauncher.mdm.timerules

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.setUpKidSheet
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.currentPolicyDecision

/**
 * Design 139 §4.3 (Q2 (b)): an app suspended by a closed app group and nothing else ([com.kidslauncher.mdm.server
 * .EnforcementPlan.groupOnly]) stays on Home and in the app list, dimmed, and a tap opens its group's sheet - the
 * group's name and "Brukt opp i dag", "Ikke i dag" or "Kan ikke telle nå". The MIT list code (`apps/AppFilter`,
 * `ui/list/`) only calls in here. The last plan's set is kept across a process start, as `PlatformRefusals.storePlan`.
 */
object AppGroupTiles {
    private const val LOG_TAG = "AppGroupTiles"
    private const val PREFS = "app_group_tiles"
    private const val KEY_GROUP_ONLY = "group_only"

    /** A dimmed tile's alpha. */
    const val DIM_ALPHA = 0.4f

    @Volatile
    private var stored: Set<String>? = null

    @Volatile
    private var storedRead = false

    /** Every apply stores its plan's group-only apps (written only when they changed). */
    fun storePlan(context: Context, groupOnly: Set<String>) {
        if (stored(context) == groupOnly) return
        stored = groupOnly
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_GROUP_ONLY, groupOnly).apply()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't store the group-only apps: ${e.javaClass.simpleName}")
        }
    }

    private fun stored(context: Context): Set<String> {
        if (!storedRead) {
            stored = try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_GROUP_ONLY, null)?.toSet()
            } catch (e: Exception) {
                null
            }
            storedRead = true
        }
        return stored.orEmpty()
    }

    /** The app is closed by its group alone: on Home and in the list, dimmed. */
    fun dimmed(context: Context, packageName: String?): Boolean =
        packageName != null && packageName in (AppEnforcer.lastPlanGroupOnly ?: stored(context))

    /** Home's grid: every dimmed app now. */
    fun dimmedSet(context: Context): Set<String> = AppEnforcer.lastPlanGroupOnly ?: stored(context)

    /** The list's one call: the icon's alpha. */
    fun alpha(context: Context, packageName: String?): Float = if (dimmed(context, packageName)) DIM_ALPHA else 1f

    /** `AppAction`'s one call: a dimmed app's tap opens its group's sheet instead of Android's "app paused". True when
     * handled. */
    fun intercept(context: Context, packageName: String?): Boolean {
        if (!dimmed(context, packageName) || context !is Activity) return false
        val group = groupOf(context, packageName ?: return false) ?: return false
        showSheet(context, group)
        return true
    }

    /** The closed group that holds [packageName] now. */
    private fun groupOf(context: Context, packageName: String): GroupState? = try {
        TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy).groups.firstOrNull { it.closed && packageName in it.apps }
    } catch (e: Exception) {
        null
    }

    /** The sheet's line for [status]. */
    fun lineRes(status: GroupStatus): Int = when (status) {
        GroupStatus.NOT_TODAY -> R.string.app_group_not_today
        GroupStatus.CANT_COUNT -> R.string.app_group_cant_count
        else -> R.string.app_group_used_up
    }

    /** Home's kid-sheet style (swipe down closes it, as every kid sheet since 2026-11-17); no ask in v1 (Q3 (b)). */
    private fun showSheet(activity: Activity, group: GroupState) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_app_group, null)
        val dialog = Dialog(activity, R.style.KidSheetDialog)
        dialog.setContentView(view)
        dialog.setUpKidSheet(view)
        view.findViewById<TextView>(R.id.app_group_name).text = group.name
        view.findViewById<TextView>(R.id.app_group_line).setText(lineRes(group.status))
        view.findViewById<TextView>(R.id.app_group_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
