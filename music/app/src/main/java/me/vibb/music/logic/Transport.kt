package me.vibb.music.logic

/*
 * Previous and next, ported from palchrb/vibb `pi/daemon.py` (`Orchestrator._command_locked`'s
 * prev/next and `PREV_RESTART_MAX_S`; MIT License, Copyright (c) 2026 palchrb), reimplemented in
 * Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Pure; TransportTest ports vibb's
 * tests/mpv_prev.py, mpv_next_wrap.py and prev_long_form.py.
 *
 * Resume rotates the queue so the bookmarked item sits in slot 0, so the item before it is at the
 * end: previous at slot 0 wraps to the end, next at the last slot wraps to slot 0. Previous more
 * than 5 s into an item restarts it - except on long-form audio (> 30 min), where a restart would
 * cost the kid their place (one press per book when browsing back, never a seek to 0).
 */

/** Previous restarts the item when more than this far into it. */
const val PREV_RESTART_AFTER_MS = 5_000L

/** Above this duration previous never restarts: it only steps back (vibb's owner's number). */
const val PREV_RESTART_MAX_MS = 30 * 60_000L

sealed interface TransportAction {
    /** Seek to the start of the current item. */
    data object Restart : TransportAction

    /** Play the item at this queue index. */
    data class GoTo(val index: Int) : TransportAction

    /** Nothing to do (a one-item queue). */
    data object Stay : TransportAction
}

/**
 * Previous at queue [index] of [count], [posMs] into an item of [durationMs] (null = unknown,
 * which behaves as before rather than as long-form).
 */
fun previousAction(index: Int, count: Int, posMs: Long, durationMs: Long?): TransportAction {
    val longForm = durationMs != null && durationMs > PREV_RESTART_MAX_MS
    if (!longForm && posMs > PREV_RESTART_AFTER_MS) return TransportAction.Restart
    return when {
        count <= 1 -> TransportAction.Stay
        index <= 0 -> TransportAction.GoTo(count - 1)
        else -> TransportAction.GoTo(index - 1)
    }
}

/** Next at queue [index] of [count]: the last slot wraps to the first. */
fun nextAction(index: Int, count: Int): TransportAction = when {
    count <= 1 -> TransportAction.Stay
    index >= count - 1 -> TransportAction.GoTo(0)
    else -> TransportAction.GoTo(index + 1)
}
