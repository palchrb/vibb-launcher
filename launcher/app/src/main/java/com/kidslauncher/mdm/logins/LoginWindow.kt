package com.kidslauncher.mdm.logins

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.ServerJson
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

private const val LOG_TAG = "LoginWindow"
private const val PREFS = "login_window"
private const val KEY_RECORD = "record"
private const val KEY_LAST = "last_window"
private const val KEY_REFUSED = "refused"
const val ACTION_LOGIN_WINDOW_CHECK = "me.vibb.launcher.action.LOGIN_WINDOW_CHECK"

/**
 * Design 119 §4.4: the login window - Chrome unhidden for one login, the anchor ([LinkGateActivity]) under its tab, and
 * every way it ends. The record ([WindowRecord]) has a small lock of its own (M3), never the enforcer's: opening holds it
 * across "write the record, unhide Chrome", closing across "delete the record, hide Chrome", and the apply re-checks the
 * record under it right before its own Chrome step ([chromeStep]). Each holds it for one or two `dpm` calls.
 *
 * Fail closed (L5): the record names its boot and main process, so a restart's apply ([duringApply]) ends a window the
 * dead process held (`stale`) and hides Chrome; an inexact elapsed alarm ([LoginWindowReceiver]) is the timer's backstop.
 */
object LoginWindow {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** The anchors under open tabs (main thread only). */
    private val anchors = mutableListOf<WeakReference<Activity>>()

    /** The grace's start (§4.4, M4), `null` while none runs - in memory: a restart ends the window anyway. */
    @Volatile
    private var graceSince: Long? = null

    private val tick = Runnable { worker.execute { appContext?.let { check(it) } } }

    @Volatile
    private var appContext: Context? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** This main process: its pid and start, so a record of an earlier process is told apart. */
    fun processToken(): String = "${Process.myPid()}:${Process.getStartElapsedRealtime()}"

