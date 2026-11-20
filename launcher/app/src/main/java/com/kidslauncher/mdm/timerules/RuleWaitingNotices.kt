package com.kidslauncher.mdm.timerules

import android.content.Context
import android.util.Log

private const val LOG_TAG = "RuleWaitingNotices"

/**
 * What a time rule without calls kept from the shade (the user, 2026-11-20: the phone is "dumb" during a rule) -
 * Meldinger's notifications (`sms/SmsRuleWait.kt`) and the missed-call notification (`noticeStepUnderRule`) - is posted
 * once, quietly, when calls are allowed again: the rule's end, a lift, the parent code's pause, the pause and the
 * override (every restriction is liftable by the pause). Called from every place the lock is decided -
 * [TimeRulesRuntime.recheck] and `AppEnforcer.apply` - and cheap when nothing waits: each side reads its own mark and
 * the call path's live answer (`TimeRulesStore.callsBlockedNow`) on its own thread. Any thread; never throws.
 */
object RuleWaitingNotices {
    fun release(context: Context) {
        try {
            com.kidslauncher.mdm.sms.SmsRuntime.releaseAfterRule(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Messages release failed: ${e.javaClass.simpleName}")
        }
        try {
            com.kidslauncher.mdm.calls.MissedCallNotifier.releaseAfterRule(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Missed-call release failed: ${e.javaClass.simpleName}")
        }
    }
}
