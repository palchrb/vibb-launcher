package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.ui.wallpaper.Wallpaper

/*
 * The kid's Settings screen as data (design 08-ui-polish.md §2) - pure, no Android imports,
 * tested in KidSettingsModelTest. KidSettingsActivity only renders it. Nothing here changes a
 * rule: no PIN, no policy write, no way into Android's Settings (QA 08 #10).
 */

/** Which of the parent's switches the kid sees ("Connection and screen"). */
sealed interface ControlsSection {
    /** The launcher isn't the device owner (not provisioned, or a second build installed beside
     * the real one): none of the switches can work. */
    data object NotOwner : ControlsSection

    /** No accepted policy cached yet - never synced, or running on the last enforced plan,
     * which carries no switches. */
    data object NoPolicyYet : ControlsSection

    /** The cached policy can't be read (fail closed: no switches, kid convenience only). */
    data object Unreadable : ControlsSection

    /** A policy is there, and the parent turned on no switch (mask 0): no card at all. */
    data object NoneEnabled : ControlsSection

    /** [sound]: the parent's bit for the sound row (design 18); whether the row really shows is
     * also up to the phone ([com.kidslauncher.mdm.server.soundRowState]: no vibrator, no row).
     * [touch]: touch sounds and vibration (design r9 §4) - always shown with its bit, read-only
     * without the app-op. [mobileData]: the kid's own Mobildata switch (design 97 §4.3, bit 32). */
    data class Rows(
        val wifi: Boolean,
        val bluetooth: Boolean,
        val brightness: Boolean,
        val sound: Boolean,
        val touch: Boolean,
        val mobileData: Boolean = false,
        /** Design 98 §4.1: the kid's Flymodus switch - bit 64 with the assistant role. */
        val airplane: Boolean = false,
        /** Design 98 §4.1 (Q98-M5): airplane mode is on without the full row - a row that only turns it off. */
        val airplaneOffOnly: Boolean = false,
    ) : ControlsSection
}

/** For the log: which case the kid is looking at, and the mask when there is one. */
fun ControlsSection.describe(): String = when (this) {
    ControlsSection.NotOwner -> "not device owner"
    ControlsSection.NoPolicyYet -> "no accepted policy cached"
    ControlsSection.Unreadable -> "cached policy unreadable"
    ControlsSection.NoneEnabled -> "quick_controls_mask 0"
    is ControlsSection.Rows ->
        "wifi=$wifi bluetooth=$bluetooth brightness=$brightness sound=$sound touch=$touch mobileData=$mobileData " +
            "airplane=$airplane airplaneOffOnly=$airplaneOffOnly"
}

/**
 * The switches from the cached policy's `quick_controls_mask`. Only an `Ok` cache counts: a
 * policy the phone didn't accept (rejected as suspect, or undecodable) never reaches the cache,
 * so the switches stay those of the last accepted one until the next good sync.
 */
fun controlsSection(isDeviceOwner: Boolean, cached: CachedPolicy, roleHeld: Boolean = false, airplaneOn: Boolean = false): ControlsSection {
    if (!isDeviceOwner) return ControlsSection.NotOwner
    // Design 98 §4.1/§7.2: with airplane mode on there is always a way out, whatever the policy says.
    val offOnly = ControlsSection.Rows(false, false, false, false, false, airplaneOffOnly = true)
    val mask = when (cached) {
        CachedPolicy.Absent -> return if (airplaneOn) offOnly else ControlsSection.NoPolicyYet
        is CachedPolicy.Corrupt -> return if (airplaneOn) offOnly else ControlsSection.Unreadable
        is CachedPolicy.Ok -> cached.policy.quickControlsMask
    }
    val fullAirplane = mask and QuickControlFeature.AIRPLANE != 0L && roleHeld
    val rows = ControlsSection.Rows(
        wifi = mask and QuickControlFeature.WIFI != 0L,
        bluetooth = mask and QuickControlFeature.BLUETOOTH != 0L,
        brightness = mask and QuickControlFeature.BRIGHTNESS != 0L,
        sound = mask and QuickControlFeature.SOUND != 0L,
        touch = mask and QuickControlFeature.TOUCH != 0L,
        mobileData = mask and QuickControlFeature.MOBILE_DATA != 0L,
        airplane = fullAirplane,
        airplaneOffOnly = airplaneOn && !fullAirplane,
    )
    val any = rows.wifi || rows.bluetooth || rows.brightness || rows.sound || rows.touch || rows.mobileData ||
        rows.airplane || rows.airplaneOffOnly
    return if (any) rows else ControlsSection.NoneEnabled
}

/** Security review S-L5: the Bluetooth devices page may stay open only while [section] still shows the Bluetooth row
 * (`quick_controls_mask`'s bit); else it finishes - at its start and after every apply. */
fun bluetoothPageAllowed(section: ControlsSection): Boolean = (section as? ControlsSection.Rows)?.bluetooth == true

/** Design 111 §2.2: the Wi-Fi networks page, as [bluetoothPageAllowed] - only while [section] shows the Wi-Fi row. */
fun wifiPageAllowed(section: ControlsSection): Boolean = (section as? ControlsSection.Rows)?.wifi == true

/** Kid Settings' four sub-pages (design 111): each is opened only from Kid Settings' own rows, in its task. */
enum class SubPage { WIFI, BLUETOOTH, SOUND_PICKER, SHORTCUTS }

/** What a sub-page does at its gate ([subPageGate]). */
enum class SubPageGate {
    /** A time rule or the budget is in force: the time-rule screen comes first, and the page finishes. */
    REDIRECT,

    /** Its row is no longer Kid Settings' (the parent's switch, the app-op, no device owner): the page finishes. */
    FINISH,

    /** The page may stay. */
    OPEN,
}

