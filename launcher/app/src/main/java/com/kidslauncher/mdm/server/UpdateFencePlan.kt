package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.play.PLAY_CORE

/*
 * The update fence (handy step 11, design 11-kiosk-escapes.md §2 with qa-11-design.md #1-#7 and
 * the binding decisions): while our own package is being replaced, Android kills us, lock task
 * ends and Home resolves to the stock launcher. Right before the self-update's commit every other
 * HOME-capable package is suspended and the status bar is disabled (through LockTaskChrome, never
 * here). Pure, no Android imports - UpdateFenceTest. UpdateFence.kt is the glue.
 *
 * The fence is a contract between two builds: the old build records it, the new build (or the old
 * one after a failed install, or any later build) releases it. RELEASE CODE IS NEVER REMOVED:
 * the prefs file name, the keys and the v1 parse below are pinned by UpdateFenceTest, and an
 * unknown `v` still releases the recorded packages (`planned` and `suspended` stay string sets in
 * every future version).
 */

/** A package with an enabled MAIN + HOME activity on this phone. */
data class HomeCandidate(
    val packageName: String,
    /** `ApplicationInfo.FLAG_SYSTEM`. */
    val system: Boolean,
    /** `ApplicationInfo.FLAG_PERSISTENT` - never suspended (the boot-loop class). */
    val persistent: Boolean,
    /** The HOME filter's priority; Settings' FallbackHome declares -1000. */
    val priority: Int,
)

/**
 * Never fenced, whatever this phone resolves (qa-11-design.md #6): the platform, SystemUI, the
 * phone process, Telecom, Settings (FallbackHome on AOSP - the resolved FallbackHome package comes
 * in through `protected` too) and Play core (FCM, the verifier).
 */
val FENCE_NEVER: Set<String> = setOf(
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.settings",
) + PLAY_CORE

/** Our package and its debug/release sibling. */
fun ownPackageFamily(ownPackage: String): Set<String> {
    val base = ownPackage.removeSuffix(".debug")
    return setOf(base, "$base.debug")
}

/** Why a HOME candidate was left out of the fence (logged, tested). */
enum class FenceSkip { OWN, NEVER, PERSISTENT, FALLBACK_HOME, PROTECTED, CONTROLLABLE, ALREADY_SUSPENDED }

data class FencePlan(
    /** Packages to suspend for the update window - none of them suspended before. */
    val suspend: Set<String>,
    val skipped: Map<String, FenceSkip>,
)

/**
 * Which HOME-capable packages to suspend while our package is replaced:
 * - never ours (also `.debug`), [FENCE_NEVER], persistent apps, a HOME with a negative priority
 *   (FallbackHome - it must still show "the real Home isn't ready" during a boot);
 * - never a [protected] package - the glue resolves it on the phone: the FallbackHome and Settings
 *   packages, SystemUI, the system and default dialer, Telecom, the emergency dialer, the IMEs,
 *   the kiosk-block and PIN-lock helpers, every lock-task package (removing one clears its locked
 *   task) and Play services/GSF;
 * - never a [controllable] package: enforcement owns those (a third-party launcher is allowed or
 *   suspended+hidden by `apply()`, which would otherwise unsuspend it mid-window or have it
 *   released into a bedtime lock - qa-11-design.md #5);
 * - never an [alreadySuspended] one: a release only ever unsuspends what the fence suspended.
 * The system's recents provider ([recentsPackage], design 16d guard 2) is fenced like any other
 * HOME although it is pinned in the kiosk (a lock-task package and kiosk-block helper, so in
 * [protected]) and although enforcement never restricts it (with a launcher icon it is in
 * [controllable] without being enforcement's): on Pixel it is the stock launcher - the fallback
 * Home the fence exists to stop. The update runs at night with the screen off, so a minute without
 * Recents doesn't matter. Ours, never, persistent, FallbackHome and already suspended still skip it.
 */
