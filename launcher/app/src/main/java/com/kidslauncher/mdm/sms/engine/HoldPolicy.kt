package com.kidslauncher.mdm.sms.engine

/*
 * Design 77 D15/D16: what the phone keeps of a message it doesn't show, and for how long. Pure, tested in
 * SmsEngineTest. A flood never pushes out a real message, and a name flood never takes the numbers' places (QA R2-M4):
 * past a cap the *new* message's body is not kept, and nothing held is evicted.
 */
object HoldPolicy {
    /** D16: the window from the phone's receipt; the parent's allow must come within it. */
    const val WINDOW_MS = 24 * 60 * 60 * 1000L

    /** D16's two pools: held messages from numbers, and from sender names (names are free to invent). */
    const val NUMBERS_POOL = 30
    const val NAMES_POOL = 20

    /** D16: per sender - one number, one sender name (by the fold). */
    const val PER_NUMBER = 10
    const val PER_NAME = 3

    /** D16: an SMS body kept for the parent's decision, at most ten parts; longer is cut and marked. */
    const val BODY_MAX = 1600

    /** D15: messages held while the rules can't be judged. */
    const val RULES_MAX = 200

    /** §6.6: blocked rows kept; past it only the count per class (the cumulative counters). */
    const val BLOCKED_MAX = 500

    /** §6.6: blocked rows go after 30 days, or once the server has them. */
    const val BLOCKED_KEEP_MS = 30 * 24 * 60 * 60 * 1000L

    /** The held rows a new message from [sender] of [kind] meets: [held] are the rows still held. */
    fun admits(kind: SenderKind, sender: String, held: List<HeldRow>): Boolean {
        val pool = held.filter { it.kind == kind }
        return when (kind) {
            SenderKind.NUMBER -> pool.size < NUMBERS_POOL && pool.count { it.sender == sender } < PER_NUMBER
            SenderKind.ALPHANUMERIC -> {
                val folded = SmsNames.fold(sender)
                pool.size < NAMES_POOL && pool.count { SmsNames.fold(it.sender) == folded } < PER_NAME
            }
            SenderKind.MALFORMED -> false
        }
    }

    /** `hold_full` in `sms_state`: whether each pool is full now. */
    fun full(held: List<HeldRow>): Pair<Boolean, Boolean> =
        (held.count { it.kind == SenderKind.NUMBER } >= NUMBERS_POOL) to (held.count { it.kind == SenderKind.ALPHANUMERIC } >= NAMES_POOL)

    /** D16: the body kept, at most [BODY_MAX] characters ("…" marks a cut). */
    fun keptBody(body: String): String {
        if (body.length <= BODY_MAX) return body
        // Never half a surrogate pair.
        val end = if (Character.isHighSurrogate(body[BODY_MAX - 2])) BODY_MAX - 2 else BODY_MAX - 1
        return body.substring(0, end) + "…"
    }

    /** Whether a held row has run out at [now] (the clock is locked while managed, D16). */
    fun expired(row: HeldRow, now: Long): Boolean = now >= row.expiresAt
}
