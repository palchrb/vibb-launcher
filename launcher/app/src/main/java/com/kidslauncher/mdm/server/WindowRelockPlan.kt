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

/** Status capability: the launcher reports `relock` and `foreign_access`, and takes `end_windows`/`allow_blocked`/`start_pause`. */
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
 * neither `apply()` nor the signer check threw ([keepPending]) - otherwise the next trigger runs
 * everything again (QA #2, #11; fix round Low 2).
 */
fun markerAfterRelock(marker: RelockMarker, genAtStart: Int, keepPending: Boolean): RelockMarker =
    if (!keepPending && marker.gen == genAtStart) RelockMarker(pending = false, gen = marker.gen) else marker

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
 * `blocked` (this run's blocks); [signers] each reported app's signing key (its first certificate's
 * SHA-256), for the PWA's "Allow these apps". A new app from Play that isn't allowlisted isn't
 * blocked or reported here: `apply()` hides it until ticked, and the Apps card and the security log
 * (`app_installed`) show it. */
data class SignerCheck(
    val blocked: Map<String, Set<String>?>,
    val newlyBlocked: Set<String>,
    val reported: Set<String>,
    val signers: Map<String, String> = emptyMap(),
    /** Why each new block ([BlockReason]), kept with it for the status report's `relock_blocks`. */
    val reasons: Map<String, String> = emptyMap(),
)

/** Why a package is in `relock_blocked` - the status report's `relock_blocks[].reason`. */
object BlockReason {
    /** Installed or replaced from an installer that isn't Play or us, with no signer known. */
    const val OUTSIDE_PLAY = "outside_play"

    /** Signed with another key than it had at the window's start. */
    const val OTHER_KEY = "other_key"

    /** Signed with another key than the catalog's pin (M10): not the catalog's app - it stays
     * blocked through "Allow these apps". */
    const val CATALOG_KEY = "catalog_key"

    /** A block from before the reasons were kept. */
    const val UNKNOWN = "unknown"
}

/** Where a block was set - `relock_blocks[].found`: the relock after a window, or the install
 * check outside windows. */
object BlockFound {
    const val RELOCK = "relock"
    const val INSTALL = "install"
}

/** [BlockReason] for a package being blocked: the catalog's pin first, then the window start's
 * signer, else it came from outside Play. */
fun blockReason(pkg: InstalledPackage, pin: Set<String>?, expected: Set<String>?): String = when {
    pin != null && !sameSigner(pin, pkg.signers) -> BlockReason.CATALOG_KEY
    expected != null && !sameSigner(expected, pkg.signers) -> BlockReason.OTHER_KEY
    else -> BlockReason.OUTSIDE_PLAY
}

/** What is kept with a block for the report: why, where and when it was set. */
@kotlinx.serialization.Serializable
data class BlockNote(val reason: String, val found: String, val atMs: Long)

/** At most this many blocks in the status report's `relock_blocks` (the server keeps the same). */
const val RELOCK_BLOCKS_MAX = 16

/**
 * The status report's `relock_blocks`: every current block - from a relock or the install check -
 * with its app's signing key now ([installedSigners]; a blocked package that isn't installed any
 * more is left out), why and when (fix of 2026-10-12: an install-time block never reached the
 * PWA, so the parent couldn't let it out). Sorted, valid package names, at most [RELOCK_BLOCKS_MAX].
 */
fun currentBlocks(
    blocked: Map<String, Set<String>?>,
    notes: Map<String, BlockNote>,
    installedSigners: Map<String, Set<String>>,
): List<com.kidslauncher.mdm.server.dto.RelockBlockReport> = blocked.keys
    .filter { it in installedSigners && reportPackages(listOf(it)).isNotEmpty() }
    .sorted()
    .take(RELOCK_BLOCKS_MAX)
    .map { name ->
        val note = notes[name]
        com.kidslauncher.mdm.server.dto.RelockBlockReport(
            packageName = name,
            signer = shownSigner(installedSigners.getValue(name))?.takeIf(::isSignerHex),
            reason = note?.reason ?: BlockReason.UNKNOWN,
            found = note?.found ?: BlockFound.RELOCK,
            atMs = note?.atMs,
        )
    }

enum class RelockBlock {
    /** Nothing to do (a new app from Play or us that isn't allowlisted is hidden by `apply()`). */
    NONE,

    /** Re-signed (another key than the catalog's pin or the window's start), or new from an
     * installer that isn't Play or us: hidden, whatever the allowlist says (QA Medium 2). */
    BLOCK,
}

/**
 * The rule for one package not blocked yet (§1.6, fix round QA Medium 2). [atStart] `null` = new
 * since the start snapshot. [expected]: the catalog's pinned signer (M10) when there is one, else
 * the snapshot's. [trustedInstallers]: the Play Store and our own package. The allowlist doesn't
 * matter: a blocked app stays hidden when its name is ticked - only "Allow these apps" lets it out.
 */
fun relockBlockDecision(
    pkg: InstalledPackage,
    atStart: PackageState?,
    expected: Set<String>?,
    trustedInstallers: Set<String>,
): RelockBlock {
    val new = atStart == null
    val changed = new || atStart?.signers != pkg.signers || atStart.updatedAt != pkg.updatedAt
    if (!changed) return RelockBlock.NONE
    return when {
        // A known signer (the catalog's pin, or the snapshot's): it must match.
        expected != null && !sameSigner(expected, pkg.signers) -> RelockBlock.BLOCK
        // No signer known: only Play or our own installs pass.
        expected == null && pkg.installer !in trustedInstallers -> RelockBlock.BLOCK
        else -> RelockBlock.NONE
    }
}

/**
 * A block stays until the package is uninstalled, its signer is the expected one again (the real
 * app back from Play), or - with no signer known - it comes from Play or us; `allow_blocked` clears
 * them, except an app signed with another key than the catalog's pin ([allowBlocked]).
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

/** The signing key the PWA shows for an app: its first certificate's SHA-256 (sorted, so stable). */
fun shownSigner(signers: Set<String>): String? = signers.minOrNull()

/**
 * Step 2 for every non-system package [installed] now. [lockedSigners]: the catalog's pinned
 * signers (M10, from the server's app list); they win over the snapshot's and a block's.
 * [allowlist] `null` = apps aren't managed: nothing is hidden there, so nothing is blocked or
 * reported. Without a [snapshot] (an adopted window, or it couldn't be taken) every package
 * installed or updated since [windowStartMs] from an installer that isn't Play or us is blocked,
 * and nothing else is judged (QA Low 2).
 */
fun signerCheck(
    installed: List<InstalledPackage>,
    snapshot: Map<String, PackageState>?,
    allowlist: Collection<String>?,
    blockedBefore: Map<String, Set<String>?>,
    trustedInstallers: Set<String>,
    lockedSigners: Map<String, Set<String>> = emptyMap(),
    windowStartMs: Long? = null,
): SignerCheck {
    if (allowlist == null) return SignerCheck(blockedBefore, emptySet(), emptySet())
    val blocked = mutableMapOf<String, Set<String>?>()
    val newly = mutableSetOf<String>()
    val reported = mutableSetOf<String>()
    val signers = mutableMapOf<String, String>()
    val reasons = mutableMapOf<String, String>()
    fun block(pkg: InstalledPackage, expected: Set<String>?, pin: Set<String>?) {
        blocked[pkg.name] = expected
        newly += pkg.name
        reported += pkg.name
        shownSigner(pkg.signers)?.let { signers[pkg.name] = it }
        reasons[pkg.name] = blockReason(pkg, pin, expected)
    }
    for (pkg in installed) {
        val locked = lockedSigners[pkg.name]?.takeIf { it.isNotEmpty() }
        if (pkg.name in blockedBefore) {
            // The snapshot holds the bad signer now: the block's own expectation decides.
            val expected = locked ?: blockedBefore[pkg.name]?.takeIf { it.isNotEmpty() }
            if (blockStays(pkg, expected, trustedInstallers)) blocked[pkg.name] = expected
            continue
        }
        if (snapshot == null) {
            val since = windowStartMs ?: continue
            if (pkg.updatedAt >= since && (if (locked != null) !sameSigner(locked, pkg.signers) else pkg.installer !in trustedInstallers)) {
                block(pkg, locked, locked)
            }
            continue
        }
        val atStart = snapshot[pkg.name]
        val expected = locked ?: atStart?.signers?.takeIf { it.isNotEmpty() }
        if (relockBlockDecision(pkg, atStart, expected, trustedInstallers) == RelockBlock.BLOCK) block(pkg, expected, locked)
    }
    // Uninstalled packages leave the set: only the installed ones were carried over.
    return SignerCheck(blocked, newly, reported, signers, reasons)
}

/**
 * The PWA's `allow_blocked` (fix round QA Medium 2): every block goes, except an app whose
 * signing key differs from the catalog's pin - that one is not the catalog's app, whatever its name.
 */
fun allowBlocked(
    blocked: Map<String, Set<String>?>,
    installed: Map<String, Set<String>>,
    lockedSigners: Map<String, Set<String>>,
): Map<String, Set<String>?> = blocked.filter { (name, expected) ->
    val pin = lockedSigners[name]?.takeIf { it.isNotEmpty() } ?: return@filter false
    val now = installed[name] ?: return@filter false
    !sameSigner(pin, now)
}

/**
 * A package installed or replaced while no window is open (fix round QA Low 3): a block whose
 * package is back to its expected signer (or, with none known, came from Play or us) goes; an app
 * installed in the last [recentMs] from an installer that isn't Play or us - or with another key
 * than the catalog's pin - is blocked. An old app shown again (unhiding sends a package-added too)
 * is left alone, and so is the very install the parent let out with "Allow these apps" ([allowed]:
 * package -> its update time then). Returns the new `relock_blocked`, or `null` for no change.
 */
fun blockAfterInstall(
    pkg: InstalledPackage,
    blocked: Map<String, Set<String>?>,
    lockedSigners: Map<String, Set<String>>,
    trustedInstallers: Set<String>,
    nowMs: Long,
    allowed: Map<String, Long> = emptyMap(),
    recentMs: Long = 10 * 60_000L,
): Map<String, Set<String>?>? {
    val locked = lockedSigners[pkg.name]?.takeIf { it.isNotEmpty() }
    if (pkg.name in blocked) {
        val expected = locked ?: blocked[pkg.name]?.takeIf { it.isNotEmpty() }
        return if (blockStays(pkg, expected, trustedInstallers)) null else blocked - pkg.name
    }
    if (nowMs - pkg.updatedAt !in 0..recentMs || allowed[pkg.name] == pkg.updatedAt) return null
    val bad = if (locked != null) !sameSigner(locked, pkg.signers) else pkg.installer !in trustedInstallers
    return if (bad) blocked + (pkg.name to locked) else null
}

/** An adopted window's start is unknown: as early as the longest window could have started. */
fun adoptedStartMs(nowMs: Long): Long = nowMs - maxOf(OVERRIDE_DURATION_MS, RESTRICTIONS_PAUSE_DURATION_MS)

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
    blockedSigners = emptyMap(),
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
fun withResets(
    report: RelockReport,
    proxiesCleared: Int,
    caCertsRemoved: Int,
    credentialRemoved: Boolean,
    blocked: Collection<String>,
    signers: Map<String, String> = emptyMap(),
): RelockReport {
    val names = reportPackages(report.blocked + blocked)
    return report.copy(
        proxiesCleared = report.proxiesCleared + proxiesCleared.coerceAtLeast(0),
        caCertsRemoved = report.caCertsRemoved + caCertsRemoved.coerceAtLeast(0),
        credentialRemoved = report.credentialRemoved || credentialRemoved,
        blocked = names,
        blockedSigners = (report.blockedSigners + signers.filterValues(::isSignerHex)).filterKeys { it in names }.toSortedMap(),
    )
}

/** A signing key as the server keeps it: SHA-256, 64 lower-case hex characters. */
fun isSignerHex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

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

/** The keyboards never hidden or suspended (§1.5): enabled ∩ (system ∪ allowlist) while apps are
 * managed, so a keyboard sideloaded and enabled in a window goes at the relock; every enabled one
 * while unmanaged. The parent PIN types on our own keypad, so no fallback is needed (QA S5). */
fun keptInputMethods(enabled: Set<String>, system: Set<String>, allowlist: Collection<String>?): Set<String> =
    if (allowlist == null) enabled else enabled.filterTo(mutableSetOf()) { it in system || it in allowlist }

/** A permitted list (`DevicePolicyManager.getPermitted*`) differs from the wanted one - order
 * doesn't count, `null` = all. */
fun permittedListChanged(current: List<String>?, wanted: List<String>?): Boolean =
    if (current == null || wanted == null) current != wanted else current.toSet() != wanted.toSet()

/** What one reset did: [done] removed or cleared, [left] still there afterwards (a failure). */
data class ResetCount(val done: Int, val left: Int)

/**
 * The user CA certificates the relock removes (fix round QA Low 4): those added since the first
 * window's start, by their SHA-256. Without a start snapshot (an adopted window, or it couldn't be
 * read) every one goes, as before - on calls-only phones too: the rule is the window's, not the
 * allowlist's.
 */
fun caCertsToRemove(now: Collection<String>, atStart: Set<String>?): Set<String> =
    if (atStart == null) now.toSet() else now.toSet() - atStart

/** A saved Wi-Fi network's proxy stays when it is the one the network had at the first window's
 * start (by SSID); one set or changed in the window is cleared, and every one without a snapshot. */
fun proxyStays(ssid: String, proxy: String, atStart: Map<String, String>?): Boolean = atStart?.get(ssid) == proxy
