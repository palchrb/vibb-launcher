package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.appdisplay.isValidPackageName
import com.kidslauncher.mdm.server.dto.RelockReport
import kotlinx.serialization.Serializable

/*
 * The pure rules of the relock engine (design 24b §1, docs/design/24b-override-relock.md): the
 * windows a correct parent PIN opens (an override, a pause) lock down again by themselves, and
 * whatever the kid changed in one is reset or reported. No Android imports (WindowRelockPlanTest,
 * RelockBlockTest); WindowRelock.kt reads the platform and acts.
 */

/** The windows the engine tracks. Install mode stays out (QA S1): it opens no Settings surface and
 * already relocks by itself. [wire] is `relock.windows`'s value. */
enum class WindowKind(val wire: String) {
    OVERRIDE("override"),
    PAUSE("pause"),
}

/** What set a relock off - `relock.trigger`. */
enum class RelockTrigger(val wire: String) {
    TIMER("timer"),
    LOCK_AGAIN("lock_again"),
    COMMAND("command"),
    SYNC("sync"),
    /** A process start: a reboot ends every window (a crash restart counts here too). */
    BOOT("boot"),
    SCREEN_ON("screen_on"),
    SETTINGS("settings"),
}

/** The fixed names `relock.failures` takes (the server keeps only these). */
object RelockFailure {
    const val CREDENTIAL = "credential"
    const val SIGNER_CHECK = "signer_check"
    const val APPLY = "apply"
    /** `setPermitted*` refused (an enabled service outside the list): the last list stays. */
    const val PERMITTED_LISTS = "permitted_lists"
    const val PROXIES = "proxies"
    const val CA_CERTS = "ca_certs"
    const val NOTIFICATIONS = "notifications"
    const val DETECTIONS = "detections"
    const val FINISH = "finish"
    val ALL = setOf(CREDENTIAL, SIGNER_CHECK, APPLY, PERMITTED_LISTS, PROXIES, CA_CERTS, NOTIFICATIONS, DETECTIONS, FINISH)
}

/** Status capability: the launcher reports `relock` and takes `end_windows`/`allow_blocked`. */
const val WINDOW_RELOCK_CAPABILITY = "window_relock_v1"

/** `end_windows`'s answer: "ended: pause, install mode", or "nothing was open". */
fun endWindowsResult(ended: Set<WindowKind>, installMode: Boolean): String {
    val names = WindowKind.entries.filter { it in ended }.map { it.wire } + listOfNotNull("install mode".takeIf { installMode })
    return if (names.isEmpty()) "nothing was open" else "ended: ${names.joinToString(", ")}"
}

/** At most this many package names per list in the report. */
const val RELOCK_LIST_MAX = 8

/**
 * The marker (CE prefs `window_relock`): a window opened and hasn't been relocked yet. [gen] goes
 * up at every opening, so a relock that started before a new window opened leaves its marker
 * (QA #2). [windows]: the windows the marker covers (they can overlap, QA #11).
 */
data class RelockMarker(val pending: Boolean = false, val gen: Int = 0, val windows: Set<String> = emptySet())

/** [WindowRelock.opened]: before anything is lifted. */
fun markerOpened(marker: RelockMarker, kind: WindowKind): RelockMarker =
    RelockMarker(pending = true, gen = marker.gen + 1, windows = marker.windows + kind.wire)

/** The start snapshot (credential, packages, disabled apps) is the first window's: a second
 * window opened while one is pending keeps it (QA #11). */
fun takesStartSnapshot(marker: RelockMarker): Boolean = !marker.pending

/** A relock is due once a window opened and none is open any more. */
fun relockDue(marker: RelockMarker, overrideActive: Boolean, pauseActive: Boolean): Boolean =
    marker.pending && !overrideActive && !pauseActive

/**
 * The marker after a relock: cleared only when no window opened meanwhile ([genAtStart]) and
 * `apply()` didn't throw - otherwise the next trigger runs everything again (QA #2, #11).
 */
