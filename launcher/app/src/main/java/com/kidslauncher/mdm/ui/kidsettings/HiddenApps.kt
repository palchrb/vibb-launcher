package com.kidslauncher.mdm.ui.kidsettings

/**
 * What Kid Settings' "Skjulte apper" shows (the user, 2026-11-03). [apps] are the kid's own hidden apps that Home would
 * otherwise show ([com.kidslauncher.mdm.ui.home.homeApps] with `EXCLUSIVE`: the parent's off-Home apps are never among
 * them): no row while there is none; the list while the kid has [open]ed it. [open] is what the screen keeps - it
 * closes when the last app is shown again, so the next app hidden brings back a closed row.
 */
data class HiddenAppsView<T>(val rowShown: Boolean, val open: Boolean, val listed: List<T>)

fun <T> hiddenAppsView(apps: List<T>, open: Boolean): HiddenAppsView<T> {
    val isOpen = open && apps.isNotEmpty()
    return HiddenAppsView(apps.isNotEmpty(), isOpen, if (isOpen) apps else emptyList())
}
