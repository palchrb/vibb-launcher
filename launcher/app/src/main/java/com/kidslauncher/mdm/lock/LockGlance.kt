package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.badges.GlanceConversation
import com.kidslauncher.mdm.music.MusicSession

/*
 * The lock's glance page (design 25, docs/design/25-lock-glance.md at the monorepo root, mockup
 * LockGlance.dc.html): pure, LockGlanceTest. Replaces step 4's media view (lock/LockMedia.kt): the
 * PIN lock always comes up on the glance page - clock, date, the music tile while something plays,
 * up to three notification rows, "Lås opp", "Nødsamtale" - and "Lås opp" or a row leads to the
 * keypad. lock/GlancePanel draws it inside PinLockActivity; lock/GlanceOpener opens a row's target
 * after the right code.
 *
 * It weakens nothing: the page's only actions are the music's play/pause, previous and next, the
 * stage switches and the lock's own emergency call. A row's target is opened only after the kid's
 * PIN or the parent code, from the unlock path (LockGlanceGuardTest).
 */

/** The status report's capability: this launcher has the glance page (the server's notes read it). */
const val LOCK_GLANCE_CAPABILITY = "lock_glance_v1"

/** The lock's two stages: the glance page, or the keypad. */
enum class LockStage { GLANCE, KEYPAD }

/**
 * What a row opens after the code (memory only; redacted toString - a conversation key can be a
 * room ID). [Conversation]: the row's newest notification ([notificationKey], posted at
 * [postTimeMs]); [MissedCalls]: one contact's sheet ([number]), else the phone book.
 */
sealed interface GlanceTarget {
    data class Conversation(
        val conversation: GlanceConversation,
        val notificationKey: String,
        val postTimeMs: Long,
    ) : GlanceTarget {
        val packageName: String get() = conversation.packageName

        override fun toString() = "Conversation($packageName)"
    }

    data class MissedCalls(val number: String?) : GlanceTarget {
        override fun toString() = "MissedCalls"
    }
}

/** The stage and the target it carries. */
data class GlanceStageState(val stage: LockStage, val target: GlanceTarget?) {
    companion object {
        val GLANCE = GlanceStageState(LockStage.GLANCE, null)
    }
}

sealed interface GlanceStageEvent {
    /** The lock comes up with the screen on ([lockStageDue]): the first show, after a screen-off. */
    data object LockUp : GlanceStageEvent

    /** "Lås opp". */
    data object Unlock : GlanceStageEvent

    /** A row: its target, or `null` when none may be taken (a time rule leaves the app unusable). */
    data class Row(val target: GlanceTarget?) : GlanceStageEvent

    /** The keypad's back arrow. */
    data object Back : GlanceStageEvent
}

/**
 * The stage machine (§1.1): every lock-up is the glance page, without a target; "Lås opp" and a
 * row lead to the keypad (a row with its target); the arrow back drops the target. Coming back
 * with the screen on (the emergency dialer, an alarm, a call) is no event: the stage and target stay.
 */
fun glanceStageStep(state: GlanceStageState, event: GlanceStageEvent): GlanceStageState = when (event) {
    GlanceStageEvent.LockUp -> GlanceStageState.GLANCE
    GlanceStageEvent.Unlock -> GlanceStageState(LockStage.KEYPAD, null)
    is GlanceStageEvent.Row -> GlanceStageState(LockStage.KEYPAD, event.target)
    GlanceStageEvent.Back -> if (state.stage == LockStage.KEYPAD) GlanceStageState.GLANCE else state
}

/**
 * Whether the lock "comes up" now (its onStart/onResume) and decides its stage again: only with the
 * screen on ([interactive] - the lock is started at every screen-off and seen at the next
 * screen-on), the first time, and after every screen-off since the last decision ([screenOffs],
 * `PinLockRuntime.screenOffs`). Coming back from the emergency dialer or an alarm with the screen
 * on keeps the stage the kid was on.
 */
fun lockStageDue(interactive: Boolean, decidedAtScreenOffs: Int?, screenOffs: Int): Boolean =
    interactive && decidedAtScreenOffs != screenOffs

/**
 * The music tile (§1.1): it appears while the music app's session ([session], `shownSession` - `null`
 * without listener access, the app suspended or gone) plays, stays while it is paused ([shown]:
 * the tile is up now), and goes when the session ends; at a lock-up ([shown] false) a paused
 * session shows none.
 */
fun glanceTile(session: MusicSession?, shown: Boolean): Boolean = session != null && (session.playing || shown)

/** The page's progress ticks once a second only while it shows a tile of a playing session. */
fun glanceTicks(tileShown: Boolean, session: MusicSession?): Boolean = tileShown && session?.playing == true

// ---- the fit -------------------------------------------------------------------------------------

/** The page shows at most this many rows; the rest is the "more" line's count. */
const val GLANCE_MAX_ROWS = 3

