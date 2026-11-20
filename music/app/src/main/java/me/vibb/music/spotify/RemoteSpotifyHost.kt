package me.vibb.music.spotify

/*
 * The web player in its own process (design 120 option B, §4.2 with the design QA's H1, M3, M4, L3, L4, L6, L8;
 * revision 2): the renderer keeps talking to a [SpotifyHost] and doesn't know the page lives in `:web`
 * ([SpotifyWebService]). Every piece of state a page depends on below its JavaScript - WebView's in-process network
 * service, its network-change state, the protected-media deferrer, Chromium's audio sink - is the process's, so a page
 * the renderer throws away goes with its process and the next one starts in a fresh one, as a force stop did (design
 * 120 §1 fact 4; 2026-11-20, the first Bluetooth pairing on the Jelly Star).
 *
 * The lifecycle (QA H1): one page per process, and a new process only once the old one has ended -
 * - [open] binds a process (asked to make its page at once: the stores cleared first after [clearStorage]); calls made
 *   before it is connected queue in order (M4: [alive] is true from [open]), a token among them dropped with the page;
 * - [destroy] sends the page's destroy (the SDK's disconnect, then the process ends itself), unbinds, and waits for the
 *   process to be gone before any new bind - a bind meanwhile would land in the dying process. Past [EXIT_WAIT_MS] the
 *   main process ends it itself ([WebConnector.kill]: our own uid's process), and past [EXIT_GIVE_UP_MS] the next bind
 *   goes anyway (the web side refuses a second page: [WebSignal.Stale], one bind more);
 * - a death nobody asked for unbinds at once (Android would restart a bound service in an empty process) and is
 *   [HostEvent.Gone] - "Stoppet - trykk spill" - when the page was up; a bind that never came up is no `ready` (R1's
 *   20 s wait, "Trenger internett", the next tap a fresh process), never Gone;
 * - the page's renderer dying in `:web` (L8) is one Gone: the event, and that process's death is then an asked one.
 *
 * Events of an old page never reach a new one: each binding has its own sink, and only the current page's pass.
 * Pure (no Android): the binder glue is [WebConnector] (SpotifyWebService.kt's [AndroidWebConnector]), the clock
 * [after]; RemoteSpotifyHostTest drives it. Main thread only. No log line carries an argument of a call (the token).
 */

/** One bound web process as the main side talks to it (main -> web; all one-way). */
internal interface WebLink {
    fun open(fakeBase: String?, clearFirst: Boolean)
    fun token(requestId: Int, token: String?)
    fun seek(positionMs: Long)
    fun volume(level: Double)
    fun command(name: String)
    fun destroy()
}

/** What a web process says, on the main thread. */
internal sealed interface WebSignal {
    /** The process's pid. */
    data class Attached(val pid: Int) : WebSignal

    /** A page event (`ready`, `notReady`, `needToken`, `state`, `error` - checked in the web process, parsed here). */
    data class Page(val event: HostEvent) : WebSignal

    /** One of the web process's fixed log lines. */
    data class Log(val line: String) : WebSignal

    /** The page's renderer died there; the process ends itself. */
    data object Gone : WebSignal

    /** That process already had a page and refused this one. */
    data object Stale : WebSignal
}

/** One binding's callbacks, on the main thread. */
internal interface WebBindingSink {
    fun connected(link: WebLink)
    fun signal(s: WebSignal)

    /** The process died, the binding died, or it never bound (a null binding). */
    fun died()
}

internal fun interface WebBinding {
    fun unbind()
}

/** The binder glue (SpotifyWebService.kt's [AndroidWebConnector]). */
internal interface WebConnector {
    /** Bind a web process for [sink]; null when Android refused the bind. */
    fun bind(sink: WebBindingSink): WebBinding?

    /** A web process is running (Android still lists it). */
    fun webRunning(): Boolean

    /** End the web process (our own uid's), if one runs. */
    fun kill()
}

