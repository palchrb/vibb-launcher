package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.InstalledApp

/*
 * Round 7 §2 (docs/design/r7-launcher-small-items.md): the catalog apps' battery state in the
 * status report. Pure, no Android imports (CatalogBatteryTest); the sync's collectInstalledApps
 * reads the inputs. The force-stop protection the round first had (`setUserControlDisabledPackages`)
 * is not built: a protected package can't be suspended, hidden or uninstalled by the device owner
 * either (qa-l7-b-code finding 1) - never set that list.
 */

/**
 * The catalog apps this launcher installed here: the stored catalog pins ([pins], `WindowRelock`'s
 * `rememberCatalogPins`: the apps checked for this phone now) that are [installed] for this user and
 * whose installing package ([installers]) is ours ([own], the server's `installed_by_launcher` test -
 * a Play-installed twin is out), never our own packages.
 */
fun catalogAppsInstalledByUs(
    pins: Set<String>,
    installed: Set<String>,
    installers: Map<String, String?>,
    own: Set<String>,
): Set<String> =
    pins.filterTo(sortedSetOf()) { pkg ->
        pkg in installed && installers[pkg]?.let { it in own } == true && pkg !in own
    }

/**
 * The status report's `installed_apps[].battery_exempt`: set for the [catalogApps] only, from
 * [exempt] (`isIgnoringBatteryOptimizations`; `null` = unreadable, left out); every other entry is
 * left as it is (the field left out).
 */
fun withBatteryExempt(apps: List<InstalledApp>, catalogApps: Set<String>, exempt: (String) -> Boolean?): List<InstalledApp> =
    apps.map { app -> if (app.packageName in catalogApps) app.copy(batteryExempt = exempt(app.packageName)) else app }
