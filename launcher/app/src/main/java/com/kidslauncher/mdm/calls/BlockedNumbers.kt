package com.kidslauncher.mdm.calls

/*
 * Android's own blocked-numbers list (`BlockedNumberContract`) is cleared while the phone is managed (either channel,
 * or failing closed), and what was cleared is reported by class only - never the number (`testdata/blocked_numbers.json`,
 * shared with the server). The first sweep after the phone becomes managed clears what was there before (the parent's
 * own switch, not something the kid did): it is counted on its own, as `initial`, and the server says nothing of it.
 * Pure; the Android side is [BlockedNumbersRuntime]. Not lifted by the pause or the offline override, as the call rules.
 */

/** A cleared entry's class, as the status report counts it (the contract's `classes`, in order). */
enum class BlockedClass(val wire: String) {
    /** A number of one of the phone's contacts. */
    CONTACT("contact"),
    /** Any other full number. */
    NUMBER("number"),
    /** A short number (3-6 digits). */
    SHORT("short"),
    /** Anything else: an e-mail address, a code, an entry that isn't a number. */
    OTHER("other"),
}

/** One row of the list: its id and `original_number` as Android stored it. */
data class BlockedRow(val id: Long, val number: String?)

/**
 * What to do with the list: the rows to delete with each one's class ([delete] empty while unmanaged), and whether this
 * is the first sweep since the phone became managed ([initial]: its clears are counted apart, never by class).
 */
data class BlockedPlan(val delete: List<Pair<Long, BlockedClass>>, val initial: Boolean = false)

/** The class of a blocked entry against the managed rules (`null`: none readable, so no contact matches). */
fun blockedClass(raw: String?, rules: CallRules?): BlockedClass {
    val normalized = raw?.let { PhoneNumbers.normalize(it, rules?.defaultCc ?: "47") } ?: return BlockedClass.OTHER
    return when (normalized) {
        is Normalized.Short -> BlockedClass.SHORT
        is Normalized.E164 ->
            if (rules?.contacts?.any { it.number == normalized.value } == true) BlockedClass.CONTACT else BlockedClass.NUMBER
    }
}

/**
 * Managed: every row goes, each with its class; [wasManaged] false (no sweep has read the list while managed since the
 * phone was last unmanaged, or ever) makes it the initial sweep. Unmanaged: the list is the kid's own and stays.
 */
fun blockedNumbersPlan(rows: List<BlockedRow>, managed: Boolean, wasManaged: Boolean, rules: CallRules?): BlockedPlan =
    if (!managed) {
        BlockedPlan(emptyList())
    } else {
        BlockedPlan(rows.map { it.id to blockedClass(it.number, rules) }, initial = !wasManaged)
    }

/** The status report's part ([com.kidslauncher.mdm.server.dto.CallState]'s `blocked_*`): counts since the install. */
data class BlockedReport(
    /** The list could be read (Vibb One is the phone or the SMS app). */
    val readable: Boolean,
    /** Rows still in the list after the last sweep; `null` when it couldn't be read. */
    val left: Int?,
    /** Cleared after the phone was already managed, by class. */
    val cleared: Map<BlockedClass, Int>,
    /** The last of those clearings, epoch ms; `null` = never. */
    val lastClearedMs: Long?,
    /** Cleared by the first sweep after the phone became managed, all classes together. */
    val clearedInitial: Int = 0,
)