fun markerAfterRelock(marker: RelockMarker, genAtStart: Int, applyFailed: Boolean): RelockMarker =
    if (!applyFailed && marker.gen == genAtStart) RelockMarker(pending = false, gen = marker.gen) else marker

/**
 * A window an older build opened (no marker) is taken over at the next check, without a start
 * snapshot: nothing is compared, no credential is removed, but it still relocks.
 */
fun adoptedMarker(marker: RelockMarker, open: Set<WindowKind>): RelockMarker? =
    if (marker.pending || open.isEmpty()) null else RelockMarker(pending = true, gen = marker.gen + 1, windows = open.map { it.wire }.toSet())

/**
 * The end of a window for the boundary alarm: the earlier of its wall-clock end and now plus the
 * elapsed time left (a clock set back during the window can't push the alarm out). `null` when it
 * is already over.
 */
fun windowEndMs(start: WindowStart, durationMs: Long, nowWallMs: Long, nowElapsedMs: Long, nowBootCount: Int): Long? {
    if (!timedWindowActive(start, nowWallMs, nowElapsedMs, nowBootCount, durationMs)) return null
    val elapsedLeft = durationMs - (nowElapsedMs - start.elapsedStartMs)
    return minOf(start.untilWallMs, nowWallMs + elapsedLeft)
}

// ---- step 1: an Android screen lock set during the window -------------------------------------

enum class CredentialAction {
    /** No credential now, or the phone had one before the first window. */
    NONE,

    /** Set during the window, and our reset token works: `resetPasswordWithToken(null)`. */
    REMOVE,

    /** Set during the window, no usable token: reported (`credential_left`). */
    LEFT,
}

/** [secureAtStart] `null` = unknown (an adopted window): never removed. */
fun credentialAction(secureAtStart: Boolean?, secureNow: Boolean, tokenUsable: Boolean): CredentialAction = when {
    !secureNow || secureAtStart != false -> CredentialAction.NONE
    tokenUsable -> CredentialAction.REMOVE
    else -> CredentialAction.LEFT
}

/** The reset token is (re)set only while it isn't active and the phone has no credential - that
 * activates it at once (QA #9). */
fun resetTokenNeeded(tokenActive: Boolean, tokenStored: Boolean, secureNow: Boolean): Boolean =
    !secureNow && !(tokenActive && tokenStored)

// ---- step 2: apps installed, updated or re-signed in the window (§1.6) ------------------------

/** One non-system package at the start of the first window. */
@Serializable
data class PackageState(val signers: Set<String>, val updatedAt: Long)

/** One non-system package now. [installer]: `getInstallSourceInfo().installingPackageName`. */
data class InstalledPackage(val name: String, val signers: Set<String>, val updatedAt: Long, val installer: String?)

/** What the signer check found: [blocked] is the whole `relock_blocked` set after it (package ->
 * the expected signers, `null` = none known); [newlyBlocked] this run's; [reported] the report's
 * `blocked` (newly blocked, plus apps new since the start that aren't allowlisted). */
data class SignerCheck(val blocked: Map<String, Set<String>?>, val newlyBlocked: Set<String>, val reported: Set<String>)

enum class RelockBlock {
    /** Nothing to do. */
    NONE,

    /** Allowlisted, but re-signed or new from an installer that isn't Play or us: hidden. */
    BLOCK,

    /** Not allowlisted and new since the start: `apply()` hides it anyway, reported only. */
    REPORT,
}

/**
 * The rule for one package not blocked yet (§1.6). [atStart] `null` = new since the start
 * snapshot. [expected]: M10's locked signer once it lands, else the snapshot's. [trustedInstallers]:
 * the Play Store and our own package.
 */
fun relockBlockDecision(
    pkg: InstalledPackage,
    allowlisted: Boolean,
    atStart: PackageState?,
    expected: Set<String>?,
    trustedInstallers: Set<String>,
): RelockBlock {
    val new = atStart == null
    if (!allowlisted) return if (new) RelockBlock.REPORT else RelockBlock.NONE
    val changed = new || atStart?.signers != pkg.signers || atStart.updatedAt != pkg.updatedAt
    if (!changed) return RelockBlock.NONE
    return when {
        // A known signer (M10, or the snapshot's): it must match.
        expected != null -> if (sameSigner(expected, pkg.signers)) RelockBlock.NONE else RelockBlock.BLOCK
        // New, no signer known: only Play or our own installs pass.
        pkg.installer in trustedInstallers -> RelockBlock.NONE
        else -> RelockBlock.BLOCK
    }
}

