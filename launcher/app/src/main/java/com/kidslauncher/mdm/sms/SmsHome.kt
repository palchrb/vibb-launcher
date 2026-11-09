package com.kidslauncher.mdm.sms

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.ui.messages.messagesTileShown
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "SmsHome"

/**
 * What Home and the time-rule screen need of the message engine (design 77 §6.7), in memory: whether there is history,
 * the unread total (the tile's badge) and whether the SMS role is ours - read on the engine thread, never on the main
 * one. Refreshed after every engine event and rule change ([SmsRuntime]) and at Home's start; its listeners run on
 * the main thread after every refresh, so the message screens also hear of a rule change that touched no conversation.
 */
object SmsHome {
    data class Summary(val hasHistory: Boolean, val unread: Int, val roleHeld: Boolean)

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

    /** Reads the summary again on the engine thread (one refresh queued at a time), then tells the listeners. Nothing
     * before the first unlock: the store is CE. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!CallPolicyStore.userUnlocked(app)) return
        if (!pending.compareAndSet(false, true)) return
        SmsRuntime.ask(app, { engine ->
            pending.set(false)
            val conversations = engine.conversations()
            Summary(conversations.isNotEmpty(), conversations.sumOf { it.unread }, SmsRuntime.roleHeld(app))
        }) { next ->
            pending.set(false)
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
