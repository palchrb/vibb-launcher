package com.kidslauncher.mdm.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.appdisplay.BUILTIN_MESSAGES
import com.kidslauncher.mdm.appdisplay.onHomeListable
import com.kidslauncher.mdm.badges.GlanceSmsRow
import com.kidslauncher.mdm.badges.GlanceStore
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
    data class Summary(
        val hasHistory: Boolean,
        val unread: Int,
        val roleHeld: Boolean,
        val unreadByNumber: Map<String, Int> = emptyMap(),
        /** The lock glance's "Meldinger" rows (design 77 §6.8, [smsGlanceRow]): one per conversation with unread
         * messages, newest first; the text only while the parent's switch is on. */
        val glance: List<GlanceSmsRow> = emptyList(),
    )

    @Volatile
    var summary = Summary(hasHistory = false, unread = 0, roleHeld = false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private val pending = AtomicBoolean(false)

    fun addListener(listener: () -> Unit) = main.post { listeners += listener }

    fun removeListener(listener: () -> Unit) = main.post { listeners -= listener }

    /** Home's Meldinger tile ([messagesTileShown]), and the time-rule screen's row with it. Design 82 W2: not while the
     * parent keeps Meldinger off Home ([onHomeListable], as every app: back during a pause or an override) - messages
     * still arrive and notify, and a thread still opens from the notification, the lock's row and the phone book. */
    fun tileShown(): Boolean {
        val windowOpen = com.kidslauncher.mdm.server.OfflineOverride.isActive() || com.kidslauncher.mdm.server.RestrictionsPause.isActive()
        return onHomeListable(AppDisplay.builtin(BUILTIN_MESSAGES), windowOpen) &&
            messagesTileShown(CallPolicyStore.state.smsMode, summary.roleHeld, summary.hasHistory)
    }

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
            // Design 82 D4: the parent's level for Meldinger on the lock - no rows at Off, the text only at Full text.
            val level = smsLockLevel(GlanceStore.policy.sms, CallPolicyStore.state)
            // Design 82 D9: each row carries Meldinger's look, so a changed icon makes the rows differ and the lock renders.
            val look = AppDisplay.messagesLook()
            val glance = if (level == SmsLockLevel.OFF) {
                emptyList()
            } else {
                conversations.filter { it.unread > 0 }
                    .mapNotNull { smsGlanceRow(it, engine.messages(it.id, null, SMS_GLANCE_LOOKBACK), showText = level == SmsLockLevel.TEXT)?.copy(look = look) }
                    .sortedByDescending { it.timeMs }
            }
            Summary(conversations.isNotEmpty(), conversations.sumOf { it.unread }, SmsRuntime.roleHeld(app), unreadByNumber(conversations), glance)
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