/**
 * A block stays until the package is uninstalled, its signer is the expected one again (the real
 * app back from Play), or - with no signer known - it comes from Play or us; `allow_blocked` clears
 * them all ([WindowRelock.allowBlocked]).
 */
fun blockStays(pkg: InstalledPackage, expected: Set<String>?, trustedInstallers: Set<String>): Boolean =
    if (expected != null) !sameSigner(expected, pkg.signers) else pkg.installer !in trustedInstallers

/**
 * The installed signers ([now]: SHA-256 of each certificate - the signing history with key
 * rotation, else every signer) share a certificate with [expected]: a rotated key's history keeps
 * the old certificate, another key shares none (like the self-update's `signerMatches`). An
 * empty [expected] matches nothing.
 */
fun sameSigner(expected: Set<String>, now: Set<String>): Boolean = now.any { it in expected }

/**
 * Step 2 for every non-system package [installed] now. [snapshot] `null` = none was taken (an
 * adopted window): only the existing blocks are re-checked. [lockedSigners]: M10's per-package
 * signer (none yet); it wins over the snapshot's and a block's. [allowlist] `null` = apps aren't
 * managed: nothing is hidden there, so nothing is blocked or reported.
 */
fun signerCheck(
    installed: List<InstalledPackage>,
    snapshot: Map<String, PackageState>?,
    allowlist: Collection<String>?,
    blockedBefore: Map<String, Set<String>?>,
    trustedInstallers: Set<String>,
    lockedSigners: Map<String, Set<String>> = emptyMap(),
): SignerCheck {
    if (allowlist == null) return SignerCheck(blockedBefore, emptySet(), emptySet())
    val allowed = allowlist.toSet()
    val blocked = mutableMapOf<String, Set<String>?>()
    val newly = mutableSetOf<String>()
    val reported = mutableSetOf<String>()
    for (pkg in installed) {
        val locked = lockedSigners[pkg.name]?.takeIf { it.isNotEmpty() }
        if (pkg.name in blockedBefore) {
            // The snapshot holds the bad signer now: the block's own expectation decides.
            val expected = locked ?: blockedBefore[pkg.name]?.takeIf { it.isNotEmpty() }
            if (blockStays(pkg, expected, trustedInstallers)) blocked[pkg.name] = expected
            continue
        }
        if (snapshot == null) continue
        val atStart = snapshot[pkg.name]
        val expected = locked ?: atStart?.signers?.takeIf { it.isNotEmpty() }
        when (relockBlockDecision(pkg, pkg.name in allowed, atStart, expected, trustedInstallers)) {
            RelockBlock.NONE -> Unit
            RelockBlock.REPORT -> reported += pkg.name
            RelockBlock.BLOCK -> {
                blocked[pkg.name] = expected
                newly += pkg.name
                reported += pkg.name
            }
        }
    }
    // Uninstalled packages leave the set: only the installed ones were carried over.
    return SignerCheck(blocked, newly, reported)
}

/** `relock_blocked` treated as not allowlisted (§1.6): hidden, never pinned, not in the keyboard
 * or accessibility lists. `null` stays unmanaged. */
fun effectiveAllowlist(allowlist: List<String>?, relockBlocked: Set<String>): List<String>? =
    allowlist?.filter { it !in relockBlocked }

// ---- the report (§1.7) --------------------------------------------------------------------------

/** Valid package names only, at most [RELOCK_LIST_MAX], sorted - the server checks the same. */
fun reportPackages(names: Collection<String>): List<String> =
    names.filter(::isValidPackageName).distinct().sorted().take(RELOCK_LIST_MAX)

