package me.vibb.music.sonos

/**
 * The room hand-over's timing (PLAN "Connecting…" item, 2026-10-25; design 54 "Hand-over timing (2026-10-25)"): one
 * line per step under [TAG], `#n +N ms what` - the timeline's number and the milliseconds since its start: a play
 * command reaching the service ([tap]), else the controller's play itself ([play]: a step, the natural advance, the
 * play button) - so the steps can be measured on the emulator against the fakes and on the phone against a real room
 * (`adb logcat -s VibbHandover`).
 *
 * Every timeline has its own number (qa-music-r13-code Q-1): a second tap while the first play still runs - the case
 * this measures - starts #n+1, and the first play's job keeps printing under its own number and time. The service
 * marks the tap whose command it runs ([running]); the controller's [play] continues that one only; the play job
 * enters its timeline on the actor ([enter], one job at a time) so the transport's steps print under it until [done].
 * The polls and the socket's events go to the newest timeline.
 *
 * Measurement only: nothing reads it back, it decides nothing. Its lines carry step names, kinds, counts, states and
 * queue positions - never an id, a uri, a title or a name (design 54 §2.14; `SourceScanTest` pins the call sites), so a
 * release build logs them too. A timeline is open for [WINDOW_MS] after its start; a [first] line prints once per
 * timeline. Called from main (the service, the controller) and from the room's actor (the transport): synchronized.
 */
object HandOverTiming {
    const val TAG = "VibbHandover"

    /** A timeline's lines stop this long after its start (a hand-over that takes longer has failed anyway). */
    const val WINDOW_MS = 30_000L

    /** One tap's (or one play's) timeline. */
    class Timeline internal constructor(val number: Int, internal val start: Long) {
        internal var played = false
        internal val once = mutableSetOf<String>()
    }

    /** Where the lines go: no-op until the service sets `Log.i` (`SonosHome.handOver`); JVM tests leave it so. */
    @Volatile
    var sink: (String) -> Unit = {}

    /** A monotonic clock (ms); a test may set its own. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var count = 0
    private var newest: Timeline? = null
    private var command: Timeline? = null
    private var job: Timeline? = null

    /** A play command reached the service for a room (or an OUTPUT to one): a new timeline, t = 0. */
    @Synchronized
    fun tap(what: String): Timeline = begin("tap: $what")

    /** The service now runs the command of [t]: the play it makes continues [t]. */
    @Synchronized
    fun running(t: Timeline) {
        command = t
    }

    /** The command of [t] is done (a newer command's mark stays). */
    @Synchronized
    fun finished(t: Timeline) {
        if (command === t) command = null
    }

    /**
     * The controller sends a play to the room's actor: it continues the tap whose command runs now, when that one has
     * had no play yet; otherwise (a step, the advance, the play button, a second play) it starts its own.
     */
    @Synchronized
    fun play(what: String): Timeline {
        val c = command
        val t = if (c != null && !c.played && isOpen(c)) c.also { emit(it, what) } else begin("(no tap) $what")
        t.played = true
        return t
    }

    /** The play job of [t] runs on the actor now: the transport's steps print under it until [done]. */
    @Synchronized
    fun enter(t: Timeline?) {
        job = t
    }

    /** A step of [t], else of the running play job, else of the newest timeline (none open: nothing). */
    @Synchronized
    fun step(what: String, t: Timeline? = null) {
        val to = t ?: job ?: newest ?: return
        if (isOpen(to)) emit(to, what)
    }

    /** The play job's last step: printed, then the transport's steps have no job until the next [enter]. */
    @Synchronized
    fun done(what: String) {
        step(what)
        job = null
    }

    /** A step printed once per timeline ([key] names it), on the newest timeline. */
    @Synchronized
    fun first(key: String, what: String) {
        val t = newest ?: return
        if (isOpen(t) && t.once.add(key)) emit(t, what)
    }

    /** A step printed only until the [first] line [key] has printed (the polls and events before the first PLAYING). */
    @Synchronized
    fun until(key: String, what: String) {
        val t = newest ?: return
        if (isOpen(t) && key !in t.once) emit(t, what)
    }

    private fun begin(what: String): Timeline {
        val t = Timeline(++count, clock())
        newest = t
        emit(t, what)
        return t
    }

    private fun isOpen(t: Timeline): Boolean = clock() - t.start <= WINDOW_MS

    private fun emit(t: Timeline, what: String) {
        sink(line(t.number, clock() - t.start, what))
    }

    /** `#3 +1234 ms what`. */
    fun line(number: Int, elapsedMs: Long, what: String): String = "#$number +$elapsedMs ms $what"
}
