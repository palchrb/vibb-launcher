package com.kidslauncher.mdm.lock

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.badges.GlanceStore
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.timerules.TimeRulesRuntime

private const val LOG_TAG = "GlanceOpener"

/** How long the time-rule screen may take to come up and open a target the lock handed over. */
const val GLANCE_HAND_OVER_MS = 10_000L

/**
 * A row's target taken at the unlock (memory only, redacted toString): [captured] is the
 * conversation's content intent at the tap (kept, never read - the open prefers a newer one);
 * [lockWasRoot]: the kiosk is on and the lock was the lock-task root (the 1 s fallback), read before
 * its lock task stopped.
 */
class PendingOpen(val target: GlanceTarget, val captured: PendingIntent?, val lockWasRoot: Boolean) {
    override fun toString() = "PendingOpen($target)"
}

/**
 * Opens a glance row's target after the right code (design 25 §1.4, the decision is the pure
 * [glanceOpenStep]): only from [PinLockActivity]'s leave step ([open], after Home is in front and
 * before the lock finishes, while the lock's window is still visible) - or, under a time rule, from
 * the time-rule screen the unlock brings up ([openHandedOver], QA Q1: the app opens over it, Back
 * returns there). What it does is what the kid's own tap in the shade would do right after the
 * unlock: the notification's own content intent (only one its app created, QA Low 10), sent with
 * the visible sender's opt-in ([VisibleSender]); else the app's launch intent; for missed calls the
 * contact's sheet or the phone book. Never a notification action, a reply, a mark-as-read or a
 * dismiss. It never throws (QA Medium 2): it runs inside the unlock's dispatch, where a crash would
 * count for the lock's crash guard. The log names the package and the reason only.
 */
object GlanceOpener {

    /** A target the lock handed to the time-rule screen, and when (elapsed realtime). */
    private var handedOver: PendingOpen? = null
    private var handedOverAt = 0L

    /** From [PinLockActivity]'s leave step only (the mode is UNLOCKED there). */
    fun open(activity: Activity, pending: PendingOpen) = guarded { run(activity, pending, GlanceOpenFrom.LOCK) }

    /** The time-rule screen resumed: a target the lock handed over within [GLANCE_HAND_OVER_MS] and
     * with the phone still unlocked is opened over it - if the rule leaves it usable. */
    fun openHandedOver(activity: Activity) = guarded {
        val pending = handedOver ?: return@guarded
        val age = SystemClock.elapsedRealtime() - handedOverAt
        handedOver = null
        if (age !in 0..GLANCE_HAND_OVER_MS || PinLockRuntime.mode == LockMode.LOCKED) {
            Log.i(LOG_TAG, "Glance: nothing opened (the hand-over expired)")
            return@guarded
        }
        run(activity, pending, GlanceOpenFrom.TIME_RULE_SCREEN)
    }

    /**
     * Whether a time rule that locks the phone now leaves [target] usable (QA Q1): the app among the
     * time lock's usable apps (the budget's messaging and music apps, a rule's exempt apps); missed
     * calls when calls are managed and the rule allows them (the time-rule screen's phone book). No
     * time lock: usable.
     */
    fun usableUnderTimeRule(context: Context, target: GlanceTarget): Boolean {
        val lock = TimeRulesRuntime.snapshot(context, currentPolicyDecision().policy).lock
        if (!lock.locked) return true
        return when (target) {
            is GlanceTarget.Conversation -> target.packageName in lock.usableApps
            is GlanceTarget.MissedCalls -> lock.callsAllowed && CallPolicyStore.state.managed
        }
    }

