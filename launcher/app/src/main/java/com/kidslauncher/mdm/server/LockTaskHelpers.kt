package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.play.PLAY_CORE

/*
 * System helpers pinned in kiosk next to the allowlist while LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
 * is on (handy step 9, B4 with qa-09-design.md #2/#3 on top). With that bit every activity start
 * whose package isn't a lock-task package becomes the system's BlockedAppActivity - with no
 * emergency exemption - so whatever emergency calls, runtime permission dialogs, the share sheet
 * and the pickers need must be pinned. Package names differ per device, so AppEnforcer resolves
 * them from intents at every apply; this file only decides. Pure, tested in LockTaskHelpersTest.
 */

/** `DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (API 30, public). */
const val LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64

enum class HelperKind {
    /** `ACTION_EMERGENCY_DIAL` (com.android.phone's EmergencyDialer on AOSP). */
    EMERGENCY_DIALER,
    /** Telecom (`com.android.server.telecom`): UserCallActivity/EmergencyCallActivity for
     * `ACTION_CALL*`, call error dialogs. */
    TELECOM,
    /** `PackageManager.permissionControllerPackageName`: runtime permission dialogs and the role
     * request dialog (our dialer/SMS role prompt). */
    PERMISSION_CONTROLLER,
    /** `ACTION_CHOOSER` - `com.android.intentresolver` on API 34+. */
    CHOOSER,
    /** `ACTION_OPEN_DOCUMENT` - DocumentsUI. */
    DOCUMENTS,
    /** `MediaStore.ACTION_PICK_IMAGES` - the photo picker. */
    PHOTO_PICKER,
    /** The receiver of `SMS_CB_RECEIVED` - emergency alerts (Nødvarsel). */
    CELL_BROADCAST,
    /** The "open with" disambiguation screen (ResolverActivity, package `android` on AOSP) for an
     * implicit intent with several handlers and no default (qa-09-code #5). */
    RESOLVER,
    /**
     * The system's Recents provider - the package of `config_recentsComponentName` (Pixel:
     * quickstep in the stock launcher). With the block bit its RecentsActivity, which quickstep
     * starts for every swipe-up, hold and the Recents key while our Home is Home, became
     * BlockedAppActivity ("App is not available"); pinned, the gestures work (design 16d,
     * experiment 2 variant A). Resolved from the framework resource, never a fixed name; system
     * only, like every helper. Residual risk (accepted, 16d decisions): an allowed app could start
     * that package's stock home, settings or search explicitly - no kid-reachable path was found.
     */
    RECENTS,

    /**
     * Emergency SOS (bug 25b-H): the system activity for `com.android.systemui.action.LAUNCH_EMERGENCY`
     * that SystemUI starts at five power presses (AOSP 16 EmergencyGestureModule: system apps only,
     * `config_preferredEmergencySosPackage` first) - Personal Safety (`com.google.android.apps.safetyhub`)
     * on Pixels, Emergency information (`com.android.emergency`) on AOSP images. Not pinned, the kiosk
     * block turned the SOS countdown into "App is not available" over the lock.
     */
    EMERGENCY_SOS,

    /**
     * Android's Credential Manager selector (passkeys-kiosk, docs/testing/2026-11-20-passkeys-kiosk.md): the sheet a
     * passkey or password request opens in the caller's task, before the provider's own screen. Not pinned, the block
     * turned every passkey sign-in into "App is not available". The package Android itself uses
     * ([credentialSelectorPackage]): AOSP's `com.android.credentialmanager` (Android 14 measured), or the OEM's sheet -
     * on a phone with Google's config overlay that is Play services, which [lockTaskHelpers] never pins, so nothing
     * changes there. Its own links stay inside the sheet or go to Settings (hidden, or blocked) and Play services
     * (blocked); a provider's screen is reachable only for an allowed app.
     */
    CREDENTIAL_SELECTOR,
}