fun fencePlan(
    homeCandidates: Collection<HomeCandidate>,
    ownPackage: String,
    protected: Set<String>,
    controllable: Set<String>,
    alreadySuspended: Set<String>,
    recentsPackage: String? = null,
): FencePlan {
    val own = ownPackageFamily(ownPackage)
    val suspend = mutableSetOf<String>()
    val skipped = mutableMapOf<String, FenceSkip>()
    for (candidate in homeCandidates) {
        val pkg = candidate.packageName
        val skip = when {
            pkg in own -> FenceSkip.OWN
            pkg in FENCE_NEVER -> FenceSkip.NEVER
            candidate.persistent -> FenceSkip.PERSISTENT
            candidate.priority < 0 -> FenceSkip.FALLBACK_HOME
            pkg in protected && pkg != recentsPackage -> FenceSkip.PROTECTED
            pkg in controllable && pkg != recentsPackage -> FenceSkip.CONTROLLABLE
            pkg in alreadySuspended -> FenceSkip.ALREADY_SUSPENDED
            else -> null
        }
        if (skip != null) {
            // A package with several HOME activities: one excluded activity excludes the package.
            skipped[pkg] = skip
            suspend.remove(pkg)
        } else if (pkg !in skipped) {
            suspend += pkg
        }
    }
    return FencePlan(suspend, skipped)
}

// ---- the record (write-ahead, versioned) -------------------------------------------------------

/** Own CE prefs file - not the default prefs, so `resetPreferences` and a rename elsewhere can't touch it. */
const val UPDATE_FENCE_PREFS = "update_fence"

/** The record version this build writes. */
const val UPDATE_FENCE_V1 = 1

/** The keys of [UPDATE_FENCE_PREFS]; pinned by UpdateFenceTest - never rename one. */
object FenceKeys {
    const val VERSION = "v"
    const val STAGE = "stage"
    const val PLANNED = "planned"
    const val SUSPENDED = "suspended"
    const val SESSION_ID = "session_id"
    const val OWN_LAST_UPDATE_MS = "own_last_update_ms"
    const val BOOT_COUNT = "boot_count"
    const val STARTED_WALL_MS = "started_wall_ms"
    const val STARTED_ELAPSED_MS = "started_elapsed_ms"
    const val RELEASE_TAG = "release_tag"
}

enum class FenceStage(val wire: String) {
    /** Written before anything is suspended (write-ahead). */
    PLANNED("planned"),
    /** The platform's answer is recorded: [FenceRecord.suspended] is what it really suspended. */
    ACTIVE("active"),
}

data class FenceRecord(
    /** [UPDATE_FENCE_V1]; anything else (or unreadable) is released on sight. */
    val version: Int,
    val stage: FenceStage,
    /** What the fence meant to suspend (none of it suspended before). */
    val planned: Set<String>,
    /** What the platform actually suspended (the request minus the packages it refused). */
    val suspended: Set<String>,
    /** The PackageInstaller session the fence guards; `null` = not created yet. */
    val sessionId: Int?,
    /** Our `PackageInfo.lastUpdateTime` when fencing: a different one means we were replaced. */
    val ownLastUpdateMs: Long,
    /** `Settings.Global.BOOT_COUNT` when fencing: a different one means a reboot. */
    val bootCount: Int,
    val startedWallMs: Long,
    val startedElapsedMs: Long,
    val releaseTag: String,
) {
    /** What a release unsuspends: everything planned or recorded - [planned] covers a death between
     * the suspend call and the second write. Readable whatever the version. */
    val toRelease: Set<String> get() = planned + suspended

    /** The packages the platform refused to suspend (reported). */
    val unsuspendable: Set<String> get() = if (stage == FenceStage.ACTIVE) planned - suspended else emptySet()
}

/** The prefs values for [record] (Int, Long, String, Set<String> - SharedPreferences types). */
fun encodeFenceRecord(record: FenceRecord): Map<String, Any> = buildMap {
    put(FenceKeys.VERSION, record.version)
    put(FenceKeys.STAGE, record.stage.wire)
    put(FenceKeys.PLANNED, record.planned)
    put(FenceKeys.SUSPENDED, record.suspended)
    put(FenceKeys.SESSION_ID, record.sessionId ?: -1)
    put(FenceKeys.OWN_LAST_UPDATE_MS, record.ownLastUpdateMs)
    put(FenceKeys.BOOT_COUNT, record.bootCount)
    put(FenceKeys.STARTED_WALL_MS, record.startedWallMs)
    put(FenceKeys.STARTED_ELAPSED_MS, record.startedElapsedMs)
    put(FenceKeys.RELEASE_TAG, record.releaseTag)
}