    private fun readRecord(context: Context): WindowRecord? = try {
        prefs(context).getString(KEY_RECORD, null)?.let { ServerJson.decodeFromString(WindowRecord.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    /** A window is open in this process now (the plan's input, §4.2). */
    fun isOpen(context: Context): Boolean = synchronized(lock) { openLocked(context) }

    private fun openLocked(context: Context): Boolean {
        val record = readRecord(context) ?: return false
        return loginWindowStep(record, graceSince, WindowEvent.CHECK, SystemClock.elapsedRealtime(), BootClock.bootCount(), processToken()).end == null
    }

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    /**
     * §4.4 step 1, on the gate's worker: the record written (a second link keeps the first's opening) and Chrome unhidden,
     * under the window's lock. Returns whether Chrome is out; a refused unhide removes the record again.
     *
     * Code QA L2: [stillAllowed] - the gate's conditions read again under the lock (the role ready, no lock, no pause, no
     * update window): an apply between the gate's decision and here may have turned the role off, and its
     * [duringApply] saw no record then. Cheap reads only: the lock is held for them.
     */
    fun open(context: Context, session: Boolean, stillAllowed: () -> Boolean): Boolean {
        appContext = context.applicationContext
        val opened = synchronized(lock) {
            if (!stillAllowed()) {
                Log.i(LOG_TAG, "No login window: its conditions changed before it opened")
                return@synchronized false
            }
            val now = SystemClock.elapsedRealtime()
            val record = windowOpenRecord(readRecord(context), now, System.currentTimeMillis(), BootClock.bootCount(), processToken(), session)
            prefs(context).edit().putString(KEY_RECORD, ServerJson.encodeToString(WindowRecord.serializer(), record)).commit()
            val ok = try {
                dpm(context).setApplicationHidden(admin(context), CHROME_PACKAGE, false) ||
                    !dpm(context).isApplicationHidden(admin(context), CHROME_PACKAGE)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't unhide Chrome: ${e.javaClass.simpleName}")
                false
            }
            if (!ok) prefs(context).edit().remove(KEY_RECORD).commit()
            ok
        }
        if (opened) {
            graceSince = null
            schedule(context)
            Log.i(LOG_TAG, "A login window is open")
        }
        return opened
    }

    /** The record's session flag, after the bind's answer (H2: `false` when it didn't come within 2 s). */
    fun sessionHeld(context: Context, held: Boolean) = synchronized(lock) {
        val record = readRecord(context) ?: return@synchronized
        if (record.session != held) {
            prefs(context).edit().putString(KEY_RECORD, ServerJson.encodeToString(WindowRecord.serializer(), record.copy(session = held))).commit()
        }
    }

    /** An event on the open window (any thread): the grace follows it, an end closes the window. */
    fun event(context: Context, event: WindowEvent) {
        val app = context.applicationContext
        worker.execute { step(app, event) }
    }

    /** [event] now, on the caller's thread (the apply's `rule`/`tightened`, before it hides Chrome itself). */
    fun eventNow(context: Context, event: WindowEvent) = step(context.applicationContext, event)

    private fun check(context: Context) = step(context, WindowEvent.CHECK)

    private fun step(context: Context, event: WindowEvent) {
        val ended: Pair<WindowRecord, String>? = synchronized(lock) {
            val record = readRecord(context) ?: return@synchronized null
            val result = loginWindowStep(record, graceSince, event, SystemClock.elapsedRealtime(), BootClock.bootCount(), processToken())
            val end = result.end
            if (end == null) {
                graceSince = result.graceSince
                null
            } else {
                // Delete the record, then hide Chrome - its process, its tab and any full Chrome task end. Not in a pause or
                // the override (code QA M2): every app is unhidden then, Chrome too; the pause's end hides it.
                prefs(context).edit().remove(KEY_RECORD).commit()
                graceSince = null
                if (!liftedNow()) {
                    try {
                        dpm(context).setApplicationHidden(admin(context), CHROME_PACKAGE, true)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Couldn't hide Chrome at the window's end: ${e.javaClass.simpleName}")
                    }
                }
                record to end
            }
        }
        if (ended == null) {
            schedule(context)
            return
        }
        val (record, end) = ended
        Log.i(LOG_TAG, "The login window ended: $end")
        val last = LastWindow(record.openedMs, System.currentTimeMillis(), end, record.session)
        prefs(context).edit().putString(KEY_LAST, ServerJson.encodeToString(LastWindow.serializer(), last)).apply()
        main.removeCallbacks(tick)
        cancelAlarm(context)
        main.post { finishAnchors() }
        com.kidslauncher.mdm.push.SyncRunner.request(context, "login_window")
    }

    /** A pause or the parent code's override is on: every restriction lifted, Chrome unhidden with every app. */
    private fun liftedNow(): Boolean = try {
        com.kidslauncher.mdm.server.RestrictionsPause.isActive() || com.kidslauncher.mdm.server.OfflineOverride.isActive()
    } catch (e: Exception) {
        false
    }

    /**
     * From `AppEnforcer.applyHeld`, on its thread: a window that is over (`stale` after a restart or a reboot,
     * `timeout`, `left`) ends - with Chrome hidden - and one whose rule no longer holds ends `rule` ([ruleEnds]: a time
     * rule, the role off or not ready, the gate off, Play's update window, a pause's start or end).
     */
    fun duringApply(context: Context, ruleEnds: Boolean) {
        appContext = context.applicationContext
        step(context.applicationContext, if (ruleEnds) WindowEvent.RULE else WindowEvent.CHECK)
    }

    /**
     * The apply's Chrome hide step for the login role (§4.2, M3): under the window's lock, Chrome hidden unless a window
     * is open now - whatever the plan read a moment earlier - or Play's update window is in force ([updateWindow], code
     * QA M1). Returns `setApplicationHidden`'s answer.
     */
    fun chromeStep(context: Context, dpm: DevicePolicyManager, admin: ComponentName, updateWindow: Boolean): Boolean = synchronized(lock) {
        dpm.setApplicationHidden(admin, CHROME_PACKAGE, loginChromeHidden(true, openLocked(context), updateWindow))
    }

    /** [enforceOnNewPackage][com.kidslauncher.mdm.server.AppEnforcer.enforceOnNewPackage]'s check: the role's Chrome
     * reappeared (the window's own unhide, the update window's, a Play update) - hidden again under the lock unless a
     * window is open or the update window is in force; also the screen-on's hide after the update window (M1). */
    fun chromeAppeared(context: Context, dpm: DevicePolicyManager, admin: ComponentName, updateWindow: Boolean) = synchronized(lock) {
        if (loginChromeHidden(true, openLocked(context), updateWindow)) {
            try {
                dpm.setApplicationHidden(admin, CHROME_PACKAGE, true)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't hide Chrome: ${e.javaClass.simpleName}")
            }
        }
    }

    /** At the main process's start (L5): a record of an earlier process or boot ends (`stale`), Chrome hidden. */
    fun atProcessStart(context: Context) {
        val app = context.applicationContext
        appContext = app
        worker.execute {
            try {
                if (synchronized(lock) { readRecord(app) } != null) check(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The start's window check failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // --- the anchors ---

    fun anchorStarted(activity: Activity) {
        anchors.removeAll { it.get() == null || it.get() === activity }
        anchors += WeakReference(activity)
    }

    fun anchorGone(activity: Activity) {
        anchors.removeAll { it.get() == null || it.get() === activity }
    }

    /** Main thread: every anchor finishes; one that is its own task's root (a `NEW_TASK` caller, L3) takes the task. */
    private fun finishAnchors() {
        val live = anchors.mapNotNull { it.get() }
        anchors.clear()
        for (anchor in live) {
            if (anchor.isFinishing) continue
            (anchor as? LinkGateActivity)?.endedByWindow()
            if (anchor.isTaskRoot) anchor.finishAndRemoveTask() else anchor.finish()
        }
    }

    // --- the timer and its backstop ---

    private fun schedule(context: Context) {
        val record = synchronized(lock) { readRecord(context) } ?: return
        val at = windowDeadline(record, graceSince)
        main.removeCallbacks(tick)
        main.postDelayed(tick, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 50L)
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            // Inexact (L5): no exact-alarm permission; the handler is the timer, this only wakes a quiet process.
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, record.openedElapsed + LOGIN_WINDOW_MAX_MS + 1_000L, alarm(context))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the window's alarm: ${e.javaClass.simpleName}")
        }
    }

    private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 9901,
        Intent(context, LoginWindowReceiver::class.java).setAction(ACTION_LOGIN_WINDOW_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelAlarm(context: Context) {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(alarm(context))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't cancel the window's alarm: ${e.javaClass.simpleName}")
        }
    }

    /** The alarm: the window checked now (a `timeout` ends it). */
    internal fun onAlarm(context: Context) {
        appContext = context.applicationContext
        check(context.applicationContext)
    }

    // --- the report ---

    /** The report's `last_window` (§7). */
    fun lastWindow(context: Context): LastWindow? = try {
        prefs(context).getString(KEY_LAST, null)?.let { ServerJson.decodeFromString(LastWindow.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    private val refusedSerializer = kotlinx.serialization.builtins.ListSerializer(RefusedHost.serializer())

    /** The report's `refused` (§7, L9): the last 10 stopped hosts of the last 7 days. */
    fun refused(context: Context): List<RefusedHost> = refusedKept(storedRefused(context), com.kidslauncher.mdm.server.TrustedClock.nowMs())

    private fun storedRefused(context: Context): List<RefusedHost> = try {
        prefs(context).getString(KEY_REFUSED, null)?.let { ServerJson.decodeFromString(refusedSerializer, it) }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** A link the gate or Meldinger stopped (§4.3 step 6): its host kept for the PWA's "Stoppet nylig". */
    fun refuse(context: Context, url: String) {
        val host = refusedHostOf(url) ?: return
        synchronized(refusedSerializer) {
            val list = refusedWith(storedRefused(context), host, com.kidslauncher.mdm.server.TrustedClock.nowMs())
            prefs(context).edit().putString(KEY_REFUSED, ServerJson.encodeToString(refusedSerializer, list)).apply()
        }
    }
}

/** The window's backstop alarm (L5): checked in the main process, which the alarm starts if it is gone. */
class LoginWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOGIN_WINDOW_CHECK) return
        val pending = goAsync()
        Thread {
            try {
                LoginWindow.onAlarm(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