/**
 * Design 111 §2.2: the one gate of Kid Settings' four sub-pages, now that they live in Kid Settings' own task and a
 * Recents card can bring one back under a rule or after the parent took its row away. Run first in `onStart` (before
 * any radio work), again in `onResume`, on a `lockReason` change and after each apply. [lockReason] first: any rule or
 * the budget sends the page to the time-rule screen (a pause or Foreldrekode is `NONE`, as for Kid Settings). Then the
 * page's row as Kid Settings shows it: Wi-Fi's and Bluetooth's bits of [section]; the sound picker the sound bit with
 * the "Modify system settings" app-op ([soundWritable], [soundPicksState]); Snarveier the cached policy's bit 128
 * ([mask], `null` without an accepted policy) on a device-owner phone.
 */
fun subPageGate(
    lockReason: LockReason,
    section: ControlsSection,
    page: SubPage,
    mask: Long? = null,
    soundWritable: Boolean = false,
): SubPageGate {
    if (lockReason != LockReason.NONE) return SubPageGate.REDIRECT
    val open = when (page) {
        SubPage.WIFI -> wifiPageAllowed(section)
        SubPage.BLUETOOTH -> bluetoothPageAllowed(section)
        SubPage.SOUND_PICKER -> soundPicksState((section as? ControlsSection.Rows)?.sound == true, soundWritable)?.writable == true
        SubPage.SHORTCUTS -> section != ControlsSection.NotOwner && mask != null && mask and QuickControlFeature.SHORTCUTS != 0L
    }
    return if (open) SubPageGate.OPEN else SubPageGate.FINISH
}

/** The Wi-Fi page's two moments for system Location ([wifiLocationStep]). */
enum class WifiPageEvent { START, STOP }

/**
 * What the Wi-Fi page does at a [WifiPageEvent]: [stopPoll] drops a connect attempt's poll (no toast), [location] turns
 * system Location on (`true`), off (`false`) or leaves it (`null`), [byUs] is whether the page turned it on, and [scan]
 * starts a scan.
 */
data class WifiLocationStep(val stopPoll: Boolean, val location: Boolean?, val byUs: Boolean, val scan: Boolean)

/**
 * Design 111 §2.2 (L1): Location only while the Wi-Fi page is shown. Android's Wi-Fi scan and config calls need system
 * Location on (`QuickControls.isLocationEnabled`); the page turned it on in `onCreate` and off only in `onDestroy`, which
 * Home's clear used to bring - in Kid Settings' own task the page can sit stopped in the card for hours.
 * - [WifiPageEvent.START] (after the gate): Location on if it is off ([enabledNow] false), marked as ours; then the scan.
 *   Already on, it is left as it is and stays whoever's it was.
 * - [WifiPageEvent.STOP]: a running connect poll ([polling]) is dropped first - with Location off its next scan would
 *   end a connect that worked as "couldn't connect" (a connect, then "Logg på" at once); then Location off only if the
 *   page turned it on, it is still on, and the parent's policy doesn't want it on now ([policyKeepsOn]: the
 *   CONFIG_LOCATION switch for Find my device or the zone watch, `locationStaysOn` - a sync while the page was shown
 *   found it on already and left it; code QA L4). Never a setting the parent had on or wants on.
 */
fun wifiLocationStep(
    event: WifiPageEvent,
    enabledNow: Boolean,
    byUs: Boolean,
    polling: Boolean,
    policyKeepsOn: Boolean = false,
): WifiLocationStep = when (event) {
    WifiPageEvent.START ->
        if (enabledNow) WifiLocationStep(stopPoll = false, location = null, byUs = byUs, scan = true)
        else WifiLocationStep(stopPoll = false, location = true, byUs = true, scan = true)
    WifiPageEvent.STOP ->
        WifiLocationStep(stopPoll = polling, location = if (byUs && enabledNow && !policyKeepsOn) false else null, byUs = false, scan = false)
}

/** One wallpaper in the picker. */
data class WallpaperTile(val wallpaper: Wallpaper, val selected: Boolean)

/**
 * The picker: the wallpapers the kid can choose now (allowed and usable), the shown one
 * selected. Fewer than two: no picker at all - there is nothing to choose.
 */
fun wallpaperTiles(choices: List<Wallpaper>, current: Wallpaper): List<WallpaperTile> =
    if (choices.size < 2) emptyList() else choices.map { WallpaperTile(it, it.id == current.id) }

/**
 * Design 64 step 2 (the user's decision 6, 2026-10-24): the calm line "This phone shares where it
 * is with mum and dad." - no control - while the phone shares its location at all: device owner,
 * an accepted policy, and its location mode isn't "off" (a policy without
 * `location_policy` is the every-10-minutes trail, so it shares). Only when asked shares on
 * request, Regularly and the zones continuously - both are sharing. Never from a corrupt cache or
 * before the first policy (nothing is shared then).
 */
fun sharesLocation(isDeviceOwner: Boolean, cached: CachedPolicy): Boolean =
    isDeviceOwner && cached is CachedPolicy.Ok && cached.policy.locationPolicy?.mode != "off"

data class KidSettingsModel(val controls: ControlsSection, val wallpapers: List<WallpaperTile>, val sharesLocation: Boolean = false)

/** The whole screen (design 08 §2): the activity only renders this. */
fun kidSettingsModel(
    isDeviceOwner: Boolean,
    cached: CachedPolicy,
    choices: List<Wallpaper>,
    current: Wallpaper,
    roleHeld: Boolean = false,
    airplaneOn: Boolean = false,
): KidSettingsModel = KidSettingsModel(
    controlsSection(isDeviceOwner, cached, roleHeld, airplaneOn),
    wallpaperTiles(choices, current),
    sharesLocation(isDeviceOwner, cached),
)