/** A new relock's report, before any step ran. */
fun startedReport(atMs: Long, trigger: RelockTrigger, windows: Set<String>): RelockReport = RelockReport(
    atMs = atMs,
    trigger = trigger.wire,
    windows = windows.filter { w -> WindowKind.entries.any { it.wire == w } }.sorted(),
    proxiesCleared = 0,
    proxyLeft = false,
    caCertsRemoved = 0,
    credentialRemoved = false,
    credentialLeft = false,
    blocked = emptyList(),
    accessibilityEnabled = emptyList(),
    adminsEnabled = emptyList(),
    appsDisabled = emptyList(),
    notificationListeners = emptyList(),
    ownNotificationsOff = false,
    failures = emptyList(),
)

/** A step that ran: its failure replaces the step's earlier one (a re-run that succeeds clears it). */
fun withStep(report: RelockReport, step: String, failed: Boolean): RelockReport {
    val failures = report.failures.filter { it != step } + listOfNotNull(step.takeIf { failed })
    return report.copy(failures = failures.filter { it in RelockFailure.ALL }.distinct().sorted())
}

/** What the resets did - added to what an earlier run of the same relock did (QA #11: a re-run
 * after a process death adds to "1 certificate removed", never overwrites it with 0). */
fun withResets(report: RelockReport, proxiesCleared: Int, caCertsRemoved: Int, credentialRemoved: Boolean, blocked: Collection<String>): RelockReport =
    report.copy(
        proxiesCleared = report.proxiesCleared + proxiesCleared.coerceAtLeast(0),
        caCertsRemoved = report.caCertsRemoved + caCertsRemoved.coerceAtLeast(0),
        credentialRemoved = report.credentialRemoved || credentialRemoved,
        blocked = reportPackages(report.blocked + blocked),
    )

/** What is still on after the resets - the latest run's view. */
data class RelockDetections(
    val proxyLeft: Boolean,
    val credentialLeft: Boolean,
    val accessibilityEnabled: Collection<String>,
    val adminsEnabled: Collection<String>,
    val appsDisabled: Collection<String>,
    val notificationListeners: Collection<String>,
    val ownNotificationsOff: Boolean,
)

fun withDetections(report: RelockReport, found: RelockDetections): RelockReport = report.copy(
    proxyLeft = found.proxyLeft,
    credentialLeft = found.credentialLeft,
    accessibilityEnabled = reportPackages(found.accessibilityEnabled),
    adminsEnabled = reportPackages(found.adminsEnabled),
    appsDisabled = reportPackages(found.appsDisabled),
    notificationListeners = reportPackages(found.notificationListeners),
    ownNotificationsOff = found.ownNotificationsOff,
)

/** System apps disabled since the first window's start (QA #5): none of it can be undone. */
fun newlyDisabled(disabledAtStart: Set<String>?, disabledNow: Set<String>): Set<String> =
    if (disabledAtStart == null) emptySet() else disabledNow - disabledAtStart

/**
 * In the sync (§1.2): its own `apply()` ran right before, with no window open - a second one is
 * needed only when step 1 or 2 changed what `apply()` decides (a credential gone: the kid's lock
 * comes back; a new block: hidden).
 */
fun applyNeeded(fromSync: Boolean, credentialRemoved: Boolean, newlyBlocked: Boolean): Boolean =
    !fromSync || credentialRemoved || newlyBlocked

/** The keyboards never hidden or suspended (§1.5): enabled ∩ (system ∪ allowlist) while apps are
 * managed, so a keyboard sideloaded and enabled in a window goes at the relock; every enabled one
 * while unmanaged. The parent PIN types on our own keypad, so no fallback is needed (QA S5). */
fun keptInputMethods(enabled: Set<String>, system: Set<String>, allowlist: Collection<String>?): Set<String> =
    if (allowlist == null) enabled else enabled.filterTo(mutableSetOf()) { it in system || it in allowlist }

/** A permitted list (`DevicePolicyManager.getPermitted*`) differs from the wanted one - order
 * doesn't count, `null` = all. */
fun permittedListChanged(current: List<String>?, wanted: List<String>?): Boolean =
    if (current == null || wanted == null) current != wanted else current.toSet() != wanted.toSet()