/**
 * The record in [values] (`SharedPreferences.getAll()`), `null` when the file is empty. Never
 * throws: a record this build can't read fully - another version, a missing or mistyped key -
 * comes back with version -1 (or its own version) and whatever package sets it has, so the check
 * releases it.
 */
fun decodeFenceRecord(values: Map<String, *>): FenceRecord? {
    if (values.isEmpty()) return null
    fun strings(key: String): Set<String> = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
    val planned = strings(FenceKeys.PLANNED)
    val suspended = strings(FenceKeys.SUSPENDED)
    val version = values[FenceKeys.VERSION] as? Int ?: -1
    val unreadable = FenceRecord(
        version = if (version == UPDATE_FENCE_V1) -1 else version,
        stage = FenceStage.PLANNED,
        planned = planned,
        suspended = suspended,
        sessionId = null,
        ownLastUpdateMs = -1,
        bootCount = -1,
        startedWallMs = 0,
        startedElapsedMs = 0,
        releaseTag = "",
    )
    if (version != UPDATE_FENCE_V1) return unreadable
    val stage = FenceStage.entries.firstOrNull { it.wire == values[FenceKeys.STAGE] } ?: return unreadable
    val session = values[FenceKeys.SESSION_ID] as? Int ?: return unreadable
    val ownLastUpdate = values[FenceKeys.OWN_LAST_UPDATE_MS] as? Long ?: return unreadable
    val boot = values[FenceKeys.BOOT_COUNT] as? Int ?: return unreadable
    val wall = values[FenceKeys.STARTED_WALL_MS] as? Long ?: return unreadable
    val elapsed = values[FenceKeys.STARTED_ELAPSED_MS] as? Long ?: return unreadable
    return FenceRecord(
        version = UPDATE_FENCE_V1,
        stage = stage,
        planned = planned,
        suspended = suspended,
        sessionId = session.takeIf { it >= 0 },
        ownLastUpdateMs = ownLastUpdate,
        bootCount = boot,
        startedWallMs = wall,
        startedElapsedMs = elapsed,
        releaseTag = values[FenceKeys.RELEASE_TAG] as? String ?: "",
    )
}

// ---- fencing and releasing, in order -----------------------------------------------------------

/** What fencing and releasing need from the platform (the glue: CE prefs + DevicePolicyManager). */
interface FencePlatform {
    /** Durable, synchronous write of the whole record (`commit()`); false = not written. */
    fun write(record: FenceRecord): Boolean

    /** Removes the record (`commit()`). */
    fun clear(): Boolean

    /** `setPackagesSuspended(true)`: the packages the platform refused. */
    fun suspend(packages: Set<String>): Set<String>

    /** `setPackagesSuspended(false)`: the packages the platform refused. */
    fun unsuspend(packages: Set<String>): Set<String>
}

/**
 * Fences with write-ahead (qa-11-design.md #1): [planned] is committed **before** anything is
 * suspended, so a death in between leaves a record the next check releases; then only what the
 * platform really suspended is recorded. `null` when the write-ahead failed - nothing was
 * suspended then. A throw from the suspend call counts as "all refused".
 */
fun runFence(platform: FencePlatform, planned: FenceRecord): FenceRecord? {
    val first = planned.copy(stage = FenceStage.PLANNED, suspended = emptySet())
    if (!platform.write(first)) return null
    val refused = if (first.planned.isEmpty()) {
        emptySet()
    } else {
        try {
            platform.suspend(first.planned)
        } catch (e: Exception) {
            first.planned
        }
    }
    val active = first.copy(stage = FenceStage.ACTIVE, suspended = first.planned - refused)
    // If this write fails the planned record still covers the release.
    platform.write(active)
    return active
}

data class ReleaseOutcome(
    /** Unsuspended here. */
    val unsuspended: Set<String>,
    /** Controllable now - left to `apply()`, which decides them (qa-11-design.md #5). */
    val leftToApply: Set<String>,
    /** The platform refused to unsuspend them (logged; nothing more we can do). */
    val refused: Set<String>,
)

