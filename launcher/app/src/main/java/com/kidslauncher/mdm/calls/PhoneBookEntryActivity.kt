package com.kidslauncher.mdm.calls

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The phone book's look-less way in from outside the process (2026-11-16, design 78 §12.2 R7, as
 * `MessagesEntryActivity`): the DIAL and `tel:` filters the dialer role requires, `tel:`/`voicemail:` links, the call
 * button and the call log - pinned as a persistent preferred activity ([com.kidslauncher.mdm.server.AppEnforcer]) and
 * named by our missed-call notification ([PhoneBookActivity.missedCallsIntent]). A filter or a `PendingIntent` that
 * named the phone book's page would name a class of one look, which after a change of look opens a page of the other
 * look (its starting window seen before it closes itself). This entry has no look: translucent (no starting window),
 * not kept in Recents or the back stack, a task of its own when started as one (empty affinity), so nothing behind it
 * is brought forward. While calls are unmanaged it passes the call log on to the system dialer; anything else starts
 * the applied look's phone book ([PhoneBookActivity.forward]), which checks a number itself. Then it closes.
 */
class PhoneBookEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asked = intent
        if (savedInstanceState == null && asked != null) {
            CallPolicyStore.ensureLoaded(this)
            if (!passOnCallLog(asked)) startActivity(PhoneBookActivity.forward(this, asked))
        }
        finish()
    }

    /**
     * The call log asked for while calls are unmanaged: the system dialer's, explicitly - our pin is permanent, so it
     * stays ours to pass on (as the Play link blocker does). Managed (or rules unknown): the phone book is the call
     * log. A fresh intent: never the caller's extras. The package comes from what `apply()` cached - no Telecom call
     * on the main thread (qa-12-code #4). Viewing the call log deals with our missed-call notification too.
     */
    private fun passOnCallLog(intent: Intent): Boolean {
        if (!isCallLogView(intent.action, intent.type, intent.dataString)) return false
        if (CallPolicyStore.effectiveState() != CallPolicyState.Unmanaged) return false
        MissedCallNotifier.dismiss(this)
        val dialer = CallPrefs.systemDialer(this)
        if (dialer == null) Log.w(LOG_TAG, "No system dialer known yet - the call log isn't passed on")
        if (dialer != null && dialer != packageName) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setType(CALL_LOG_TYPE).setPackage(dialer)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The system dialer has no call log to pass on to", e)
            }
        }
        return true
    }

    private companion object {
        const val LOG_TAG = "PhoneBookEntry"
    }
}
