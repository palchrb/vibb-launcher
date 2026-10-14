package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_SCHOOL

/*
 * The time-rule screen's content as pure rules (design 34 P6, `LockActivity`, `TimeLockModelTest`):
 * the kind's icon, the screen-time sentence and the card's rows. Visual only - what the lock allows
 * (its usable apps, calls) is decided by `decideTimeLock`, never here.
 */

/** The small icon in the peach disc above the title. */
enum class LockGlyph { HOURGLASS, MOON, BOOK, DOT }

/** Screen time used up (no rule) is the hourglass; a rule shows its kind: bedtime the moon, school
 * the book, any other (custom or unknown) kind a plain dot. */
fun lockGlyph(ruleKind: String?): LockGlyph = when (ruleKind) {
    null -> LockGlyph.HOURGLASS
    KIND_BEDTIME -> LockGlyph.MOON
    KIND_SCHOOL -> LockGlyph.BOOK
    else -> LockGlyph.DOT
}

/**
 * A length of time in whole minutes as the kid reads it: "45 min", "2 t", "1 t 30 min" (nb) /
 * "45 min", "2 h", "1 h 30 min" (en) - the units come from the strings (`duration_unit_*`).
 * Abbreviations, so one and many read the same ("1 min", "1 t"). Negative counts as 0.
 */
fun durationText(minutes: Int, hourUnit: String, minuteUnit: String): String {
    val total = minutes.coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "$total $minuteUnit"
        rest == 0 -> "$hours $hourUnit"
        else -> "$hours $hourUnit $rest $minuteUnit"
    }
}

/** The screen-time sentence under the title. */
sealed interface BudgetLine {
    /** "Du har brukt dagens {allowance}." - [minutes] is today's budget plus today's extra minutes. */
    data class Used(val minutes: Int) : BudgetLine

    /** Today's budget is 0 (with no extra minutes): "Ingen skjermtid i dag." */
    data object NoneToday : BudgetLine

    /** A rule is in force and the budget is used up as well. */
    data object AlsoUsed : BudgetLine
}

/**
 * Which budget sentence the screen shows. Without a rule (the budget lock itself) it is always the
 * day's whole allowance - [BudgetUse.effectiveMinutes], the budget plus the parent's extra minutes -
 * never the minutes used: what was counted can be more than the allowance (the budget lowered during
 * the day, or a minute counted after the boundary), which used to read "6 av 1 minutter brukt i dag".
 * Under a rule only "also used up" when it is. Nothing when there is nothing to say.
 */
fun budgetLine(ruleActive: Boolean, budget: BudgetUse?, budgetExhausted: Boolean): BudgetLine? {
    val allowance = budget?.effectiveMinutes
    return when {
        !ruleActive && allowance != null -> if (allowance > 0) BudgetLine.Used(allowance) else BudgetLine.NoneToday
        budgetExhausted -> BudgetLine.AlsoUsed
        else -> null
    }
}

/** An app the lock leaves usable and that can be opened, with the name the kid reads. */
data class LockApp(val packageName: String, val label: String)

/** One row of the card: the phone book or an app. [label] is also what TalkBack reads (with the
 * row's button role: "Telefonbok, knapp"). */
sealed interface LockRow {
    val label: String

    data class PhoneBook(override val label: String) : LockRow

    data class App(val packageName: String, override val label: String) : LockRow
}

/**
 * The card's rows: the phone book first while calls are managed and the lock allows them (as
 * before P6, when it was its own button), then every usable [apps] entry sorted by its name (QA #6
 * of design 14) - the music app among them under a budget lock. Duplicates by package are dropped.
 */
fun timeLockRows(callsManaged: Boolean, callsAllowed: Boolean, phoneBookLabel: String, apps: List<LockApp>): List<LockRow> {
    val rows = mutableListOf<LockRow>()
    if (callsManaged && callsAllowed) rows += LockRow.PhoneBook(phoneBookLabel)
    apps.distinctBy { it.packageName }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        .mapTo(rows) { LockRow.App(it.packageName, it.label) }
    return rows
}