/**
 * Each part's height as measured (any unit, the same for all and for the available height):
 * [fixed] = "Lås opp" and "Nødsamtale" with their margins; [date] with its margin; the tiles with
 * their top margin; [rowsTop] the list's margin below the tile, [rowsTopNoTile] below the date;
 * [row] one row (the tallest); [rowGap] between rows; [more] the "more" line (nothing above it).
 */
data class GlanceHeights(
    val fixed: Float,
    val clockBig: Float,
    val clockSmall: Float,
    val date: Float,
    val tileFull: Float,
    val tileCompact: Float,
    val rowsTop: Float,
    val rowsTopNoTile: Float,
    val row: Float,
    val rowGap: Float,
    val more: Float,
)

enum class GlanceTileSize { NONE, FULL, COMPACT }

/** What the page shows: [candidate] is §1.2's number (1-7), for the logs and tests. */
data class GlanceFit(
    val candidate: Int,
    val bigClock: Boolean,
    val date: Boolean,
    val tile: GlanceTileSize,
    val rows: Int,
    val more: Int,
    val height: Float,
)

/**
 * The fit (§1.2): the page never scrolls. The first candidate whose height fits [available] wins:
 * 1. big clock, full tile, min(n, 3) rows (+ the "more" line when n > 3);
 * 2. small clock, full tile, the same rows;
 * 3. big clock, compact tile; 4. small clock, compact tile;
 * 5. small clock, compact tile, one row less each time down to 1 (the "more" line counts the rest);
 * 6. as 5 with one row and no date; 7. the same with no row (only the "more" line).
 * Without a tile, 2-4 are only the clock step. Nothing fits: the last candidate.
 */
fun glanceFit(available: Float, heights: GlanceHeights, tile: Boolean, rowCount: Int): GlanceFit {
    val n = rowCount.coerceAtLeast(0)
    val most = minOf(n, GLANCE_MAX_ROWS)
    val full = if (tile) GlanceTileSize.FULL else GlanceTileSize.NONE
    val compact = if (tile) GlanceTileSize.COMPACT else GlanceTileSize.NONE
    fun fit(candidate: Int, big: Boolean, date: Boolean, size: GlanceTileSize, rows: Int): GlanceFit {
        val more = n - rows
        val tileHeight = when (size) {
            GlanceTileSize.NONE -> 0f
            GlanceTileSize.FULL -> heights.tileFull
            GlanceTileSize.COMPACT -> heights.tileCompact
        }
        val list = if (rows == 0 && more == 0) {
            0f
        } else {
            (if (size == GlanceTileSize.NONE) heights.rowsTopNoTile else heights.rowsTop) +
                rows * heights.row + (rows - 1).coerceAtLeast(0) * heights.rowGap +
                (if (more > 0) heights.more else 0f)
        }
        val height = heights.fixed + (if (big) heights.clockBig else heights.clockSmall) +
            (if (date) heights.date else 0f) + tileHeight + list
        return GlanceFit(candidate, big, date, size, rows, more, height)
    }
    val candidates = buildList {
        add(fit(1, big = true, date = true, size = full, rows = most))
        add(fit(2, big = false, date = true, size = full, rows = most))
        if (tile) {
            add(fit(3, big = true, date = true, size = compact, rows = most))
            add(fit(4, big = false, date = true, size = compact, rows = most))
        }
        for (rows in most - 1 downTo 1) add(fit(5, big = false, date = true, size = compact, rows = rows))
        add(fit(6, big = false, date = false, size = compact, rows = minOf(most, 1)))
        add(fit(7, big = false, date = false, size = compact, rows = 0))
    }
    return candidates.firstOrNull { it.height <= available } ?: candidates.last()
}

// ---- the keypad's prompt -------------------------------------------------------------------------

/** A label in the keypad's hint is cut to this many characters (QA Low 5: the parent's label can be
 * 100 characters; the prompt keeps to two lines). */
const val GLANCE_PROMPT_LABEL_MAX = 20

/** [label] as the hint shows it: at most [GLANCE_PROMPT_LABEL_MAX] characters, a cut one ends in "…". */
fun glancePromptLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.codePointCount(0, trimmed.length) <= GLANCE_PROMPT_LABEL_MAX) return trimmed
    val end = trimmed.offsetByCodePoints(0, GLANCE_PROMPT_LABEL_MAX - 1)
    return trimmed.substring(0, end).trimEnd() + "…"
}

/** Which prompt the keypad shows (§1.1): the usual wait/wrong/checking/unusable texts first; else the
 * target's hint; else the plain prompt. */
enum class GlancePrompt { UNUSABLE, WAIT, CHECKING, WRONG, OPEN_APP, SEE_MISSED, PLAIN }

fun glancePrompt(usable: Boolean, waiting: Boolean, checking: Boolean, wrong: Boolean, target: GlanceTarget?): GlancePrompt = when {
    !usable -> GlancePrompt.UNUSABLE
    waiting -> GlancePrompt.WAIT
    checking -> GlancePrompt.CHECKING
    wrong -> GlancePrompt.WRONG
    target is GlanceTarget.Conversation -> GlancePrompt.OPEN_APP
    target is GlanceTarget.MissedCalls -> GlancePrompt.SEE_MISSED
    else -> GlancePrompt.PLAIN
}

