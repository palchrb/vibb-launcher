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

/**
 * At most this many hashes in one `wantArt` call (the launcher caps the same: bridge_contract.json). Not a cap on
 * the pictures (the user, 2026-10-19: "alle bilder lastes ned ASAP så lenge den er på wifi"): only what the phone
 * lacks goes on the wire, so as the pictures arrive the next send names the next ones. It keeps one oneway Binder
 * call well under the 1 MB transaction buffer (~136 bytes a hash: ~136 KB; qa-art-asap-code L2: two queued calls
 * share the async half).
 */
const val WANT_ART_MAX = 1000

/**
 * The `now` list: what is streaming's (the user, 2026-10-19: pictures count as streaming - the launcher fetches them
 * where streaming is allowed): the current item and five each side ("+/- x episoder", [NOW_REACH]), the next
 * [NOW_AHEAD] items the queue reaches, then the playing Spotify list-mode entry's next [SPOTIFY_ART_AHEAD] tracks
 * (qa-art-asap-code M3: never the others'). At most this many (bridge_contract.json `want_art.now_max`).
 */
const val WANT_ART_NOW_MAX = 500
const val NOW_REACH = 5
const val NOW_AHEAD = 50

/** Repeated item changes (skips) send the want set at most this often; the first goes at once. */
const val WANT_ART_GAP_MS = 2_000L

/**
 * A Spotify list-mode entry keeps no audio, so without this its track pictures came only as each
 * track neared the now window. Its next this many tracks from where a tap would start it go into
 * `now` for the playing entry (streaming's gate, the user's rule of 2026-10-19), into `later` (Wi-Fi only) for
 * every other one (qa-art-asap-code M3): ~50 KB a picture, so ~2.5 MB an entry.
 * A shuffled entry (resume off) gets its first ones in list order: a fresh permutation can't be
 * foreseen, so at worst those ~2.5 MB aren't the tracks that play next.
 */
const val SPOTIFY_ART_AHEAD = 50

/** The pictures shown last are kept a while, so swiping back and forth doesn't fetch them again (QA L4). */
const val RECENT_ART_MAX = 20

private val HASH = Regex("[0-9a-f]{64}")

/** A cover file's name: a 64-hex SHA-256. */
fun isArtHash(value: String?): Boolean = value != null && HASH.matches(value)

/**
 * What the phone asks the launcher for ([now]: where streaming is allowed; [later]: on Wi-Fi only), and every
 * picture it keeps ([keep], the held ones too - the prune's universe).
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
 * The want set. [now]: the own-art hashes of the now-playing window ([nowWindow]), the queue's next items and the
 * Spotify list-mode entries' next tracks ([nowArt]); [kept]: those of
 * every entry's keep set (the art of the episodes kept offline goes with their audio); [news]: the
 * Nytt tray's; [ahead]: anything else to fetch with `later`, after them; [all]: every
 * item's picture of every ticked entry in play order (the addendum of 2026-10-19), in `later` only when
 * [allLater] (an unmetered network), last. Library covers come their own way, so they are left out.
 * Deduplicated, [now] first, at most [WANT_ART_MAX] on the wire; the lists leave out what the phone already
 * [held]. [keep] (the prune's universe) has no cap: everything above - [all] whatever the network, so a
 * picture fetched on Wi-Fi stays while its entry is ticked - plus the [recent] pictures shown. The episode
 * list's held pictures ([list], design 39 §2.2) go right after [now] on the wire and are in [keep] too.
 */
fun wantedArt(
    now: List<String?>,
    kept: List<String?>,
    news: List<String?>,
    recent: List<String>,
    held: Set<String>,
    libraryCovers: Set<String>,
    ahead: List<String?> = emptyList(),
    list: List<String> = emptyList(),
    all: List<String?> = emptyList(),
    allLater: Boolean = false,
    /** Pictures the launcher passed over again and again ([skippedArt], qa-art-asap-code L6): off the `later` wire. */
    skip: Set<String> = emptySet(),
): WantedArt {
    fun own(hashes: List<String?>) = hashes.filter { isArtHash(it) && it !in libraryCovers }.map { it!! }.distinct()
    val nowAll = own(now).take(WANT_ART_NOW_MAX).toSet()
    val rest = own(kept + news + ahead)
    val listOwn = own(list)
    val allOwn = own(all)
    val laterWire = (rest + (if (allLater) allOwn else emptyList())).filter { it !in skip }
    val wire = nowAll + listOwn + laterWire // a set: deduplicated, in order
    val missing = wire.filter { it !in held }.take(WANT_ART_MAX)
    return WantedArt(
        now = missing.filter { it in nowAll },
        later = missing.filter { it !in nowAll },
        keep = nowAll + rest + listOwn + allOwn + recent.filter(::isArtHash),
    )
}

/**
 * What goes in `now`, in order: the window around [index] of [queue] ([nowWindow], ±[NOW_REACH]), the next
 * [NOW_AHEAD] items after the current one (no wrap: what auto-advance reaches), then [spotifyAhead]. Hashes, nulls
 * kept (wantedArt drops them).
 */
fun <T> nowArt(queue: List<T>, index: Int, spotifyAhead: List<String?>, art: (T) -> String?): List<String?> {
    val queueAhead = if (index in queue.indices) queue.drop(index + 1).take(NOW_AHEAD) else emptyList()
    return (nowWindow(queue, index) + queueAhead).map(art) + spotifyAhead
}

/** A `later` picture the launcher passed over this many syncs in a row is left off the wire ([artSkipsAfter]). */
const val ART_SKIPS_MAX = 3

