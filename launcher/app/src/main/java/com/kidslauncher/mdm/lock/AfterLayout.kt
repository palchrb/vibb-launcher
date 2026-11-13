package com.kidslauncher.mdm.lock

import android.view.View

/**
 * The lock glance's layout work, run right after the layout pass instead of inside it (E6 finding L1). A padding, a
 * margin, a visibility or a text size set from an `OnLayoutChangeListener` is a `requestLayout()` during layout, which
 * Android logs as "requestLayout() improperly called by … during layout: running second layout pass" (the glance's
 * `lock_glance_top`, `lock_glance_list` and `lock_glance_clock`). A listener hands its work to [add]; the window's
 * global-layout callback - after the pass, before the frame is drawn - runs it, in the order it was added, each key
 * once. A layout that work asks for skips that frame, so the first frame drawn is the settled page, as the second pass
 * within the frame gave it before.
 */
class AfterLayout(private val root: View) {
    private val due = LinkedHashMap<String, () -> Unit>()
    private var skip = false

    init {
        root.viewTreeObserver.addOnGlobalLayoutListener { runDue() }
        root.viewTreeObserver.addOnPreDrawListener {
            if (!skip) return@addOnPreDrawListener true
            skip = false
            false
        }
    }

    /** [work] after this layout pass (under [key]: the same key added twice in one pass runs once, at its last place). */
    fun add(key: String, work: () -> Unit) {
        due.remove(key)
        due[key] = work
    }

    private fun runDue() {
        if (due.isEmpty()) return
        val now = due.values.toList()
        due.clear()
        for (work in now) work()
        if (root.isLayoutRequested) skip = true
    }
}