/**
 * The package of the Credential Manager selector Android starts, by AOSP's rule (`IntentFactory`): the OEM's component
 * ([oemComponent], `config_oemCredentialManagerDialogComponent`, Android 15+) when it is set and its activity is
 * [oemEnabled], else the platform's ([defaultComponent], `config_credentialManagerDialogComponent`). Each is a
 * flattened component (`package/class`); `null` when neither names one.
 */
fun credentialSelectorPackage(oemComponent: String?, oemEnabled: Boolean, defaultComponent: String?): String? {
    fun pkg(component: String?): String? =
        component?.trim()?.takeIf { '/' in it }?.substringBefore('/')?.takeIf { it.isNotBlank() }
    return pkg(oemComponent)?.takeIf { oemEnabled } ?: pkg(defaultComponent)
}

/** SystemUI's Emergency SOS intent action (`EmergencyGesture.ACTION_LAUNCH_EMERGENCY`). */
const val ACTION_LAUNCH_EMERGENCY = "com.android.systemui.action.LAUNCH_EMERGENCY"

/**
 * Of the system handlers of [ACTION_LAUNCH_EMERGENCY] (already system-only, in the platform's
 * order), the Emergency SOS package to pin and never restrict - SystemUI's choice: its [preferred]
 * package (`config_preferredEmergencySosPackage`) when that is a handler, else the first. Never one
 * that is [forbidden] (Settings, the camera) or Play core - then none.
 */
fun emergencySosHandler(systemHandlers: List<String>, forbidden: Set<String>, preferred: String? = null): String? {
    val chosen = systemHandlers.firstOrNull { it == preferred } ?: systemHandlers.firstOrNull() ?: return null
    return chosen.takeIf { it !in forbidden && it !in PLAY_CORE }
}

/** What an intent resolved to: the package and whether it is a system app (FLAG_SYSTEM). */
data class ResolvedHelper(val packageName: String, val system: Boolean)

/**
 * The helper packages to pin: every resolved helper that is a system app, except packages that
 * must never be reachable this way ([forbidden]: Settings, the launcher-visible camera and
 * whatever else the caller names) and Play core (Play Store, Play services, GSF). A forbidden
 * package the parent allowlisted is pinned by the allowlist anyway, not by this. The system
 * dialer is pinned by [computeEnforcementPlan] itself whenever the block is on (qa-09-code #1).
 */
fun lockTaskHelpers(resolved: Map<HelperKind, ResolvedHelper?>, forbidden: Set<String>): Set<String> =
    resolved.values.filterNotNull()
        .filter { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }
        .mapTo(mutableSetOf()) { it.packageName }

/**
 * Of the packages an intent resolves to (in the platform's order), the first that is a system app
 * and not [forbidden] - a forbidden first match (the dialer or the camera for `ACTION_CALL`) must
 * not hide the real helper behind it (qa-09-code #6).
 */
fun firstHelper(matches: List<ResolvedHelper>, forbidden: Set<String>): ResolvedHelper? =
    matches.firstOrNull { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }

/**
 * The lock-task features to set: the server's (minus the block bit - that travels as its own
 * switch), keyguard always, and the block bit when the
 * server's [blockActivityStart] switch is on.
 */
fun lockTaskFeatures(serverFeatures: Long, blockActivityStart: Boolean): Int {
    val base = (serverFeatures.toInt() and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK.inv()) or LOCK_TASK_FEATURE_KEYGUARD
    return if (blockActivityStart) base or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK else base
}

/**
 * The features to write right before the lock-task packages are emptied (the kiosk's end: a pause, an
 * override, the kiosk switched off): [current] without the app block, or `null` when the block is
 * not set (nothing to write).
 *
 * Why (seen on the emulator, 2026-11-10, docs/testing/emulator.md §6af): `setLockTaskPackages(admin, [])`
 * removes Home's task from lock task and, inside the same call, restarts Home - but the end of
 * lock-task mode is only posted to a handler, and the features are reset after the packages. If the
 * start is checked before that handler ran, lock task still reads LOCKED with the block bit and an
 * empty list: Home's start is swapped for the system's BlockedAppActivity ("App is not available"),
 * which locks the phone to its own task. Without the bit the start is never swapped. The pin writes
 * the plan's features again before its packages, so the block is back whenever the kiosk is.
 */
