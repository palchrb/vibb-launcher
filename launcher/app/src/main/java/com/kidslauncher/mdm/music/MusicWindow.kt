package com.kidslauncher.mdm.music

import android.media.AudioDeviceInfo

/*
 * The listening window (design 28 §2-§3, docs/design/28-music-card-session.md): Home's music card
 * and the lock's music tile show while the music plays, and otherwise only for 10 minutes after
 * the end of play or a fresh headset connection. Pure rules, MusicWindowTest; the glue is
 * MusicListening (the stamps, the headset callback) and the surfaces that read it.
 */

/** A moment on the elapsed clock of one boot (`Settings.Global.BOOT_COUNT`; -1 = unknown). */
data class BootStamp(val elapsedMs: Long, val bootCount: Int)

/** The last moment we knew the music played in this boot ([playedAt]), and the last fresh headset ([headsetAt]). */
data class Listening(val playedAt: BootStamp?, val headsetAt: BootStamp?)

/** How long a surface stays after the end of play or a fresh headset. */
const val MUSIC_LINGER_MS = 10 * 60_000L

/** Connections this soon after boot are the bonded devices coming back by themselves: baseline. */
const val HEADSET_BOOT_GRACE_MS = 2 * 60_000L

/** A device back this soon after it went (a BT blip, a codec change, an active-device switch) is not fresh (QA M2). */
const val HEADSET_REJOIN_MS = 60_000L

/** A stamp counts only in [now]'s boot (a known one) and not from the future: anything else is nothing. */
private fun BootStamp.validAt(now: BootStamp): Boolean =
    bootCount >= 0 && bootCount == now.bootCount && elapsedMs <= now.elapsedMs

/**
 * Long.MAX_VALUE while [playing]; else the elapsed end of the later valid window when now < end, or
 * null (hidden). The end is exclusive (QA L7): [musicRecheckInMs] agrees with it.
 */
fun musicShownUntil(playing: Boolean, listening: Listening, now: BootStamp): Long? {
    if (playing) return Long.MAX_VALUE
    val end = listOfNotNull(listening.playedAt, listening.headsetAt)
        .filter { it.validAt(now) }
        .maxOfOrNull { it.elapsedMs + MUSIC_LINGER_MS }
        ?: return null
    return end.takeIf { now.elapsedMs < it }
}

/** The live session a surface may show: shownSession's result, kept while playing or inside a window. */
fun windowedSession(session: MusicSession?, listening: Listening, now: BootStamp): MusicSession? =
    session?.takeIf { musicShownUntil(it.playing, listening, now) != null }

/**
 * The stamp after a bridge observation (§2.1): playing moves it to [now]; the end of play (playing
 * before, not now) too, but only after a stamp valid in [now]'s boot (QA L1: a battery that died
 * while it played leaves `playing_at` set across the boot, and the music app's first report after
 * it must not open a window). Anything else keeps it.
 */
fun playedStampAfter(previous: BootStamp?, wasPlaying: Boolean, playing: Boolean, now: BootStamp): BootStamp? = when {
    playing -> now
    wasPlaying && previous != null && previous.validAt(now) -> now
    else -> previous
}

/** Notify only when an end of play moved the stamp: a playing stamp (every 15 s) opens no window. */
fun playStampNotifies(previous: BootStamp?, next: BootStamp?, playing: Boolean): Boolean =
    !playing && next != null && next != previous

/** An audio device as the callback reports it: `getId()` (the port, new for every connection), `getType()`, `isSink()`, `getAddress()`. */
data class AudioOut(val id: Int, val type: Int, val sink: Boolean, val address: String)

/** Wired, USB and Bluetooth listening sinks - the music app's own `OutputKind.WIRED`/`BLUETOOTH` (§2.3). */
private val HEADSET_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
)

/**
 * §2.3's types, sinks only. Not SCO (calls), not an ASHA hearing aid (worn all day), not an LE
 * broadcast (no connection), and no USB device, HDMI, dock, line, built-in or source.
 */
fun countsAsHeadset(out: AudioOut): Boolean = out.sink && out.type in HEADSET_TYPES

/** A device across its connections: the BT address is stable per device; a wired one keys by its type alone. */
private fun headsetKey(out: AudioOut) = "${out.type}/${out.address}"

/** The device ids seen (the baseline and every one added since), and when each counted device last went (key -> elapsed). */
data class HeadsetWatch(val known: Set<Int>, val removedAt: Map<String, Long>)

private fun Map<String, Long>.recent(nowElapsedMs: Long) = filterValues { nowElapsedMs - it in 0 until HEADSET_REJOIN_MS }

/**
 * The watch after added devices, and whether a fresh headset connected: a counted type whose id
 * isn't known (the baseline at registration, AOSP's first callback), after the boot grace, and not
 * the same device back within [HEADSET_REJOIN_MS]. Every added id joins [HeadsetWatch.known].
 */
fun headsetAdded(watch: HeadsetWatch, added: List<AudioOut>, nowElapsedMs: Long): Pair<HeadsetWatch, Boolean> {
    val recent = watch.removedAt.recent(nowElapsedMs)
    val fresh = nowElapsedMs >= HEADSET_BOOT_GRACE_MS && added.any {
        countsAsHeadset(it) && it.id !in watch.known && headsetKey(it) !in recent
    }
    return HeadsetWatch(watch.known + added.map { it.id }, recent) to fresh
}

/** The watch after removed devices: their ids go, and each counted device's removal is kept for the rejoin rule. */
fun headsetRemoved(watch: HeadsetWatch, removed: List<AudioOut>, nowElapsedMs: Long): HeadsetWatch =
    HeadsetWatch(
        watch.known - removed.map { it.id }.toSet(),
        watch.removedAt.recent(nowElapsedMs) + removed.filter(::countsAsHeadset).associate { headsetKey(it) to nowElapsedMs },
    )

/**
 * The delay to a shown, non-playing surface's end: (end - now).coerceAtLeast(1), so the end is
 * never re-rendered in a loop (QA L7). Null while playing (Long.MAX_VALUE), hidden or without an end.
 */
fun musicRecheckInMs(hideAtElapsedMs: Long?, nowElapsedMs: Long): Long? =
    hideAtElapsedMs?.takeIf { it != Long.MAX_VALUE }?.let { (it - nowElapsedMs).coerceAtLeast(1) }
