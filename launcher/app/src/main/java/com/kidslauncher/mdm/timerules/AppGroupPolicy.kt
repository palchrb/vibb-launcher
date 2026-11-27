package com.kidslauncher.mdm.timerules

import java.time.LocalDate

/*
 * Design 139 §4.2-§4.3: what the app groups decide - pure, unit-tested in AppGroupStatesTest. The fold
 * (AppGroups.kt) counts each group's time into the ledger; this reads the ledger against the policy's limits.
 */

/** A group's state today (§4.2). [closes]: the group's apps are suspended unless a pause or the override lifts it. */
enum class GroupStatus(val closes: Boolean) {
    /** Time left today. */
    OPEN(false),
    /** No limit today (`null`). */
    NO_LIMIT(false),
    /** The limit and its extra minutes are used up. */
    USED_UP(true),
    /** Today's limit is 0 (D3): closed all day. */
    NOT_TODAY(true),
    /** D6: no usage access, an unreadable ledger or a read that failed - the time can't be counted, so it closes. */
    CANT_COUNT(true),
}

/** One group today: its [limitMinutes] (`null`: none), [usedMs] and [extraMinutes] from the ledger, its [status], and
 * whether a pause or the override [lifted] it (D9: they lift groups; the time still counts, D10). */
data class GroupState(
    val id: Long,
    val name: String,
    val apps: Set<String>,
    val limitMinutes: Int?,
    val usedMs: Long,
    val extraMinutes: Int,
    val status: GroupStatus,
    val lifted: Boolean = false,
) {
    /** The group's apps are suspended now. */
    val closed: Boolean get() = status.closes && !lifted

    /** Time left today while the group is open and limited, else `null`. */
    val remainingMs: Long?
        get() = if (status == GroupStatus.OPEN && limitMinutes != null) {
            ((limitMinutes + extraMinutes) * 60_000L - usedMs).coerceAtLeast(0)
        } else {
            null
        }
}

/** Vibb One's own application ids, release and debug. */
val VIBB_ONE_PACKAGES = setOf("me.vibb.launcher", "me.vibb.launcher.debug")

/** Packages a group never closes (D8, code QA L3, L5): Vibb One, the Play Store (its own rule decides it, so other
 * apps' FCM keeps working), and the trusted Vibb music app ([music], `MusicTrust.installed`). The plan also spares
 * `neverRestrict` (the dialer, the WebView, Play services, the keyboards) and the alarm apps; the server never offers
 * any of these for a group. */
fun neverGrouped(pkg: String, music: Set<String> = emptySet()): Boolean =
    pkg in VIBB_ONE_PACKAGES || pkg == com.kidslauncher.mdm.play.PLAY_STORE || pkg in music

/** A group's limit on [date]: Monday first; a [AppGroup.days] of the wrong size is 0 (D6), a value clamped to a day. */
fun groupLimitFor(group: AppGroup, date: LocalDate): Int? {
    if (group.days.size != 7) return 0
    return group.days[date.dayOfWeek.value - 1]?.coerceIn(0, MINUTES_PER_DAY)
}

/** The policy has a group with a limit on some day (the setup step's `app_groups`, §5.3). A wrong-size day list is a
 * limit. */
fun groupsLimit(policy: TimePolicy?): Boolean =
    policy?.groups.orEmpty().any { g -> g.days.size != 7 || g.days.any { it != null } }

/**
 * Each of [policy]'s groups today (§4.2's `groupStates`), from [ledger] (already observed: its day is today).
 * [access]: the usage access (`null` unreadable, which counts as not granted, D6); [readFailed]: the last read threw;
 * [lifted]: a pause or the override is on.
 */
fun groupStates(
    policy: TimePolicy?,
    ledger: ScreenTimeLedger,
    access: Boolean?,
    readFailed: Boolean,
    lifted: Boolean,
    music: Set<String> = emptySet(),
): List<GroupState> {
    val date = ledger.date() ?: return emptyList()
    val cantCount = access != true || readFailed || ledger.unreadable
    return policy?.groups.orEmpty().map { g ->
        val limit = groupLimitFor(g, date)
        val used = ledger.groupUsedMs[g.id] ?: 0L
        val extra = ledger.groupExtraMinutes[g.id] ?: 0
        val status = when {
            limit == null -> GroupStatus.NO_LIMIT
            limit == 0 -> GroupStatus.NOT_TODAY
            cantCount -> GroupStatus.CANT_COUNT
            used >= (limit + extra) * 60_000L -> GroupStatus.USED_UP
            else -> GroupStatus.OPEN
        }
        GroupState(g.id, g.name, g.apps.filterNot { neverGrouped(it, music) }.toSet(), limit, used, extra, status, lifted)
    }
}

