package me.vibb.music.sonos

/**
 * The room hand-over's timing (PLAN "Connecting…" item, 2026-10-25; design 54 "Hand-over timing (2026-10-25)"): one
 * line per step under [TAG], each with the milliseconds since the tap - a play command reaching the service ([tap]),
 * else the controller's play itself ([play]: a step, the natural advance, the play button) - so the steps can be
 * measured on the emulator against the fakes and on the phone against a real room (`adb logcat -s VibbHandover`).
 *
 * Measurement only: nothing reads it back, it decides nothing. Its lines carry step names, kinds, counts, states and
 * queue positions - never an id, a uri, a title or a name (design 54 §2.14), so a release build logs them too. A
 * timeline is open for [WINDOW_MS] after its start; a [first] line prints once per timeline. Called from main (the
 * service, the controller) and from the room's actor (the transport): every entry is synchronized.
 */
object HandOverTiming {
    const val TAG = "VibbHandover"

    /** A timeline's lines stop this long after its start (a hand-over that takes longer has failed anyway). */
    const val WINDOW_MS = 30_000L

    /** Where the lines go: no-op until the service sets `Log.i` (`SonosHome.handOver`); JVM tests leave it so. */
    @Volatile
    var sink: (String) -> Unit = {}

    /** A monotonic clock (ms); a test may set its own. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var start: Long? = null
    private var played = false
    private val once = mutableSetOf<String>()

    /** A play command reached the service for a room (or an OUTPUT to one): t = 0. */
    @Synchronized
    fun tap(what: String) = begin("tap: $what")

    /**
     * The controller sends a play to the room's actor. It continues the tap's timeline when a tap came first and has
     * had no play yet; otherwise (a step, the advance, the play button, a second play) it starts its own.
     */
    @Synchronized
    fun play(what: String) {
        val t = start
        if (t == null || played || clock() - t > WINDOW_MS) begin("(no tap) $what") else emit(what)
        played = true
    }

    /** A step of the open timeline (none open: nothing). */
    @Synchronized
    fun step(what: String) {
        if (open()) emit(what)
    }

    /** A step printed once per timeline ([key] names it). */
    @Synchronized
    fun first(key: String, what: String) {
        if (open() && once.add(key)) emit(what)
    }

    /** A step printed only until the [first] line [key] has printed (the polls and events before the first PLAYING). */
    @Synchronized
    fun until(key: String, what: String) {
        if (open() && key !in once) emit(what)
    }

    private fun begin(what: String) {
        start = clock()
        played = false
        once.clear()
        emit(what)
    }

    private fun open(): Boolean = start?.let { clock() - it <= WINDOW_MS } ?: false

    private fun emit(what: String) {
        val t = start ?: return
        sink(line(clock() - t, what))
    }

    /** `+1234 ms what`. */
    fun line(elapsedMs: Long, what: String): String = "+$elapsedMs ms $what"
}
