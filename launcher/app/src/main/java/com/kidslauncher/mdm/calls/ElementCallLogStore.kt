package com.kidslauncher.mdm.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Design 106 §3.3 (Q3): the missed Element X calls Siste shows, in CE prefs `element_call_log` (versioned `v1`) - the
 * contact's number, the call's time, a hash of the notification's key and whether it is still open; never a room ID or
 * an MXID, never in DE storage, never logged or sent. Written from [ElementMissedCallStore]'s own changes ([changed],
 * the rule is the pure [elementCallLogAfter]), pruned at each [CallPolicyStore.refresh] ([prune]) and filtered on every
 * read ([shown]). Never throws.
 */
object ElementCallLogStore {
    private const val PREFS = "element_call_log"
    private const val KEY = "v1"

    /** The application's context (never an activity's), from the notification listener's connect - every live change
     * comes from it. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    @Volatile
    private var app: Context? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** The live store went from [before] to [after] by [change]: the records follow ([elementCallLogAfter]). */
    @Synchronized
    fun changed(before: Map<String, ElementMissedCall>, after: Map<String, ElementMissedCall>, change: ElementLogChange) {
        if (change == ElementLogChange.CLEARED) return
        if (change != ElementLogChange.SWEPT && before == after) return
        val context = app ?: return
        try {
            val rules = (CallPolicyStore.callsState as? CallPolicyState.Managed)?.rules
            val stored = read(context)
            val next = elementCallLogAfter(
                stored, before, after, change, { mxid -> rules?.let { elementContactNumber(it, mxid) } }, System.currentTimeMillis(),
            )
            write(context, stored, next)
        } catch (e: Exception) {
            // The next change writes again; the badge reads the live store anyway.
        }
    }

    /** At each CE refresh of the call rules ([pruneElementCallLog]). */
    @Synchronized
    fun prune(context: Context, state: CallPolicyState) {
        try {
            val stored = read(context)
            write(context, stored, pruneElementCallLog(stored, state, System.currentTimeMillis()))
        } catch (e: Exception) {
            // Tried again at the next refresh; a read filters anyway.
        }
    }

    /** What Siste may show now ([shownElementCallLog]); empty when unreadable. */
    @Synchronized
    fun shown(context: Context, state: CallPolicyState, nowMs: Long): List<ElementCallRecord> = try {
        shownElementCallLog(read(context), state, nowMs)
    } catch (e: Exception) {
        emptyList()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<ElementCallRecord> = decodeElementCallLog(prefs(context).getString(KEY, null))

    private fun write(context: Context, before: List<ElementCallRecord>, after: List<ElementCallRecord>) {
        if (after == before) return
        prefs(context).edit { if (after.isEmpty()) remove(KEY) else putString(KEY, encodeElementCallLog(after)) }
        listeners.forEach { main.post(it) }
    }
}