fun unpinFeatures(current: Int): Int? =
    if (current and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK == 0) null
    else current and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK.inv()

/*
 * Handy's PIN lock (step 10, design 10-lock-and-call-ui.md §3 with qa-10-design.md #1/#10): while
 * the lock is LOCKED the lock screen always runs in lock task. The same pure functions serve the
 * fast path (PinLockRuntime via LockTaskChrome) and every apply(), so a sync can't undo them.
 */

/** `DevicePolicyManager.LOCK_TASK_FEATURE_*` (public constants, duplicated to stay Android-free). */
const val LOCK_TASK_FEATURE_SYSTEM_INFO = 1
const val LOCK_TASK_FEATURE_NOTIFICATIONS = 2
const val LOCK_TASK_FEATURE_HOME = 4
const val LOCK_TASK_FEATURE_OVERVIEW = 8
const val LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 16

/** Kiosk off while LOCKED: the status line (time, battery), the power menu and the keyguard bit
 * (it carries AOSP's emergency-call exemption) - no shade, no Home, no Recents, no app block. */
const val PIN_LOCK_FEATURES_KIOSK_OFF =
    LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_GLOBAL_ACTIONS or LOCK_TASK_FEATURE_SYSTEM_INFO

/**
 * Kiosk on: the plan's features minus the shade/heads-up alerts, Home and Recents while LOCKED.
 * SYSTEM_INFO (the mock shows Android's status line), GLOBAL_ACTIONS as the server set it,
 * KEYGUARD and the app-block bit stay.
 */
fun featuresWhileLocked(base: Int, locked: Boolean): Int =
    if (!locked) {
        base
    } else {
        (base and (LOCK_TASK_FEATURE_NOTIFICATIONS or LOCK_TASK_FEATURE_HOME or LOCK_TASK_FEATURE_OVERVIEW).inv()) or
            LOCK_TASK_FEATURE_KEYGUARD
    }

/**
 * The lock-task features while the Flymodus route's window pins Settings (design 98 §2.2 step 4, design 99's QA Q99-H1):
 * [base] without the shade. In lock task `setStatusBarDisabled` is only recorded (the kiosk's lock task owns the status
 * bar, `lock/StatusBarHealPlan.kt`), so the route's `statusBarDisabled` does nothing there; without
 * [LOCK_TASK_FEATURE_NOTIFICATIONS] Android's LockTaskController sets `DISABLE2_NOTIFICATION_SHADE`, SystemUI collapses
 * an open shade and the window can't open it - no notification (Android's "Wi-Fi stays on in airplane mode", a
 * Settings nag) can be tapped through to the pinned Settings. Quick Settings is never on in lock task. Every other bit
 * stays, KEYGUARD and the app block included. The window's close writes [base] again (the plan's pass; after a process
 * death the put-back's apply()).
 */
fun routeWindowFeatures(base: Int): Int = base and LOCK_TASK_FEATURE_NOTIFICATIONS.inv()

/**
 * What to set on the platform: lock-task [packages] (`null` = none, kiosk off and unlocked),
 * [features], `setStatusBarDisabled` ([statusBarDisabled]: a backstop while LOCKED - ineffective
 * while pinned - and the shade block of the update fence, step 11) and `DISALLOW_CREATE_WINDOWS`
 * ([createWindowsBlocked]: no chat heads or other overlays over the lock, QA 10 #10).
 */
data class LockTaskSetting(
    val packages: Set<String>?,
    val features: Int,
    val statusBarDisabled: Boolean,
    val createWindowsBlocked: Boolean,
)

