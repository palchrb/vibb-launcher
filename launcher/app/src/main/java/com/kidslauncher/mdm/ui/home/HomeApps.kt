package com.kidslauncher.mdm.ui.home

import android.content.Context
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.apps.AppFilter.Companion.AppSetVisibility
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.play.playPackageOnHome

/**
 * The apps on Home's grid ([AppSetVisibility.HIDDEN], the default) - or, with [AppSetVisibility.EXCLUSIVE], the apps
 * the kid hid with Home's long-press "Skjul" that the grid would otherwise show (Kid Settings' "Skjulte apper", the
 * user 2026-11-03). One filter for both, so the kid's list never holds an app Home wouldn't show: [AppFilter] (allowed
 * and not suspended, listable, not kept off Home by the parent's `app_display.on_home: false` outside a window), never
 * Vibb One itself, and the Play Store only in install mode - the drawer lists it in a parent-PIN window too, Home's
 * grid doesn't (design 28; unreadable = not in install mode). Sorted by the name the kid sees. **Off the main thread**:
 * [AppFilter] asks the package manager about every app.
 */
fun homeApps(
    context: Context,
    all: List<AbstractDetailedAppInfo>,
    visibility: AppSetVisibility = AppSetVisibility.HIDDEN,
): List<AbstractDetailedAppInfo> {
    val installMode = try {
        PlayRuntime.installModeActive(context)
    } catch (e: Exception) {
        false
    }
    return AppFilter(context, visibility).invoke(all)
        .filter { (it.getRawInfo() as? AppInfo)?.packageName != context.packageName }
        .filter { playPackageOnHome((it.getRawInfo() as? AppInfo)?.packageName, installMode) }
}
