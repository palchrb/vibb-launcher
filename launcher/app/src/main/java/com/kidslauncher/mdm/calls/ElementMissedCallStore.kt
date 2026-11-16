package com.kidslauncher.mdm.calls

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Round L5: the missed Element X calls the notification listener sees now ([afterElementPost] and its pair), in
 * memory only - notification key -> the contact's MXID and when it turned into a missed call. Nothing of it is stored,
 * logged or sent: after a restart Android binds the listener again and its sweep finds what is still there. Design
 * 106 §3.3: each change also tells [ElementCallLogStore], which keeps Siste's small record (a number, a time, a hash of
 * the key - never the key or the MXID). Written on the main thread (the listener's callbacks); read from any thread
 * ([calls], [entries]); listeners run on the main thread.
 */
object ElementMissedCallStore {
    @Volatile
    private var current: Map<String, ElementMissedCall> = emptyMap()

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Every missed Element X call of a contact the listener sees now. */
    val calls: Collection<ElementMissedCall> get() = current.values

    /** The same by notification key (design 106 §3.2: a Siste row finds its live entry by the key's hash). */
    val entries: Map<String, ElementMissedCall> get() = current

    @Synchronized
    fun posted(post: ElementPost) = set(afterElementPost(current, post), ElementLogChange.POSTED)

    @Synchronized
    fun removed(key: String) = set(afterElementRemoval(current, key), ElementLogChange.REMOVED)

    /** The listener's connect: every active notification ([afterElementSweep]). */
    @Synchronized
    fun swept(active: List<ElementPost>) = set(afterElementSweep(current, active), ElementLogChange.SWEPT)

    /** The listener's sweep of Element X's active notifications, while it is connected ([attach]). */
    @Volatile
    private var resweep: (() -> Unit)? = null

    fun attach(sweep: () -> Unit) {
        resweep = sweep
    }

    fun detach(sweep: () -> Unit) {
        if (resweep === sweep) resweep = null
    }

    /**
     * The call rules changed the phone-book Element contacts ([elementMissedRejudgeDue]): the listener judges every
     * active notification again on the main thread. From any thread; never throws.
     */
    fun rejudge() {
        main.post {
            try {
                resweep?.invoke()
            } catch (e: Exception) {
                // Unbound in between: the next connect sweeps anyway.
            }
        }
    }

    /** The listener lost its access or was unbound: it can't tell when one goes away, so nothing is shown. */
    @Synchronized
    fun cleared() = set(emptyMap(), ElementLogChange.CLEARED)

    private fun set(next: Map<String, ElementMissedCall>, change: ElementLogChange) {
        // Before the early return: a sweep that changes nothing here may still close a record (design 106 §3.3).
        ElementCallLogStore.changed(current, next, change)
        if (next == current) return
        current = next
        main.post { listeners.forEach { it() } }
    }
}
