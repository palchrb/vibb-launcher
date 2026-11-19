package com.kidslauncher.mdm.shortcuts

import com.kidslauncher.mdm.assistant.AirplaneRowState
import com.kidslauncher.mdm.server.KidMobileRow
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.timerules.AskPart
import kotlinx.serialization.Serializable

/*
 * Snarveier, the kid's quick panel in the shade (design 99) as data - pure, no Android imports, `ShortcutsTest`. The
 * shared contract is `shortcuts.json` (byte-identical with `server/testdata/`): the keys, their gating bits, the
 * default order and the report. The panel's own rules - what shows, what is dimmed, what a tap may do - are the
 * launcher's alone.
 */

/** §3: four quarter cells, one row. */
const val SHORTCUTS_MAX = 4

/** §4.1: the nine items. [bit]: the parent's switch in `quick_controls_mask` that offers it (`null`: none). */
enum class ShortcutKey(val wire: String, val bit: Long?) {
    TORCH("torch", null),
    SOUND("sound", QuickControlFeature.SOUND),
    AIRPLANE("airplane", QuickControlFeature.AIRPLANE),
    WIFI("wifi", QuickControlFeature.WIFI),
    BLUETOOTH("bluetooth", QuickControlFeature.BLUETOOTH),
    MOBILE_DATA("mobile_data", QuickControlFeature.MOBILE_DATA),
    BRIGHTNESS("brightness", QuickControlFeature.BRIGHTNESS),
    MORE_TIME("more_time", null),
    MUSIC("music", null),
    ;

    companion object {
        fun of(wire: String?): ShortcutKey? = entries.firstOrNull { it.wire == wire }
    }
}

/** §4.3 (D4): the default order for a phone whose kid hasn't picked; the first four offered show. */
val SHORTCUT_DEFAULTS: List<ShortcutKey> = listOf(
    ShortcutKey.TORCH, ShortcutKey.SOUND, ShortcutKey.AIRPLANE, ShortcutKey.WIFI, ShortcutKey.BLUETOOTH,
    ShortcutKey.MOBILE_DATA, ShortcutKey.BRIGHTNESS, ShortcutKey.MORE_TIME, ShortcutKey.MUSIC,
)

/** Vibb music as the panel reads it: [session] - Vibb music's own session exists; [playing]; [usable] - the app is
 * usable now (`MusicSessions.usable`: not suspended by a rule, the used-up budget or the camera lock). */
data class ShortcutMusic(val session: Boolean, val playing: Boolean, val usable: Boolean)

/**
 * The platform's state the panel shows, read by `ShortcutsRuntime`. Each `null` is "absent" (§4.1's column): no flash
 * unit, no vibrator, no Bluetooth adapter, Vibb music not installed.
 */
data class ShortcutsPlatform(
    val torchLit: Boolean? = null,
    /** Lyd: the ringer rings ([soundOn]) and whether a tap may change it ([soundEnabled]: false while DND is on);
     * `null` = no vibrator (no Lyd row, design 18). */
    val sound: Pair<Boolean, Boolean>? = null,
    /** Flymodus's row state (`airplaneRow` with the bit, the role, the restriction, the route's busy state). */
    val airplane: AirplaneRowState = AirplaneRowState.HIDDEN,
    val airplaneOn: Boolean = false,
    val wifiOn: Boolean = false,
    val bluetoothOn: Boolean? = null,
    /** The kid's Mobildata row (`kidMobileRow`); HIDDEN without bit 32. */
    val mobile: KidMobileRow = KidMobileRow.HIDDEN,
    /** The anchor notice's ask part (`timePart`'s `ask`, design 100 §14.3): usable, dimmed while a request is open, or none. */
    val ask: AskPart = AskPart.None,
    val music: ShortcutMusic? = null,
)

/**
 * One button: [on] - the peach disc (never Lysstyrke's: it opens the brightness sheet); [usable] - a tap does something
 * now (else dimmed); [busy] - Flymodus's route or its Off is running; [asked] - Mer tid's request waits ("Spurt").
 */
data class ShortcutItem(
    val key: ShortcutKey,
    val on: Boolean = false,
    val usable: Boolean = true,
    val busy: Boolean = false,
    val asked: Boolean = false,
)

/** The panel: [shown] - posted (§2.2); [hidden] - the kid turned the channel off (§2.6); [items] - at most four. */
data class ShortcutsPanel(val shown: Boolean, val hidden: Boolean, val items: List<ShortcutItem>)

/** The kid's picks as stored (`null` = the default, a new phone): known keys in order, no duplicates. Unknown keys
 * from a later build are kept in storage and skipped here. */
fun shortcutPicks(stored: List<String>?): List<ShortcutKey> =
    stored?.mapNotNull { ShortcutKey.of(it) }?.distinct() ?: SHORTCUT_DEFAULTS

