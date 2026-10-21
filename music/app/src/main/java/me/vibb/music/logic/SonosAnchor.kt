package me.vibb.music.logic

import kotlin.math.abs

/*
 * The Sonos bar's display anchor (design 53 §2.4, QA 53-2; ours, GPL-3.0 or later - vibb shows the
 * poll's truncated second). Display only: no reader of it persists (`positionMs()` stays on
 * GuardsTest's allowlist; the bookmark reads the measured snapshot). Pure; SonosAnchorTest.
 */

/**
 * The bar at [posMs] at the elapsed [at], advancing with the clock while [moving]; [key] names the
 * item and everything that moves it on purpose (the generation, the item, its uri, the last seek).
 */
data class SonosAnchor(val posMs: Long, val at: Long, val moving: Boolean, val key: String) {
    fun positionAt(now: Long): Long = if (moving) posMs + (now - at).coerceAtLeast(0) else posMs
}

/** One measurement: a socket event's exact position, or a poll's ([poll]) `RelTime` + half a second. */
data class SonosMeasure(val posMs: Long, val at: Long, val moving: Boolean, val key: String, val poll: Boolean)

/** Within this of the old extrapolation a measurement re-bases smoothly; at it or more, the bar jumps. */
const val SONOS_ANCHOR_JUMP_MS = 1_000L

/**
 * The re-anchor with hysteresis: a measurement of the same item ([SonosAnchor.key]) in the same
 * motion within [SONOS_ANCHOR_JUMP_MS] of the old extrapolation re-bases the anchor at the old
 * extrapolation's value at the measurement's time - monotone and smooth, never frozen, never a
 * step, and every measurement moves [SonosAnchor.at] on, so the extrapolation cap is never reached
 * while measurements come. A difference of a second or more, a change of motion, a seek, a step, a
 * new item or a play takes the measurement at once. Under a healthy socket ([socketHealthy]) a poll
 * within the second changes nothing: the event is exact, a poll is not.
 */
fun sonosAnchor(old: SonosAnchor?, m: SonosMeasure, socketHealthy: Boolean): SonosAnchor {
    val fresh = SonosAnchor(m.posMs, m.at, m.moving, m.key)
    if (old == null || old.key != m.key || old.moving != m.moving) return fresh
    val predicted = old.positionAt(m.at)
    if (abs(m.posMs - predicted) >= SONOS_ANCHOR_JUMP_MS) return fresh
    if (m.poll && socketHealthy) return old
    // never backwards in time: a measurement older than the anchor keeps the anchor's own instant
    return if (m.at <= old.at) old else SonosAnchor(predicted, m.at, m.moving, m.key)
}
