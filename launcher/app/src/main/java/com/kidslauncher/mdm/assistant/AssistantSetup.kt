package com.kidslauncher.mdm.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.systemPageIntent
import kotlinx.serialization.Serializable

private const val LOG_TAG = "AssistantSetup"
private const val PREFS = "airplane_mode"
private const val KEY_ROLE_SEEN = "assistant_role_seen"
private const val KEY_PICK = "assistant_pick_window"
private const val KEY_SNAPSHOT = "assistant_pick_snapshot"
/** Design 114 §2.2.1: the snapshot of the window that moved the roles still listed - the original holders. */
private const val KEY_MOVED_FROM = "assistant_moved_from"
private const val KEY_WIFI_STEP = "wifi_step_open"

const val NOTIFICATION_CHANNEL_SETUP = "launcher:setup"
private const val SETUP_NOTIFICATION_ID = 1010
const val ACTION_SETUP_DEADLINE = "me.vibb.launcher.action.SETUP_DEADLINE"

/** Each deadline its own alarm (code QA L1): one alarm's PendingIntent would move the other's. */
private const val DEADLINE_PICK = 9801
private const val DEADLINE_WIFI = 9802

/** A stored window ([WindowStart] by every clock, `BootClock`): a reboot or the clock set back ends it. */
@Serializable
private data class StoredWindow(val untilWallMs: Long, val elapsedStartMs: Long, val bootCount: Int) {
    fun start() = WindowStart(untilWallMs, elapsedStartMs, bootCount)

    companion object {
        fun of(start: WindowStart) = StoredWindow(start.untilWallMs, start.elapsedStartMs, start.bootCount)
    }
}

/**
 * Design 98 §3: "Fullfør oppsett" - the assistant step's default-apps window (§3.2), the Wi-Fi step (§3.5), the setup
 * notification (§3.3) and the role's changes (§3.6). All behind the parent PIN (Settings' gate) and only while the
 * phone is open for setup (the pause or the override).
 */
object AssistantSetup {
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun window(context: Context, key: String): WindowStart? = try {
        prefs(context).getString(key, null)?.let { ServerJson.decodeFromString(StoredWindow.serializer(), it).start() }
    } catch (e: Exception) {
        null
    }

    private fun storeWindow(context: Context, key: String, start: WindowStart) {
        prefs(context).edit().putString(key, ServerJson.encodeToString(StoredWindow.serializer(), StoredWindow.of(start))).commit()
    }

    /** The pause or the override: Åpne for oppsett, or Settings' own pause. */
    fun setupWindowOpen(): Boolean = RestrictionsPause.isActive() || OfflineOverride.isActive()

    // --- §3.6 the role ---

    /** The role just became ours (`VibbAssistantService.onReady`): the default-apps window closes now. */
    fun roleBecameOurs(context: Context) {
        roleMaybeChanged(context)
        if (prefs(context).contains(KEY_PICK)) endPickWindow(context, "role")
    }

    /** §3.6: a change of the role held asks for one sync (once per change). Any thread. */
    fun roleMaybeChanged(context: Context) {
        try {
            val held = AirplaneRuntime.roleHeld(context)
            val p = prefs(context)
            val seen = if (p.contains(KEY_ROLE_SEEN)) p.getBoolean(KEY_ROLE_SEEN, false) else null
            if (seen == held) return
            p.edit().putBoolean(KEY_ROLE_SEEN, held).apply()
            Log.i(LOG_TAG, "The assistant role is ${if (held) "ours" else "not ours"}: a report")
            AirplaneRuntime.changed()
            com.kidslauncher.mdm.push.SyncRunner.request(context, "assistant_role")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Role check failed: ${e.javaClass.simpleName}")
        }
    }

    // --- §3.2 the default-apps window ---

    /** `lockDefaultAppsFor` gains `&& !assistantPickOpen`: the window is stored, active and the phone open for setup. */
    fun assistantPickOpen(context: Context): Boolean {
        val start = window(context, KEY_PICK) ?: return false
        return BootClock.isActive(start, ASSISTANT_PICK_MS) && setupWindowOpen()
    }