/**
 * Releases [record]: unsuspends every recorded package that enforcement doesn't own now, **then**
 * clears the record (a death in between only means the next check releases again - unsuspending is
 * idempotent). A throw from the unsuspend call counts as "all refused"; the record is cleared
 * anyway, so a phone that is no longer device owner can't loop on it.
 */
fun runRelease(platform: FencePlatform, record: FenceRecord, controllableNow: Set<String>, recentsPackage: String? = null): ReleaseOutcome {
    // The recents provider is the fence's own even with a launcher icon (enforcement never restricts it).
    val leftToApply = record.toRelease intersect (controllableNow - setOfNotNull(recentsPackage))
    val target = record.toRelease - leftToApply
    val refused = if (target.isEmpty()) {
        emptySet()
    } else {
        try {
            platform.unsuspend(target)
        } catch (e: Exception) {
            target
        }
    }
    platform.clear()
    return ReleaseOutcome(target - refused, leftToApply, refused)
}

// ---- the release check ------------------------------------------------------------------------

/** A fence never lasts longer than this within one boot (backstop for a hung install). */
const val FENCE_MAX_MS = 10 * 60_000L

/** In the new process: released at the latest this long after our package was replaced, even
 * if Home or the lock never came to the front (a crash-looping Home, A5). */
const val FENCE_TAIL_BACKSTOP_MS = 2 * 60_000L

/** The guarded session as PackageInstaller sees it now. */
enum class FenceSession {
    /** The record has no session id yet. */
    NONE,
    /** Exists, not committed. */
    UNCOMMITTED,
    /** Exists and committed: the install is running. */
    COMMITTED,
    /** `getSessionInfo` is null: finished (installed or failed) or abandoned. */
    GONE,
    /** Couldn't be asked. */
    UNKNOWN,
}

/** A PackageInstaller result broadcast for the fence's own session (any other session's result
 * is [NONE] - it only triggers the check). */
enum class FenceInstallResult { NONE, SUCCESS, FAILURE, PENDING_USER_ACTION }

data class FenceCheck(
    /** Our `PackageInfo.lastUpdateTime` now (-1 = unreadable: counts as not replaced). */
    val ownLastUpdateMs: Long,
    val bootCount: Int,
    val nowElapsedMs: Long,
    val nowWallMs: Long,
    val session: FenceSession,
    val installResult: FenceInstallResult = FenceInstallResult.NONE,
    /** This process wrote the record and committed (or is about to commit) the session. */
    val committingHere: Boolean = false,
    /** Home or the lock screen came to the front in this process, or the screen is off. */
    val frontReady: Boolean = false,
    /** Since this process started. */
    val processAgeMs: Long = 0,
    /** Apps or calls are managed (an unmanaged/unenrolled phone keeps no fence). */
    val managed: Boolean = true,
    /** The server's `update_fence` switch. */
    val switchOn: Boolean = true,
)

enum class FenceReleaseReason(val wire: String) {
    UNKNOWN_VERSION("unknown_version"),
    UNMANAGED("unmanaged"),
    SWITCHED_OFF("switched_off"),
    REBOOTED("rebooted"),
    INSTALL_FAILED("install_failed"),
    PENDING_USER_ACTION("pending_user_action"),
    REPLACED("replaced"),
    REPLACED_BACKSTOP("replaced_backstop"),
    SUCCESS_NOT_REPLACED("success_not_replaced"),
    EXPIRED("expired"),
    NO_SESSION("no_session"),
    NOT_COMMITTED("not_committed"),
    SESSION_GONE("session_gone"),
    /** The commit threw after the fence went up (the session is abandoned). */
    COMMIT_FAILED("commit_failed"),
    /** A new fence replaces a leftover one (released first, then fenced anew). */
    SUPERSEDED("superseded"),
    /** No record, but HOME packages only a fence can have suspended were found suspended (a lost or
     * corrupt record, qa-11-code #1) - unsuspended by [orphanFenceTargets]. */
    ORPHAN("orphan"),
}

enum class FenceKeepReason(val wire: String) {
    /** Replaced: waiting for our Home or lock to be in front (or the screen off). */
    AWAITING_FRONT("awaiting_front"),
    /** The install is running (or about to be committed by this process). */
    INSTALLING("installing"),
}

