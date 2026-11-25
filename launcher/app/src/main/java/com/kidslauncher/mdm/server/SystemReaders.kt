package com.kidslauncher.mdm.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The system apps outside the controllable set that the plan restricts while apps are managed, and the report of the
 * apps with notification access. Pure, no Android imports - SystemReadersTest and the shared contract
 * `notification_listeners.json` (in `contracts/core/`).
 *
 * - Android Auto ([ANDROID_AUTO]) is hidden unless the parent allows it (the policy's `allow_android_auto`, Advanced ›
 *   Android Auto on the server). It has no launcher icon on current Android, so the allowlist never reached it.
 * - Every other Home app ([otherHomes]) is suspended, never hidden: Android keeps a Home to fall back on, and hiding
 *   broadcasts a removal. The recents provider stays as it is - the stock gestures run in it (the user's rule: no
 *   standard gesture is removed without asking) - and so does every package the plan never restricts.
 *
 * Both are lifted by the pause and the override with every other app restriction (`appsManaged`).
 */

/** Android Auto's package. */
const val ANDROID_AUTO = "com.google.android.projection.gearhead"

/** The most listeners one report carries (the server keeps as many). */
const val MAX_LISTENERS = 32

/**
 * The other Home apps enforcement keeps suspended while apps are managed: every HOME candidate but ours (either build),
 * [FENCE_NEVER] (the platform, SystemUI, Settings with its FallbackHome, Play core), a persistent app, a fallback Home
 * (a negative priority), a [controllable] one (the allowlist owns it) and [keep] (the plan's never-restrict set, the
 * recents provider among them). A package with one excluded HOME activity is excluded, as in [fencePlan].
 */
fun otherHomes(
    candidates: Collection<HomeCandidate>,
    ownPackage: String,
    controllable: Set<String>,
    keep: Set<String>,
): Set<String> {
    val own = ownPackageFamily(ownPackage)
    val excluded = mutableSetOf<String>()
    val homes = mutableSetOf<String>()
    for (candidate in candidates) {
        val pkg = candidate.packageName
        val skip = pkg in own || pkg in FENCE_NEVER || candidate.persistent || candidate.priority < 0 ||
            pkg in controllable || pkg in keep
        if (skip) excluded += pkg else homes += pkg
    }
    return homes - excluded
}

/**
 * A package added (installed, updated or unhidden) that the full plan owns rather than the new-package path: Android
 * Auto and the other Home apps ([otherHomes] with nothing kept - the recents provider is never restricted either way).
 * That path hides what isn't on the allowlist, and these never are: an allowed Android Auto unhidden by `apply()` is
 * itself a PACKAGE_ADDED, so the two undid each other in a loop of syncs (emulator gate §6be, 2026-11-24).
 */
fun planOwnsAddedPackage(
    packageName: String,
    homeCandidates: Collection<HomeCandidate>,
    ownPackage: String,
    controllable: Set<String>,
): Boolean = packageName == ANDROID_AUTO || packageName in otherHomes(homeCandidates, ownPackage, controllable, emptySet())

/** One app with notification access in the status report: [off] while Vibb has it hidden (a suspension isn't counted:
 * Android may keep a suspended listener bound - the emulator gate checks), [system] when it came with the phone. */
@Serializable
data class NotificationListenerReport(
    @SerialName("package") val packageName: String,
    val off: Boolean,
    val system: Boolean,
)

/** The report's `notification_listeners` from Android's enabled listeners: Vibb One (either build) left out, each
 * package once, sorted, at most [MAX_LISTENERS], each marked off by [hidden] and system by [system]. */
fun notificationListenersReport(
    enabled: Collection<String>,
    ownPackage: String,
    hidden: (String) -> Boolean,
    system: (String) -> Boolean,
): List<NotificationListenerReport> {
    val own = ownPackageFamily(ownPackage)
    return enabled.filter { it !in own }.distinct().sorted().take(MAX_LISTENERS)
        .map { NotificationListenerReport(it, hidden(it), system(it)) }
}

/**
 * The fence's orphan sweep without the Homes enforcement owns ([otherHomes]: suspended while apps are managed, released
 * otherwise): the sweep never unsuspends them, or it and `apply()` would undo each other on every pass. [protected]
 * and [recentsPackage] are the sweep's own; the recents provider is kept out of the enforced set, so it is still swept.
 */
fun orphanSweepTargets(
    homeCandidates: Collection<HomeCandidate>,
    ownPackage: String,
    protected: Set<String>,
    controllable: Set<String>,
    suspendedNow: Set<String>,
    recentsPackage: String?,
): Set<String> =
    orphanFenceTargets(homeCandidates, ownPackage, protected, controllable, suspendedNow, recentsPackage = recentsPackage) -
        otherHomes(homeCandidates, ownPackage, controllable, protected + setOfNotNull(recentsPackage))