internal class RemoteSpotifyHost(
    private val connector: WebConnector,
    /** The main thread's timer: run [block] after the delay; the returned function cancels it. */
    private val after: (Long, () -> Unit) -> () -> Unit,
    /** The debug switch's base for the web process (`SpotifyEndpoints.webBase()`: null in a release). */
    private val fakeBase: () -> String?,
    private val log: (String) -> Unit,
) : SpotifyHost {
    override var events: ((HostEvent) -> Unit)? = null
    override val alive: Boolean get() = page != null

    private inner class Conn {
        var binding: WebBinding? = null
        var link: WebLink? = null
        var clearFirst = false

        /** Calls made before the link came, in order. */
        val queue = ArrayList<(WebLink) -> Unit>()

        /** Its process ended (or the bind failed). */
        var dead = false

        /** We asked it to end: its death is silent. */
        var asked = false

        /** Binds after a [WebSignal.Stale]. */
        var rebinds = 0

        /** The current binding's sink: an earlier binding's (a refused process's) callbacks aren't this page's. */
        var sink: Sink? = null
    }

    /** One binding of [c]: its callbacks reach [c] only while it is [c]'s current one. */
    private inner class Sink(private val c: Conn) : WebBindingSink {
        private val current: Boolean get() = c.sink === this

        override fun connected(link: WebLink) {
            if (current) onConnected(c, link) else link.destroy()
        }

        override fun signal(s: WebSignal) {
            if (current || s is WebSignal.Log) onSignal(c, s)
        }

        override fun died() {
            if (current) onDied(c) else if (exitWait) checkExit()
        }
    }

    private var page: Conn? = null
    private var clearNext = false

    /** Waiting for an old web process to end before the next bind; the polls since the wait began. */
    private var exitWait = false
    private var exitPolls = 0
    private var exitTimer: (() -> Unit)? = null

    override fun open() {
        if (page != null) return
        val c = Conn()
        c.clearFirst = clearNext
        clearNext = false
        page = c
        if (exitWait) log("spotify: the old web player's process is still ending - the new one waits") else bind(c)
    }

    override fun call(name: String, vararg args: Any?) {
        val c = page ?: return
        if (c.dead) return
        val send: (WebLink) -> Unit = when (name) {
            "token" -> {
                val id = args.getOrNull(0) as? Int ?: return
                val token = args.getOrNull(1) as? String
                ({ it.token(id, token) })
            }
            "seek" -> {
                val ms = (args.getOrNull(0) as? Number)?.toLong() ?: return
                ({ it.seek(ms) })
            }
            "volume" -> {
                val level = (args.getOrNull(0) as? Number)?.toDouble() ?: return
                ({ it.volume(level) })
            }
            in COMMANDS -> ({ it.command(name) })
            else -> return
        }
        val link = c.link
        if (link != null) send(link) else c.queue += send
    }

    override fun destroy() {
        val c = page ?: return
        page = null
        end(c)
    }

    /**
     * Design 120 QA M3: the stores are cleared in the next page's own process, before it loads (`open`'s `clearFirst`) -
     * a process that ends itself right after a clear could cut it short. [done] at once: the renderer's next page carries
     * the clear, and a renderer with no next page leaves its marker missing, which clears at the first page after.
     */
    override fun clearStorage(done: () -> Unit) {
        clearNext = true
        page?.takeIf { it.link == null && !it.dead }?.clearFirst = true
        after(0) { done() }
    }

    private fun bind(c: Conn) {
        val sink = Sink(c)
        c.sink = sink
        val b = connector.bind(sink)
        if (b == null) {
            log("spotify: the web player's process couldn't be bound")
            c.dead = true
            return
        }
        c.binding = b
    }

    private fun onConnected(c: Conn, link: WebLink) {
        if (c !== page || c.dead) {
            // a binding we gave up on before it came up: that process ends at once
            link.destroy()
            return
        }
        c.link = link
        link.open(fakeBase(), c.clearFirst)
        c.clearFirst = false
        val queued = c.queue.toList()
        c.queue.clear()
        for (send in queued) send(link)
    }

    private fun onSignal(c: Conn, s: WebSignal) {
        when (s) {
            is WebSignal.Log -> log(s.line) // a dying process's disconnect says its lines too
            is WebSignal.Attached -> Unit
            is WebSignal.Page -> if (c === page && c.link != null) events?.invoke(s.event)
            WebSignal.Gone -> if (c === page) {
                // L8: the renderer died in `:web`, which ends itself - one Gone, its process's death then an asked one
                page = null
                end(c)
                events?.invoke(HostEvent.Gone)
            }
            WebSignal.Stale -> if (c === page) {
                c.link = null
                c.binding?.unbind()
                c.binding = null
                c.sink = null // that process's death is no longer this page's
                if (c.rebinds++ >= 1) {
                    log("spotify: the web player's process refused a page twice")
                    c.dead = true
                    return
                }
                log("spotify: a web player's process that had a page - a fresh one")
                awaitExit()
            }
        }
    }

    private fun onDied(c: Conn) {
        if (c.dead) return
        c.dead = true
        val wasUp = c.link != null
        c.link = null
        if (c.asked || c !== page) {
            if (exitWait) checkExit()
            return
        }
        // a death nobody asked for: unbind at once (Android would restart a bound service in an empty process)
        page = null
        c.binding?.unbind()
        c.binding = null
        awaitExit()
        if (wasUp) {
            log("spotify: the web player's process ended")
            events?.invoke(HostEvent.Gone)
        } else {
            // M4: a bind that never came up is no `ready` - R1's wait says "Trenger internett", never "Stoppet"
            log("spotify: the web player's process didn't come up")
        }
    }

    /** [c] goes: its page's destroy, the unbind, and the wait for its process to end before the next bind. */
    private fun end(c: Conn) {
        c.asked = true
        c.queue.clear()
        c.link?.destroy()
        c.link = null
        c.binding?.unbind()
        c.binding = null
        if (c.dead) return
        awaitExit()
    }

    private fun awaitExit() {
        if (exitWait) return
        exitWait = true
        exitPolls = 0
        poll()
    }

    private fun poll() {
        exitTimer = after(EXIT_POLL_MS) {
            exitTimer = null
            exitPolls++
            checkExit()
        }
    }

    private fun checkExit() {
        if (!exitWait) return
        val waited = exitPolls * EXIT_POLL_MS
        when {
            !connector.webRunning() -> exitDone()
            waited >= EXIT_GIVE_UP_MS -> {
                log("spotify: the old web player's process didn't end - the next one is bound anyway")
                exitDone()
            }
            else -> {
                if (waited >= EXIT_WAIT_MS && waited - EXIT_POLL_MS < EXIT_WAIT_MS) {
                    log("spotify: the old web player's process outlived its destroy - ended")
                    connector.kill()
                }
                // the kill's death notice may have ended the wait already
                if (exitWait && exitTimer == null) poll()
            }
        }
    }

    private fun exitDone() {
        exitWait = false
        exitTimer?.invoke()
        exitTimer = null
        val c = page ?: return
        if (c.binding == null && !c.dead) bind(c)
    }

    companion object {
        /** The page's calls with no argument ([WebLink.command]); `connect` and `disconnect` are the web side's own. */
        val COMMANDS = setOf("pause", "resume", "next", "previous", "state")

        /** How often the main process looks whether the old web process has ended. */
        const val EXIT_POLL_MS = 250L

        /** The web side's disconnect (at most 1 s) and its own end; past this the main process ends it. */
        const val EXIT_WAIT_MS = 3_000L

        /** Past this the next process is bound anyway (the web side then refuses a second page: [WebSignal.Stale]). */
        const val EXIT_GIVE_UP_MS = 4_000L
    }
}