/** The apps of the closed groups (§4.2's `groupClosedApps`). An app in two groups (D2's race) closes with either. */
fun groupClosedApps(states: List<GroupState>): Set<String> =
    states.filter { it.closed }.flatMapTo(mutableSetOf()) { it.apps }

/** At most this many group-lift ids are remembered (ids only grow on the server). */
private const val MAX_REMEMBERED_GROUP_LIFTS = 50

/** Adds each group lift not applied before to its group's extra minutes today, once per id (D9). A lift whose group
 * is gone is marked applied and adds nothing. */
fun applyGroupLifts(ledger: ScreenTimeLedger, lifts: List<Lift>): ScreenTimeLedger {
    val fresh = lifts.filter { it.target == TARGET_GROUP && it.id !in ledger.appliedGroupLifts }
    if (fresh.isEmpty()) return ledger
    val extra = ledger.groupExtraMinutes.toMutableMap()
    for (lift in fresh) {
        val group = lift.groupId ?: continue
        extra[group] = ((extra[group] ?: 0) + lift.minutes.coerceIn(0, MINUTES_PER_DAY)).coerceAtMost(MINUTES_PER_DAY)
    }
    val applied = (ledger.appliedGroupLifts + fresh.map { it.id }).distinct().sortedDescending().take(MAX_REMEMBERED_GROUP_LIFTS)
    return ledger.copy(groupExtraMinutes = extra, appliedGroupLifts = applied)
}

/** The group timer's delay (§4.1 "The timer"): the least time left of the open groups plus 1 s, `null` with none. */
fun groupRunsOutDelayMs(states: List<GroupState>): Long? =
    states.mapNotNull { it.remainingMs }.filter { it > 0 }.minOrNull()?.let { it + 1_000L }

/** The 5-minute warning's window (Q4 (b)). */
const val GROUP_WARNING_MS = 5 * 60_000L

/** Q4 (b): the group the anchor notification's title names - one with at most 5 minutes left (and some) whose app was
 * on at the last fold ([onScreen], the fold's carry). The least time left first. */
fun groupWarning(states: List<GroupState>, onScreen: Set<String>): GroupState? =
    states.filter { s ->
        !s.lifted && s.remainingMs.let { it != null && it in 1..GROUP_WARNING_MS } && s.apps.any { it in onScreen }
    }.minByOrNull { it.remainingMs ?: Long.MAX_VALUE }

/** Code QA M1: whether the groups' closed apps ([closedNow], the VoIP hold applied) call for a re-check - a change
 * since the last fold ([lastSeen]); on a process's first fold (`null`) a difference from what the last apply enforced
 * ([enforced], `null` before any apply: nothing), so a group that ran out while the process was dead is enforced. */
fun groupRecheckNeeded(closedNow: Set<String>, lastSeen: Set<String>?, enforced: Set<String>?): Boolean =
    if (lastSeen != null) closedNow != lastSeen else closedNow != enforced.orEmpty()

/** Code QA M1: the cut after an apply - Home over a [onScreen] app of a group that closed since the last apply
 * ([closedBefore], `null` on the process's first apply: nothing was enforced by this process, so every closed group
 * counts as just closed). */
fun cutAfterApply(closedNow: Set<String>, closedBefore: Set<String>?, onScreen: Set<String>): String? =
    if (closedNow == closedBefore) null else cutTarget(closedNow, closedBefore.orEmpty(), onScreen, voipPackage = null)

/** Code QA L4: an app installed (again) while its group is closed stays closed - by the plan's own filter: never what
 * is spared from every restriction ([neverRestrict], the last plan's), an alarm app or [neverGrouped]. */
fun groupClosesNewPackage(pkg: String, groupClosed: Set<String>, neverRestrict: Set<String>, alarmApps: Set<String>): Boolean =
    pkg in groupClosed && pkg !in neverRestrict && pkg !in alarmApps && !neverGrouped(pkg)

/** The cut (D7, §4.2): what Home must cover - a [onScreen] package of a group that just closed ([closedNow] minus the
 * closed set before, [closedBefore]), except the live VoIP call's own package ([voipPackage]), which is held back
 * until the call ends. `null`: nothing to cover. */
fun cutTarget(closedNow: Set<String>, closedBefore: Set<String>, onScreen: Set<String>, voipPackage: String?): String? =
    onScreen.firstOrNull { it in closedNow && it !in closedBefore && it != voipPackage }

/** D7: the apps a closing group suspends now - every closed app but the live VoIP call's own package, which keeps
 * running until the call ends (`VoipCalls.pinnedPackage`'s change re-checks). */
fun groupSuspends(closed: Set<String>, voipPackage: String?): Set<String> =
    if (voipPackage == null) closed else closed - voipPackage
