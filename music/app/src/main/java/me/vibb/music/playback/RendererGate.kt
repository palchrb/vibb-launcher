package me.vibb.music.playback

import kotlinx.coroutines.CompletableDeferred
import me.vibb.music.sonos.RendererChoice

/**
 * Who plays is settled (design 26a §4.7, QA H5): every custom command and playback resumption waits
 * for it, so a RESUME after a restart can never start the phone under a playing speaker (vibb's
 * double-playback trap, `sonos_renderer.py` check 2). Settled at once on the phone, after the look at
 * the stored room otherwise ([startupLook]). Android-free; RendererGateTest.
 */
class RendererGate {
    private val ready = CompletableDeferred<Unit>()

    val settled: Boolean get() = ready.isCompleted

    fun settle() {
        ready.complete(Unit)
    }

    suspend fun await() {
        ready.await()
    }
}

/** The start looks at a room first only when renderer.json names one (anything else is the phone). */
fun startupLook(choice: RendererChoice): Boolean = choice.sonos
