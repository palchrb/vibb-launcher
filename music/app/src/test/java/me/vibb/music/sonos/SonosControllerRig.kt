package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import me.vibb.music.model.PlayItem
import me.vibb.music.storytel.Mint

/** The actor without threads: jobs wait in [jobs] until [drain] runs them in order, results handed back at once. */
class FakeWork : SonosWork {
    val jobs = ArrayDeque<() -> Unit>()
    val afters = mutableListOf<Pair<Long, () -> Unit>>()

    /** The next job fails with this instead of running. */
    var failNext: Exception? = null

    override fun <T> submit(job: suspend () -> T, then: (Result<T>) -> Unit) {
        jobs.addLast {
            val fail = failNext
            failNext = null
            val r = if (fail != null) Result.failure(fail) else runCatching { runBlocking { job() } }
            then(r)
        }
    }

    override fun after(delayMs: Long, block: () -> Unit) {
        afters.add(delayMs to block)
    }

    /** Run every queued job (and the ones they queue), and the delayed blocks too when [afters]. */
    fun drain(afters: Boolean = true) {
        while (true) {
            val job = jobs.removeFirstOrNull()
            if (job != null) {
                job()
                continue
            }
            if (!afters || this.afters.isEmpty()) return
            val pending = this.afters.toList()
            this.afters.clear()
            pending.forEach { it.second() }
        }
    }
}

/** The bookmark rows as Room would get them, with the speaker's command count at the moment of each write. */
class FakeStore(private val spk: FakeSpeaker) : SonosStore {
    data class Write(val index: Int, val itemKey: String, val posMs: Long, val durationMs: Long?, val force: Boolean, val commandsBefore: Int)

    val saved = mutableMapOf<String, Long>()
    val writes = mutableListOf<Write>()
    var throwOnSave: RuntimeException? = null

    override fun savedPosMs(itemKey: String): Long = saved[itemKey] ?: 0L

    override fun save(index: Int, itemKey: String, posMs: Long, durationMs: Long?, shownDurationMs: Long?, force: Boolean) {
        throwOnSave?.let { throw it }
        writes.add(Write(index, itemKey, posMs, durationMs, force, spk.commands.size))
        saved[itemKey] = posMs
    }
}

class FakeHost : SonosHost {
    var changes = 0
    val played = mutableListOf<Pair<String, String>>()
    val followed = mutableListOf<Pair<String, String?>>()
    val ramps = mutableListOf<Int?>()
    val logs = mutableListOf<String>()

    /** Every change of the wake/Wi-Fi locks' rule (design 26a §6.3). */
    val locks = mutableListOf<Boolean>()
    var onFollowed: () -> Unit = {}

    override fun changed() {
        changes++
    }

    override fun played(itemKey: String, uri: String) {
        played.add(itemKey to uri)
    }

    /** The uri each follow went out with (renderer.json writes it through storedUri, design 26b §5.3). */
    val followedUris = mutableListOf<Pair<String?, String>>()

    /** Storytel plays that got no URL: (item key, code). */
    val unavailable = mutableListOf<Pair<String, String>>()

    override fun followed(uid: String, name: String?, itemKey: String?, uri: String) {
        onFollowed()
        followed.add(uid to name)
        followedUris.add(itemKey to uri)
    }

    override fun storytelUnavailable(itemKey: String, code: String) {
        unavailable.add(itemKey to code)
    }

    override fun ramp(volume: Int?) {
        ramps.add(volume)
    }

    override fun armedPlaying(playing: Boolean) {
        locks.add(playing)
    }

    override fun log(message: String) {
        logs.add(message)
    }
}

/**
 * Storytel's account as the play job sees it (design 26b §5.1): every call recorded (the cid, the asset
 * call's timeout, the deadline as time left), the answer [answer]'s, a signed URL by default (fake values:
 * `cdn.example.invalid`, `FAKE-SIG-<n>`). [during] runs inside the mint - what the main thread does
 * while a real mint takes its seconds (a step, a release, a suspension); [takesMs] moves the clock.
 */
class FakeMinter(private val clock: FakeClock) : StorytelMinter {
    data class Call(val cid: String, val timeoutMs: Long, val budgetMs: Long)

    val calls = mutableListOf<Call>()
    var answer: (String) -> Mint = { cid -> Mint.Url(signed(cid, calls.size)) }
    var during: (() -> Unit)? = null
    var takesMs = 0L

    override suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint {
        calls.add(Call(cid, timeoutMs, deadlineMs - clock.now))
        clock.advance(takesMs)
        during?.let {
            during = null
            it()
        }
        return answer(cid)
    }

    companion object {
        /** A signed CDN URL as Storytel's Location carries it: the book in `consumableId`, a fake token. */
        fun signed(cid: String, n: Int) = "https://cdn.example.invalid/mp3/$cid/abook.mp3?consumableId=$cid&token=FAKE-SIG-$n"
    }
}

/**
 * A controller on a recording speaker, the real SonosSession and SonosTransport, a clock that moves
 * when told, and three RSS episodes of 24:13.
 */
class ControllerRig(val uid: String = "RINCON_KID01400") {
    val clock = FakeClock()
    val spk = FakeSpeaker(uid)
    val work = FakeWork()
    val store = FakeStore(spk)
    val host = FakeHost()
    val session = SonosSession({ clock.now })
    var resolvedUids = mutableListOf<String>()
    val transport = SonosTransport(session, { u, _, _ -> resolvedUids.add(u); spk }, NrkService({ _, _ -> SonosFakes.fixture("accounts.xml") }), clock.clock)
    /** playAllowed: false while the launcher has suspended the app. */
    var allowed = true

    /** The launcher's `volume_cap_pct` (null = none). */
    var capPct: Int? = null

    /** Storytel's account (design 26b §5.1) and the debug switch's private-literal allowance (§4.6 (d)). */
    val minter = FakeMinter(clock)
    var privateMint = false
    val c = SonosController(
        transport, work, store, { clock.now }, uid, "Barnerom", host, sleep = clock.clock.sleep, mayPlay = { allowed }, cap = { capPct },
        minter = minter, privateMintAllowed = { privateMint },
    )

    val entry = SonosFakes.entry()
    val items: List<PlayItem> = (1..3).map { SonosFakes.item(key = "ep$it", url = "https://podkast.example/ep$it.mp3") }

    fun url(i: Int) = items[i].url!!

    val now: Long get() = clock.now

    /** Load the queue at [index], [startMs] and play it; the play runs in the actor. */
    fun start(index: Int = 0, startMs: Long = 0, drain: Boolean = true) {
        c.load(entry, items, index, startMs)
        c.playTile()
        if (drain) work.drain()
    }

    /** What the speaker reports next. */
    fun speaker(rel: String, transport: String = "PLAYING", uri: String? = url(c.index), dur: String = "0:24:13") {
        spk.position = PositionInfo("1", uri ?: "", rel, dur, "<DIDL-Lite/>")
        spk.transport = transport
    }

    /** One poll, its snapshot handed to the controller as the actor would. */
    fun poll(): Tick {
        val tick = runBlocking { transport.poll(null) }
        c.onPoll(tick, session.snapshot)
        work.drain()
        return tick
    }

    fun advance(ms: Long) = clock.advance(ms)
}
