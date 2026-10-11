package me.vibb.music.sonos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The threading of design 26a §0 (QA M2): vibb's daemon talks to its sidecar over HTTP while the
 * sidecar holds one session lock; here one actor - a Channel consumed by one coroutine on IO - runs
 * every SOAP sequence in order (SonosTransport's mutex inside it), and each result is posted back to
 * the main thread, in the order the actor ran them. Player handlers never wait on SOAP. Android-free:
 * the main thread comes in as [post] and [postDelayed]; SonosActorTest.
 */

/**
 * The actor: [submit]ted jobs run one after the other on [io]; each one's `then` is [post]ed with its
 * result before the next job starts, so the main thread sees results in the actor's order (a poll of
 * the old track can never land after the play that replaced it).
 */
class SonosActor(
    private val scope: CoroutineScope,
    io: CoroutineDispatcher,
    private val post: (() -> Unit) -> Unit,
    private val postDelayed: (Long, () -> Unit) -> Unit,
) : SonosWork {
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val consumer: Job = scope.launch(io) {
        for (job in jobs) job()
    }

    override fun <T> submit(job: suspend () -> T, then: (Result<T>) -> Unit) {
        jobs.trySend {
            val result = run(job)
            post { then(result) }
        }
    }

    /** [job] in the actor, awaited (its `then` is posted first): the poller's pacing. */
    suspend fun <T> call(job: suspend () -> T, then: (Result<T>) -> Unit): Result<T> {
        val done = CompletableDeferred<Result<T>>()
        val sent = jobs.trySend {
            val result = run(job)
            post { then(result) }
            done.complete(result)
        }
        if (sent.isFailure) return Result.failure(CancellationException("actor closed"))
        return done.await()
    }

    override fun after(delayMs: Long, block: () -> Unit) = postDelayed(delayMs, block)

    /** No more jobs; a running one finishes its SOAP (each is bounded by the client's timeouts). */
    fun close() {
        jobs.close()
        consumer.cancel()
    }

    private suspend fun <T> run(job: suspend () -> T): Result<T> = try {
        Result.success(job())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * The poll loop (`poll_loop`, sonosd.py 790-830): one poll through the actor, its snapshot posted to
 * the controller, then wait the cadence the session chose - or less, when a verb or a play [wake]s it
 * (the cached snapshot still says the old state). Unarmed (nothing to poll) it waits for a wake.
 * [onResult] runs on the main thread; a poll that throws never ends the loop.
 */
class SonosPoller(
    private val scope: CoroutineScope,
    private val actor: SonosActor,
    private val poll: suspend () -> Pair<Tick, Fields>,
    private val onResult: (Tick, Fields) -> Unit,
) {
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    fun wake() {
        wakes.trySend(Unit)
    }

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                val r = actor.call(poll) { result -> result.onSuccess { (tick, fields) -> onResult(tick, fields) } }
                if (r.exceptionOrNull() is CancellationException) break
                // a poll that threw is retried at the base cadence; unarmed (Idle, Disarmed) waits for a wake
                val wait = when (val tick = r.getOrNull()?.first) {
                    null -> SonosSession.POLL_MS
                    is Tick.Next -> tick.delayMs
                    else -> null
                }
                if (wait == null) wakes.receive() else withTimeoutOrNull(wait) { wakes.receive() }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }
}
