package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem

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


/**
 * The picture for the media session and the launcher's surfaces (vibb `render_now`'s
 * `artwork_local`): the item's own art when the phone [has] it, else the entry's cover - never a
 * picture that isn't there.
 */
fun artOrCover(item: PlayItem, entry: Entry, has: (String) -> Boolean): String? =
    item.artHash?.takeIf(has) ?: entry.cover

// The Now Playing carousel (design 27 §3; new here - vibb's Now Playing is a single cover).

/**
 * Does the player show the carousel for [queue] (the queue in its order, `NowView.queue`)? When it
 * has more than one item and its pages show at least two different pictures, the entry's [cover]
 * (the fallback) counting as one (the user's decisions, QA H4). A podcast where one episode has its
 * own picture gets it; an album whose songs all carry one cover, or a podcast without episode art,
 * looks as before. Over the listing's hashes, not the files on the phone, so it never flickers.
 */
fun showsCarousel(queue: List<PlayItem>, cover: String?): Boolean =
    queue.size > 1 && queue.asSequence().map { it.artHash ?: cover }.distinct().take(2).count() == 2

/** A swipe's skip: the queue index it landed on, and the direction it travelled. */
data class SwipeSkip(val index: Int, val forward: Boolean)

/**
 * The pager settled on [settledPage] (virtual, as the library's carousel) coming from [fromPage]
 * while the screen showed queue index [shownIndex] of [n]: one skip to where it landed - a fling
 * across several pages is one skip - or null when it landed on the item shown.
 */
fun swipeSkip(fromPage: Int, settledPage: Int, n: Int, shownIndex: Int): SwipeSkip? {
    if (n <= 1 || settledPage == fromPage) return null
    val index = carouselIndex(settledPage, n)
    return if (index == shownIndex) null else SwipeSkip(index, settledPage > fromPage)
}

/** What the pager does to follow the player. */
sealed interface PagerFollow {
    data object Stay : PagerFollow

    /** One page: animated. */
    data class Animate(val page: Int) : PagerFollow

    /** Further: straight there. */
    data class Jump(val page: Int) : PagerFollow
}

/**
 * The pager on [currentPage] follows the item shown ([shownIndex] of [n]) when the player's index
 * changed for any other reason (the buttons, a headset, auto-advance, a pick): one page animated,
 * further by a jump, the short way round. Never while it is dragged or flung ([scrolling], QA M7 a),
 * and not while its own skip is pending: [holdFrom] is the index shown when it asked, and while that
 * still shows the pager isn't pulled back.
 */
fun pagerFollow(currentPage: Int, shownIndex: Int, n: Int, scrolling: Boolean, holdFrom: Int?): PagerFollow {
    if (n <= 1 || scrolling || shownIndex !in 0 until n || shownIndex == holdFrom) return PagerFollow.Stay
    val at = carouselIndex(currentPage, n)
    if (at == shownIndex) return PagerFollow.Stay
    val forward = (shownIndex - at).mod(n)
    val delta = if (forward <= n - forward) forward else forward - n
    val page = (currentPage + delta).coerceIn(0, carouselPageCount(n) - 1)
    return if (delta == 1 || delta == -1) PagerFollow.Animate(page) else PagerFollow.Jump(page)
}

/**
 * The player's `SKIP_TO` (a swipe): the queue index of [key] in [keys], or null when the command is
 * for another entry than the one loaded (a stale pager after a Nytt tap, QA M7 c), the key isn't in
 * the queue, or it is the item playing. Unlike previous, never a restart: the kid sees the picture
 * she picked.
 */
fun swipeTarget(entryLoaded: Boolean, keys: List<String>, current: Int, key: String): Int? {
    if (!entryLoaded) return null
    val index = keys.indexOf(key)
    return index.takeIf { it >= 0 && it != current }
}

/**
 * The first index from [index] on (or back, ![forward]) that can play now ([playable]), wrapping
 * when [wrap]; null when none can. An unplayable swipe target skips on in its direction, as next.
 */
fun firstPlayable(n: Int, index: Int, forward: Boolean, wrap: Boolean, playable: (Int) -> Boolean): Int? {
    for (step in 0 until n) {
        val i = if (forward) index + step else index - step
        val j = if (wrap) i.mod(n) else i
        if (j !in 0 until n) return null
        if (playable(j)) return j
    }
    return null
}
