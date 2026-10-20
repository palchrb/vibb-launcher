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

// --- design 50 §1.2: "Til slutten" (ours, not vibb's: the user's pick of 2026-10-20) ---

/** Is "Til slutten" offered for an item of [durationMs]? Not for one with no duration (live, a context before its first state). */
fun sleepAtEndOffered(durationMs: Long?): Boolean = durationMs != null && durationMs > 0

/** The current item's time left (ms) from the live [posMs] and [durationMs]; null when the duration isn't known. */
fun itemRemaining(posMs: Long, durationMs: Long?): Long? = durationMs?.takeIf { it > 0 }?.let { (it - posMs).coerceAtLeast(0) }

/** The gain under "Til slutten" with [remainingMs] of the item left: 1 until the renderer's [fadeMs], then down to 0. */
fun atEndGain(remainingMs: Long?, fadeMs: Long): Float =
    if (remainingMs == null || fadeMs <= 0) 1f else (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)

/** The fade's step while it runs (the phone's 250 ms), and the longest wait before the next look outside it. */
const val AT_END_STEP_MS = 250L
const val AT_END_LOOK_MS = 1_000L

/**
 * When a renderer looks at the item's time left again under "Til slutten" (ms): every [AT_END_STEP_MS] inside the
 * fade, else at the fade's start but at least every [AT_END_LOOK_MS] - so a seek or a step is seen within a second
 * and the fade is recomputed from the new place.
 */
fun atEndNextLookMs(remainingMs: Long?, fadeMs: Long): Long = when {
    remainingMs == null -> AT_END_LOOK_MS
    remainingMs > fadeMs -> minOf(remainingMs - fadeMs, AT_END_LOOK_MS).coerceAtLeast(1)
    else -> AT_END_STEP_MS
}

/**
 * The web player pauses itself this close to the end (design 50 §1.2): in list mode Spotify advances inside its window
 * by itself, so the fade's own pause comes first; the renderer's belt pauses the next track if Spotify got there anyway.
 */
const val AT_END_SPOTIFY_PAUSE_MS = 400L

/** What may end "Til slutten" (design 50 §1.2). */
enum class SleepEvent { STEP, NEW_QUEUE, KID_PAUSE, ITEM_END_PAUSE, QUEUE_ENDED, TIMER_SET }

/**
 * Is "Til slutten" still on after [event]? It survives a manual step - finish what plays (the user's call); a new
 * queue, the pause it causes, a kid's own pause, the queue's natural end and the minute timer end it.
 */
fun sleepAtEndAfter(on: Boolean, event: SleepEvent): Boolean = on && event == SleepEvent.STEP
