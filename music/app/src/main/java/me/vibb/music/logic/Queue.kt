package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.Source
import kotlin.random.Random

/*
 * Building a queue (design 21 §4.2 shuffle, §4.4): what a cover tap, a pick from the list, search
 * or Nytt, and the shuffle toggle start. The queue rules follow palchrb/vibb `pi/player.py`'s queue
 * planning (`rotate_to_bookmark`, the explicit `--episode` pick; MIT License, Copyright (c) 2026
 * palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Pure; QueueTest.
 */

/** A queue to start: item keys in the order they play, the index to start at and where in it. */
data class QueuePlan(val keys: List<String>, val startIndex: Int, val startPosMs: Long)

/** Shuffle is offered for music entries only: own files (later Spotify) with resume off. */
fun canShuffle(entry: Entry): Boolean = entry.source == Source.OWN && !entry.resume

/** A permutation of [keys] with [first] (when it is one of them) first, the rest at random. */
fun shuffledWithFirst(keys: List<String>, first: String?, random: Random): List<String> {
    val rest = keys.filter { it != first }.shuffled(random)
    return if (first != null && first in keys) listOf(first) + rest else rest
}

/** [keys] rotated so [first] comes first (unchanged when it isn't there). */
fun rotatedTo(keys: List<String>, first: String): List<String> {
    val i = keys.indexOf(first)
    return if (i <= 0) keys else keys.subList(i, keys.size) + keys.subList(0, i)
}

/**
 * A cover tap. [order]: the entry's playable item keys in play order. Resume on: the order rotated
 * to the bookmark, at its position; resume off: the first item at 0, or with [shuffle] a fresh
 * permutation. Null when nothing is playable.
 */
fun coverTapPlan(order: List<String>, resume: Boolean, bookmark: Bookmark?, shuffle: Boolean, random: Random = Random.Default): QueuePlan? {
    if (order.isEmpty()) return null
    if (shuffle && !resume) return QueuePlan(shuffledWithFirst(order, null, random), 0, 0)
    if (!resume) return QueuePlan(order, 0, 0)
    val (keys, pos) = rotateToBookmark(order, bookmark)
    return QueuePlan(keys, 0, pos)
}

/**
 * A pick from the list, search or Nytt: [pick] first, then the rest of the order around it (vibb
 * rotates for a picked episode too), at its own position when resume is on. With shuffle on the
 * pick continues within [shuffleQueue] (the order the list shows), or a new permutation from it.
 */
fun pickPlan(
    order: List<String>,
    pick: String,
    resume: Boolean,
    position: ItemPosition?,
    shuffleQueue: List<String>?,
    random: Random = Random.Default,
): QueuePlan? {
    if (pick !in order) return null
    val base = when {
        shuffleQueue == null -> order
        pick in shuffleQueue && shuffleQueue.toSet() == order.toSet() -> shuffleQueue
        else -> shuffledWithFirst(order, pick, random)
    }
    val pos = if (resume) seekGate(resumeFrom(position)) else 0
    return QueuePlan(rotatedTo(base, pick), 0, pos)
}

/**
 * The queue after the shuffle toggle, keeping [current] playing first: on, a permutation of
 * [order] with the current item first; off, the play order from the current item.
 */
fun toggledQueue(order: List<String>, current: String, shuffleOn: Boolean, random: Random = Random.Default): List<String> =
    if (shuffleOn) shuffledWithFirst(order, current, random) else rotatedTo(order, current)

/**
 * The queue after the kid's order flip (design 39 §3.3, qa-39-design M1): the queue's own [queueKeys] in [newOrder]'s
 * direction, [current] first - a room's speaker-playable queue stays that (nothing it left out comes back).
 */
fun flippedQueue(newOrder: List<String>, queueKeys: List<String>, current: String): List<String> {
    val keys = queueKeys.toSet()
    return rotatedTo(newOrder.filter { it in keys }, current)
}

/**
 * A stored queue brought back after a restart: the keys still playable, the index of [currentKey]
 * (or of the first stored key after it that is still there), or null when none is left.
 */
fun restoredQueue(stored: List<String>, currentIndex: Int, playable: Set<String>): Pair<List<String>, Int>? {
    val keys = stored.filter { it in playable }
    if (keys.isEmpty()) return null
    val after = stored.drop(currentIndex.coerceIn(0, stored.size)).firstOrNull { it in playable } ?: keys.first()
    return keys to keys.indexOf(after)
}

/**
 * The stored queue as a plan: [restoredQueue]'s keys and index, at the stored [rowPosMs] when the
 * stored item is still the one at that index, else where [startPos] says that item starts.
 */
fun storedPlan(stored: List<String>, rowIndex: Int, rowPosMs: Long, playable: Set<String>, startPos: (String) -> Long): QueuePlan? {
    val (keys, index) = restoredQueue(stored, rowIndex, playable) ?: return null
    val pos = if (keys[index] == stored.getOrNull(rowIndex)) rowPosMs else startPos(keys[index])
    return QueuePlan(keys, index, pos)
}

/**
 * Coming home from a Sonos room (design 26a §4.6): the entry's whole play order (the speaker's queue
 * held only what a speaker can play) rotated to the item that played, as a pick does, at [posMs] -
 * the bookmark the hand-off just wrote.
 */
fun homePlan(order: List<String>, current: String, posMs: Long): QueuePlan =
    QueuePlan(rotatedTo(order, current), 0, if (current in order) posMs else 0)