/**
 * The passed-over counts after a sync's pull (qa-art-asap-code L6): the launcher fetches the last [sent] `later` page
 * in order, so a hash before the last one that arrived ([held]) that is still missing was passed over (failed, backed
 * off for good) - it counts once more; a held one leaves the counts. A hash the launcher can never fetch then stops
 * holding a slot of the page.
 */
fun artSkipsAfter(skips: Map<String, Int>, sent: List<String>, held: Set<String>): Map<String, Int> {
    val out = skips.filterKeys { it !in held }.toMutableMap()
    val last = sent.indexOfLast { it in held }
    for (hash in sent.take(last.coerceAtLeast(0))) if (hash !in held) out[hash] = (out[hash] ?: 0) + 1
    return out
}

/** The hashes passed over [ART_SKIPS_MAX] times or more. */
fun skippedArt(skips: Map<String, Int>): Set<String> = skips.filterValues { it >= ART_SKIPS_MAX }.keys

/**
 * Does a sync on a metered network leave pictures for Wi-Fi ([onWifi]: the same want set computed for an unmetered
 * network, [now]: the one sent)? Then a sync waits for an unmetered network, so the whole set is sent as soon as there
 * is one.
 */
fun wifiSyncWanted(now: WantedArt, onWifi: WantedArt): Boolean = onWifi.later.any { it !in now.later }

/**
 * When the player sends its want set after an item change ([lastSentAt]: its last send, null = none yet): at once,
 * or - a skip within [WANT_ART_GAP_MS] of the last send - at the end of that gap, so a run of skips sends twice,
 * not once per skip (the user, 2026-10-19: the playing episode's picture must come now, not 2 s later).
 */
fun wantArtDelay(lastSentAt: Long?, nowMs: Long, gap: Long = WANT_ART_GAP_MS): Long =
    if (lastSentAt == null || nowMs - lastSentAt !in 0 until gap) 0L else gap - (nowMs - lastSentAt)

/**
 * Does `LiveBridge` send this want set (design 39 §2.2, qa-39-design H1)? The player's (not [force]d): when its
 * `now` has something and differs from the last one sent. The list's ([force]): when [later] has a hash the last
 * send's [sentLater] didn't, whatever `now` is - a downloaded episode plays, its neighbours' pictures came with their
 * audio, `now` is empty (the launcher's `wantArtLists` takes an empty `now`).
 */
fun wantArtSend(now: List<String>, later: List<String>, sentNow: List<String>?, sentLater: Collection<String>?, force: Boolean): Boolean =
    if (force) later.any { it !in sentLater.orEmpty() } else now.isNotEmpty() && now != sentNow

// The episode list's pictures (design 39 §2; the user's call, not vibb's: vibb shows own art only in the playing row).

/** Does the list show a picture per row? The carousel's rule over the rows shown, so the two never disagree. */
fun rowPictures(items: List<PlayItem>, cover: String?): Boolean = showsCarousel(items, cover)

/** The rows each side of the visible ones whose pictures are fetched too. */
const val LIST_ART_MARGIN = 10

/** At most this many list pictures are held (~3 MB), whatever else wants them. */
const val LIST_ART_MAX = 60

/** The art of the visible rows [first]..[last] of [shown] (the rows' art in their order) and [margin] each side, clamped. */
fun listArtWindow(shown: List<String?>, first: Int, last: Int, margin: Int = LIST_ART_MARGIN): List<String?> {
    if (shown.isEmpty()) return emptyList()
    val from = (minOf(first, last) - margin).coerceIn(0, shown.size - 1)
    val to = (maxOf(first, last) + margin).coerceIn(0, shown.size - 1)
    return shown.subList(from, to + 1).toList()
}

/** The list's held pictures (`list_art.txt`: the entry id on the first line, then the hashes, newest window first). */
data class ListArt(val entryId: Long, val hashes: List<String>) {
    fun encode(): String = (listOf(entryId.toString()) + hashes).joinToString("\n")

    companion object {
        fun decode(text: String?): ListArt? {
            val lines = text?.lines()?.filter { it.isNotEmpty() } ?: return null
            val id = lines.first().toLongOrNull() ?: return null
            return ListArt(id, lines.drop(1).filter(::isArtHash).distinct().take(LIST_ART_MAX))
        }
    }
}

/**
 * The held list after [window] showed in [entryId]'s list: the window's own hashes first (never a library cover),
 * then what was held, deduplicated, at most [LIST_ART_MAX]; another entry's list starts empty.
 */
fun listArtAfter(before: ListArt?, entryId: Long, window: List<String?>, libraryCovers: Set<String> = emptySet()): ListArt {
    val own = window.filter { isArtHash(it) && it !in libraryCovers }.map { it!! }
    val kept = before?.takeIf { it.entryId == entryId }?.hashes.orEmpty()
    return ListArt(entryId, (own + kept).distinct().take(LIST_ART_MAX))
}

/** Does [window] hold a picture the phone lacks ([held]: the covers dir)? Only then is the want set computed (QA L7). */
fun listArtMissing(window: List<String?>, held: Set<String>, libraryCovers: Set<String>): Boolean =
    window.any { isArtHash(it) && it !in libraryCovers && it !in held }

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

/**
 * The session's pictures whose small copy must be made (again): not made yet, or made and since
 * deleted by the prune - a swipe back to an episode played days ago (QA L8). [hasCopy]: the copy is there.
 */
fun smallArtMissing(hashes: List<String?>, hasCopy: (String) -> Boolean): List<String> =
    hashes.filterNotNull().distinct().filterNot(hasCopy)

/** The queue with item [key]'s own picture now [art] (a listing applied while it played, QA L7). */
fun withItemArt(items: List<PlayItem>, key: String, art: String?): List<PlayItem> =
    items.map { if (it.key == key && it.artHash != art) it.copy(artHash = art) else it }

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
