package me.vibb.music.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The actor (design 26a §0, QA M2): jobs in order, results on the "main" thread in the actor's order. */
class SonosActorTest {
    private val main = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actor = SonosActor(scope, Dispatchers.IO, post = { block -> main.execute { block() } }, postDelayed = { _, block -> main.execute { block() } })

    @Test
    fun `results arrive in the order the actor ran the jobs, failures included`() {
        val seen = mutableListOf<String>()
        val done = CountDownLatch(3)
        actor.submit({ delay(50); "slow" }) { r -> seen.add(r.getOrThrow()); done.countDown() }
        actor.submit({ throw SpeakerUnreachable("gone") }) { r -> seen.add(r.exceptionOrNull()!!.javaClass.simpleName); done.countDown() }
        actor.submit({ "fast" }) { r -> seen.add(r.getOrThrow()); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("slow", "SpeakerUnreachable", "fast"), seen)
        scope.cancel()
        main.shutdown()
    }

    @Test
    fun `call waits for its job, and its result was posted before the next job's`() = runBlocking {
        val seen = mutableListOf<String>()
        val posted = CountDownLatch(2)
        val r = actor.call({ "poll" }) { seen.add("poll"); posted.countDown() }
        actor.submit({ "play" }) { seen.add("play"); posted.countDown() }
        assertEquals("poll", r.getOrThrow())
        assertTrue(posted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("poll", "play"), seen)
        actor.close()
        scope.cancel()
        main.shutdown()
    }
}
