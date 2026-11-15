package com.kidslauncher.mdm.timerules

/*
 * The anchor notification's time part (design r13 §2.2, design 100 §2.5 and §14.3, `TimeLeftNoticeTest`,
 * `TimeRequestsTest`): pure. With a screen-time budget set today: the time left ("1 t 20 min igjen i dag", or
 * "Skjermtiden er brukt opp i dag"), the note under it - the request on its way, waiting, or the parent's answer
 * ("Pappa svarte nei kl. 14:10", "Mamma ga deg 30 min") - and whether the kid may ask now; otherwise the plain
 * "Vibb One". Never a second notification.
 */

/** The title's part: [Left] minutes of today's screen time, or [UsedUp]. */
sealed interface TimeLeftText {
    data class Left(val minutes: Int) : TimeLeftText

    data object UsedUp : TimeLeftText
}

/** The ask's part - the plain notice's action and Snarveier's Mer tid disc: [Action] "Be om mer tid" (usable),
 * [Asked] a request is open (the disc dimmed, "Mer tid, spurt"; no action), or [None] (no budget, the pause, or the
 * day's three used). */
sealed interface AskPart {
    data object Action : AskPart

    data class Asked(val atMs: Long) : AskPart

    data object None : AskPart
}

/** The note line under the time (design 100 §14.3): text only, never a button (R13-8). [by] is the parent's display
 * name, `null` without one (D11: never the login). */
sealed interface AskNote {
    /** "Spurt kl. 14:02 · sender …", or after [SENDING_OFFLINE_MS] "· sendes når telefonen er på nett". */
    data class Sending(val atMs: Long, val offline: Boolean) : AskNote

    /** "Spurt kl. 14:02 · venter på svar". */
    data class Waiting(val atMs: Long) : AskNote

    /** "Pappa svarte nei kl. 14:10" / "Svaret ble nei kl. 14:10". */
    data class No(val by: String?, val atMs: Long) : AskNote

    /** "Mamma ga deg 30 min" / "Du fikk 30 min mer". */
    data class Given(val by: String?, val minutes: Int) : AskNote

    data object None : AskNote
}

/** Where a note shows (D12): the shade (the panel's note line, the plain notice's text) only for
 * [ANSWER_NOTE_SHADE_MS] after the answer; the time-rule screen for as long as it is today's newest. */
enum class NoteSurface { SHADE, SCREEN }

/** The time part: [text] `null` = the plain "Vibb One" title; [note] the second line; [ask] the action's part. */
data class TimePart(val text: TimeLeftText?, val note: AskNote, val ask: AskPart) {
    /** The kid may ask now: the plain notice's action, the Mer tid disc usable. */
    val canAsk: Boolean get() = ask == AskPart.Action
}

/** The plain "Vibb One", nothing below it - what the anchor posts first, and without a budget today. */
val PLAIN_NOTICE = TimePart(null, AskNote.None, AskPart.None)

/** A request still unsent this long after the tap says "sendes når telefonen er på nett" (Q100-L10). */
const val SENDING_OFFLINE_MS = 15_000L

/** An answer (or time given without a request, D10) shows in the shade this long (D12). */
const val ANSWER_NOTE_SHADE_MS = 30 * 60_000L

/** Whole minutes left, rounded up: "1 min" shows until the last second, "0 min" never (the budget lock and "brukt
 * opp" come together). */
