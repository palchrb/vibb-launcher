package com.kidslauncher.mdm.sms

/*
 * Meldinger under a time rule without calls (the user, 2026-11-20: "da skal jo telefonen være veldig dum"): a message or
 * a reaction is stored and unread as before, but no notification is posted while the rule is in force - its
 * conversation waits (CE prefs, `SmsPrefs.ruleWaiting`; not D16's messages held for the parent, which never make a
 * conversation). When calls are allowed again (the rule's end, a lift, a pause, the override) each waiting conversation
 * is posted once, quietly: the silent channel, no sound, no buzz, no heads-up, no lock wake
 * (`SmsRuntime.releaseAfterRule`). Pure, SmsRuleWaitTest.
 */

/** How an accepted message's (or a reaction's) notification goes out. */
enum class SmsPost {
    /** As always: the message channel (a reaction's own), with the wake and the buzz the lock decides. */
    ALERT,

    /** Posted on the silent channel without a wake (a picture told again). */
    QUIET,

    /** Not posted: the conversation waits until calls are allowed again. */
    WAIT,
}

/** [callsBlocked]: a rule without calls is in force now (`TimeRulesStore.callsBlockedNow`, fail closed); [again]: a
 * waiting picture the kid was told of once. */
fun smsPost(callsBlocked: Boolean, again: Boolean): SmsPost = when {
    callsBlocked -> SmsPost.WAIT
    again -> SmsPost.QUIET
    else -> SmsPost.ALERT
}

/** What waits after [conversationIds] wait too (a message's own, and the one a group MMS left - its notification is
 * rebuilt at the release, not under the rule). */
fun smsWaitingAfter(waiting: Set<String>, conversationIds: Collection<String?>): Set<String> = waiting + conversationIds.filterNotNull()

/**
 * The conversations posted at a release, or none: only once calls are allowed ([callsBlocked] false); the thread in
 * front ([inFront]) reads its own messages and is left out. Each is posted once, quietly, from what is still unread
 * there (or its notification goes when nothing is).
 */
fun smsReleased(waiting: Set<String>, callsBlocked: Boolean, inFront: String?): List<String> =
    if (callsBlocked) emptyList() else waiting.filter { it != inFront }.sorted()
