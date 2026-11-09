package com.kidslauncher.mdm.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.ui.messages.messagesTileShown
import com.kidslauncher.mdm.ui.messages.unreadByNumber
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "SmsHome"

/**
 * What Home and the time-rule screen need of the message engine (design 77 §6.7), in memory: whether there is history,
 * the unread total (the tile's badge) and whether the SMS role is ours - read on the engine thread, never on the main
 * one. Refreshed after every engine event and rule change ([SmsRuntime]) and at Home's start; its listeners run on
 * the main thread after every refresh, so the message screens also hear of a rule change that touched no conversation.
 */
object SmsHome {
    /** [unreadByNumber]: each one-to-one conversation's unread, by its number - the contact avatars' badge (§6.7,
     * [com.kidslauncher.mdm.ui.messages.unreadByNumber]). */
    data class Summary(val hasHistory: Boolean, val unread: Int, val roleHeld: Boolean, val unreadByNumber: Map<String, Int> = emptyMap())

    @Volatile
    var summary = Summary(hasHistory = false, unread = 0, roleHeld = false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private val pending = AtomicBoolean(false)

    fun addListener(listener: () -> Unit) = main.post { listeners += listener }

    fun removeListener(listener: () -> Unit) = main.post { listeners -= listener }

    /** Home's Meldinger tile ([messagesTileShown]). */
    fun tileShown(): Boolean = messagesTileShown(CallPolicyStore.state.smsMode, summary.roleHeld, summary.hasHistory)


    /** A contact's unread messages (its avatar's badge, §6.7). */
    fun unreadFrom(number: String): Int = summary.unreadByNumber[number] ?: 0

    /** Reads the summary again on the engine thread (one refresh queued at a time), then tells the listeners. Nothing
     * before the first unlock: the store is CE. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!CallPolicyStore.userUnlocked(app)) return
        if (!pending.compareAndSet(false, true)) return
        SmsRuntime.ask(app, { engine ->
            // Cleared as the read begins: a change after this point queues the next read (code QA N5: only here).
            pending.set(false)
            val conversations = engine.conversations()
            Summary(conversations.isNotEmpty(), conversations.sumOf { it.unread }, SmsRuntime.roleHeld(app), unreadByNumber(conversations))
        }) { next ->
            // No engine (the work never ran): nothing cleared the flag.
            if (next == null) pending.set(false)
            if (next != null) summary = next
            for (listener in listeners.toList()) {
                try {
                    listener()
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "A message listener failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