sealed interface FenceVerdict {
    data object NoFence : FenceVerdict
    /** Keep the fence; check again in [recheckInMs] at the latest. */
    data class Keep(val reason: FenceKeepReason, val recheckInMs: Long) : FenceVerdict
    data class Release(val reason: FenceReleaseReason) : FenceVerdict
}

/**
 * The one release rule (qa-11-design.md #1/#3/#7), idempotent - run at every process start, boot,
 * install result, `apply()`, Home/lock in front, screen-off and from the backstop alarm:
 * 1. an unreadable or other-version record, an unmanaged phone or the server switch off: release;
 * 2. another boot (reboot mid-install, kiosk on or off): release;
 * 3. the session's result was a failure or needs user action: release;
 * 4. our package was replaced since the fence: release once Home or the lock is in front or the
 *    screen is off, at the latest [FENCE_TAIL_BACKSTOP_MS] after the replacement;
 * 5. a success that didn't replace us (the APK was another package): release;
 * 6. older than [FENCE_MAX_MS]: release;
 * 7. no session or an uncommitted one in a process that isn't committing it (it died before the
 *    commit): release; the session gone (finished or abandoned): release;
 * 8. otherwise keep - in particular in the committing process while its session is open: the kill
 *    comes seconds after `commit()`, and Home or the lock resuming in the **old** process (a wake
 *    between commit and freeze) must not end the fence early (finding 3).
 */
fun fenceRelease(record: FenceRecord?, now: FenceCheck): FenceVerdict {
    if (record == null) return FenceVerdict.NoFence
    if (record.version != UPDATE_FENCE_V1) return FenceVerdict.Release(FenceReleaseReason.UNKNOWN_VERSION)
    if (!now.managed) return FenceVerdict.Release(FenceReleaseReason.UNMANAGED)
    if (!now.switchOn) return FenceVerdict.Release(FenceReleaseReason.SWITCHED_OFF)
    if (record.bootCount < 0 || now.bootCount < 0 || record.bootCount != now.bootCount) {
        return FenceVerdict.Release(FenceReleaseReason.REBOOTED)
    }
    when (now.installResult) {
        FenceInstallResult.FAILURE -> return FenceVerdict.Release(FenceReleaseReason.INSTALL_FAILED)
        FenceInstallResult.PENDING_USER_ACTION -> return FenceVerdict.Release(FenceReleaseReason.PENDING_USER_ACTION)
        else -> {}
    }
    val replaced = now.ownLastUpdateMs >= 0 && now.ownLastUpdateMs != record.ownLastUpdateMs
    if (replaced) {
        if (now.frontReady) return FenceVerdict.Release(FenceReleaseReason.REPLACED)
        val sinceReplaced = maxOf(now.processAgeMs, now.nowWallMs - now.ownLastUpdateMs)
        if (sinceReplaced >= FENCE_TAIL_BACKSTOP_MS) return FenceVerdict.Release(FenceReleaseReason.REPLACED_BACKSTOP)
        return FenceVerdict.Keep(FenceKeepReason.AWAITING_FRONT, FENCE_TAIL_BACKSTOP_MS - sinceReplaced.coerceAtLeast(0))
    }
    if (now.installResult == FenceInstallResult.SUCCESS) return FenceVerdict.Release(FenceReleaseReason.SUCCESS_NOT_REPLACED)
    val age = now.nowElapsedMs - record.startedElapsedMs
    if (age < 0 || age >= FENCE_MAX_MS) return FenceVerdict.Release(FenceReleaseReason.EXPIRED)
    when (now.session) {
        FenceSession.NONE -> if (!now.committingHere) return FenceVerdict.Release(FenceReleaseReason.NO_SESSION)
        FenceSession.UNCOMMITTED -> if (!now.committingHere) return FenceVerdict.Release(FenceReleaseReason.NOT_COMMITTED)
        FenceSession.GONE -> return FenceVerdict.Release(FenceReleaseReason.SESSION_GONE)
        FenceSession.COMMITTED, FenceSession.UNKNOWN -> {}
    }
    return FenceVerdict.Keep(FenceKeepReason.INSTALLING, FENCE_MAX_MS - age)
}

/**
 * `apply()` while a fence is up (qa-11-design.md #5 and the binding decisions): a package the
 * fence holds is never unsuspended by enforcement - it stays suspended until the release, after
 * which the next pass decides it. Only matters for a package that became controllable mid-window.
 */