/**
 * The lock-task setting for the plan's [kioskPackages]/[baseFeatures]/[restrictCreateWindows]
 * and the PIN lock state:
 * - unlocked (or no lock): the plan as it is;
 * - LOCKED, kiosk on: the kiosk list is **never** touched (removing a package would clear its
 *   locked task - the kid's app would lose its state at every screen-off), only the features -
 *   except the recents provider ([recentsPin], qa-16d-code #1): it leaves the list while LOCKED,
 *   so no Recents start (a keyboard's Recents key, an accessibility action, a desynced SystemUI)
 *   can show the Overview's snapshots over the lock. That clears its RecentsActivity task, which
 *   isn't the kid's; the unlocked list pins it again and quickstep starts it anew;
 * - LOCKED, kiosk off: our package plus [lockHelpers] (emergency dialer, Telecom, the system
 *   dialer, the system clock app - no third-party app) with [PIN_LOCK_FEATURES_KIOSK_OFF]; the
 *   lock screen starts lock task itself and stops it on unlock.
 * The status bar is disabled while LOCKED **or** while the update fence is up ([fenced], step 11,
 * qa-11-design.md #4): the fence is an input here, so LockTaskChrome stays the only owner of the
 * status bar and a release always re-enables it when nothing else wants it off.
 * [voipPackages] (design 17, QA #4/#6): while an allowed app's VoIP call rings or lives, LOCKED
 * with the kiosk off also pins that package and the permission controller (mic/camera prompts)
 * and sets the app-block bit, so the call app's other screens (links, viewers) can't open over the
 * lock. With the kiosk on the list is untouched (the app is allowlisted).
 *
 * [routePin] (design 98 §2.2 step 4): Settings for the second of a Flymodus turn-on - added to the kiosk's list with the
 * shade closed and blocked ([routeWindowFeatures]), only while unlocked and the kiosk is on. A LOCKED pass leaves it out
 * (the route then times out). [routeClosing]: the close's first pass - Settings out, the shade still blocked - so the
 * shade is never back while Settings is pinnable (a pass writes the features before the list); the next pass, without
 * it, gives the shade back.
 *
 * [ruleClockPins] (design 105 §2.1, the plan's [EnforcementPlan.ruleClockPins]): the clocks a time rule took out of the
 * kiosk's list. The kiosk-on LOCKED row adds them always (no reach while LOCKED: Home, Recents and the shade are gone,
 * and any clock screen but the ringing one needs the keyguard dismissed - the UNLOCKED edge); the not-LOCKED rows only
 * while [dark] ([clockDark]: the gap before the keyguard locks, a phone without a lock), so a full-screen intent in the
 * dark finds the clock allowed. Unlocked and lit they are out, and Android closes the clock's task. The SOS-narrowed
 * row (its helpers carry the clocks) and the kiosk off are unchanged.
 */
fun lockTaskWhileLocked(
    kioskPackages: Set<String>?,
    baseFeatures: Int,
    restrictCreateWindows: Boolean,
    locked: Boolean,
    ownPackage: String,
    lockHelpers: Set<String>,
    fenced: Boolean = false,
    voipPackages: Set<String> = emptySet(),
    recentsPin: String? = null,
    sosNarrowed: Set<String>? = null,
    routePin: String? = null,
    routeClosing: Boolean = false,
    ruleClockPins: Set<String> = emptySet(),
    dark: Boolean = false,
): LockTaskSetting {
    val darkPins = if (dark) ruleClockPins else emptySet()
    return when {
        !locked && kioskPackages != null && routePin != null ->
            LockTaskSetting(kioskPackages + darkPins + routePin, routeWindowFeatures(baseFeatures), statusBarDisabled = true, createWindowsBlocked = restrictCreateWindows)
        !locked && kioskPackages != null && routeClosing ->
            LockTaskSetting(kioskPackages + darkPins, routeWindowFeatures(baseFeatures), statusBarDisabled = true, createWindowsBlocked = restrictCreateWindows)
        !locked -> LockTaskSetting(kioskPackages?.plus(darkPins), baseFeatures, statusBarDisabled = fenced, createWindowsBlocked = restrictCreateWindows)
        // qa-25b-25f H1: during the "sos" yield the kiosk's list shrinks to the kiosk-off one, so Android
        // removes every other locked task (an allowed app brought up in the SOS window).
        kioskPackages != null && sosNarrowed != null -> LockTaskSetting(
            setOf(ownPackage) + (sosNarrowed - PLAY_CORE), featuresWhileLocked(baseFeatures, true), statusBarDisabled = true, createWindowsBlocked = true,
        )
        kioskPackages != null -> LockTaskSetting(
            kioskPackages + ruleClockPins - setOfNotNull(recentsPin), featuresWhileLocked(baseFeatures, true), statusBarDisabled = true, createWindowsBlocked = true,
        )
        else -> {
            val voip = voipPackages - PLAY_CORE
            LockTaskSetting(
                setOf(ownPackage) + (lockHelpers - PLAY_CORE) + voip,
                if (voip.isEmpty()) PIN_LOCK_FEATURES_KIOSK_OFF else PIN_LOCK_FEATURES_KIOSK_OFF or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK,
                statusBarDisabled = true,
                createWindowsBlocked = true,
            )
        }
    }
}

