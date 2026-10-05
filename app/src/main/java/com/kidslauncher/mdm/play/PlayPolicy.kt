package com.kidslauncher.mdm.play

import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.timedWindowActive

/*
 * Play as an app source (handy step 7, design 07 §4 and the binding decisions after QA review).
 * Pure, no Android imports, unit-tested in PlayPolicyTest.
 *
 * - Play services and Google Services Framework are never suspended or hidden: FCM runs in them.
 * - The Play Store is never hidden (FCM requires it installed) but SUSPENDED whenever apps are
 *   managed, except during the parent's install mode and the nightly update window. Suspension,
 *   not only kiosk, because Play opened with an explicit intent from inside an allowed app's
 *   task (rate-us buttons, ad SDKs) would otherwise give the kid the whole store (QA #1); a
 *   suspended package's activities don't start anywhere.
 * - None of them is ever launchable from Home or pinned in kiosk - except the Play Store during
 *   install mode.
 * - Anything Play installs is caught by the usual new-package path: hidden and suspended until
 *   the parent allowlists it.
 */

const val PLAY_STORE = "com.android.vending"
const val PLAY_SERVICES = "com.google.android.gms"
const val GOOGLE_SERVICES_FRAMEWORK = "com.google.android.gsf"

/** Never shown, never pinned (outside install mode), never hidden. */
val PLAY_CORE = setOf(PLAY_STORE, PLAY_SERVICES, GOOGLE_SERVICES_FRAMEWORK)

/** Never suspended or hidden, managed or not, locked or not: FCM. */
val PLAY_NEVER_RESTRICT = setOf(PLAY_SERVICES, GOOGLE_SERVICES_FRAMEWORK)

/**
 * The nightly window in which the Play Store is unsuspended so it can update apps, in local
 * minutes since midnight, `[startMinute, endMinute)`; `startMinute > endMinute` wraps past
 * midnight. Default 02:00-04:00.
 */
data class UpdateWindow(val startMinute: Int = 120, val endMinute: Int = 240) {
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    /** Minutes from [minuteOfDay] to the next start or end of the window (1..1440). */
    fun minutesToNextEdge(minuteOfDay: Int): Int {
        fun until(edge: Int) = ((edge - minuteOfDay) % 1440 + 1440) % 1440
        val candidates = listOf(until(startMinute), until(endMinute)).map { if (it == 0) 1440 else it }
        return candidates.min()
    }
}

val DEFAULT_UPDATE_WINDOW = UpdateWindow()

/**
 * The update window is in force: inside the window and the screen is off. Turning the screen on
 * ends it at once (the next re-check suspends Play again before the keyguard can be passed);
 * it resumes once the screen is off again, still inside the window.
 */
fun updateWindowActive(minuteOfDay: Int, screenInteractive: Boolean, window: UpdateWindow = DEFAULT_UPDATE_WINDOW): Boolean =
    !screenInteractive && window.contains(minuteOfDay)

/** What the plan does with the Play packages right now. */
data class PlayState(
    /** Install mode is on (and not cut short by a time-rule lock). */
    val installMode: Boolean = false,
    /** The nightly update window is in force. */
    val updateWindow: Boolean = false,
) {
    /** Changes of this key make a re-check re-apply the plan (like the time-rule lock key). */
    fun key(): String = "install=$installMode;window=$updateWindow"
}

/**
 * Whether the Play Store must be suspended: whenever apps are managed ([appsManaged] - an
 * allowlist and no override; the override and pause lift it like every app restriction), except
 * in install mode (not while a time rule locks the phone: install mode is refused then, and a lock
 * that begins cuts it short) or the update window (also during bedtime - the screen is off).
 */
fun playStoreSuspended(appsManaged: Boolean, locked: Boolean, play: PlayState): Boolean {
    if (!appsManaged) return false
    if (play.updateWindow) return false
    if (play.installMode && !locked) return false
    return true
}

/** Whether [packageName] may be shown on Home and in the drawer: never for [PLAY_CORE], except
 * the Play Store during install mode. */
fun playPackageLaunchable(packageName: String, installModeActive: Boolean): Boolean =
    packageName !in PLAY_CORE || (packageName == PLAY_STORE && installModeActive)

// ---------------------------------------------------------------------------------------------
// Install mode: the parent opens Play for a bounded time with the override PIN.
// ---------------------------------------------------------------------------------------------

/** How long one install mode lasts. "End now" or a reboot ends it sooner. */
const val INSTALL_MODE_DURATION_MS = 15 * 60 * 1000L

/** Same clocks as the pause: wall clock, elapsed realtime and the boot count must all agree, so
 * neither a clock change nor a reboot can stretch it. */
fun installModeActive(start: WindowStart?, nowWallMs: Long, nowElapsedMs: Long, nowBootCount: Int): Boolean =
    start != null && timedWindowActive(start, nowWallMs, nowElapsedMs, nowBootCount, INSTALL_MODE_DURATION_MS)

enum class InstallModeStart {
    OK,

    /** The phone isn't managed (no allowlist, or an override/pause is on): Play isn't suspended,
     * nothing to open. */
    NOT_NEEDED,

    /** No override PIN on the server - install mode needs one. */
    NO_PIN,

    /** Too many wrong PINs; the shared lockout is running. */
    LOCKED_OUT,

    /** A time rule or the used-up budget locks the phone (QA #3: refused). */
    TIME_LOCKED,

    /** The Play Store isn't installed (or is disabled). */
    NO_PLAY_STORE,
}

fun canStartInstallMode(
    appsManaged: Boolean,
    pinConfigured: Boolean,
    pinLockedOut: Boolean,
    timeLocked: Boolean,
    playStoreInstalled: Boolean,
): InstallModeStart = when {
    !playStoreInstalled -> InstallModeStart.NO_PLAY_STORE
    !appsManaged -> InstallModeStart.NOT_NEEDED
    !pinConfigured -> InstallModeStart.NO_PIN
    pinLockedOut -> InstallModeStart.LOCKED_OUT
    timeLocked -> InstallModeStart.TIME_LOCKED
    else -> InstallModeStart.OK
}

// ---------------------------------------------------------------------------------------------
// Links into Play and one source per package
// ---------------------------------------------------------------------------------------------

/** `market://...` and `https://play.google.com/store/...` - the links our blocker answers
 * (persistent preferred activity, never lifted - QA #2). */
fun isPlayLink(scheme: String?, host: String?, path: String?): Boolean = when (scheme?.lowercase()) {
    "market" -> true
    "http", "https" -> host?.lowercase() == "play.google.com" && (path ?: "").startsWith("/store")
    else -> false
}

/** A catalog update for a package Play installed is skipped: other signature, and Android 14's
 * update ownership gives the package to Play. Switching source means uninstalling first. */
fun catalogUpdateBlockedByPlay(installedFrom: String?): Boolean = installedFrom == PLAY_STORE
