package com.kidslauncher.mdm.calls

/*
 * Android's own blocked-numbers list (`BlockedNumberContract`). While the phone is managed (either channel, or failing
 * closed) a row is deleted only when it blocks a number the rules need: one of the rules' contacts or the phone's own
 * numbers, or an emergency number ([Emergency.staticSet]), compared after the shared normalisation ([PhoneNumbers]).
 * Every other row - the kid's own spam blocks, e-mail addresses, codes - stays. What was cleared is reported by class
 * only - never the number (`testdata/blocked_numbers.json`, shared with the server). The first sweep after the phone
 * becomes managed clears by the same rule; it is counted on its own, as `initial`, and the server says nothing of it.
 * Pure; the Android side is [BlockedNumbersRuntime], which runs it after every apply, so a number the parent adds as a
 * contact later is cleared then. Not lifted by the pause or the offline override, as the call rules.
 */

/** A cleared entry's class, as the status report counts it (the contract's `classes`, in order). */
enum class BlockedClass(val wire: String) {
    /** A number of one of the rules' contacts, or one of the phone's own numbers. */
    CONTACT("contact"),
    /** An emergency number (112, 911 and the default country's). */
    EMERGENCY("emergency"),
}

/** One row of the list: its id, `original_number` as Android stored it and Android's own `e164_number` (may be empty). */
data class BlockedRow(val id: Long, val number: String?, val e164: String? = null)

/**
 * What to do with the list: the rows to delete with each one's class ([delete] empty while unmanaged), how many stay
 * ([kept]: the kid's own), and whether this is the first sweep since the phone became managed ([initial]: its clears
 * are counted apart, never by class).
 */
data class BlockedPlan(val delete: List<Pair<Long, BlockedClass>>, val kept: Int = 0, val initial: Boolean = false)

/** Whether a blocked row hits the managed rules, and how (`null`: it doesn't - the row stays). Either spelling counts. */
fun blockedClass(row: BlockedRow, rules: CallRules?): BlockedClass? =
    listOf(row.number, row.e164).firstNotNullOfOrNull { blockedClass(it, rules) }

/** One spelling of an entry against the rules (`null`: no match; [rules] `null`, none readable: emergency numbers only). */
fun blockedClass(raw: String?, rules: CallRules?): BlockedClass? {
    val cc = rules?.defaultCc ?: "47"
    val normalized = raw?.takeIf { it.isNotBlank() }?.let { PhoneNumbers.normalize(it, cc) } ?: return null
    val ruleNumbers = rules?.contacts.orEmpty().map { it.number } + rules?.ownNumbers.orEmpty().map { it.number }
    return when {
        normalized.value in ruleNumbers -> BlockedClass.CONTACT
        normalized is Normalized.Short && normalized.value in Emergency.staticSet(cc) -> BlockedClass.EMERGENCY
        else -> null
    }
}

/**
 * Managed: the rows that hit the rules go, each with its class, the rest stay; [wasManaged] false (no sweep has read
 * the list while managed since the phone was last unmanaged, or ever) makes it the initial sweep. Unmanaged: the list
 * is the kid's own and stays.
 */
fun blockedNumbersPlan(rows: List<BlockedRow>, managed: Boolean, wasManaged: Boolean, rules: CallRules?): BlockedPlan {
    if (!managed) return BlockedPlan(emptyList(), kept = rows.size)
    val delete = rows.mapNotNull { row -> blockedClass(row, rules)?.let { row.id to it } }
    return BlockedPlan(delete, kept = rows.size - delete.size, initial = !wasManaged)
}

/**
 * Whether [BlockedNumbersGate] takes Android's "Blocked numbers" page (the owner, 2026-11-24): only while neither channel
 * is "Ikke styrt" - calls and SMS both managed (or off), or the rules unknown (failing closed). Then nothing reaches the
 * kid that they could want to block: unknown numbers are refused and a contact's block is cleared. With either channel
 * unmanaged (or the phone unmanaged) the page is the kid's, to manage their own blocks.
 */
fun blockedGateOn(state: CallPolicyState): Boolean = when (state) {
    CallPolicyState.Unmanaged -> false
    CallPolicyState.UnknownFailClosed -> true
    is CallPolicyState.Managed ->
        state.rules.callsMode != ChannelMode.UNMANAGED && state.rules.smsMode != ChannelMode.UNMANAGED
}

/** The status report's part ([com.kidslauncher.mdm.server.dto.CallState]'s `blocked_*`): counts since the install. */
data class BlockedReport(
    /** The list could be read (Vibb One is the phone or the SMS app). */
    val readable: Boolean,
    /** Rows the rule clears still in the list after the last sweep (a delete failed); `null` when it couldn't be read. */
    val left: Int?,
    /** Cleared after the phone was already managed, by class. */
    val cleared: Map<BlockedClass, Int>,
    /** The last of those clearings, epoch ms; `null` = never. */
    val lastClearedMs: Long?,
    /** Cleared by the first sweep after the phone became managed, all classes together. */
    val clearedInitial: Int = 0,
    /** Rows the rule keeps (the kid's own blocks); `null` when it couldn't be read. */
    val kept: Int? = null,
)
