package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/devices/power` (design 38 §2): the phone's last shutdown and this boot's
 * start, each optional. Instants, a battery, flags, the shutdown's last stored fix - no address, no user text (the shutdown reason
 * is the system property's own word, which the server only matches against known words). `null`s
 * are left out (`ServerJson` encodes no defaults). Pinned by `power_report.json` (byte-identical
 * with `server/testdata/`, `PowerEventsTest`).
 */
@Serializable
data class PowerReport(val off: PowerOff? = null, val on: PowerOn? = null)

/** `ACTION_SHUTDOWN` seen: when, in which boot, the battery then, a restart (`true`), a power-off
 * (`false`) or unknown (`null`, the usual case), and `sys.shutdown.requested`'s reason when it
 * could be read. Since 2026-10-25 also [location], the newest fix the phone already held at the
 * shutdown (never a fresh one; `null` without one or under location "Off") - kept only in CE
 * storage, never in the device-protected record (`PowerEvents`' `OffLocations`). */
@Serializable
data class PowerOff(
    val atMs: Long,
    val bootCount: Int,
    val battery: BatteryReport? = null,
    val reboot: Boolean? = null,
    val reason: String? = null,
    val location: PowerLocation? = null,
)

/** The shutdown's last fix (2026-10-25): where, how sure, when it was taken and Android's provider
 * word (`null` unknown, left out). A mock fix is never one (only a debug build keeps one). */
@Serializable
data class PowerLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val atMs: Long,
    val provider: String? = null,
)

/** The boot this process's first start saw: its instant (wall clock minus elapsed realtime), its
 * `BOOT_COUNT`, the battery at the start, and (design 59 §1) when the storage first unlocked in
 * this boot - the Child PIN typed in android mode; `null` = not known. */
@Serializable
data class PowerOn(val atMs: Long, val bootCount: Int, val battery: BatteryReport? = null, val unlockedAtMs: Long? = null)
