package me.vibb.music.logic

/*
 * Per-episode art (design 27): which pictures the phone wants, keeps and shows. The behaviour is
 * palchrb/vibb's (`pi/vibb/content.py` `_sync_episode_images`, `_image_local_or_remote`,
 * `prune_cache`; `pi/ui.py` `render_now`, `episode_art`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md: the art of the episodes kept
 * offline goes with their audio, the art of what is shown is fetched when shown, and anything
 * without its own picture (or not on the phone yet) shows the entry's cover. Here the server has
 * fetched and resized every picture once; the launcher carries them. Pure; EpisodeArtTest.
 */

/** At most this many pictures wanted at once (the launcher caps the same: bridge_contract.json). */
const val WANT_ART_MAX = 600

/** The `now` list: the current item and two each side. */
const val WANT_ART_NOW_MAX = 5
const val NOW_REACH = 2

/** The pictures shown last are kept a while, so swiping back and forth doesn't fetch them again (QA L4). */
const val RECENT_ART_MAX = 20

private val HASH = Regex("[0-9a-f]{64}")

/** A cover file's name: a 64-hex SHA-256. */
fun isArtHash(value: String?): Boolean = value != null && HASH.matches(value)

/**
 * What the phone asks the launcher for ([now]: on any network; [later]: with the audio's network
 * gate), and every picture it keeps ([keep], the held ones too - the prune's universe).
 */
data class WantedArt(val now: List<String>, val later: List<String>, val keep: Set<String>)

/**
 * The now-playing window: the item at [index] and [reach] each side of it, wrapping as previous and
 * next do - the current item first, then next, previous, and so on (a short queue lists each once).
 */
fun <T> nowWindow(queue: List<T>, index: Int, reach: Int = NOW_REACH): List<T> {
    if (index !in queue.indices) return emptyList()
    val offsets = listOf(0) + (1..reach).flatMap { listOf(it, -it) }
    return offsets.map { queue[(index + it).mod(queue.size)] }.distinct()
}

/**
 * The want set. [now]: the own-art hashes of the now-playing window ([nowWindow]); [kept]: those of
 * every entry's keep set (the art of the episodes kept offline goes with their audio); [news]: the
 * Nytt tray's. Library covers come their own way, so they are left out. Deduplicated, [now] first,
 * at most [WANT_ART_MAX] in all; the lists leave out what the phone already [held], [keep] includes
 * it, plus the [recent] pictures shown.
 */
fun wantedArt(
    now: List<String?>,
    kept: List<String?>,
    news: List<String?>,
    recent: List<String>,
    held: Set<String>,
    libraryCovers: Set<String>,
): WantedArt {
    fun own(hashes: List<String?>) = hashes.filter { isArtHash(it) && it !in libraryCovers }.map { it!! }.distinct()
    val nowAll = own(now).take(WANT_ART_NOW_MAX).toSet()
    val ordered = (nowAll + own(kept + news)).distinct().take(WANT_ART_MAX)
    return WantedArt(
        now = ordered.filter { it in nowAll && it !in held },
        later = ordered.filter { it !in nowAll && it !in held },
        keep = ordered.toSet() + recent.filter(::isArtHash),
    )
}

/** The recent pictures after [shown] (newest first), at most [RECENT_ART_MAX]. */
fun recentArt(recent: List<String>, shown: List<String?>): List<String> =
    (shown.filter(::isArtHash).map { it!! } + recent).distinct().take(RECENT_ART_MAX)

/** Cover files to delete (`prune_cache`): every one that is neither a library cover nor in the want set's universe. */
fun coversToDelete(present: Set<String>, libraryCovers: Set<String>, keep: Set<String>): Set<String> =
    present.filterTo(sortedSetOf()) { it !in libraryCovers && it !in keep }

