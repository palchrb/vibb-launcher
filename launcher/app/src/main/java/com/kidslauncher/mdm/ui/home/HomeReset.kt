package com.kidslauncher.mdm.ui.home

/*
 * Home starts at the top after an unlock (PLAN "Fix backlog", the user 2026-10-24: "når jeg slår av og på skjerm og
 * taster pin så tror jeg at jeg vil at home starter på null"). Home learns why it is shown from the typed HOME start
 * that brought it (`HomeFront.bring`, its `EXTRA_ARRIVAL` next to `EXTRA_BROUGHT`) - no broadcast of its own.
 */

/** Why Home is shown - what the HOME intent that reached it says ([homeArrivalOf]). */
enum class HomeArrival {
    /** The lock was left for Home: the PIN after a screen-off, the handle's swipe, Android's pad, "Lås opp". */
    LOCK_LEFT_FOR_HOME,

    /** The lock was left for an app - a glance row's target (a conversation, the missed calls, the clock app) opens
     * over Home, which is shown for a frame at most. */
    LOCK_LEFT_FOR_APP,

    /** The boot's Home start (`BootHome`), before the boot's first lock. */
    BOOT,

    /** Any other start of ours: under the lock at a dark lock-up, rooting lock task, an update, a relock, a role
     * change, the end of install mode. */
    OURS,

    /** Not ours: the kid's Home button or gesture, the system's Home. Back and Recents bring no intent at all. */
    PLAIN,
}

/** [brought]: `HomeFront.EXTRA_BROUGHT`; [arrival]: `HomeFront.EXTRA_ARRIVAL` (a [HomeArrival] name). An unknown or
 * missing name on our start is [HomeArrival.OURS]; never ours is [HomeArrival.PLAIN], whatever the extra says. */
fun homeArrivalOf(brought: Boolean, arrival: String?): HomeArrival = when {
    !brought -> HomeArrival.PLAIN
    else -> HomeArrival.entries.firstOrNull { it.name == arrival && it != HomeArrival.PLAIN } ?: HomeArrival.OURS
}

/**
 * The lock's leave through Home (`PinLockActivity.go`): [hasTarget] - a glance row's target opens next (the unlock
 * really happened, the mode is UNLOCKED); [lockWasRoot] - the lock was the kiosk's lock-task root, where the opener
 * opens nothing (design 25 §1.4), so Home is what the kid sees.
 */
fun lockLeaveArrival(hasTarget: Boolean, lockWasRoot: Boolean): HomeArrival =
    if (hasTarget && !lockWasRoot) HomeArrival.LOCK_LEFT_FOR_APP else HomeArrival.LOCK_LEFT_FOR_HOME

/**
 * Whether Home's grid goes back to the top and the contacts row to its start, at the content's next resume: only when
 * the lock was left for Home, or at the boot - never when the unlock's target is an app (Home is resumed under it for
 * a frame at most), never for our other starts, and never on an ordinary return from an app (the Home button, Back,
 * Recents: the kid's place in the grid stays, as before). The kid's press on Home itself is [homePressToTop]'s.
 */
fun homeResetDue(arrival: HomeArrival): Boolean = when (arrival) {
    HomeArrival.LOCK_LEFT_FOR_HOME, HomeArrival.BOOT -> true
    HomeArrival.LOCK_LEFT_FOR_APP, HomeArrival.OURS, HomeArrival.PLAIN -> false
}

/**
 * The reset still owed at the content's next resume after [arrival] reached Home, with [pending] owed before it: a due
 * arrival sets it, an unlock into an app clears it, any other start of ours or the kid's press keeps it (a relock's
 * queued Home may reach Home right after the lock's leave, before the resume). Taken by that resume, and dropped when
 * Home stops - a reset happens at the resume the leave brought, or not at all.
 */
fun homeResetPending(pending: Boolean, arrival: HomeArrival): Boolean =
    homeResetDue(arrival) || (pending && arrival != HomeArrival.LOCK_LEFT_FOR_APP)