    /** §3.2 on open: the window stored with its snapshot, the 5-minute alarm, then apply() - before Android's page
     * opens (the caller opens it after this returns). Background thread. Returns whether it opened. [keepSnapshot]
     * (design 114 §2.2.1, the flow's "Standardapper"): the snapshot is the one of the window that moved the roles still
     * listed, and the list stays - opening the page doesn't clear the step, and the comparison stays against the
     * original holders. */
    fun openPickWindow(context: Context, keepSnapshot: Boolean = false): Boolean {
        if (!setupWindowOpen()) return false
        // Code QA L2: while roles are listed, every step's window keeps the original holders and the list.
        val kept = if (keepSnapshot || AirplaneRuntime.defaultAppsChanged(context).isNotEmpty()) movedFrom(context) else null
        val snap = kept ?: snapshot(context)
        prefs(context).edit().putString(KEY_SNAPSHOT, ServerJson.encodeToString(DefaultAppsSnapshot.serializer(), snap)).commit()
        storeWindow(context, KEY_PICK, BootClock.windowStart(ASSISTANT_PICK_MS))
        if (kept == null) AirplaneRuntime.storeDefaultAppsChanged(context, emptyList())
        scheduleDeadline(context, DEADLINE_PICK, ASSISTANT_PICK_MS)
        Log.i(LOG_TAG, "The default-apps window is open (5 min at most)")
        AppEnforcer.applyCurrent(context)
        return true
    }

    /** The window ends (the role, the page's answer): the restriction comes back with an apply, the comparison after
     * it ([afterApply]). Any thread. */
    fun endPickWindow(context: Context, why: String) {
        val start = window(context, KEY_PICK) ?: return
        // Expired, the snapshot kept for the comparison.
        storeWindow(context, KEY_PICK, start.copy(untilWallMs = 0))
        Log.i(LOG_TAG, "The default-apps window ends ($why)")
        val app = context.applicationContext
        Thread { runCatching { AppEnforcer.applyCurrent(app) } }.start()
    }

    private fun movedFrom(context: Context): DefaultAppsSnapshot? = runCatching {
        prefs(context).getString(KEY_MOVED_FROM, null)?.let { ServerJson.decodeFromString(DefaultAppsSnapshot.serializer(), it) }
    }.getOrNull()

    /**
     * Design 114 §2.2.1 (QA H1): the moved roles, read live - each stored entry leaves once its role is back
     * ([stillMoved]); the store follows, so the report and the server read the same list. Call screening and redirection
     * are row 13's, never listed here. Background thread (it reads the roles).
     */
    fun defaultAppsChanged(context: Context): List<String> {
        val stored = AirplaneRuntime.defaultAppsChanged(context)
        if (stored.isEmpty()) return stored
        val left = try {
            stillMoved(stored, movedFrom(context), snapshot(context))
        } catch (e: Exception) {
            return stored.filter { it != "call_screening" && it != "call_redirection" }
        }
        if (left != stored) {
            AirplaneRuntime.storeDefaultAppsChanged(context, left)
            if (left.isEmpty()) prefs(context).edit().remove(KEY_MOVED_FROM).apply()
        }
        return left
    }

