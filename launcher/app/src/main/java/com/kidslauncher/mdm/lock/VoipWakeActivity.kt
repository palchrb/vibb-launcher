package com.kidslauncher.mdm.lock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Wakes the screen for a VoIP ring over the PIN lock (design 17, qa-16-17-code #2). AOSP decides
 * whether a start on a sleeping display turns the screen on from the activity's turn-screen-on bit
 * *at the start*, and a set bit stays: the existing, stopped lock would learn `setTurnScreenOn`
 * only in `onNewIntent`, after the start, and keep it for a later, unrelated start. This empty,
 * translucent activity has `android:turnScreenOn` in its manifest (known at the start), joins the
 * lock's task on top of it (`taskAffinity` .pinlock - our package is lock-task permitted) and
 * finishes itself; the lock with its ring card is underneath. A new instance per ring. Not
 * direct-boot-aware.
 */
class VoipWakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ if (!isFinishing) finish() }, HOLD_MS)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        // Not finishing yet: something came over it - possibly the app's own ring screen, launched
        // by SystemUI (qa-17b-code #5): then the lock never sends it again for this ring.
        PinLockRuntime.voipWakePaused(covered = !isFinishing)
        if (!isFinishing) finish()
        super.onPause()
    }

    companion object {
        /** Long enough for the window to be shown (and the screen woken), short enough not to be seen. */
        private const val HOLD_MS = 500L

        fun intent(context: Context): Intent =
            Intent(context, VoipWakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
