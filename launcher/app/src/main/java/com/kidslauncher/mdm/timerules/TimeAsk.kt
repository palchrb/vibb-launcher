package com.kidslauncher.mdm.timerules

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.AnchorNotice
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.ui.LockAsk
import com.kidslauncher.mdm.ui.lockAsk
import java.time.LocalDate

private const val LOG_TAG = "TimeAsk"

/**
 * The one request path (design 100 §8.4): the anchor's "Be om mer tid" (`TimeAskReceiver`), Snarveier's Mer tid disc
 * (`ShortcutsRuntime.askForTime`) and the time-rule screen's "Be om mer tid" / "Spør igjen" (`LockActivity`) all ask
 * here, and nothing else calls [TimeRequests.ask]. `askStep` decides again inside, so a stale notification or screen
 * can't ask twice.
 */
object TimeAsk {
    /** The pause or the offline override is open: no time part, no ask. */
    fun windowOpen(): Boolean = OfflineOverride.isActive() || RestrictionsPause.isActive()

    /**
     * A tap: a fresh snapshot, a PENDING record when `askStep` says so, the notice at once ("Spurt kl. 14:02 · sender
     * …"), then - only for a new request - the boundary alarm (the note's 15-second mark, QA R2-L3) and a sync that
     * posts it. Any thread; never throws. True when a request was made.
     */
    fun askNow(context: Context): Boolean {
        val app = context.applicationContext
        return try {
            val snap = TimeRulesRuntime.snapshot(app, currentPolicyDecision().policy)
            val asked = TimeRequests.ask(app, LauncherPreferences.mdm().enrolled(), windowOpen(), snap)
            AnchorNotice.refresh(app, snap)
            if (asked) {
                TimeRuleAlarm.schedule(app)
                SyncRunner.request(app, "time_request")
            }
            asked
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The request for more time failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * The time-rule screen's ask block for [snap] ([lockAsk]): [ruleInForce] the screen names a rule (D4: the used-up
     * budget's screen only); the note with no time limit (D12, `NoteSurface.SCREEN`), a gift of today counted from the
     * ledger day's start in the snapshot's zone.
     */
    fun lockAskFor(context: Context, snap: TimeRulesRuntime.Snapshot, ruleInForce: Boolean): LockAsk? {
        val state = TimeRequests.state(context, snap)
        val dayStart = try {
            snap.ledger?.day?.let { LocalDate.parse(it).atStartOfDay(snap.zone).toInstant().toEpochMilli() }
        } catch (e: Exception) {
            null
        }
        val note = askNote(state, snap.policy, snap.clocks.wallMs, NoteSurface.SCREEN, dayStart)
        return lockAsk(
            ruleInForce,
            budgetToday = snap.budget?.effectiveMinutes != null,
            windowOpen = windowOpen(),
            enrolled = LauncherPreferences.mdm().enrolled(),
            state = state,
            note = note,
        )
    }
}
