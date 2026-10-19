package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/devices/power` (design 38 §2): the phone's last shutdown and this boot's
 * start, each optional. Instants, a battery, flags - no address, no user text (the shutdown reason
 * is the system property's own word, which the server only matches against known words). `null`s
 * are left out (`ServerJson` encodes no defaults). Pinned by `power_report.json` (byte-identical
 * with `server/testdata/`, `PowerEventsTest`).
 */
@Serializable
data class PowerReport(val off: PowerOff? = null, val on: PowerOn? = null)

/** `ACTION_SHUTDOWN` seen: when, in which boot, the battery then, a restart (`true`), a power-off
 * (`false`) or unknown (`null`, the usual case), and `sys.shutdown.requested`'s reason when it
 * could be read. */
@Serializable
data class PowerOff(
    val atMs: Long,
    val bootCount: Int,
    val battery: BatteryReport? = null,
    val reboot: Boolean? = null,
    val reason: String? = null,
)

/** The boot this process's first start saw: its instant (wall clock minus elapsed realtime), its
 * `BOOT_COUNT` and the battery at the start. */
@Serializable
data class PowerOn(val atMs: Long, val bootCount: Int, val battery: BatteryReport? = null)
