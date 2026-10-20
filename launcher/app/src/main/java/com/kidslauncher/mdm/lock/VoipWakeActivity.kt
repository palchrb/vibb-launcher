package com.kidslauncher.mdm.lock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Wakes the screen for a VoIP ring over the PIN lock (design 17, qa-16-17-code #2) and, since
 * launcher round 7 §3, for a notice the parent's switch wakes for ([WakeKind.NOTICE]). AOSP decides
 * whether a start on a sleeping display turns the screen on from the activity's turn-screen-on bit
 * *at the start*, and a set bit stays: the existing, stopped lock would learn `setTurnScreenOn`
 * only in `onNewIntent`, after the start, and keep it for a later, unrelated start. This empty,
 * translucent activity has `android:turnScreenOn` in its manifest (known at the start), joins the
 * lock's task on top of it (`taskAffinity` .pinlock - our package is lock-task permitted) and
 * finishes itself; the lock is underneath. Only [PinLockRuntime.show] starts it, right after the
 * lock's own start - never alone. A new instance per wake. Not direct-boot-aware.
 */
class VoipWakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    /** Why it was started: only a VoIP ring's wake counts for 17b's try detection. */
    private val kind: WakeKind
        get() = wakeKindOf(intent?.getStringExtra(EXTRA_KIND))

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ if (!isFinishing) finish() }, HOLD_MS)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        // Not finishing yet: something came over it - possibly the app's own ring screen, launched
        // by SystemUI (qa-17b-code #5): then the lock never sends it again for this ring. A notice
        // wake never touches the ring's bookkeeping (round 7 §3.1.3).
        if (kind == WakeKind.VOIP) PinLockRuntime.voipWakePaused(covered = !isFinishing)
        if (!isFinishing) finish()
        super.onPause()
    }

    companion object {
        /** Long enough for the window to be shown (and the screen woken), short enough not to be seen. */
        private const val HOLD_MS = 500L

        private const val EXTRA_KIND = "wake_kind"

        fun intent(context: Context, kind: WakeKind): Intent =
            Intent(context, VoipWakeActivity::class.java)
                .putExtra(EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

/** The wake's kind from its extra: anything but NOTICE is a VoIP ring's (the old behaviour). */
fun wakeKindOf(extra: String?): WakeKind = if (extra == WakeKind.NOTICE.name) WakeKind.NOTICE else WakeKind.VOIP