fun leftMinutes(remainingMs: Long): Int =
    if (remainingMs <= 0) 0 else ((remainingMs + 59_999L) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * The time part for [budget] (today's use, the snapshot's), [windowOpen] (the 2-hour pause or the offline override),
 * [state] (`requestState`) and [policy] (its answer and lifts) at [nowMs]: plain without a budget today (none at all,
 * or none on today's weekday) and in the pause; else the time left or "used up" (an unreadable ledger counts as used
 * up - its use is more than any budget), the note for [surface] ([askNote]; [dayStartMs] the ledger day's start, for
 * the screen's note of time given without a request), and the ask: the action while idle or answered under the day's
 * three, "asked" while one is open. A rule that locks changes neither part (D3).
 */
fun timePart(
    budget: BudgetUse?,
    windowOpen: Boolean,
    state: RequestState,
    policy: TimePolicy?,
    nowMs: Long,
    surface: NoteSurface = NoteSurface.SHADE,
    dayStartMs: Long? = null,
): TimePart {
    if (windowOpen) return PLAIN_NOTICE
    val remaining = budget?.takeIf { it.effectiveMinutes != null }?.remainingMs ?: return PLAIN_NOTICE
    val text = if (remaining <= 0) TimeLeftText.UsedUp else TimeLeftText.Left(leftMinutes(remaining))
    return TimePart(text, askNote(state, policy, nowMs, surface, dayStartMs), askPart(state))
}

/** The ask's part for [state]: the action while idle or answered under the day's three, "asked" while open. */
fun askPart(state: RequestState): AskPart = when (state) {
    RequestState.Idle -> AskPart.Action
    is RequestState.Pending -> AskPart.Asked(state.atMs)
    is RequestState.Waiting -> AskPart.Asked(state.atMs)
    is RequestState.Answered -> if (state.canAskAgain) AskPart.Action else AskPart.None
    RequestState.Capped -> AskPart.None
}

/**
 * The note (§14.3's table): Pending "sender …" ("sendes når …" from [SENDING_OFFLINE_MS]); Waiting "venter på svar";
 * Answered its answer; Capped the policy's answer of today, if any; Idle time a parent gave without a request
 * ([givenLift], D10). An answer or a gift shows in the shade for [ANSWER_NOTE_SHADE_MS] after it (D12), on the screen
 * as long as it is today's (a gift from [dayStartMs] on).
 */
fun askNote(state: RequestState, policy: TimePolicy?, nowMs: Long, surface: NoteSurface, dayStartMs: Long? = null): AskNote {
    fun shown(atMs: Long) = surface == NoteSurface.SCREEN || (atMs > 0 && nowMs < atMs + ANSWER_NOTE_SHADE_MS)
    val answered = when (state) {
        is RequestState.Pending -> return AskNote.Sending(state.atMs, offline = nowMs - state.atMs >= SENDING_OFFLINE_MS)
        is RequestState.Waiting -> return AskNote.Waiting(state.atMs)
        is RequestState.Answered -> state
        RequestState.Capped -> policy?.answer?.let { answerState(it, policy, canAskAgain = false) }
        RequestState.Idle -> {
            val lift = givenLift(policy) ?: return AskNote.None
            val today = if (surface == NoteSurface.SCREEN) dayStartMs != null && lift.atMs >= dayStartMs else shown(lift.atMs)
            return if (today) AskNote.Given(liftBy(lift, policy), lift.minutes) else AskNote.None
        }
    } ?: return AskNote.None
    if (!shown(answered.atMs)) return AskNote.None
    return when {
        answered.no -> AskNote.No(answered.by, answered.atMs)
        answered.minutes != null && answered.minutes > 0 -> AskNote.Given(answered.by, answered.minutes)
        else -> AskNote.None
    }
}

/**
 * Time a parent gave without a request (D10, QA R2-L2): the newest screen-time lift of the PWA - `budget`, a positive
 * id (never design 93's twins), no `phone_key` (never design 98's parent-code pause) - that says when it was given (a
 * server from design 100 on).
 */
fun givenLift(policy: TimePolicy?): Lift? =
    policy?.lifts.orEmpty()
        .filter { it.target == TARGET_BUDGET && it.id > 0 && it.phoneKey == null && it.atMs > 0 && it.minutes > 0 }
        .maxWithOrNull(compareBy<Lift>({ it.atMs }, { it.id }))

/** Who gave [lift]: the answer's name when the lift answered a request (its snapshot, QA R2-L10), else the lift's. */
fun liftBy(lift: Lift, policy: TimePolicy?): String? =
    policy?.answer?.takeIf { it.liftId == lift.id }?.let { cleanBy(it.by) } ?: cleanBy(lift.by)

/** A display name as shown: trimmed, blank = none (D11). */
fun cleanBy(by: String?): String? = by?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The next instant after [nowMs] at which the shade's note changes by itself (QA R2-L3, where r13's `openUntilMs`
 * was): a Pending request's [SENDING_OFFLINE_MS] mark, the end of an answer's or a gift's [ANSWER_NOTE_SHADE_MS].
 * `null`: none ahead. The boundary alarm re-posts the shade and re-renders the time-rule screen then.
 */
fun noteChangeMs(state: RequestState, policy: TimePolicy?, nowMs: Long): Long? {
    val at = when (state) {
        is RequestState.Pending -> state.atMs + SENDING_OFFLINE_MS
        is RequestState.Waiting -> null
        is RequestState.Answered -> state.atMs.takeIf { it > 0 }?.plus(ANSWER_NOTE_SHADE_MS)
        RequestState.Capped -> policy?.answer?.atMs?.takeIf { it > 0 }?.plus(ANSWER_NOTE_SHADE_MS)
        RequestState.Idle -> givenLift(policy)?.atMs?.plus(ANSWER_NOTE_SHADE_MS)
    }
    return at?.takeIf { it > nowMs }
}
