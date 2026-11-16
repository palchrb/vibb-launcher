package com.kidslauncher.mdm.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Design 106 §3.3 (Q3): the missed Element X calls Siste shows, in CE prefs `element_call_log` (versioned `v1`) - the
 * contact's number, the call's time, a hash of the notification's key and whether it is still open; never a room ID or
 * an MXID, never in DE storage, never logged or sent. Written from [ElementMissedCallStore]'s own changes ([changed],
 * the rule is the pure [elementCallLogAfter]), pruned at each [CallPolicyStore.refresh] ([prune]) and filtered on every
 * read ([shown]). Never throws.
 *
 * Code QA L5: [changed] and [prune] are called on the main thread (the listener's callbacks, a refresh), so they only
 * queue their work - the prefs read, the JSON, the hashing and the write run on one background thread, in order; the
 * decoded list is kept in memory after its first read.
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

    /** One thread, so the changes are applied in the order the live store made them. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "element-call-log").apply { isDaemon = true } }

    /** The decoded records after the first read (guarded by `this`); `null` = not read yet. */
    private var cached: List<ElementCallRecord>? = null

    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** The live store went from [before] to [after] by [change]: the records follow ([elementCallLogAfter]), on the
     * worker. The rules are the ones in force now, as the live store judged the post by. */
    fun changed(before: Map<String, ElementMissedCall>, after: Map<String, ElementMissedCall>, change: ElementLogChange) {
        if (change == ElementLogChange.CLEARED) return
        if (change != ElementLogChange.SWEPT && before == after) return
        val context = app ?: return
        val rules = (CallPolicyStore.callsState as? CallPolicyState.Managed)?.rules
        val nowMs = System.currentTimeMillis()
        queue {
            val byMxid = rules?.let(::elementNumberByMxid).orEmpty()
            update(context) { stored -> elementCallLogAfter(stored, before, after, change, { byMxid[it] }, nowMs) }
        }
    }

    /** At each CE refresh of the call rules ([pruneElementCallLog]), on the worker. */
    fun prune(context: Context, state: CallPolicyState) {
        val app = context.applicationContext
        val nowMs = System.currentTimeMillis()
        queue { update(app) { stored -> pruneElementCallLog(stored, state, nowMs) } }
    }

    /** What Siste may show now ([shownElementCallLog]); empty when unreadable. A background thread (the phone book's
     * load). */
    fun shown(context: Context, state: CallPolicyState, nowMs: Long): List<ElementCallRecord> = try {
        shownElementCallLog(read(context.applicationContext), state, nowMs)
    } catch (e: Exception) {
        emptyList()
    }

    private fun queue(work: () -> Unit) {
        try {
            worker.execute {
                try {
                    work()
                } catch (e: Exception) {
                    // The next change writes again; the badge reads the live store anyway.
                }
            }
        } catch (e: Exception) {
            // Rejected (never shut down): nothing to do.
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun read(context: Context): List<ElementCallRecord> =
        cached ?: decodeElementCallLog(prefs(context).getString(KEY, null)).also { cached = it }

    /** The records after [next] of what is stored, kept in memory and written when they changed. */
    private fun update(context: Context, next: (List<ElementCallRecord>) -> List<ElementCallRecord>) {
        synchronized(this) {
            val before = read(context)
            val after = next(before)
            if (after == before) return
            cached = after
            prefs(context).edit { if (after.isEmpty()) remove(KEY) else putString(KEY, encodeElementCallLog(after)) }
        }
        listeners.forEach { main.post(it) }
    }
}