/** §4.1's "offered" (the gate and absent columns) and its state now; `null` = not offered. */
fun shortcutItem(key: ShortcutKey, mask: Long, p: ShortcutsPlatform): ShortcutItem? {
    fun bit(b: Long) = mask and b != 0L
    return when (key) {
        ShortcutKey.TORCH -> p.torchLit?.let { ShortcutItem(key, on = it) }
        ShortcutKey.SOUND -> p.sound?.takeIf { bit(QuickControlFeature.SOUND) }
            ?.let { (on, enabled) -> ShortcutItem(key, on = on, usable = enabled) }
        ShortcutKey.AIRPLANE -> when (val s = p.airplane) {
            AirplaneRowState.HIDDEN -> null
            AirplaneRowState.BUSY_ON, AirplaneRowState.BUSY_OFF -> ShortcutItem(key, on = p.airplaneOn, usable = false, busy = true)
            else -> ShortcutItem(key, on = p.airplaneOn, usable = s.clickable)
        }
        ShortcutKey.WIFI -> if (bit(QuickControlFeature.WIFI)) ShortcutItem(key, on = p.wifiOn, usable = !p.airplaneOn) else null
        ShortcutKey.BLUETOOTH -> p.bluetoothOn?.takeIf { bit(QuickControlFeature.BLUETOOTH) }?.let { ShortcutItem(key, on = it) }
        ShortcutKey.MOBILE_DATA -> when {
            !bit(QuickControlFeature.MOBILE_DATA) || p.mobile == KidMobileRow.HIDDEN -> null
            else -> ShortcutItem(
                key,
                on = p.mobile == KidMobileRow.ON && !p.airplaneOn,
                usable = (p.mobile == KidMobileRow.ON || p.mobile == KidMobileRow.OFF) && !p.airplaneOn,
            )
        }
        ShortcutKey.BRIGHTNESS -> if (bit(QuickControlFeature.BRIGHTNESS)) ShortcutItem(key) else null
        ShortcutKey.MORE_TIME -> when (p.ask) {
            AskPart.None -> null
            AskPart.Action -> ShortcutItem(key)
            is AskPart.Asked -> ShortcutItem(key, usable = false, asked = true)
        }
        ShortcutKey.MUSIC -> p.music?.let { ShortcutItem(key, on = it.playing && it.session, usable = it.session && it.usable) }
    }
}

/**
 * The panel (§2.2, §4.3). [owner]: device owner; [mask]: the `Ok` cached policy's `quick_controls_mask` (`null`: no
 * accepted policy, a corrupt cache - no panel); [picks]: as stored (`null` = the default); [hidden]: the kid turned the
 * channel off. The items are the picks offered now, the first four, a dimmed item keeping its place.
 */
fun shortcutsModel(owner: Boolean, mask: Long?, picks: List<String>?, hidden: Boolean, platform: ShortcutsPlatform): ShortcutsPanel {
    if (!owner || mask == null || mask and QuickControlFeature.SHORTCUTS == 0L) return ShortcutsPanel(false, hidden, emptyList())
    val items = shortcutPicks(picks).mapNotNull { shortcutItem(it, mask, platform) }.take(SHORTCUTS_MAX)
    return ShortcutsPanel(shown = !hidden && items.isNotEmpty(), hidden = hidden, items = items)
}

/** §2.2's `shortcutsShown`. */
fun shortcutsShown(panel: ShortcutsPanel): Boolean = panel.shown

/** §4.2: the picker's candidates - every item the parent's switches allow now, offered or not usable now; the
 * ones whose absence is the phone's (no flash, no vibrator, no adapter, no music app) are left out. */
fun shortcutCandidates(mask: Long?, platform: ShortcutsPlatform): List<ShortcutKey> {
    if (mask == null || mask and QuickControlFeature.SHORTCUTS == 0L) return emptyList()
    return SHORTCUT_DEFAULTS.filter { key ->
        when (key) {
            ShortcutKey.TORCH -> platform.torchLit != null
            ShortcutKey.SOUND -> platform.sound != null && mask and QuickControlFeature.SOUND != 0L
            ShortcutKey.BLUETOOTH -> platform.bluetoothOn != null && mask and QuickControlFeature.BLUETOOTH != 0L
            ShortcutKey.MUSIC -> platform.music != null
            // Mer tid is listed always: it shows on days with screen time (its sub-line says so).
            ShortcutKey.MORE_TIME -> true
            // Flymodus while the row would show (bit 64 with the role, or airplane mode on).
            ShortcutKey.AIRPLANE -> platform.airplane != AirplaneRowState.HIDDEN
            else -> mask and key.bit!! != 0L
        }
    }
}

/** §4.2: the picker's toggle of [key]. Returns the new picks, or `null` when a fifth is refused. */
fun togglePick(current: List<ShortcutKey>, key: ShortcutKey): List<ShortcutKey>? = when {
    key in current -> current - key
    current.size >= SHORTCUTS_MAX -> null
    else -> current + key
}

