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

// --- design 50 §1.1: the skip pair (ours, not vibb's: the user's pick of 2026-10-20) ---

/** "15 s tilbake" and "15 s fram" (design 50 §1.1, r12 §2: the user's pick of 2026-10-24, 15 s both ways). */
const val SKIP_BACK_MS = 15_000L
const val SKIP_FORWARD_MS = 15_000L

/** The skip button's number (r12 §2): the step's own seconds, so the text can't drift from the step. */
fun skipSeconds(ms: Long): String = (ms / 1000).toString()

/** A step forward never lands closer to the item's end than this: it stays inside the item, never moves on. */
const val SKIP_END_MARGIN_MS = 1_000L

/**
 * Where a skip of [deltaMs] from [posMs] lands (design 50 §1.1): clamped to the item - never below 0, never past
 * [durationMs] less [SKIP_END_MARGIN_MS] (a step never crosses into the next item; already that close, forward stays
 * put). [durationMs] is the live one (a Spotify context's item has no listing duration: the web player's state gives
 * it); unknown, a step forward does nothing and a step back still goes back.
 */
fun skipWithin(posMs: Long, deltaMs: Long, durationMs: Long?): Long {
    val pos = posMs.coerceAtLeast(0)
    val duration = durationMs?.takeIf { it > 0 }
    if (duration == null) return if (deltaMs < 0) (pos + deltaMs).coerceAtLeast(0) else pos
    val last = maxOf(minOf(pos, duration), duration - SKIP_END_MARGIN_MS).coerceAtLeast(0)
    return (pos + deltaMs).coerceIn(0, last)
}
