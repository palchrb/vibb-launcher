package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.InstalledApp

/*
 * Round 7 §2 (docs/design/r7-launcher-small-items.md): the catalog apps the launcher installed are
 * protected from force-stop and Clear storage (`setUserControlDisabledPackages`), lifted by the
 * offline override and the pause like every other restriction. Pure, no Android imports
 * (UserControlPlanTest); AppEnforcer.apply() reads the inputs and sets the list.
 */

/**
 * The catalog apps this launcher installed here: the stored catalog pins ([pins], `WindowRelock`'s
 * `rememberCatalogPins`: the apps checked for this phone now) that are [installed] for this user,
 * whose installing package ([installers]) is ours ([own], the server's `installed_by_launcher` test -
 * a Play-installed twin is out), minus what enforcement hides ([hidden]) and minus our own packages.
 */
fun protectedCatalogPackages(
    pins: Set<String>,
    installed: Set<String>,
    installers: Map<String, String?>,
    hidden: Set<String>,
    own: Set<String>,
): Set<String> =
    pins.filterTo(sortedSetOf()) { pkg ->
        pkg in installed && installers[pkg]?.let { it in own } == true && pkg !in hidden && pkg !in own
    }

/**
 * The list `setUserControlDisabledPackages` gets: [protected] while the phone is [managed]
 * (`hardeningManaged`), empty while [overrideActive] (the offline override or the pause: the parent
 * can force-stop or clear an app that is stuck, the standing rule) and empty when unmanaged (cleared).
 */
fun userControlDisabledPackages(managed: Boolean, overrideActive: Boolean, protected: Set<String>): Set<String> =
    if (!managed || overrideActive) emptySet() else protected

/**
 * The status report's `installed_apps[].battery_exempt` (round 7 §2.1.2): set for the [protected]
 * packages only, from [exempt] (`isIgnoringBatteryOptimizations`; `null` = unreadable, left out);
 * every other entry is left as it is (the field left out).
 */
fun withBatteryExempt(apps: List<InstalledApp>, protected: Set<String>, exempt: (String) -> Boolean?): List<InstalledApp> =
    apps.map { app -> if (app.packageName in protected) app.copy(batteryExempt = exempt(app.packageName)) else app }
