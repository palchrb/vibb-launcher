package me.vibb.music.logic

/*
 * The switch to what the kid tapped (a cover, a Nytt row, a search hit, a pick from the list,
 * next/previous). The screens open the player at once, but the player service publishes the new
 * item a moment later; until then the screens show the request, not the item still loaded (the old
 * podcast's cover flashed). Pure; SwitchTest.
 */

/** An item of an entry, as the screens show it playing. */
data class ItemRef(val entryId: Long, val itemKey: String)

/**
 * A tap that starts [entryId] ([itemKey]: that item; null: whatever a cover tap starts), made while
 * the screens showed [from] as playing (null: nothing), at [atMs] (`elapsedRealtime`).
 */
data class PlayRequest(val entryId: Long, val itemKey: String?, val from: ItemRef?, val atMs: Long)

/** The request is shown at most this long: the player refused it, or it never came. */
const val SWITCH_HOLD_MS = 3_000L

/**
 * The screens show [request] rather than [current] (what the player says plays): while the player
 * still shows what it showed at the tap and that isn't already what was asked for (a tap on the
 * entry or item already loaded), for at most [SWITCH_HOLD_MS]. As soon as the player moves - to the
 * request, or elsewhere (an item it couldn't play skipped) - the player is shown again.
 */
fun requestStands(request: PlayRequest?, current: ItemRef?, nowMs: Long): Boolean {
    request ?: return false
    if (nowMs - request.atMs !in 0 until SWITCH_HOLD_MS) return false
    if (current != request.from) return false
    val reached = current != null && current.entryId == request.entryId && (request.itemKey == null || request.itemKey == current.itemKey)
    return !reached
}

/**
 * What a cover tap on an entry starts, as far as the screens know it ([coverTapPlan]): (the item's
 * key, its start position); the key is null when shuffle picks the item. Null when nothing plays.
 */
fun coverTapStart(order: List<String>, resume: Boolean, bookmark: Bookmark?, shuffle: Boolean): Pair<String?, Long>? {
    if (order.isEmpty()) return null
    if (shuffle && !resume) return null to 0L
    val plan = coverTapPlan(order, resume, bookmark, shuffle = false) ?: return null
    return plan.keys[plan.startIndex] to plan.startPosMs
}

/** The screens' position lags the player's (a tick); previous counts on it being this much further. */
const val SKIP_POSITION_SLACK_MS = 1_000L

/**
 * The queue index next ([forward]) or previous goes to from [index] of [count], [posMs] into an
 * item of [durationMs]; null when it stays on the item (a restart, a one-item queue) or when
 * previous is too near the restart point to know.
 */
fun skipTarget(forward: Boolean, index: Int, count: Int, posMs: Long, durationMs: Long?): Int? {
    val action = if (forward) nextAction(index, count) else previousAction(index, count, posMs + SKIP_POSITION_SLACK_MS, durationMs)
    return (action as? TransportAction.GoTo)?.index
}
