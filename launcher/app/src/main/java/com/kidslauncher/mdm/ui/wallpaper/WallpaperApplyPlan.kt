package com.kidslauncher.mdm.ui.wallpaper

/*
 * When the launcher puts its wallpaper on the system wallpaper (design 08 §3, QA 08 #1, #2, #4,
 * code review qa-08-code.md #1-3) - pure, tested in WallpaperApplyPlanTest. WallpaperApplier only
 * feeds it and acts.
 */

/** What was last applied (CE prefs `wallpaper_state`). */
data class ApplyRecord(
    /** [wallpaperKey] of the last fully successful apply, null when nothing of ours is applied. */
    val appliedKey: String? = null,
    /** `WallpaperManager.setBitmap`'s id for the system wallpaper then. */
    val appliedId: Int = 0,
    /** The last key we tried, and the day (epoch day) we tried it. */
    val attemptKey: String? = null,
    val attemptDay: Long = -1,
    /**
     * A key that may be partly on the system or lock wallpaper: one of its two `setBitmap` calls
     * went through, the other didn't. Counts as "ours" for the reset and the revocation check
     * until a full apply or a reset succeeds.
     */
    val partialKey: String? = null,
) {
    /** Something of ours may be on the system or lock wallpaper. */
    val oursMayShow: Boolean get() = appliedKey != null || partialKey != null
}

sealed interface ApplyDecision {
    data object Apply : ApplyDecision

    /** Unmanaged after we had set one: put navy back (or clear), retried on every pass until it
     * worked - always before `DISALLOW_SET_WALLPAPER` is lifted. */
    data object Reset : ApplyDecision

    data class Skip(val reason: String) : ApplyDecision
}

/** What the system wallpaper should be: the home fill, the lock-screen fill and the size. */
fun wallpaperKey(home: Wallpaper, widthPx: Int, heightPx: Int): String =
    "home=${home.fill.describe()}|lock=${lockScreenFill(home).describe()}|${widthPx}x$heightPx"

/** The photo hashes a [wallpaperKey] puts on the system or lock wallpaper. */
fun imagesInKey(key: String?): Set<String> =
    key?.split('|')?.mapNotNullTo(mutableSetOf()) { part ->
        part.substringAfter('=', "").takeIf { it.startsWith("image:") }?.removePrefix("image:")
    } ?: emptySet()

/** A photo of ours may be showing that the parent no longer allows (or that is gone). */
fun revokedImageShows(record: ApplyRecord, allowedImages: Set<String>): Boolean =
    (imagesInKey(record.appliedKey) + imagesInKey(record.partialKey)).any { it !in allowedImages }

/**
 * Only while the phone is managed, only as device owner when the platform says it may, only
 * when the wanted wallpaper isn't already there, and never twice in one day for the same key
 * (a refused `setBitmap` returns 0 rather than throwing: no fight with the platform, no PNG
 * encode on every sync) - **except** when a photo the parent took away may still be showing:
 * then it is replaced on every pass until that worked (the applier falls back to a reset).
 * Unmanaged: back to navy whenever something of ours may show, until that worked.
 */
fun wallpaperApplyPlan(
    managed: Boolean,
    canSet: Boolean,
    wantKey: String,
    record: ApplyRecord,
    currentSystemId: Int,
    today: Long,
    revoked: Boolean = false,
): ApplyDecision {
    if (!managed) {
        return if (record.oursMayShow) ApplyDecision.Reset else ApplyDecision.Skip("unmanaged")
    }
    if (!canSet) return ApplyDecision.Skip("not allowed or not supported")
    if (revoked) return ApplyDecision.Apply
    if (record.appliedKey == wantKey && record.appliedId != 0 && record.partialKey == null &&
        currentSystemId == record.appliedId
    ) {
        return ApplyDecision.Skip("in place")
    }
    if (record.attemptKey == wantKey && record.attemptDay == today) return ApplyDecision.Skip("already tried today")
    return ApplyDecision.Apply
}

/** How an apply went: the system wallpaper's id (0 = refused/failed), and whether any of the
 * two `setBitmap` calls went through. */
data class ApplyOutcome(val systemId: Int, val lockOk: Boolean) {
    val complete: Boolean get() = systemId != 0 && lockOk
    val anyApplied: Boolean get() = systemId != 0 || lockOk
}

/** The record after an apply of [key] on [today]. */
fun recordAttempt(record: ApplyRecord, key: String, outcome: ApplyOutcome, today: Long): ApplyRecord = when {
    outcome.complete -> ApplyRecord(appliedKey = key, appliedId = outcome.systemId, attemptKey = key, attemptDay = today)
    // Half of it may show: remember it, so a reset or a revocation still finds it.
    outcome.anyApplied -> record.copy(attemptKey = key, attemptDay = today, partialKey = key)
    else -> record.copy(attemptKey = key, attemptDay = today)
}

/** The record after a reset: empty only when it worked, else unchanged (retried next pass). */
fun recordReset(record: ApplyRecord, worked: Boolean): ApplyRecord = if (worked) ApplyRecord() else record

/** Whether the system wallpaper is ours and current, so Home can be transparent over it. */
fun systemShowsOurs(record: ApplyRecord, wantKey: String, currentSystemId: Int): Boolean =
    record.appliedKey == wantKey && record.appliedId != 0 && record.partialKey == null &&
        currentSystemId == record.appliedId

/** One step of lifting the hardening restrictions that are off. */
sealed interface ClearStep {
    /** Put navy back if ours may show; [Clear] of SET_WALLPAPER only runs when this worked. */
    data object ResetWallpaper : ClearStep

    data class Clear(val restriction: com.kidslauncher.mdm.server.HardeningRestriction) : ClearStep
}

/**
 * The order in which `AppEnforcer` lifts restrictions that are off (qa-08-code.md #1): the
 * wallpaper reset comes right before `DISALLOW_SET_WALLPAPER` is cleared, so nobody else can set
 * a wallpaper while our (maybe private) photo is still the system one.
 */
fun hardeningClearSteps(plan: com.kidslauncher.mdm.server.HardeningPlan): List<ClearStep> =
    plan.restrictions.filterValues { !it }.keys.flatMap { restriction ->
        if (restriction == com.kidslauncher.mdm.server.HardeningRestriction.SET_WALLPAPER) {
            listOf(ClearStep.ResetWallpaper, ClearStep.Clear(restriction))
        } else {
            listOf(ClearStep.Clear(restriction))
        }
    }