/**
 * Design 105 §2.1: the screen counts as dark for the rule's clocks ([lockTaskWhileLocked]'s `dark`) while it is off
 * ([interactive] false), and for [com.kidslauncher.mdm.lock.ALARM_SCREEN_SETTLE_MS] after it came on ([screenOnAtMs],
 * elapsed realtime), so a ring that woke the screen reaches the keyguard first. `null` = no screen-on since the last
 * screen-off (code QA L1: interactive before SCREEN_ON arrived) - dark too; a process that starts with the screen on
 * notes its start as settled.
 */
fun clockDark(interactive: Boolean, screenOnAtMs: Long?, nowMs: Long): Boolean =
    !interactive || screenOnAtMs == null || nowMs - screenOnAtMs < com.kidslauncher.mdm.lock.ALARM_SCREEN_SETTLE_MS

/**
 * Design 105 §2.1 (code QA L1): whether a not-LOCKED pass puts the rule's clocks in the list - while [clockDark], or
 * while Android's keyguard is locked ([keyguardLocked]): with Android's PIN lock as the kid's lock Vibb's own lock is
 * never LOCKED, and the keyguard gives no reach either (any clock screen but the ringing one needs it dismissed, and
 * its dismissal is USER_PRESENT, which runs a pass).
 */
fun ruleClocksIn(interactive: Boolean, screenOnAtMs: Long?, nowMs: Long, keyguardLocked: Boolean): Boolean =
    keyguardLocked || clockDark(interactive, screenOnAtMs, nowMs)

/**
 * The kiosk list of a pass without a plan (LockTaskChrome's fallback), from the platform's
 * [platformPackages]: a LOCKED pass took the [recentsPin] out (qa-16d-code #1), so an unlocked one
 * puts it back whenever the app block is on ([features]) - the plan pins it then too. `null` pin
 * (unknown, or not pinnable): the platform's list as it is. Never Settings (design 98 code QA L4): only
 * the Flymodus route's window pins it, and that needs a plan - in the platform's list it is a window a
 * process death left open, which the put-back's apply closes. Design 105 (QA L3): the last plan's stored
 * [ruleClockPins] go out on an unlocked, lit pass and in on a LOCKED or dark one ([clocksIn]).
 */
fun fallbackKioskPackages(
    platformPackages: Set<String>,
    recentsPin: String?,
    features: Int,
    ruleClockPins: Set<String> = emptySet(),
    clocksIn: Boolean = false,
): Set<String> =
    (if (recentsPin != null && features and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK != 0) platformPackages + recentsPin else platformPackages) -
        com.kidslauncher.mdm.assistant.SETTINGS_PACKAGE - ruleClockPins + (if (clocksIn) ruleClockPins else emptySet())

/**
 * Whether `config_recentsComponentName`'s [packageName] may be trusted as the recents provider
 * (design 16d, qa-16d-code #5): a system app with a HOME activity ([hasHome] - quickstep lives in
 * the stock launcher) or the configured recents activity itself ([hasRecentsActivity]), never
 * SystemUI (legacy recents: pinning it would open every exported SystemUI activity in the kiosk),
 * the platform or Play core. `null` = no provider: nothing pinned, exempted or fenced as one.
 */