// ---- taking a target and opening it ------------------------------------------------------------

/**
 * Whether a row's tap takes its target (§1.4 with the decision on QA Q1): always without a time
 * rule; under one ([timeRule]) only when the rule leaves it usable - the row's app among the time
 * lock's usable apps ([usableUnderRule]), a missed-call row when the rule allows calls and calls are
 * managed (the time-rule screen's phone book). Otherwise the row just opens the keypad.
 */
fun glanceTargetTaken(timeRule: Boolean, usableUnderRule: Boolean): Boolean = !timeRule || usableUnderRule

/** Where the open after the code runs from. */
enum class GlanceOpenFrom {
    /** The lock's unlock path ([PinLockActivity]'s leave step). */
    LOCK,

    /** The time-rule screen, after the lock handed the target over (QA Q1). */
    TIME_RULE_SCREEN,
}

/** What happens to a target after the code ([glanceOpenStep]). */
enum class GlanceOpen {
    /** A time rule locks the phone: the time-rule screen comes up first and opens it over itself. */
    HAND_TO_TIME_RULE_SCREEN,

    /** The missed calls: one contact's sheet, else the phone book. */
    MISSED_CALLS,

    /** The notification's own content intent, sent from our visible window. */
    SEND,

    /** The app's launch intent. */
    LAUNCH,

    NOTHING,
}

/** The facts [glanceOpenStep] decides on; the Android glue (GlanceOpener) reads them. */
data class GlanceOpenFacts(
    val from: GlanceOpenFrom,
    val missedCalls: Boolean,
    /** A time rule locks the phone now (`lockReason != NONE`). */
    val timeRule: Boolean,
    /** From the time-rule screen: the rule leaves the target usable (the app is among its usable
     * apps; for missed calls, calls are managed and the rule allows them). */
    val usableUnderRule: Boolean,
    /** Kiosk on and the lock was the lock-task root (the 1 s fallback) - read before its stop. */
    val lockWasRoot: Boolean,
    val installed: Boolean,
    val suspended: Boolean,
    val hidden: Boolean,
    /** The conversation's newest content intent (else the one captured at the tap) exists. */
    val hasIntent: Boolean,
    val intentIsActivity: Boolean,
    /** The intent's creator is the notification's own app (QA Low 10). */
    val intentFromApp: Boolean,
    /** The send already threw ([GlanceOpen.SEND] failed): the launch intent instead. */
    val sendFailed: Boolean,
    val hasLaunchIntent: Boolean,
)

/** The decision with its reason (the log names the package and the reason only). */
data class GlanceOpenStep(val open: GlanceOpen, val reason: String)

/**
 * §1.4's table, with QA Q1 (a time rule hands the target to the time-rule screen), QA Medium 2 (no
 * launch intent: nothing) and QA Low 10 (only the app's own intent is sent):
 * | a time rule locks the phone (from the lock)    | the time-rule screen opens it over itself    |
 * | from the time-rule screen, not usable under it | nothing                                      |
 * | kiosk on and the lock was the lock-task root   | nothing (the app would cover Home)            |
 * | missed calls                                   | one contact's sheet, else the phone book      |
 * | not installed, suspended or hidden             | nothing                                      |
 * | an activity intent created by the app itself   | sent from our visible window                 |
 * | otherwise, or the send threw                   | the app's launch intent; none: nothing       |
 */
fun glanceOpenStep(f: GlanceOpenFacts): GlanceOpenStep = when {
    f.from == GlanceOpenFrom.LOCK && f.timeRule -> GlanceOpenStep(GlanceOpen.HAND_TO_TIME_RULE_SCREEN, "time rule")
    f.from == GlanceOpenFrom.TIME_RULE_SCREEN && !f.usableUnderRule -> GlanceOpenStep(GlanceOpen.NOTHING, "not usable under the time rule")
    f.from == GlanceOpenFrom.LOCK && f.lockWasRoot -> GlanceOpenStep(GlanceOpen.NOTHING, "the lock was the lock-task root")
    f.missedCalls -> GlanceOpenStep(GlanceOpen.MISSED_CALLS, "missed calls")
    !f.installed -> GlanceOpenStep(GlanceOpen.NOTHING, "not installed")
    f.suspended -> GlanceOpenStep(GlanceOpen.NOTHING, "suspended")
    f.hidden -> GlanceOpenStep(GlanceOpen.NOTHING, "hidden")
    f.hasIntent && f.intentIsActivity && f.intentFromApp && !f.sendFailed -> GlanceOpenStep(GlanceOpen.SEND, "intent")
    f.hasLaunchIntent -> GlanceOpenStep(GlanceOpen.LAUNCH, "app")
    else -> GlanceOpenStep(GlanceOpen.NOTHING, "no launch intent")
}
