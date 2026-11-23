package com.kidslauncher.mdm.calls

/*
 * Android's own blocked-numbers list (`BlockedNumberContract`): Telecom rejects a listed caller's calls and texts
 * before Vibb One's screening sees them, so a number blocked in Android's settings would silently cut a parent off.
 * While the phone is managed (either channel, or failing closed) Vibb One clears the list and reports what it cleared
 * by class only - never the number (`testdata/blocked_numbers.json`, shared with the server). Pure; the Android side is
 * [BlockedNumbersRuntime]. Not lifted by the pause or the offline override: the call rules never are.
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

/** What to do with the list: the rows to delete with each one's class ([delete] empty while unmanaged). */
data class BlockedPlan(val delete: List<Pair<Long, BlockedClass>>)

/** The class of a blocked entry against the managed rules (`null`: none readable, so no contact matches). */
fun blockedClass(raw: String?, rules: CallRules?): BlockedClass {
    val normalized = raw?.let { PhoneNumbers.normalize(it, rules?.defaultCc ?: "47") } ?: return BlockedClass.OTHER
    return when (normalized) {
        is Normalized.Short -> BlockedClass.SHORT
        is Normalized.E164 ->
            if (rules?.contacts?.any { it.number == normalized.value } == true) BlockedClass.CONTACT else BlockedClass.NUMBER
    }
}

/** Managed: every row goes, each with its class. Unmanaged: the list is the kid's own and stays. */
fun blockedNumbersPlan(rows: List<BlockedRow>, managed: Boolean, rules: CallRules?): BlockedPlan =
    if (!managed) BlockedPlan(emptyList()) else BlockedPlan(rows.map { it.id to blockedClass(it.number, rules) })

/** The status report's part ([com.kidslauncher.mdm.server.dto.CallState]'s `blocked_*`): counts since the install. */
data class BlockedReport(
    /** The list could be read (Vibb One is the phone or the SMS app). */
    val readable: Boolean,
    /** Rows still in the list after the last sweep; `null` when it couldn't be read. */
    val left: Int?,
    val cleared: Map<BlockedClass, Int>,
    /** The last clearing, epoch ms; `null` = never. */
    val lastClearedMs: Long?,
)