fun trustedRecentsProvider(packageName: String?, system: Boolean, hasHome: Boolean, hasRecentsActivity: Boolean): String? =
    packageName?.takeIf {
        system && (hasHome || hasRecentsActivity) && it != "com.android.systemui" && it != "android" && it !in PLAY_CORE
    }

/**
 * LockTaskChrome's memory of the status-bar state it set, so it doesn't call
 * `setStatusBarDisabled` on every pass. [invalidate] (process start, every fence and release)
 * makes the next pass write whatever it wants - a release must re-enable the bar even if the
 * memory says it already is (qa-11-design.md #4).
 */
class StatusBarLatch {
    private var applied: Boolean? = null

    /** The value to write, or `null` when [wanted] is already set. */
    @Synchronized
    fun toWrite(wanted: Boolean): Boolean? = if (applied == wanted) null else wanted

    /** The platform accepted [value]. */
    @Synchronized
    fun written(value: Boolean) {
        applied = value
    }

    @Synchronized
    fun invalidate() {
        applied = null
    }
}

/**
 * The system packages the lock pins with the kiosk off: whatever handles the emergency dialer and
 * `ACTION_CALL` (Telecom), the system dialer - only while our dialer role isn't held, when it is
 * the in-call UI of every call (qa-10-code #5; with the role held its emergency in-call UI runs
 * under AOSP's KEYGUARD exemption, and pinning it would let its full UI - call log, contacts,
 * keypad - stay over the lock) - and the clock app (its alarm screen shows over the lock). Only system apps, never a
 * forbidden one (Settings, the camera) or Play - except [clockApp], the catalog's clock app with its pinned signer
 * (design 42 §1.2, `ClockApp.installed`), whose alarm screen shows over the lock the same way.
 */
fun pinLockHelpers(
    emergencyDialer: ResolvedHelper?,
    telecom: ResolvedHelper?,
    systemDialer: ResolvedHelper?,
    alarmApp: ResolvedHelper?,
    forbidden: Set<String>,
    ourDialerHeld: Boolean = false,
    emergencySos: ResolvedHelper? = null,
    clockApp: String? = null,
    /** Code QA L2: every spared alarm app (each system clock among them) - filtered like the rest. */
    alarmApps: List<ResolvedHelper> = emptyList(),
): Set<String> = (listOfNotNull(emergencyDialer, telecom, systemDialer.takeIf { !ourDialerHeld }, alarmApp, emergencySos) + alarmApps)
    .filter { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }
    .mapTo(mutableSetOf()) { it.packageName }
    .apply { clockApp?.takeIf { it !in forbidden }?.let(::add) }

/** One of the chrome's writes ([chromeWriteOrder]). */
enum class ChromeWrite { STATUS_BAR, CREATE_WINDOWS, LOCK_TASK }

/**
 * The order of a chrome pass's writes (qa-16c-code #1): a LOCKED pass blocks the shade and
 * overlays first (two single DPM calls) and then sets the lock-task features and packages; any
 * other pass sets lock task first and lifts the status bar and `DISALLOW_CREATE_WINDOWS` last - so
 * the protections are the first thing on and the last thing off.
 */
fun chromeWriteOrder(locked: Boolean): List<ChromeWrite> =
    if (locked) {
        listOf(ChromeWrite.STATUS_BAR, ChromeWrite.CREATE_WINDOWS, ChromeWrite.LOCK_TASK)
    } else {
        listOf(ChromeWrite.LOCK_TASK, ChromeWrite.STATUS_BAR, ChromeWrite.CREATE_WINDOWS)
    }

/**
 * The lock-task list during the "sos" yield (qa-25b-25f H1): our package, the lock's helpers
 * ([lockHelpers]: [pinLockHelpers] - emergency dialer, Telecom, the system dialer, the clock app, the
 * SOS app) and the [sosPackage] - never Play core, never an allowed app or the recents provider.
 */
fun sosNarrowedPackages(ownPackage: String, lockHelpers: Set<String>?, sosPackage: String?): Set<String> =
    setOf(ownPackage) + lockHelpers.orEmpty() + setOfNotNull(sosPackage) - PLAY_CORE
