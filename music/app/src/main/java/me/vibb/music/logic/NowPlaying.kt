package me.vibb.music.logic

/*
 * The now-playing view's rules, ported from palchrb/vibb `pi/daemon.py` (`_resume_overlap` and its
 * `RESUME_OVERLAP_*` values, and the session/boot rules: a restart lands paused; MIT License,
 * Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Pure; NowPlayingTest ports tests/now_playing.py, resume_overlap.py and
 * session_window.py, as far as they concern a phone. vibb's `_settle_position` isn't needed:
 * ExoPlayer reports the seek target as its position at once.
 */

/** The play/pause button shows "playing" at once on a tap (the intent), not when audio starts. */
fun showsPlaying(playWhenReady: Boolean, ended: Boolean): Boolean = playWhenReady && !ended

/** A clause for speech, a beat for music, a run-up after a long outage. */
const val RESUME_OVERLAP_SPEECH_MS = 3_000L
const val RESUME_OVERLAP_MUSIC_MS = 1_000L
const val RESUME_OVERLAP_LONG_MS = 8_000L
const val RESUME_LONG_GAP_MS = 120_000L

/**
 * How far to back up when playback resumes after an outage (the output went, or a call took the
 * audio) - never after a tap, and only on the item that was interrupted. [music]: an entry played
 * without resume (music); [goneMs]: how long it was gone; [recentFaults]: outages in the last minute.
 * A flapping output gets nothing, or ten faults would walk the story ten clauses back.
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