    /** The exclusive roles Vibb can read (§3.2). */
    private fun snapshot(context: Context): DefaultAppsSnapshot {
        val rm = context.getSystemService(RoleManager::class.java)
        fun held(role: String) = runCatching { rm?.isRoleHeld(role) == true }.getOrDefault(false)
        val browser = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")).addCategory(Intent.CATEGORY_BROWSABLE),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        return DefaultAppsSnapshot(
            home = held(RoleManager.ROLE_HOME),
            dialer = held(RoleManager.ROLE_DIALER),
            sms = held(RoleManager.ROLE_SMS),
            callScreening = held(RoleManager.ROLE_CALL_SCREENING),
            callRedirection = held(RoleManager.ROLE_CALL_REDIRECTION),
            browser = browser,
        )
    }

    /**
     * At the end of every apply() (its role take-back done): a window that is over (the role, the page, 5 minutes, the
     * pause's end, a reboot) closes for good - HOME re-seated, the roles compared with the snapshot, the moved ones
     * stored for the report (§3.2). Then the Wi-Fi step's own end, and the setup notification. Background thread.
     */
    fun afterApply(context: Context) {
        try {
            val start = window(context, KEY_PICK)
            if (windowCloses(start != null, start != null && BootClock.isActive(start, ASSISTANT_PICK_MS), setupWindowOpen())) {
                com.kidslauncher.mdm.lock.HomePin.check(context, "assistant_window")
                val before = runCatching {
                    prefs(context).getString(KEY_SNAPSHOT, null)?.let { ServerJson.decodeFromString(DefaultAppsSnapshot.serializer(), it) }
                }.getOrNull()
                val moved = before?.let { movedRoles(it, snapshot(context)) }.orEmpty()
                AirplaneRuntime.storeDefaultAppsChanged(context, moved)
                val edit = prefs(context).edit().remove(KEY_PICK).remove(KEY_SNAPSHOT)
                // Design 114 §2.2.1: the original holders, kept while a moved role is listed (the live read compares).
                if (moved.isNotEmpty() && before != null) {
                    edit.putString(KEY_MOVED_FROM, ServerJson.encodeToString(DefaultAppsSnapshot.serializer(), before))
                } else {
                    edit.remove(KEY_MOVED_FROM)
                }
                edit.commit()
                Log.i(LOG_TAG, "The default-apps window closed; moved and not taken back: $moved")
                com.kidslauncher.mdm.push.SyncRunner.request(context, "assistant_window")
            }
            if (!setupWindowOpen() && wifiStepOpen(context)) {
                val app = context.applicationContext
                Thread { endWifiStep(app, "pause_end") }.start()
            }
            refreshNotice(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "After-apply setup check failed: ${e.javaClass.simpleName}")
        }
    }

    /** Android's "Digital assistant app" page (M1), explicit to the system Settings component. */
    fun assistantPageIntents(context: Context): List<Intent> =
        listOfNotNull(systemPageIntent(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)))

    /** D10: Android's request for the call-screening and call-redirection roles, inside the same window. */
    fun screeningRoleIntents(context: Context): List<Intent> {
        val rm = context.getSystemService(RoleManager::class.java) ?: return emptyList()
        return listOf(RoleManager.ROLE_CALL_SCREENING, RoleManager.ROLE_CALL_REDIRECTION)
            .filter { runCatching { !rm.isRoleHeld(it) && rm.isRoleAvailable(it) }.getOrDefault(false) }
            .mapNotNull { runCatching { rm.createRequestRoleIntent(it) }.getOrNull() }
    }

    // --- §3.5 the Wi-Fi step ---

    fun wifiStepOpen(context: Context): Boolean = prefs(context).contains(KEY_WIFI_STEP)

    /** §3.5 step 1: the flag with its deadline, then airplane mode on through §2.2. Returns [AirplaneOn.GO] or why not.
     * Background thread; the caller opens the Wi-Fi panel after a GO. */
    fun startWifiStep(context: Context): String {
        if (!setupWindowOpen()) return AirplaneError.REFUSED
        storeWindow(context, KEY_WIFI_STEP, BootClock.windowStart(WIFI_STEP_MS))
        scheduleDeadline(context, DEADLINE_WIFI, WIFI_STEP_MS)
        // §5.1: the parent's setup step turns airplane mode on - `other`, not the kid's (code QA L2).
        val result = AirplaneRoute.turnOn(context, AirplaneBy.OTHER)
        if (result != AirplaneOn.GO && result != AirplaneOn.ALREADY_ON) {
            prefs(context).edit().remove(KEY_WIFI_STEP).commit()
            return result
        }
        return AirplaneOn.GO
    }

    /** The Wi-Fi panel (§3.5 step 2). */
    fun wifiPanelIntent(): Intent = Intent(Settings.Panel.ACTION_WIFI)

    /**
     * §3.5 steps 3-4: the step ends (Settings' resume, the panel's answer, the deadline, the pause's end, process
     * start). Wi-Fi still on in airplane mode stores `wifi_kept = true` (off stores nothing); then airplane mode goes
     * off through §2.3 and the flag goes. Background thread.
     */
    fun endWifiStep(context: Context, why: String) {
        if (!wifiStepOpen(context)) return
        try {
            if (AirplaneRuntime.isOn(context)) {
                val wifiOn = runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
                if (wifiOn) AirplaneRuntime.storeWifiKept(context, true)
                Log.i(LOG_TAG, "The Wi-Fi step ends ($why): Wi-Fi ${if (wifiOn) "stays on" else "is off"} in airplane mode")
                AirplaneRoute.turnOff(context)
            }
        } finally {
            prefs(context).edit().remove(KEY_WIFI_STEP).commit()
            AirplaneRuntime.changed()
            com.kidslauncher.mdm.push.SyncRunner.request(context, "wifi_step")
        }
    }

    /** At process start (§3.5): a Wi-Fi step left open ends. Background thread. */
    fun atProcessStart(context: Context) {
        if (wifiStepOpen(context)) endWifiStep(context, "process_start")
    }

    /** The deadline's alarm: a Wi-Fi step past its 5 minutes ends; the apply closes a default-apps window that is over. */
    fun onDeadline(context: Context) {
        val step = window(context, KEY_WIFI_STEP)
        if (wifiStepOpen(context) && (step == null || !BootClock.isActive(step, WIFI_STEP_MS))) endWifiStep(context, "deadline")
        AppEnforcer.applyCurrent(context)
    }

    /** The deadline [which] ([DEADLINE_PICK], [DEADLINE_WIFI]) [afterMs] from now; the other's alarm stays as it is. */
    private fun scheduleDeadline(context: Context, which: Int, afterMs: Long) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, SetupDeadlineReceiver::class.java).setAction(ACTION_SETUP_DEADLINE)
            val pending = PendingIntent.getBroadcast(context, which, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + afterMs + 1_000, pending)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the setup deadline: ${e.javaClass.simpleName}")
        }
    }

    // --- §3.3 the setup notification ---

    /** The steps left now: design 114's shared rule ([com.kidslauncher.mdm.setup.SetupReadings.count]), so the
     * notification's N equals the PWA's for the same report. */
    fun stepsLeft(context: Context): Int = com.kidslauncher.mdm.setup.SetupReadings.count(context)

    /** One quiet notification while the phone is open for setup and steps are left; gone otherwise. */
    fun refreshNotice(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val left = if (setupWindowOpen()) stepsLeft(context) else 0
        if (left == 0) {
            nm.cancel(SETUP_NOTIFICATION_ID)
            return
        }
        try {
            nm.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_SETUP, context.getString(R.string.notification_channel_setup), NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    },
            )
            // Design 114 §6.4: the tap opens the setup flow (only while a window is open, so no PIN).
            val open = com.kidslauncher.mdm.setup.SetupFlowActivity.intent(context)
            val tap = PendingIntent.getActivity(context, SETUP_NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_SETUP)
                .setSmallIcon(R.drawable.ic_stat_vibb)
                .setContentTitle(context.getString(R.string.setup_notice_title))
                .setContentText(context.resources.getQuantityString(R.plurals.setup_notice_steps, left, left))
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(tap)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(SETUP_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the setup notification: ${e.javaClass.simpleName}")
        }
    }
}

/** The default-apps window's and the Wi-Fi step's deadline (§3.2, §3.5). */
class SetupDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SETUP_DEADLINE) return
        val pending = goAsync()
        Thread {
            try {
                AssistantSetup.onDeadline(context.applicationContext)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Setup deadline failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
