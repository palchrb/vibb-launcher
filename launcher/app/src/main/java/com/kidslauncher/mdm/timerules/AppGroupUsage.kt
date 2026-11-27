package com.kidslauncher.mdm.timerules

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.ClockTrust
import com.kidslauncher.mdm.server.KidModeEnforcer
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.currentPolicyDecision
import java.util.concurrent.Executors

private const val LOG_TAG = "AppGroupUsage"

/** What a read's result does (D6, §4.1 "The access"). */
enum class ReadOutcome { APPLY, NO_ACCESS, FAILED }

/** A read whose access read [before] and [after] the query aren't both granted applies nothing (the groups close by
 * the access itself); one that threw ([read] false) applies nothing and closes the groups until a read works. */
fun readOutcome(before: Boolean?, after: Boolean?, read: Boolean): ReadOutcome = when {
    !read -> ReadOutcome.FAILED
    before != true || after != true -> ReadOutcome.NO_ACCESS
    else -> ReadOutcome.APPLY
}

/**
 * Design 139 §4.1: the Android side of the app groups' fold ([groupScreenMs], pure). Each fold reads Android's usage
 * events of `[from - LATE_OVERLAP_MS, now - SETTLE_MS)` on one IO thread (a binder call; one read in flight), holds
 * them in memory for that fold only, and applies the result on the main thread to today's ledger, where the last folded
 * point and its state are saved with the minutes ([TimeRulesRuntime.applyGroupFold]). A failed or dropped read leaves
 * the point where it was, so the next fold reads the gap again.
 *
 * The access is read before and after every query: a fold without it on both sides applies nothing, and the groups
 * close ([groupStates], D6) - without the grant the query returns nothing and throws nothing. A read that throws
 * closes them too ([readFailed]) until a fold works again.
 *
 * Called by [ScreenTimeTracker] under its group gate (the screen interactive, the keyguard down) and once after the
 * screen goes off ([live] false). Main thread only, except the read.
 */
