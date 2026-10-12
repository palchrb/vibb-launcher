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
 * ... unless the player service still has the tap's command waiting (`PlayerHub.State.waiting`):
 * behind the renderer's settle at start, a room switch's hand-off (the old room's bookmark and Stop,
 * `HANDOFF_MS` 15 s) or the look at a recorded room before a play (design 26a §4.6-§4.7). A Sonos
 * play takes seconds there, and the hold mustn't run out into the old item meanwhile; once the
 * command has run the plain hold applies again. The longest wait is at a fresh service start: the
 * adopt waits for the destroyed service's hand-off (`SonosTail.MAX_MS`, 16 s), then looks at the room
 * (`ADOPT_MS`, 4 s), then the play loads its queue - so 25 s (qa-26a-merge-code L2). Bounded, so a
 * service that never answers can't hold the screens.
 */
const val SWITCH_WAIT_MAX_MS = 25_000L

/** When a request held from [PlayRequest.atMs] stops standing by itself; [waiting]: [SWITCH_WAIT_MAX_MS]. */
fun holdEndsAt(request: PlayRequest, waiting: Boolean): Long = request.atMs + if (waiting) SWITCH_WAIT_MAX_MS else SWITCH_HOLD_MS

/**
 * The screens show [request] rather than [current] (what the player says plays): while the player
 * still shows what it showed at the tap and that isn't already what was asked for (a tap on the
 * entry or item already loaded), for at most [SWITCH_HOLD_MS] - or [SWITCH_WAIT_MAX_MS] while the
 * service still has the command [waiting] ([holdEndsAt]). As soon as the player moves - to the
 * request, or elsewhere (an item it couldn't play skipped) - the player is shown again.
 */
fun requestStands(request: PlayRequest?, current: ItemRef?, nowMs: Long, waiting: Boolean = false): Boolean {
    request ?: return false
    if (nowMs < request.atMs || nowMs >= holdEndsAt(request, waiting)) return false
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
