package com.kidslauncher.mdm.ui.messages

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The look-less way into a thread for an intent that outlives the moment it was made - the message notification's tap
 * (design 78 §12.2 R7). A `PendingIntent` that named Meldinger's list or thread would name a class of the look of the
 * day it was posted, and after a change of look it would open a page of the other look (whose starting window the kid
 * would see before the page closes itself). This entry has no look: translucent (so no starting window), not kept in
 * Recents or the back stack, a task of its own so nothing behind it is brought forward; it asks
 * [MessagesScreens.startThread] - the applied look's classes - and closes at once. Not exported: only the launcher's
 * own notification names it.
 */
class MessagesEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION)
        if (conversationId != null) MessagesScreens.startThread(this, conversationId, null)
        finish()
    }

    companion object {
        private const val EXTRA_CONVERSATION = "me.vibb.launcher.extra.SMS_ENTRY_CONVERSATION"

        /** The notification's tap for [conversationId]; told apart by its identifier, never by a hashCode (QA L1). */
        fun intent(context: Context, conversationId: String): Intent =
            Intent(context, MessagesEntryActivity::class.java).putExtra(EXTRA_CONVERSATION, conversationId).setIdentifier(conversationId)
    }
}
