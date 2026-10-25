package com.kidslauncher.mdm.timerules

/*
 * What the anchor's one notification says (design r13 §2.2, `TimeLeftNoticeTest`): pure. With a screen-time budget
 * set today its title is the time left ("1 t 20 min igjen i dag", or "Skjermtiden er brukt opp i dag") and it has
 * one action, "Be om mer tid"; otherwise it stays the plain "Vibb One". Never a second notification, never a panel.
 */

/** The title's part: [Left] minutes of today's screen time, or [UsedUp]. */
sealed interface TimeLeftText {
    data class Left(val minutes: Int) : TimeLeftText

    data object UsedUp : TimeLeftText
}

/** The request's part: the [Action] "Be om mer tid", the [Asked] line (plain text, never a button - R13-8), or
 * [None] (a request being sent, the day's three used, or no budget). */
sealed interface AskPart {
    data object Action : AskPart

    data class Asked(val atMs: Long) : AskPart

    data object None : AskPart
}

/** The notice: [text] `null` = the plain "Vibb One" title. */
data class TimeLeftNotice(val text: TimeLeftText?, val ask: AskPart)

/** The plain "Vibb One", nothing below it - what the anchor posts first, and without a budget today. */
val PLAIN_NOTICE = TimeLeftNotice(null, AskPart.None)

/** Whole minutes left, rounded up: "1 min" shows until the last second, "0 min" never (the budget lock and "brukt
 * opp" come together). */
fun leftMinutes(remainingMs: Long): Int =
    if (remainingMs <= 0) 0 else ((remainingMs + 59_999L) / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * The notice for [budget] (today's use, the snapshot's), [windowOpen] (the 2-hour pause or the offline override) and
 * [request] (`requestState`): plain without a budget today (none at all, or none on today's weekday) and in the pause;
 * else the time left or "used up" (an unreadable ledger counts as used up - its use is more than any budget), with the
 * action only while no request is pending, taken or capped. A rule that locks changes neither part (D3).
 */
fun timeLeftNotice(budget: BudgetUse?, windowOpen: Boolean, request: RequestState): TimeLeftNotice {
    if (windowOpen) return PLAIN_NOTICE
    val remaining = budget?.takeIf { it.effectiveMinutes != null }?.remainingMs ?: return PLAIN_NOTICE
    val text = if (remaining <= 0) TimeLeftText.UsedUp else TimeLeftText.Left(leftMinutes(remaining))
    val ask = when (request) {
        RequestState.Idle -> AskPart.Action
        is RequestState.Asked -> AskPart.Asked(request.atMs)
        RequestState.Pending, RequestState.Capped -> AskPart.None
    }
    return TimeLeftNotice(text, ask)
}
