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

    @Test
    fun `close drains - a Stop queued before the release still goes out`() {
        val ran = CountDownLatch(1)
        actor.submit({ delay(100); "busy" }) { }
        actor.submit({ ran.countDown(); "stop" }) { }
        actor.close()
        assertTrue("the queued job ran after close", ran.await(5, TimeUnit.SECONDS))
        main.shutdown()
    }

    @Test
    fun `a job behind a slow mint waits for it - never cancelled - and a dropped or failed play leaves the consumer running`() = runBlocking {
        // Design 26b §5.1 (the merge's 4b list): the room's actor is serial and never cancels a job, so a poll
        // queued behind a Storytel play waits for its mint; a play its token dropped (null) or whose mint
        // failed (StorytelUnavailable) is a result like any other - the loop goes on.
        val t0 = System.nanoTime()
        actor.submit({ delay(300); null as PlayResult? }) { }
        actor.submit<PlayResult?>({ throw StorytelUnavailable("network") }) { r -> assertTrue(r.exceptionOrNull() is StorytelUnavailable) }
        val poll = actor.call({ "poll" }) { }
        assertEquals("poll", poll.getOrThrow())
        assertTrue("the poll waited for the mint", (System.nanoTime() - t0) / 1_000_000 >= 300)
        assertEquals("still alive", "next", actor.call({ "next" }) { }.getOrThrow())
        actor.close()
        scope.cancel()
        main.shutdown()
    }
}
