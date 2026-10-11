package me.vibb.music.sonos

/*
 * The Sonos bookmark rules, ported from palchrb/vibb `pi/daemon.py` (`_sonos_bookmark_now`,
 * `BM_HOLD_MAX_S`, `BM_RESTART_FLOOR_S`, `BM_REGRESS_MIN_S`, `BM_USER_GRACE_S`; MIT License,
 * Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md.
 * Design 26a §4.4. Pure; SonosBookmarksTest.
 *
 * A position is written from the MEASURED snapshot only (plus the one age term while PLAYING), never
 * from a seek's guess or the display's extrapolation (QA H3). Every way vibb has lost a place looks
 * the same - the speaker reports a few seconds in because it restarted the track - so two guards
 * stand in front of the write: the refused-seek hold, bounded by real listening, and the regression
 * guard keyed on WHERE the position landed (the top of a long track), not on how far it fell.
 */

/** A refused start-seek: no write below [minPosS] for [itemKey] until [BM_HOLD_MAX_MS] of playback since [atMs]. */
data class BmHold(val itemKey: String, val minPosS: Double, val atMs: Long)

/** What one bookmark pass decided; [hold] is the hold to keep afterwards. */
data class BookmarkVerdict(val write: BookmarkWrite?, val hold: BmHold?, val why: String)

/** One write: [posMs] of [itemKey]; [durationMs] only when the SPEAKER measured it (else null). */
data class BookmarkWrite(val itemKey: String, val posMs: Long, val durationMs: Long?)

/** What the bookmark reads from the snapshot (measured values only). */
data class BookmarkInput(
    val itemKey: String?,
    val ours: Boolean,
    val transport: TransportState?,
    val relS: Double?,
    val durS: Double?,
    /** The duration came from the listing (or was carried from it), not from the speaker. */
    val durFromListing: Boolean,
    /** Elapsed time since the poll measured [relS] (the one age term, added only while PLAYING). */
    val ageMs: Long,
)

object SonosBookmarks {
    /** How long a refused-seek hold may silence writes (`BM_HOLD_MAX_S`). */
    const val BM_HOLD_MAX_MS = 120_000L

    /** A landing below this, ... (`BM_RESTART_FLOOR_S`) */
    const val BM_RESTART_FLOOR_S = 90.0

    /** ... over a saved place above this (`BM_REGRESS_MIN_S`), ... */
    const val BM_REGRESS_MIN_S = 300.0

    /** ... with no seek or deliberate restart within this (`BM_USER_GRACE_S`), keeps the bookmark. */
    const val BM_USER_GRACE_MS = 180_000L

    /**
     * `_sonos_bookmark_now` for [input]: [prevS] is the saved place of the item (vibb's
     * `episode_pos`: 0 when unknown or done), [seekAtMs] the last user seek or deliberate restart,
     * [nowMs] elapsed time. The hold is checked first and cleared by any pass that gets past it.
     */
    fun decide(input: BookmarkInput, hold: BmHold?, prevS: Double, seekAtMs: Long, nowMs: Long): BookmarkVerdict {
        val key = input.itemKey ?: return BookmarkVerdict(null, hold, "no index")
        if (!input.ours) return BookmarkVerdict(null, hold, "not ours")
        val measured = input.relS ?: return BookmarkVerdict(null, hold, "nothing measured")
        if (input.transport == TransportState.STOPPED) return BookmarkVerdict(null, hold, "stopped (reads 0:00)")
        val rel = if (input.transport == TransportState.PLAYING) measured + input.ageMs.coerceAtLeast(0) / 1000.0 else measured
        if (hold != null && hold.itemKey == key && rel < hold.minPosS) {
            if (nowMs - hold.atMs < BM_HOLD_MAX_MS) return BookmarkVerdict(null, hold, "refused-seek hold")
            // past the bound the listening is real and outranks the place we failed to resume to
        }
        if (rel < BM_RESTART_FLOOR_S && prevS > BM_REGRESS_MIN_S && nowMs - seekAtMs > BM_USER_GRACE_MS) {
            return BookmarkVerdict(null, null, "the speaker restarted the track (${prevS.toInt()}s -> ${rel.toInt()}s) - keeping the bookmark")
        }
        val duration = if (input.durFromListing) null else input.durS?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        return BookmarkVerdict(BookmarkWrite(key, (rel * 1000).toLong(), duration), null, "written")
    }
}
