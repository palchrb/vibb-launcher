package me.vibb.music.sonos

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The full stop a destroyed player service still runs on its room (design 26a §6.2): process-wide,
 * so the suspension's standalone path and the next service's adopt wait for it instead of racing it
 * on the same speaker with a second transport and a second renderer.json writer
 * (qa-26a-step3b-code L2). Bounded: the tail itself ends within the hand-off's budget.
 */
object SonosTail {
    @Volatile
    private var job: Job? = null

    fun started(tail: Job) {
        job = tail
    }

    /** Wait for a tail still running, at most [maxMs]. */
    suspend fun join(maxMs: Long = MAX_MS) {
        val tail = job?.takeIf { it.isActive } ?: return
        withTimeoutOrNull(maxMs) { tail.join() }
    }

    /** The service's hand-off budget (`PlaybackService.HANDOFF_MS`, 25 s since design 26b's mint) and the actor's last post. */
    const val MAX_MS = 26_000L
}
