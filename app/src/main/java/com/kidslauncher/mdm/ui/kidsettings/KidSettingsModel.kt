package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.QuickControlFeature

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

    data class Rows(val wifi: Boolean, val bluetooth: Boolean, val brightness: Boolean) : ControlsSection
}

/** For the log: which case the kid is looking at, and the mask when there is one. */
fun ControlsSection.describe(): String = when (this) {
    ControlsSection.NotOwner -> "not device owner"
    ControlsSection.NoPolicyYet -> "no accepted policy cached"
    ControlsSection.Unreadable -> "cached policy unreadable"
    ControlsSection.NoneEnabled -> "quick_controls_mask 0"
    is ControlsSection.Rows -> "wifi=$wifi bluetooth=$bluetooth brightness=$brightness"
}

/**
 * The switches from the cached policy's `quick_controls_mask`. Only an `Ok` cache counts: a
 * policy the phone didn't accept (rejected as suspect, or undecodable) never reaches the cache,
 * so the switches stay those of the last accepted one until the next good sync.
 */
fun controlsSection(isDeviceOwner: Boolean, cached: CachedPolicy): ControlsSection {
    if (!isDeviceOwner) return ControlsSection.NotOwner
    val mask = when (cached) {
        CachedPolicy.Absent -> return ControlsSection.NoPolicyYet
        is CachedPolicy.Corrupt -> return ControlsSection.Unreadable
        is CachedPolicy.Ok -> cached.policy.quickControlsMask
    }
    val rows = ControlsSection.Rows(
        wifi = mask and QuickControlFeature.WIFI != 0L,
        bluetooth = mask and QuickControlFeature.BLUETOOTH != 0L,
        brightness = mask and QuickControlFeature.BRIGHTNESS != 0L,
    )
    return if (rows.wifi || rows.bluetooth || rows.brightness) rows else ControlsSection.NoneEnabled
}
