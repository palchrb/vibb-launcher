package me.vibb.music.logic

import kotlin.math.roundToInt

/*
 * Volume in a Sonos group (design 53 §2.12; the user's answer to Q1, 2026-10-21: "Volum i en gruppe burde ideelt
 * sett kunne styres både sammen og individuelt"). Ours, not vibb's (vibb's volume is the coordinator room's own).
 * The group's value is the mean of its rooms; a group move is the same delta for every room, each clamped to 0..100
 * and to the parents' cap per room - never Sonos' `SetGroupVolume`, which scales the proportions on the speaker and
 * knows no cap per room. Pure; GroupVolumeTest.
 */

/** The group's value: the mean of its rooms' volumes, rounded; null without any. */
fun groupMean(volumes: Collection<Int>): Int? = if (volumes.isEmpty()) null else (volumes.sum().toDouble() / volumes.size).roundToInt()

/**
 * One room moved by [delta] (§2.12): 0..100, never set above the parents' [cap] (1..99, else none) - a raise leaves a
 * room already at or above the cap where it is (a louder volume set elsewhere is never pulled down by a raise, 26a
 * §5.3), a lower clamps to the cap like every set of ours.
 */
fun roomMove(volume: Int, delta: Int, cap: Int?): Int {
    val c = cap?.takeIf { it in 1..99 }
    if (delta >= 0 && c != null && volume >= c) return volume
    val v = (volume + delta).coerceIn(0, 100)
    return if (c != null) minOf(v, c) else v
}

/**
 * The "Begge"/"Alle" slider, the system's slider (§2.12): from the volumes as they were when the drag began ([base],
 * uid -> volume) to the group value [newMean] - the same delta for every room, each clamped ([roomMove]), so dragging
 * back within one drag restores the balance. Empty without a base.
 */
fun groupMove(base: Map<String, Int>, newMean: Int, cap: Int?): Map<String, Int> {
    val mean = groupMean(base.values) ?: return emptyMap()
    val delta = newMean.coerceIn(0, 100) - mean
    return base.mapValues { (_, v) -> roomMove(v, delta, cap) }
}

/** A volume key on a group (§2.12): [delta] (±3) to each room from its own [current] volume, each clamped. */
fun groupStep(current: Map<String, Int>, delta: Int, cap: Int?): Map<String, Int> = current.mapValues { (_, v) -> roomMove(v, delta, cap) }
