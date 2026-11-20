package me.vibb.music.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 120 option B (revision 2, the design QA's H1, M3, M4, L4, L6, L8): the web player's own process as the renderer's
 * [SpotifyHost], against a fake binder glue ([WebConnector]) and a virtual clock - the binder itself can't run on the JVM,
 * so the glue is thin and this is the logic: calls queue until the process is connected, a new process is bound only once
 * the old one has ended (or been ended), an old process's events never reach the new page, an unasked death is one
 * `Gone`, a bind that never came up is none, a process that had a page is refused once more, and no log line carries a
 * call's argument.
 */
class RemoteSpotifyHostTest {
    private var now = 0L
    private var seq = 0L

    private class Task(val at: Long, val seq: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    private fun after(delayMs: Long, block: () -> Unit): () -> Unit {
        val t = Task(now + delayMs, seq++, block)
        tasks += t
        return { t.cancelled = true }
    }

    private fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            tasks.removeAll { it.cancelled }
            val next = tasks.filter { it.at <= target }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = maxOf(now, next.at)
            next.block()
        }
        now = target
    }

    private inner class Proc(val sink: WebBindingSink) {
        var running = true
        var bound = true
        val calls = mutableListOf<String>()
        val link = object : WebLink {
            override fun open(fakeBase: String?, clearFirst: Boolean) {
                calls += "open clear=$clearFirst base=$fakeBase"
            }

            override fun token(requestId: Int, token: String?) {
                calls += "token $requestId ${if (token == null) "none" else "set"}"
            }

            override fun seek(positionMs: Long) {
                calls += "seek $positionMs"
            }

            override fun volume(level: Double) {
                calls += "volume $level"
            }

            override fun command(name: String) {
                calls += name
            }

            override fun destroy() {
                calls += "destroy"
            }
        }

        fun connect() = sink.connected(link)

        /** The process ends (by itself or killed): Android's death notice. */
        fun end() {
            running = false
            sink.died()
        }
    }

    private inner class Connector : WebConnector {
        val procs = mutableListOf<Proc>()
        var refuse = false
        var kills = 0

        override fun bind(sink: WebBindingSink): WebBinding? {
            if (refuse) return null
            val p = Proc(sink)
            procs += p
            return WebBinding { p.bound = false }
        }

        override fun webRunning(): Boolean = procs.any { it.running }

        override fun kill() {
            kills++
            procs.filter { it.running }.forEach { it.end() }
        }
    }

    private val connector = Connector()
    private val log = mutableListOf<String>()
    private val events = mutableListOf<HostEvent>()
    private var base: String? = null
    private val host = RemoteSpotifyHost(connector, ::after, { base }, { log += it }).also { h -> h.events = { events += it } }

    private fun proc(i: Int) = connector.procs[i]

    @Test
    fun `alive from open, calls queue in order until connected, the page's open first - typed calls only, no argument logged`() {
        assertFalse(host.alive)
        host.open()
        assertTrue("M4: alive from open", host.alive)
        host.call("volume", 1.0)
        host.call("token", 3, "FAKE-ACCESS-1")
        host.call("seek", 42_000L)
        host.call("pause")
        host.call("connect", "x") // the web side's own: never sent from here
        host.call("token", "not an id", "FAKE-ACCESS-2")
        assertEquals(1, connector.procs.size)
        assertTrue(proc(0).calls.isEmpty())
        proc(0).connect()
        assertEquals(listOf("open clear=false base=null", "volume 1.0", "token 3 set", "seek 42000", "pause"), proc(0).calls)
        host.call("resume")
        host.call("token", 4, null)
        assertEquals(listOf("resume", "token 4 none"), proc(0).calls.takeLast(2))
        assertFalse("the token is in no log line", log.any { it.contains("FAKE-ACCESS") })
    }

    @Test
    fun `the debug base and a clear go with the next page's open (QA M3)`() {
        var cleared = false
        host.clearStorage { cleared = true }
        advance(0)
        assertTrue("the renderer's wait ends at once", cleared)
        base = "http://10.0.2.2:8731/"
        host.open()
        proc(0).connect()
        assertEquals("open clear=true base=http://10.0.2.2:8731/", proc(0).calls.first())
        host.destroy()
        proc(0).end()
        advance(RemoteSpotifyHost.EXIT_POLL_MS)
        host.open()
        proc(1).connect()
        assertEquals("one clear only", "open clear=false base=http://10.0.2.2:8731/", proc(1).calls.first())
    }

    @Test
    fun `QA H1 - a destroy unbinds, the next page waits for the old process to end, and its late events are dropped`() {
        host.open()
        proc(0).connect()
        proc(0).sink.signal(WebSignal.Page(HostEvent.Ready("dev1")))
        assertEquals(listOf<HostEvent>(HostEvent.Ready("dev1")), events)
        host.destroy()
        assertFalse(host.alive)
        assertEquals("destroy", proc(0).calls.last())
        assertFalse("unbound at once: Android never restarts it", proc(0).bound)
        proc(0).sink.signal(WebSignal.Page(HostEvent.NotReady("dev1")))
        assertEquals("an old page's event is dropped", 1, events.size)
        proc(0).sink.signal(WebSignal.Log("spotify: web player destroyed"))
        assertEquals("its log lines still come", "spotify: web player destroyed", log.last())
        host.open()
        host.call("pause")
        assertTrue(host.alive)
        advance(RemoteSpotifyHost.EXIT_POLL_MS * 4)
        assertEquals("no bind into the dying process", 1, connector.procs.size)
        proc(0).end()
        assertEquals("its death was asked: silent", 1, events.size)
        assertEquals("bound once it ended", 2, connector.procs.size)
        proc(1).connect()
        assertEquals(listOf("open clear=false base=null", "pause"), proc(1).calls)
        proc(1).sink.signal(WebSignal.Page(HostEvent.Ready("dev2")))
        assertEquals(HostEvent.Ready("dev2"), events.last())
    }

    @Test
    fun `QA H1 - a process that outlives its destroy is ended by the main process, then the next is bound`() {
        host.open()
        proc(0).connect()
        host.destroy()
        host.open()
        advance(RemoteSpotifyHost.EXIT_WAIT_MS - RemoteSpotifyHost.EXIT_POLL_MS)
        assertEquals(0, connector.kills)
        assertEquals(1, connector.procs.size)
        advance(RemoteSpotifyHost.EXIT_POLL_MS)
        assertEquals(1, connector.kills)
        assertEquals(2, connector.procs.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an unasked death is one Gone and unbinds at once - a bind that never came up is none (QA M4)`() {
        host.open()
        proc(0).connect()
        proc(0).end()
        assertEquals(listOf<HostEvent>(HostEvent.Gone), events)
        assertFalse(host.alive)
        assertFalse(proc(0).bound)
        assertTrue(log.contains("spotify: the web player's process ended"))

        host.open()
        advance(RemoteSpotifyHost.EXIT_POLL_MS)
        proc(1).end() // a null binding, or the process died before it was connected
        assertEquals("no Gone: R1's wait says trenger internett", 1, events.size)
        assertFalse(proc(1).bound)
        host.destroy() // the renderer's R1 path: nothing to end
        advance(RemoteSpotifyHost.EXIT_POLL_MS)

        connector.refuse = true
        host.open()
        assertTrue("a refused bind: no ready, no Gone", host.alive)
        host.call("pause")
        assertEquals(1, events.size)
        host.destroy()
        assertFalse(host.alive)
    }

    @Test
    fun `QA L8 - the renderer dying in the web process is one Gone, and that process's death is then silent`() {
        host.open()
        proc(0).connect()
        proc(0).sink.signal(WebSignal.Gone)
        assertEquals(listOf<HostEvent>(HostEvent.Gone), events)
        assertFalse(host.alive)
        assertFalse(proc(0).bound)
        proc(0).end()
        assertEquals(1, events.size)
    }

    @Test
    fun `QA H1 4 - a process that had a page refuses another, and a fresh one is bound once it has ended - once`() {
        host.open()
        proc(0).connect()
        proc(0).sink.signal(WebSignal.Stale)
        assertTrue(host.alive)
        assertFalse(proc(0).bound)
        proc(0).end()
        assertEquals(2, connector.procs.size)
        proc(1).connect()
        assertEquals("open clear=false base=null", proc(1).calls.single())
        proc(1).sink.signal(WebSignal.Stale)
        proc(1).end()
        assertEquals("only once more", 2, connector.procs.size)
        assertTrue(events.isEmpty())
    }
}
