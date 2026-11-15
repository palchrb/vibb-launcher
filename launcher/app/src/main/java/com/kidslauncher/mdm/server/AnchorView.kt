package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.shortcuts.ShortcutsPanel
import com.kidslauncher.mdm.shortcuts.shortcutPostDelay
import com.kidslauncher.mdm.timerules.TimeLeftNotice

/*
 * What the anchor's one notification shows (design 100 §2.5, `AnchorViewTest`): pure. While Snarveier is shown, the
 * panel with the time part (design 99's row, the time line and "Spurt kl. 14:02" over it); otherwise r13's plain
 * notice. One id (1002), two channels: the glue (`AnchorNotification`) swaps them with `notify`.
 */

/** The anchor notification's view: [Plain] r13's notice, or the Snarveier [Panel] with the same time part. */
sealed interface AnchorView {
    val notice: TimeLeftNotice

    data class Plain(override val notice: TimeLeftNotice) : AnchorView

    data class Panel(val panel: ShortcutsPanel, override val notice: TimeLeftNotice) : AnchorView
}

/** The view for [panel] (`null`: no model - none yet, or it couldn't be built, Q100-M1) and [notice]: the panel only
 * while design 99 says it is shown, else the plain notice. Never nothing. */
fun anchorView(panel: ShortcutsPanel?, notice: TimeLeftNotice): AnchorView =
    if (panel == null || !panel.shown) AnchorView.Plain(notice) else AnchorView.Panel(panel, notice)

/**
 * The poster's one queued refresh (§2.2, design 99's Q99-L7): the first request after a quiet second runs at once;
 * requests inside the second ride on the one already queued, which runs at the second's end and computes the state
 * when it runs - so the last change is always posted (the trailing post, M5), and at most one post a second.
 * Thread-safe; the delays are elapsed-realtime milliseconds.
 */
class AnchorPostQueue(private val gapMs: Long = 1_000) {
    private var queued = false
    private var lastPostMs: Long? = null

    /** A change: the delay of a new refresh to schedule, or `null` when one is already queued (it will post this). */
    @Synchronized
    fun request(nowMs: Long): Long? {
        if (queued) return null
        queued = true
        return shortcutPostDelay(nowMs, lastPostMs, gapMs)
    }

    /** The queued refresh starts: a change from now on queues the next one. */
    @Synchronized
    fun started() {
        queued = false
    }

    /** A post went out at [nowMs] (the refresh's, or the anchor's `startForeground`). */
    @Synchronized
    fun posted(nowMs: Long) {
        lastPostMs = nowMs
    }
}