    private fun run(activity: Activity, pending: PendingOpen, from: GlanceOpenFrom) {
        val target = pending.target
        val conversation = target as? GlanceTarget.Conversation
        val what = conversation?.packageName ?: "the missed calls"
        // The conversation's newest content intent, else the one captured at the tap (the row's
        // notification may be gone by now - the room still opens).
        val intent = conversation?.let { GlanceStore.contentIntent(it.conversation) ?: pending.captured }
        val facts = facts(activity, pending, from, intent)
        var step = glanceOpenStep(facts)
        if (step.open == GlanceOpen.SEND && intent != null && !send(activity, intent)) {
            step = glanceOpenStep(facts.copy(sendFailed = true))
        }
        when (step.open) {
            GlanceOpen.HAND_TO_TIME_RULE_SCREEN -> {
                handedOver = pending
                handedOverAt = SystemClock.elapsedRealtime()
                Log.i(LOG_TAG, "Glance: $what handed to the time-rule screen")
            }
            GlanceOpen.NOTHING -> Log.i(LOG_TAG, "Glance: nothing opened (${step.reason})")
            GlanceOpen.MISSED_CALLS -> {
                val number = (target as? GlanceTarget.MissedCalls)?.number
                activity.startActivity(PhoneBookActivity.missedCallsIntent(activity, number))
                Log.i(LOG_TAG, "Glance: opened $what")
            }
            GlanceOpen.SEND -> openedThroughGlance(conversation, "intent")
            GlanceOpen.LAUNCH -> {
                val launch = conversation?.let { activity.packageManager.getLaunchIntentForPackage(it.packageName) }
                if (launch == null) {
                    Log.i(LOG_TAG, "Glance: nothing opened (no launch intent)")
                } else {
                    activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    openedThroughGlance(conversation, "app")
                }
            }
        }
    }

    /** Opened through the glance: the row stays hidden until the conversation's next message. */
    private fun openedThroughGlance(conversation: GlanceTarget.Conversation?, how: String) {
        conversation ?: return
        GlanceStore.markOpened(conversation.conversation, conversation.postTimeMs)
        Log.i(LOG_TAG, "Glance: opened ${conversation.packageName} ($how)")
    }

    /** The one send: from the caller's visible window, with the sender's opt-in. A cancelled or
     * refused intent is `false` (then the launch intent). */
    private fun send(activity: Activity, intent: PendingIntent): Boolean = try {
        intent.send(activity, 0, null, null, null, null, VisibleSender.options())
        true
    } catch (e: Exception) {
        Log.i(LOG_TAG, "Glance: the content intent couldn't be sent: ${e.javaClass.simpleName}")
        false
    }

    private fun facts(context: Context, pending: PendingOpen, from: GlanceOpenFrom, intent: PendingIntent?): GlanceOpenFacts {
        val target = pending.target
        val pkg = (target as? GlanceTarget.Conversation)?.packageName
        val pm = context.packageManager
        val installed = pkg != null && try {
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return GlanceOpenFacts(
            from = from,
            missedCalls = target is GlanceTarget.MissedCalls,
            timeRule = LauncherPreferences.mdm().lockReason() != LockReason.NONE,
            usableUnderRule = from == GlanceOpenFrom.TIME_RULE_SCREEN && usableUnderTimeRule(context, target),
            lockWasRoot = pending.lockWasRoot,
            installed = installed,
            suspended = installed && pm.isPackageSuspended(pkg!!),
            hidden = installed && hidden(context, pkg!!),
            hasIntent = intent != null,
            intentIsActivity = intent?.isActivity == true,
            intentFromApp = pkg != null && intent?.creatorPackage == pkg,
            sendFailed = false,
            hasLaunchIntent = installed && pm.getLaunchIntentForPackage(pkg!!) != null,
        )
    }

    /** Hidden by the device owner (not allowed): never started. */
    private fun hidden(context: Context, pkg: String): Boolean = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm != null && dpm.isDeviceOwnerApp(context.packageName) &&
            dpm.isApplicationHidden(ComponentName(context, MdmDeviceAdminReceiver::class.java), pkg)
    } catch (e: Exception) {
        false
    }

    /** Every entry point: nothing here may reach the crash guard. */
    private inline fun guarded(work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Glance: nothing opened (${e.javaClass.simpleName})")
        }
    }
}
