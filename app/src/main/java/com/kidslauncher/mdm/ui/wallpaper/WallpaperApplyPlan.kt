package com.kidslauncher.mdm.ui.wallpaper

/*
 * When the launcher puts its wallpaper on the system wallpaper (design 08 §3, QA 08 #1, #2, #4)
 * - pure, tested in WallpaperApplyPlanTest. WallpaperApplier only feeds it and acts.
 */

/** What was last applied (CE prefs `wallpaper_state`). */
data class ApplyRecord(
    /** [wallpaperKey] of the last successful apply, null when nothing of ours is applied. */
    val appliedKey: String? = null,
    /** `WallpaperManager.setBitmap`'s id for the system wallpaper then. */
    val appliedId: Int = 0,
    /** The last key we tried, and the day (epoch day) we tried it. */
    val attemptKey: String? = null,
    val attemptDay: Long = -1,
)

sealed interface ApplyDecision {
    data object Apply : ApplyDecision

    /** Unmanaged after we had set one: put navy back once, before the restriction is lifted. */
    data object Reset : ApplyDecision

    data class Skip(val reason: String) : ApplyDecision
}

/** What the system wallpaper should be: the home fill, the lock-screen fill and the size. */
fun wallpaperKey(home: Wallpaper, widthPx: Int, heightPx: Int): String =
    "home=${home.fill.describe()}|lock=${lockScreenFill(home).describe()}|${widthPx}x$heightPx"

/**
 * Only while the phone is managed, only as device owner when the platform says it may, only
 * when the wanted wallpaper isn't already there, and never twice in one day for the same key
 * (a refused `setBitmap` returns 0 rather than throwing: no fight with the platform, no PNG
 * encode on every sync). Unmanaged: once back to navy if we had set ours, then nothing.
 */
fun wallpaperApplyPlan(
    managed: Boolean,
    canSet: Boolean,
    wantKey: String,
    record: ApplyRecord,
    currentSystemId: Int,
    today: Long,
): ApplyDecision {
    if (!managed) {
        return if (record.appliedKey != null) ApplyDecision.Reset else ApplyDecision.Skip("unmanaged")
    }
    if (!canSet) return ApplyDecision.Skip("not allowed or not supported")
    if (record.appliedKey == wantKey && record.appliedId != 0 && currentSystemId == record.appliedId) {
        return ApplyDecision.Skip("in place")
    }
    if (record.attemptKey == wantKey && record.attemptDay == today) return ApplyDecision.Skip("already tried today")
    return ApplyDecision.Apply
}

/** The record after an apply of [key] on [today] that returned [systemId] (0 = refused). */
fun recordAttempt(record: ApplyRecord, key: String, systemId: Int, today: Long): ApplyRecord =
    if (systemId != 0) {
        ApplyRecord(appliedKey = key, appliedId = systemId, attemptKey = key, attemptDay = today)
    } else {
        record.copy(attemptKey = key, attemptDay = today)
    }

/** Whether the system wallpaper is ours and current, so Home can be transparent over it. */
fun systemShowsOurs(record: ApplyRecord, wantKey: String, currentSystemId: Int): Boolean =
    record.appliedKey == wantKey && record.appliedId != 0 && currentSystemId == record.appliedId