/** §4.2: "Flytt opp" ([up]) or "Flytt ned" for [key]; at an end, unchanged. */
fun movePick(current: List<ShortcutKey>, key: ShortcutKey, up: Boolean): List<ShortcutKey> {
    val i = current.indexOf(key)
    val j = if (up) i - 1 else i + 1
    if (i < 0 || j !in current.indices) return current
    return current.toMutableList().also { it[i] = it[j]; it[j] = key }
}

/** §4.2: the picks the picker starts from - the stored list, or the default's first four offered on a new phone. */
fun pickerStart(stored: List<String>?, candidates: List<ShortcutKey>): List<ShortcutKey> =
    if (stored == null) SHORTCUT_DEFAULTS.filter { it in candidates }.take(SHORTCUTS_MAX)
    else shortcutPicks(stored).filter { it in candidates }.take(SHORTCUTS_MAX)

/**
 * The picks written after an edit: [chosen] in order, then the stored keys the picker didn't list (unknown to this
 * build, or not allowed now) so they come back with their switch - cut so the shown ones stay first.
 */
fun picksToStore(chosen: List<ShortcutKey>, stored: List<String>?, listed: List<ShortcutKey>): List<String> {
    val listedWires = listed.map { it.wire }.toSet()
    val kept = stored.orEmpty().filter { it !in listedWires && it !in chosen.map { c -> c.wire } }
    return (chosen.map { it.wire } + kept).distinct()
}

/** Why [shortcutTapAllowed] answered as it did (logged). */
enum class ShortcutTap { ALLOW, UNKNOWN_KEY, STATE_UNKNOWN, LOCKED, KEYGUARD, NOT_IN_PANEL, DIMMED, BUSY, MUSIC_UNUSABLE }

/**
 * §2.1 (Q99-M1): the one fail-closed check every tap passes. [key] - the intent's only extra; [panel] - the model built
 * at the tap; [lockKnown] - this process has decided its lock mode and read the time rules without error; [locked] -
 * Vibb's PIN lock is LOCKED; [keyguardLocked] - Android's keyguard is up (a Swipe keyguard may show the panel, Q99-L1);
 * [musicUsable] - Musikk only: the music app is usable now (`null` = unread, refused).
 */
fun shortcutTapAllowed(
    key: String?,
    panel: ShortcutsPanel,
    lockKnown: Boolean,
    locked: Boolean,
    keyguardLocked: Boolean,
    musicUsable: Boolean?,
): ShortcutTap {
    val k = ShortcutKey.of(key) ?: return ShortcutTap.UNKNOWN_KEY
    if (!lockKnown) return ShortcutTap.STATE_UNKNOWN
    if (locked) return ShortcutTap.LOCKED
    if (keyguardLocked) return ShortcutTap.KEYGUARD
    if (!panel.shown) return ShortcutTap.NOT_IN_PANEL
    val item = panel.items.firstOrNull { it.key == k } ?: return ShortcutTap.NOT_IN_PANEL
    if (item.busy) return ShortcutTap.BUSY
    if (!item.usable) return ShortcutTap.DIMMED
    if (k == ShortcutKey.MUSIC && musicUsable != true && !item.on) return ShortcutTap.MUSIC_UNUSABLE
    return ShortcutTap.ALLOW
}

/** The windows a cell's tap may open instead of a broadcast ([shortcutWindow]). */
enum class ShortcutWindow { AIRPLANE, BRIGHTNESS }

/**
 * §4.1, C99-M1: whether a cell's tap opens a window of ours, which Android starts with the shade closed - Flymodus while
 * off, usable and not busy ([ShortcutAirplaneActivity]), and Lysstyrke, the brightness sheet ([BrightnessSheetActivity],
 * 2026-11-19: a slider, not three steps). A dimmed or busy item is a broadcast like every other, refused by
 * [shortcutTapAllowed] with the shade left open (D10: a dimmed tap does nothing). `null`: a broadcast.
 */
fun shortcutWindow(item: ShortcutItem): ShortcutWindow? = when {
    !item.usable || item.busy -> null
    item.key == ShortcutKey.AIRPLANE && !item.on -> ShortcutWindow.AIRPLANE
    item.key == ShortcutKey.BRIGHTNESS -> ShortcutWindow.BRIGHTNESS
    else -> null
}

/** §2.3 (Q99-L7): at most one post a second. The delay before the next post may go, from the last post's time. */
fun shortcutPostDelay(nowMs: Long, lastPostMs: Long?, gapMs: Long = 1_000): Long =
    if (lastPostMs == null || nowMs - lastPostMs >= gapMs || nowMs < lastPostMs) 0 else gapMs - (nowMs - lastPostMs)

/** §6.1: the report's `shortcuts`. */
@Serializable
data class ShortcutsReport(val shown: Boolean, val hidden: Boolean, val items: List<String>)

fun shortcutsReport(panel: ShortcutsPanel): ShortcutsReport =
    ShortcutsReport(panel.shown, panel.hidden, panel.items.map { it.key.wire })
