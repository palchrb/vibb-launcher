package com.kidslauncher.mdm.ui.messages

import android.content.Context
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.smsMode

/**
 * The threads' unsent texts (design 78 §5.4.4): kept in this process's memory and in CE prefs (`sms_drafts`, the
 * launcher's own, never sent anywhere), so a text survives a change of look - which closes Meldinger's task - and a
 * process that dies while the kid is elsewhere. The rules are [keepText], [keptTextDropped] and [keptTextsStay]
 * (MessagesModelTest); this only reads and writes them. A text goes when it is sent or emptied, when its conversation
 * is deleted, when the kid may no longer text there, and all of them when SMS is off or the phone unmanaged - checked
 * at every read, so none is shown after that. The picture picked for a draft stays in memory only (the JPEG is the
 * engine's). Main thread; never read before the first unlock (Meldinger's screens are not direct-boot-aware).
 */
object ThreadDrafts {
    private const val FILE = "sms_drafts"

    private var cache: Map<String, KeptText>? = null

    /** Every kept text by its thread's key ([draftKeyOf]); none while no text may stay ([keptTextsStay]). */
    fun all(context: Context): Map<String, KeptText> {
        if (!keptTextsStay(CallPolicyStore.state.smsMode)) {
            if (cache?.isEmpty() != true) clear(context)
            return emptyMap()
        }
        return cache ?: read(context).also { cache = it }
    }

    /** The thread [key]'s field holds [text] now (empty: nothing to keep). */
    fun keep(context: Context, key: String, text: String, clientId: String) =
        write(context, keepText(all(context), key, text, clientId, System.currentTimeMillis()))

    fun drop(context: Context, key: String) {
        val kept = all(context)
        if (key in kept) write(context, kept - key)
    }

    /** A thread opened by number learned its conversation: its text moves to the conversation's key. */
    fun move(context: Context, from: String, to: String) {
        val kept = all(context)
        val text = kept[from] ?: return
        write(context, kept - from + (to to text))
    }

    private fun clear(context: Context) {
        cache = emptyMap()
        prefs(context).edit().clear().apply()
    }

    private fun read(context: Context): Map<String, KeptText> =
        prefs(context).all.mapNotNull { (key, value) -> decodeKeptText(value as? String)?.let { key to it } }.toMap()

    private fun write(context: Context, next: Map<String, KeptText>) {
        val before = cache.orEmpty()
        cache = next
        val edit = prefs(context).edit()
        for (key in before.keys - next.keys) edit.remove(key)
        for ((key, kept) in next) if (before[key] != kept) edit.putString(key, encodeKeptText(kept))
        edit.apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
