package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.CachedPolicy
import kotlinx.serialization.Serializable

/*
 * The emergency menu (design 73) - pure Kotlin, tested in EmergencyMenuTest. The parent's list arrives as
 * `call_policy.emergency_menu` ([com.kidslauncher.mdm.server.dto.PolicyEmergencyEntry]); every "Nødsamtale" shows
 * it through [EmergencyCall.confirm]: one number is today's "Call 112?" dialog, more are the sheet, where one tap
 * calls. Only a real emergency number ([Emergency.isEmergencyOutgoing]: the platform's answer ORed with the fixed
 * list, `emergency_numbers.json`) passes the call rules and the locks; any other number is decided exactly like a
 * phone-book call ([decideOutgoing] with `isEmergency = false`) - no new way past the rules.
 */

/** One number on the menu: [number] normalised by the server, [name] the parent's (may be empty). */
@Serializable
data class EmergencyEntry(val number: String = "", val name: String = "")

/** At most this many numbers (the server's `emergency::MAX_ENTRIES`), so the sheet fits the screen. */
const val EMERGENCY_MENU_MAX = 6

/** A name's length on the sheet (the server's `MAX_NAME_CHARS`). */
const val EMERGENCY_NAME_MAX = 40

/** What a phone without a menu - an older server, an unreadable cache - offers: 112 alone, today's dialog. */
val DEFAULT_EMERGENCY_MENU = listOf(EmergencyEntry(EmergencyCall.NUMBER))

/**
 * The menu to show, from what the server sent ([raw], `null` = nothing): numbers that don't normalise dropped,
 * duplicates and anything past [EMERGENCY_MENU_MAX] dropped, names trimmed and clipped. Never without a real
 * emergency number: a list with none (a broken server) gets 112 first - so `null` or `[]` reads as `[112]`.
 */
fun emergencyMenu(raw: List<EmergencyEntry>?, defaultCc: String, isEmergency: (String) -> Boolean): List<EmergencyEntry> {
    val seen = mutableSetOf<String>()
    val clean = raw.orEmpty().mapNotNull { entry ->
        val number = PhoneNumbers.normalize(entry.number, defaultCc)?.value ?: return@mapNotNull null
        if (seen.add(number)) EmergencyEntry(number, entry.name.trim().take(EMERGENCY_NAME_MAX)) else null
    }.take(EMERGENCY_MENU_MAX)
    if (clean.any { isEmergency(it.number) }) return clean
    return (DEFAULT_EMERGENCY_MENU + clean.filter { it.number != EmergencyCall.NUMBER }).take(EMERGENCY_MENU_MAX)
}

/** The list the store keeps from one CE read: the cached policy's, else the one stored with the last managed rules
 * (a cache that can't be read keeps the parent's numbers), else `null` (the default). */
fun storedEmergencyMenu(cached: CachedPolicy, lastRules: CallRules?): List<EmergencyEntry>? =
    (cached as? CachedPolicy.Ok)?.policy?.callPolicy?.emergencyMenu?.map { EmergencyEntry(it.number, it.name) }
        ?: lastRules?.emergencyMenu

enum class EmergencyRowKind {
    /** A real emergency number: always callable, Telecom else the emergency dialer. */
    EMERGENCY,

    /** Any other number the call rules allow right now. */
    ALLOWED,

    /** Any other number the call rules block now (dimmed, not callable). */
    BLOCKED,
}

/** One row of the sheet; [big] is the large red one. */
data class EmergencyRow(val entry: EmergencyEntry, val kind: EmergencyRowKind, val big: Boolean)

/**
 * The sheet's rows: the first real emergency number of [menu] as the large row, on top, then the others in the
 * parent's order (a non-emergency number moved first is never the big red row). [state] is what the call path
 * enforces now (`CallPolicyStore.effectiveState()`: a no-calls time rule included); [busy] = another call is live
 * ([secondCallAllowed] says no to a non-emergency number).
 */
fun emergencyRows(
    menu: List<EmergencyEntry>,
    state: CallPolicyState,
    busy: Boolean,
    isEmergency: (String) -> Boolean,
): List<EmergencyRow> {
    val bigAt = menu.indexOfFirst { isEmergency(it.number) }
    val ordered = if (bigAt <= 0) menu else listOf(menu[bigAt]) + menu.filterIndexed { i, _ -> i != bigAt }
    return ordered.mapIndexed { i, entry ->
        val kind = when {
            isEmergency(entry.number) -> EmergencyRowKind.EMERGENCY
            busy -> EmergencyRowKind.BLOCKED
            decideOutgoing(entry.number, state, isEmergency = false) == Verdict.ALLOW -> EmergencyRowKind.ALLOWED
            else -> EmergencyRowKind.BLOCKED
        }
        EmergencyRow(entry, kind, big = i == 0 && bigAt >= 0)
    }
}

/** What Nødsamtale shows: one number is today's confirm dialog, more the sheet. */
sealed interface EmergencyMenuShape {
    data class Dialog(val row: EmergencyRow) : EmergencyMenuShape
    data class Sheet(val rows: List<EmergencyRow>) : EmergencyMenuShape
}

fun emergencyMenuShape(rows: List<EmergencyRow>): EmergencyMenuShape =
    if (rows.size == 1) EmergencyMenuShape.Dialog(rows[0]) else EmergencyMenuShape.Sheet(rows)

/** What a tap on a row does: the sheet is the confirm, so a tap calls - or nothing, on a blocked row. */
sealed interface EmergencyTap {
    data object Nothing : EmergencyTap

    /** Telecom, else the platform's emergency dialer ([EmergencyCall.callEmergency]). */
    data class Emergency(val number: String) : EmergencyTap

    /** Telecom only, like a phone-book call: our redirection and in-call services check it again. */
    data class ByRules(val number: String) : EmergencyTap
}

fun emergencyTap(row: EmergencyRow): EmergencyTap = when (row.kind) {
    EmergencyRowKind.EMERGENCY -> EmergencyTap.Emergency(row.entry.number)
    EmergencyRowKind.ALLOWED -> EmergencyTap.ByRules(row.entry.number)
    EmergencyRowKind.BLOCKED -> EmergencyTap.Nothing
}

/** A row's words: [number] in the number column (a short number only - an E.164 one is too long), [label] the
 * name, else [defaultName] for an emergency number, else the long number. */
data class EmergencyRowText(val number: String?, val label: String)

fun emergencyRowText(row: EmergencyRow, defaultName: String): EmergencyRowText {
    val number = row.entry.number
    val short = !number.startsWith("+")
    val label = row.entry.name.ifEmpty {
        when {
            row.kind == EmergencyRowKind.EMERGENCY -> defaultName
            short -> ""
            else -> number
        }
    }
    return EmergencyRowText(if (short) number else null, label)
}