fun suspendTarget(packageName: String, planSuspend: Set<String>, fenceHeld: Set<String>): Boolean =
    packageName in planSuspend || packageName in fenceHeld

/**
 * The orphan sweep (qa-11-code #1): a lost or corrupt record (SharedPreferences swallows a parse
 * error and loads an empty map; a later build could drop the file) would leave the fenced HOME
 * packages suspended for good - and every later fence would skip them as already suspended. With
 * **no record**, every HOME candidate the fence could have suspended ([fencePlan] with nothing
 * counted as already suspended: not ours, never, persistent, FallbackHome, protected or
 * controllable) that is suspended now is unsuspended. Safe: nothing else in the launcher suspends
 * such a package (`apply()` only touches controllable ones, Play's rule only the Play Store), and
 * `setPackagesSuspended(false)` removes only our own suspension.
 * Design 16d: the [recentsPackage] is fenced although pinned or listed - so it is swept too.
 */
fun orphanFenceTargets(
    homeCandidates: Collection<HomeCandidate>,
    ownPackage: String,
    protected: Set<String>,
    controllable: Set<String>,
    suspendedNow: Set<String>,
    recentsPackage: String? = null,
): Set<String> =
    fencePlan(homeCandidates, ownPackage, protected, controllable, alreadySuspended = emptySet(), recentsPackage = recentsPackage)
        .suspend intersect suspendedNow

// ---- the last fence, for the status report (qa-11-code #5) -------------------------------------

/**
 * The last fence's summary, kept after the record is gone: the status report goes out before the
 * commit and the new build releases at its start, so the live record alone never reaches the
 * parent. Its own prefs file; keys pinned by UpdateFenceTest - only ever add keys.
 */
const val UPDATE_FENCE_LAST_PREFS = "update_fence_last"

object FenceSummaryKeys {
    const val REASON = "reason"
    const val UNSUSPENDABLE = "unsuspendable"
    /** -1 unknown, 0 no, 1 yes. */
    const val HOME_ROLE = "home_role"
    const val FENCED_AT_MS = "fenced_at_ms"
    const val RELEASED_AT_MS = "released_at_ms"
    const val RELEASE_TAG = "release_tag"
}

data class FenceSummary(
    /** Why it ended ([FenceReleaseReason.wire]); `null` while it is up. */
    val reason: String? = null,
    val unsuspendable: Set<String> = emptySet(),
    /** We held ROLE_HOME when fencing (`false` = a partial fence). */
    val homeRoleHeld: Boolean? = null,
    val fencedAtMs: Long? = null,
    val releasedAtMs: Long? = null,
    val releaseTag: String? = null,
)

fun encodeFenceSummary(summary: FenceSummary): Map<String, Any> = buildMap {
    summary.reason?.let { put(FenceSummaryKeys.REASON, it) }
    put(FenceSummaryKeys.UNSUSPENDABLE, summary.unsuspendable)
    put(FenceSummaryKeys.HOME_ROLE, when (summary.homeRoleHeld) { null -> -1; true -> 1; false -> 0 })
    summary.fencedAtMs?.let { put(FenceSummaryKeys.FENCED_AT_MS, it) }
    summary.releasedAtMs?.let { put(FenceSummaryKeys.RELEASED_AT_MS, it) }
    summary.releaseTag?.let { put(FenceSummaryKeys.RELEASE_TAG, it) }
}

/** Lenient: whatever is readable; `null` for an empty file. */
fun decodeFenceSummary(values: Map<String, *>): FenceSummary? {
    if (values.isEmpty()) return null
    return FenceSummary(
        reason = values[FenceSummaryKeys.REASON] as? String,
        unsuspendable = (values[FenceSummaryKeys.UNSUSPENDABLE] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
        homeRoleHeld = when (values[FenceSummaryKeys.HOME_ROLE] as? Int) { 1 -> true; 0 -> false; else -> null },
        fencedAtMs = values[FenceSummaryKeys.FENCED_AT_MS] as? Long,
        releasedAtMs = values[FenceSummaryKeys.RELEASED_AT_MS] as? Long,
        releaseTag = values[FenceSummaryKeys.RELEASE_TAG] as? String,
    )
}
