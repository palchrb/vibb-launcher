package me.vibb.music.logic

/*
 * The now-playing view's rules, ported from palchrb/vibb `pi/daemon.py` (`_settle_position`,
 * `POSITION_SETTLE_MAX_S`, `_resume_overlap` and its `RESUME_OVERLAP_*` values, and the
 * session/boot rules: a restart lands paused, the first tap ends the old session; MIT License,
 * Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Pure; NowPlayingTest ports tests/now_playing.py, resume_overlap.py and
 * session_window.py, as far as they concern a phone.
 */

/** The position display holds at a resume target at most this long after a start. */
const val POSITION_SETTLE_MAX_MS = 20_000L

/** Within this of the target, the seek has landed. */
const val POSITION_SETTLE_TOL_MS = 3_000L

/**
 * The position to show while the player seeks to a resume target ([resumeMs]): the target until
 * the live position reaches it, then live - bounded to [POSITION_SETTLE_MAX_MS] after the start,
 * never after the user moved the position, and not for a fresh start (target within the first
 * [RESUME_MIN_MS]).
 */
fun settledPosition(liveMs: Long?, resumeMs: Long, sinceStartMs: Long, userSeeked: Boolean): Long {
    val live = liveMs ?: 0
    if (userSeeked || resumeMs <= RESUME_MIN_MS || sinceStartMs > POSITION_SETTLE_MAX_MS) return live
    return if (liveMs == null || liveMs < resumeMs - POSITION_SETTLE_TOL_MS) resumeMs else live
}

/** The play/pause button shows "playing" at once on a tap (the intent), not when audio starts. */
fun showsPlaying(playWhenReady: Boolean, ended: Boolean): Boolean = playWhenReady && !ended

/** A clause for speech, a beat for music, a run-up after a long outage. */
const val RESUME_OVERLAP_SPEECH_MS = 3_000L
const val RESUME_OVERLAP_MUSIC_MS = 1_000L
const val RESUME_OVERLAP_LONG_MS = 8_000L
const val RESUME_LONG_GAP_MS = 120_000L

/**
 * How far to back up when playback resumes after an outage (the output went, or a call took the
 * audio) - never after a tap. [goneMs]: how long it was gone; [recentFaults]: outages in the last
 * minute. A flapping output gets nothing, or ten faults would walk the story ten clauses back.
 */
fun resumeOverlapMs(music: Boolean, goneMs: Long, recentFaults: Int): Long = when {
    recentFaults > 1 && goneMs < 15_000 -> 0
    goneMs > RESUME_LONG_GAP_MS -> RESUME_OVERLAP_LONG_MS
    music -> RESUME_OVERLAP_MUSIC_MS
    else -> RESUME_OVERLAP_SPEECH_MS
}

/** Where a resume after an outage starts: [posMs] minus the overlap, never below 0. */
fun overlapStart(posMs: Long, overlapMs: Long): Long = (posMs - overlapMs).coerceAtLeast(0)

/** The fade at the end of the sleep timer (21 §4.6, ~10 s). */
const val SLEEP_FADE_MS = 10_000L

/** The player volume with [remainingMs] left on the sleep timer: 1 until the fade, then down to 0. */
fun sleepVolume(remainingMs: Long): Float = (remainingMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
