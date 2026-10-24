package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.playback.RendererState
import kotlin.random.Random

/*
 * The favourites queue (design r12 §3; ours, not vibb's: the user's decisions of 2026-10-24, PLAN's "Music: what a
 * heart means" option B, replacing r8's option C): under "Favoritter" one tile plays every heart of every entry in one
 * queue, newest heart first. The service plays it as runs - the consecutive refs of one entry, each an ordinary
 * one-entry queue on that entry's renderer - and steps across them itself. Pure; MixTest.
 */

/** The favourites queue's own rows (shuffle, repeat): never an entry key (the server's `state_key` is [a-z0-9_-]+ or `own-<id>`). */
const val MIX_KEY = "vibb:favourites"

/** One hearted item: its entry's KEY (stable across an id change, as every per-entry state) and its item key. */
data class MixRef(val entryKey: String, val itemKey: String)

/**
 * Every heart [entries] (library order) still list ([listed]: an entry's keys in reading order), newest heart first;
 * [hearts]: entry key -> item key -> heartedAt (0 = set before Room 8), ties by entry order, then reading order. A
 * heart on an item the listing dropped, or in an entry the library no longer holds, is out (it waits for `Sync`'s prune).
 */
fun mixOrder(entries: List<Entry>, listed: (Entry) -> List<String>, hearts: Map<String, Map<String, Long>>): List<MixRef> {
    data class Row(val ref: MixRef, val at: Long, val entry: Int, val item: Int)
    val rows = mutableListOf<Row>()
    entries.forEachIndexed { e, entry ->
        val of = hearts[entry.key] ?: return@forEachIndexed
        listed(entry).forEachIndexed { i, key -> of[key]?.let { rows += Row(MixRef(entry.key, key), it, e, i) } }
    }
    return rows.sortedWith(compareByDescending<Row> { it.at }.thenBy { it.entry }.thenBy { it.item }).map { it.ref }
}

/** This renderer's view of [order]: the refs it can hold ([holds]: the phone's `visible`, a room's speaker filter). */
fun mixView(order: List<MixRef>, holds: (MixRef) -> Boolean): List<MixRef> = order.filter(holds)

/** A tap: the queue fixed now - [view] as it is, or shuffled ([random] for the tests); null when empty. */
fun mixTapQueue(view: List<MixRef>, shuffle: Boolean, random: Random = Random.Default): List<MixRef>? = when {
    view.isEmpty() -> null
    shuffle -> view.shuffled(random)
    else -> view
}

/** A run: the consecutive refs of [entryKey] in the queue, [from] its first index there. */
data class Run(val entryKey: String, val from: Int, val keys: List<String>) {
    /** The queue index just after the run. */
    val end: Int get() = from + keys.size
}

/** The run around [index]: the consecutive refs of its entry, from its first index in [queue]. */
fun segmentAt(queue: List<MixRef>, index: Int): Run {
    val key = queue[index].entryKey
    var from = index
    while (from > 0 && queue[from - 1].entryKey == key) from--
    var end = index + 1
    while (end < queue.size && queue[end].entryKey == key) end++
    return Run(key, from, queue.subList(from, end).map { it.itemKey })
}

/** After [run]'s natural end: the next index, 0 with repeat ALL past the last, null = the queue's end. */
fun afterRun(queue: List<MixRef>, run: Run, repeat: Repeat): Int? = when {
    run.end < queue.size -> run.end
    repeat == Repeat.ALL && queue.isNotEmpty() -> 0
    else -> null
}

/**
 * The first index from [from] (itself included; forward, or back for previous) that can play now ([playable]), one lap
 * at most: past an end it wraps only with [wrap] (a run's end: repeat ALL; a step: always, as `goTo` does), and it
 * never lands on [origin] (the item the step or the run of failures began at) - null when none.
 */
fun mixPlayableFrom(size: Int, from: Int, forward: Boolean, wrap: Boolean, origin: Int?, playable: (Int) -> Boolean): Int? {
    for (step in 0 until size) {
        val i = if (forward) from + step else from - step
        val j = if (wrap) i.mod(size) else i
        if (j !in 0 until size) return null
        if (j == origin) return null
        if (playable(j)) return j
    }
    return null
}

/**
 * Shuffle toggled with [current] playing: on - [current] first, the rest of [queue] a permutation; off - [order] (the
 * tap's unshuffled view) rotated to [current], refs no longer in [order] dropped but [current].
 */
fun toggledMix(order: List<MixRef>, queue: List<MixRef>, current: MixRef, on: Boolean, random: Random = Random.Default): List<MixRef> {
    if (on) return listOf(current) + queue.filter { it != current }.shuffled(random)
    val at = order.indexOf(current)
    if (at < 0) return listOf(current) + order
    return order.subList(at, order.size) + order.subList(0, at)
}

/** The stored queue (`now_playing.mix`): refs as "entryKey\u001FitemKey" joined by '\n'. */
fun encodeMix(queue: List<MixRef>): String = queue.joinToString("\n") { "${it.entryKey}$MIX_SEP${it.itemKey}" }

/** [encodeMix]'s text back; a malformed line is dropped; null for no text (an entry's queue) or nothing left. */
fun decodeMix(text: String?): List<MixRef>? {
    text ?: return null
    return text.split('\n').mapNotNull { line ->
        val parts = line.split(MIX_SEP)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) null else MixRef(parts[0], parts[1])
    }.ifEmpty { null }
}

private const val MIX_SEP = '\u001F'

/**
 * After a restart, a room switch or coming home: [stored] kept to this renderer's view ([holds]); [index] is that of
 * [current] when it survived ([same] true: its stored place stands), else of the next surviving ref after it in
 * [stored] (wrapping; [same] false: its own place) - the first when [current] is unknown; null when nothing survived.
 */
data class MixRestore(val queue: List<MixRef>, val index: Int, val same: Boolean)

fun restoredMix(stored: List<MixRef>, current: MixRef?, holds: (MixRef) -> Boolean): MixRestore? {
    val queue = stored.filter(holds)
    if (queue.isEmpty()) return null
    val at = queue.indexOf(current)
    if (at >= 0) return MixRestore(queue, at, same = true)
    val from = stored.indexOf(current)
    if (from < 0) return MixRestore(queue, 0, same = false)
    val next = (1..stored.size).map { stored[(from + it) % stored.size] }.first { it in queue }
    return MixRestore(queue, queue.indexOf(next), same = false)
}

/** What a run's end does: the next run playing, held at its start (the sleep sheet's "Til slutten" fired there), or the end. */
sealed interface RunEnd {
    data class Next(val index: Int, val play: Boolean) : RunEnd

    data object End : RunEnd
}

fun runEnd(next: Int?, heldBySleep: Boolean): RunEnd = if (next == null) RunEnd.End else RunEnd.Next(next, play = !heldBySleep)

/**
 * A web player line that ends Spotify for this favourites queue (§3.4): an adult's matter or a phone without Widevine
 * - never the network's lines (the gate is checked first), "Spilles et annet sted" or "Stoppet - trykk spill".
 */
fun mixDropsSpotify(state: RendererState?): Boolean =
    state == RendererState.SPOTIFY_ASK_ADULT || state == RendererState.SPOTIFY_NOT_ON_PHONE || state == RendererState.SPOTIFY_UNAVAILABLE
