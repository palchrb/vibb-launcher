package com.kidslauncher.mdm.calls

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Round L5: the missed Element X calls the notification listener sees now ([afterElementPost] and its pair), in
 * memory only - notification key -> the contact's MXID and when it turned into a missed call. Nothing is stored,
 * logged or sent: after a restart Android binds the listener again and its sweep finds what is still there. Written
 * on the main thread (the listener's callbacks); read from any thread ([calls]); listeners run on the main thread.
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

    @Synchronized
    fun posted(post: ElementPost) = set(afterElementPost(current, post))

    @Synchronized
    fun removed(key: String) = set(afterElementRemoval(current, key))

    /** The listener's connect: every active notification ([afterElementSweep]). */
    @Synchronized
    fun swept(active: List<ElementPost>) = set(afterElementSweep(current, active))

    /** The listener lost its access or was unbound: it can't tell when one goes away, so nothing is shown. */
    @Synchronized
    fun cleared() = set(emptyMap())

    private fun set(next: Map<String, ElementMissedCall>) {
        if (next == current) return
        current = next
        main.post { listeners.forEach { it() } }
    }
}