object AppGroupUsage {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "app-group-usage") }
    private val main = Handler(Looper.getMainLooper())

    /** One read in flight (main thread). */
    private var inFlight = false

    /** A fold asked for while one was in flight runs after it (main thread). */
    private var again: Boolean? = null

    /** The keys of the events applied in the last [APPLIED_KEYS_MS] (QA L4), in memory only, never stored. */
    private val appliedKeys = ArrayDeque<Pair<Long, String>>()

    /** The last read threw: the groups with a limit close until a fold works again (D6). */
    @Volatile
    var readFailed: Boolean = false
        private set

    /** Debug builds re-read the part of the last fold's window that has left the overlap (QA H1, gate row 18): the
     * window and the stamps of the events it held. */
    private var lastWindow: ReadWindow? = null

    private class ReadWindow(val from: Long, val to: Long, val stamps: LongArray)

    /** What the fold does after it applied (or found nothing to read): [ScreenTimeTracker] re-arms the group timer and
     * re-checks when a group closed or opened. */
    @Volatile
    var onFolded: ((Context) -> Unit)? = null

    /** A fold now, for the cached policy's groups. [live]: inside the gate (the live check, §4.1). */
    fun fold(context: Context, live: Boolean) {
        val app = context.applicationContext
        if (inFlight) {
            again = (again ?: false) || live
            return
        }
        val policy = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy)
        if (policy == null || policy.groups.isEmpty()) {
            // §4.1 "The first fold": the point goes with the last group, so the next group starts from a lookback.
            TimeRulesRuntime.clearFoldPoint(app)
            return
        }
        val ledger = TimeRulesRuntime.ledger(app, policy)
        val clocks = TimeRulesRuntime.clocks()
        val checked = clocks.trust == ClockTrust.SERVER || clocks.trust == ClockTrust.GNSS
        val planned = planRead(ledger.foldPoint, System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount(), checked)
        if (planned == null) {
            done(app)
            return
        }
        val music = TimeRulesRuntime.musicApps(app)
        val groups = policy.groups.map { g -> FoldGroup(g.id, g.apps.filterNot { neverGrouped(it, music) }.toSet()) }
        // D10: the setup hold's time is setup, not use - the state moves, nothing counts; the first fold after it counts
        // from its end (revision 6, gate F2). The end is written before the hold's flag clears, so it is read after.
        val count = !planned.first && !RestrictionsPause.holdActive()
        val plan = if (count) countFromHoldEnd(planned, RestrictionsPause.holdEndedElapsedMs) else planned
        trimKeys(plan.to)
        // Every fold skips this process's applied keys, not only a backward step's (the folds after it read old events).
        val skip = appliedKeys.mapTo(HashSet()) { it.second }
        val point = ledger.foldPoint
        val carry = point?.carry ?: FoldCarry()
        val recheck = if (com.kidslauncher.mdm.BuildConfig.DEBUG) lastWindow else null
        inFlight = true
        io.execute {
            val before = UsageAccess.granted(app)
            val read = try {
                if (recheck != null) runCatching { checkLate(app, recheck, plan.queryFrom) }
                Result.success(readEvents(app, plan.queryFrom, plan.to))
            } catch (e: Exception) {
                Result.failure(e)
            }
            val after = UsageAccess.granted(app)
            main.post {
                inFlight = false
                try {
                    val events = read.getOrNull()
                    when (readOutcome(before, after, events != null)) {
                        ReadOutcome.FAILED -> {
                            Log.w(LOG_TAG, "Usage events unreadable: the groups close until a read works", read.exceptionOrNull())
                            readFailed = true
                        }
                        // D6: no access on either side - an empty read is never "nothing in front".
                        ReadOutcome.NO_ACCESS -> {}
                        ReadOutcome.APPLY -> {
                            readFailed = false
                            val read = events.orEmpty()
                            val out = groupScreenMs(read, plan, carry, groups, live, count, skip)
                            if (TimeRulesRuntime.applyGroupFold(app, policy, plan, out, count, point)) {
                                appliedKeys.addAll(out.appliedKeys)
                                lastWindow = ReadWindow(plan.queryFrom, plan.to, LongArray(read.size) { read[it].timeMs })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Fold failed", e)
                }
                done(app)
                again?.let { liveAgain ->
                    again = null
                    fold(app, liveAgain)
                }
            }
        }
    }

    private fun done(context: Context) {
        try {
            onFolded?.invoke(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "After the fold: ${e.javaClass.simpleName}")
        }
    }

    private fun trimKeys(nowWall: Long) {
        while (appliedKeys.isNotEmpty() && appliedKeys.first().first < nowWall - APPLIED_KEYS_MS) appliedKeys.removeFirst()
    }

    /** `queryEvents(begin, end)`: the kinds the fold reads, in Android's order (never re-sorted, QA L5). */
    private fun readEvents(context: Context, from: Long, to: Long): List<UsageEv> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: error("no UsageStatsManager")
        val result = usm.queryEvents(from, to) ?: error("no events object")
        val out = ArrayList<UsageEv>()
        val event = UsageEvents.Event()
        while (result.hasNextEvent()) {
            result.getNextEvent(event)
            val kind = kindOf(event.eventType) ?: continue
            out += UsageEv(event.timeStamp, event.packageName ?: continue, event.className, kind)
        }
        return out
    }

    /** QA H1 (gate row 18, revision 6): the part of the last window before this read's start ([nextFrom]) read again;
     * more events than the fold saw there is an event stored after the overlap had passed it ([LATE_OVERLAP_MS]), lost
     * for good, logged as `late event`. Debug builds only. */
    private fun checkLate(context: Context, window: ReadWindow, nextFrom: Long) {
        val end = minOf(window.to, nextFrom)
        if (end <= window.from) return
        val seen = window.stamps.count { it < end }
        val now = readEvents(context, window.from, end).size
        if (now > seen) Log.w(LOG_TAG, "late event: ${now - seen} more in [${window.from}, $end)")
    }

    private fun kindOf(type: Int): UsageKind? = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED -> UsageKind.RESUMED
        UsageEvents.Event.ACTIVITY_PAUSED -> UsageKind.PAUSED
        UsageEvents.Event.ACTIVITY_STOPPED -> UsageKind.STOPPED
        UsageEvents.Event.SCREEN_INTERACTIVE -> UsageKind.SCREEN_INTERACTIVE
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageKind.SCREEN_NON_INTERACTIVE
        UsageEvents.Event.KEYGUARD_SHOWN -> UsageKind.KEYGUARD_SHOWN
        UsageEvents.Event.KEYGUARD_HIDDEN -> UsageKind.KEYGUARD_HIDDEN
        UsageEvents.Event.DEVICE_SHUTDOWN -> UsageKind.DEVICE_SHUTDOWN
        UsageEvents.Event.DEVICE_STARTUP -> UsageKind.DEVICE_STARTUP
        else -> null
    }

    /** The packages on screen at the last folded point (on or on-paused, §4.1's carry). */
    fun onScreen(context: Context): Set<String> =
        TimeRulesRuntime.foldPoint(context)?.carry?.on?.mapTo(mutableSetOf()) { it.pkg }.orEmpty()

    /**
     * D7, §4.2 "At the cut": after an apply whose groups closed [closedNow] (the closed set before: [closedBefore],
     * `null` on the first apply of the process), Home comes forward when a package on screen at the last fold belongs to
     * a group that just closed - Android finishes a suspended app's activity itself, but resumes the task below, which
     * is another app's when the kiosk is off. The VoIP call's own package is never in [closedNow] (held back).
     */
    fun afterApply(context: Context, closedNow: Set<String>, closedBefore: Set<String>?) {
        if (closedNow == closedBefore || closedNow.isEmpty()) return
        val target = cutAfterApply(closedNow, closedBefore, onScreen(context)) ?: return
        Log.i(LOG_TAG, "A group closed: Home over $target")
        com.kidslauncher.mdm.lock.HomeFront.bring(context, "app group")
    }
}
