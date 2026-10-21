package me.vibb.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.Repeat
import me.vibb.music.sonos.RoomsView

/** The app's own session commands (only our own controller may send them). */
object Commands {
    const val PLAY_ENTRY = "me.vibb.music.PLAY_ENTRY"
    const val PLAY_ITEM = "me.vibb.music.PLAY_ITEM"
    const val RESUME = "me.vibb.music.RESUME"
    const val RESTORE = "me.vibb.music.RESTORE"
    const val SHUFFLE = "me.vibb.music.SHUFFLE"

    /**
     * The repeat button (design 49 §1.3): off -> all -> one for the loaded entry, remembered per entry (Room's `repeat`).
     * Only our own commands change shuffle and repeat: `KidPlayer` refuses the Player's set-mode commands. Not a play.
     */
    const val REPEAT = "me.vibb.music.REPEAT"
    const val SLEEP = "me.vibb.music.SLEEP"
    const val OUTPUT = "me.vibb.music.OUTPUT"

    /** "Spill av på" opened: one topology refresh (design 26a §2.1, §5.1). */
    const val ROOMS_REFRESH = "me.vibb.music.ROOMS_REFRESH"

    /** "Se etter høyttalere": one SSDP round in the background. */
    const val ROOMS_RESCAN = "me.vibb.music.ROOMS_RESCAN"

    /**
     * Design 53 §2.7: "+" on a row of "Spill av på" ([ARG_OUTPUT] = the row's uid): each of its rooms joins our
     * coordinator. A play (in [PLAYS]: refused while suspended) - the service checks the gates and the scope again.
     */
    const val ROOM_JOIN = "me.vibb.music.ROOM_JOIN"

    /** Design 53 §2.7: "−" on a member of our group's block ([ARG_OUTPUT] = its uid): it leaves, the rest play on. */
    const val ROOM_LEAVE = "me.vibb.music.ROOM_LEAVE"

    /**
     * Design 53 §2.12: a volume slider of the group block - [ARG_OUTPUT] a member's uid, or our coordinator's with
     * [ARG_GROUP] true ("Begge"/"Alle", the drag [ARG_DRAG]); [ARG_VOLUME] 0..100. Not a play.
     */
    const val ROOM_VOLUME = "me.vibb.music.ROOM_VOLUME"
    const val ARG_GROUP = "group"
    const val ARG_VOLUME = "volume"
    const val ARG_DRAG = "drag"

    /** A swipe of the episode carousel (design 27): to [ARG_KEY] of [ARG_ENTRY] (ignored unless that
     * entry is loaded), as next or previous by [ARG_FORWARD]. Like next it starts nothing - not in
     * [PLAYS]; on a Sonos room it goes through the renderer as next does (`goTo` ->
     * `renderer.seekTo`, a room's `stepTo`; design 26a, the merge of design 27). */
    const val SKIP_TO = "me.vibb.music.SKIP_TO"

    /**
     * The kid's order flip (design 39 §3.3): sent by the list only to a live player, after `Store.setFlip` has
     * returned (qa-39-design L9), with the entry's key ([ARG_ENTRY_KEY]) and the new shown direction
     * ([ARG_NEWEST_FIRST]) - the service uses them, not a snapshot that might predate the row; another entry's is
     * dropped. The queue goes the new way from the current item, which plays on. Not a play.
     */
    const val FLIP_ORDER = "me.vibb.music.FLIP_ORDER"
    const val ARG_ENTRY_KEY = "entry_key"
    const val ARG_NEWEST_FIRST = "newest_first"

    const val ARG_ENTRY = "entry"
    const val ARG_KEY = "key"
    const val ARG_MINUTES = "minutes"

    /** [SLEEP] with this true: "Til slutten" (design 50 §1.2) - the timer ends at the current item's end; minutes unread. */
    const val ARG_AT_END = "at_end"
    /** An [Output.key] (a String since design 26a step 2). */
    const val ARG_OUTPUT = "output"
    const val ARG_FORWARD = "forward"

    /** [RESTORE]: the stored queue loaded paused, then this (one of the THEN_ values) - so the
     * player's controls work before play after a restart (qa-21-step2-code M6). */
    const val ARG_THEN = "then"
    const val ARG_POSITION = "position"
    const val THEN_NEXT = "next"
    const val THEN_PREVIOUS = "previous"
    const val THEN_SEEK = "seek"
    const val THEN_SHUFFLE = "shuffle"
    const val THEN_REPEAT = "repeat"

    /** [RESTORE] then [SKIP_TO] (with its [ARG_ENTRY], [ARG_KEY], [ARG_FORWARD]): a swipe on the restored queue stays paused. */
    const val THEN_SKIP_TO = "skip_to"

    val ALL: List<SessionCommand> =
        listOf(
            PLAY_ENTRY, PLAY_ITEM, RESUME, RESTORE, SHUFFLE, REPEAT, SLEEP, OUTPUT, ROOMS_REFRESH, ROOMS_RESCAN, SKIP_TO, FLIP_ORDER,
            ROOM_JOIN, ROOM_LEAVE, ROOM_VOLUME,
        ).map { SessionCommand(it, Bundle.EMPTY) }

    /** Design 53 §2.6-§2.12: run beside the switch lock, never behind it - a join or a drag never waits for a hand-off. */
    val GROUPING: Set<String> = setOf(ROOM_JOIN, ROOM_LEAVE, ROOM_VOLUME)

    /**
     * The launcher's call rule (design 26a §6.7, qa-26a-step3b-code H1): a call that rings or is up
     * while the music plays -> [CALL_HOLD]; the call over -> [CALL_RELEASE]. The service pauses as its
     * own pause (the sleep timer runs on) and plays again only what it paused, and not after the
     * timer ended meanwhile. Spelled in bridge_contract.json; granted only to a launcher signed like
     * this app (`onConnect`), as the platform session's custom actions.
     */
    const val CALL_HOLD = "me.vibb.music.CALL_HOLD"
    const val CALL_RELEASE = "me.vibb.music.CALL_RELEASE"

    val LAUNCHER: List<SessionCommand> = listOf(CALL_HOLD, CALL_RELEASE).map { SessionCommand(it, Bundle.EMPTY) }

    /**
     * The commands that start playing - refused while the app is suspended (`playAllowed`). [OUTPUT]
     * to a Sonos room starts the speaker too: that depends on its argument, so the service's command
     * gate reads [ARG_OUTPUT] (`Output.sonosUid`) beside this set (design 26a §3.3).
     */
    val PLAYS: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, RESUME, ROOM_JOIN)

    /**
     * The commands a tap's instant switch waits for (`logic/Switch.kt`): a cover, a pick,
     * next/previous on the restored queue and a carousel swipe (design 27's [SKIP_TO]; on a room it
     * waits behind the renderer's settle and a switch's hand-off like the others). While one is
     * accepted but hasn't run, [PlayerHub.State.waiting].
     */
    val MOVES: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, RESTORE, SKIP_TO)
}

/**
 * What the player service tells the screens (same process): the live queue, play state, the
 * sleep timer and outputs ([Output], [RendererState]: PlayerModel.kt). While no service holds a
 * queue the screens show the stored now-playing row, paused (21 §4.4).
 */
object PlayerHub {
    data class State(
        /** The service holds a queue. */
        val live: Boolean = false,
        val entryId: Long? = null,
        val keys: List<String> = emptyList(),
        val index: Int = 0,
        val shuffled: Boolean = false,
        /** The loaded entry's repeat (design 49 §1.2). */
        val repeat: Repeat = Repeat.OFF,
        /** The intent: "playing" shows at once on a tap. */
        val playing: Boolean = false,
        val durationMs: Long? = null,
        /** `SystemClock.elapsedRealtime()` when the sleep timer ends; null = off. */
        val sleepEndsAt: Long? = null,
        val sleepMinutes: Int? = null,
        /** "Til slutten" is set (design 50 §1.2): the pause comes at the current item's end. */
        val sleepAtEnd: Boolean = false,
        val outputs: List<Output> = listOf(Output.PHONE),
        /** The output in use (the chosen one, else where the system routes). */
        val output: Output = Output.PHONE,
        /**
         * Why the renderer isn't playing what we sent it (the player's quiet line): a Sonos room's four (design 26a
         * §3.3, §4.8) or Spotify's web player's (design 26c §5.8, also the loaded entry's tile - [entryId]); null
         * for the phone's own player.
         */
        val rendererState: RendererState? = null,
        /** The Sonos rows of "Spill av på", stale and scanning (`RoomDirectory.state`, design 26a §5.1). */
        val rooms: RoomsView = RoomsView(emptyList(), stale = false, scanning = false),
        /**
         * A [Commands.MOVES] command is accepted but hasn't run yet: it waits for the renderer (the
         * settle at start, a room switch, a look at a recorded room; design 26a §4.6-§4.7). The
         * screens hold a tap's switch meanwhile (`logic/Switch.kt`, `holdEndsAt`).
         */
        val waiting: Boolean = false,
        /**
         * Design 53 §2.7: the room session is live (fresh, ours, PLAYING or PAUSED) - "+" and "−" are offered only
         * then; [groupPending] the rows and members a "+" or "−" went out for and the topology doesn't show yet (the
         * Spinner in the button's place, at most 8 s); [groupFailed] those whose verb got no answer ("Fikk ikke kontakt
         * med X - er den på?" until the next tap on it or 30 s). Published state: the commands answer at once.
         */
        val roomLive: Boolean = false,
        val groupPending: Set<String> = emptySet(),
        val groupFailed: Set<String> = emptySet(),
        /** Design 53 §2.12: each room of our group's volume as the app knows it (uid -> 0..100), and the group's mean. */
        val roomVolumes: Map<String, Int> = emptyMap(),
        val groupVolume: Int? = null,
    )

    /**
     * NOT_DOWNLOADED: not on the phone and no network to stream it; CANT_PLAY: it failed anyway;
     * CANT_PLAY_ON_SONOS: nothing of it a speaker can play, or the speaker refused (design 26a §4.2);
     * ROOM_NOT_ALLOWED: the parent's room scope doesn't (any longer) allow the room or a room grouped
     * with it - the session came home, or the pick was refused (§5.5).
     */
    /**
     * A word to the screens: each ends the instant switch's hold (`logic/Switch.kt`); all but [STAYED] show their line
     * for 3 s. [STAYED]: the player refused a step and stayed where it was - the player's own line says why (a Spotify
     * entry the network gate refuses: "Trenger internett"), so nothing more is shown (emulator check §6l A, F3: the
     * hold showed the next track for 3 s although nothing moved).
     */
    /**
     * [HEADSET_NO_CONTACT] (design 52 decision 3): a "Koble til" in "Spill av på" failed after the kid closed the sheet
     * - the enum has no payload, so its line names no device. Sent by the screens' view model, not the service.
     */
    enum class Message { NOT_DOWNLOADED, CANT_PLAY, CANT_PLAY_ON_SONOS, ROOM_NOT_ALLOWED, STAYED, HEADSET_NO_CONTACT }

    internal val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState

    internal val mutableMessages = MutableSharedFlow<Message>(extraBufferCapacity = 4)
    val messages: SharedFlow<Message> = mutableMessages

    /**
     * The player screen shows: composed, its activity resumed (`PlayerScreen` sets it). A Sonos room
     * without a healthy event socket polls every second meanwhile (design 53 §2.4); the session reads
     * the value, nothing calls into it.
     */
    val playerShown = MutableStateFlow(false)

    /**
     * "Spill av på" is open (the view model's `outputSheetOpened`/`outputSheetClosed`; design 53 §2.7): a `groups`
     * event looks at the other rooms' transports again only meanwhile ("Spiller noe annet"), never on a timer.
     */
    val sheetOpen = MutableStateFlow(false)

    /** The live position (ms), read on the main thread; null without a live queue. */
    @Volatile internal var position: (() -> Long)? = null

    /**
     * The launcher suspended the app (design 26a §6.1): the service's stop - a Sonos room's live
     * classify, bookmark, Stop and park, then the phone paused; the phone's own pause - started in the
     * service's scope (main thread). Null without a player service: the receiver's standalone path.
     */
    @Volatile internal var suspendStop: (() -> Job)? = null

    /**
     * The managed config changed (the restrictions broadcast): a room session's own volume above a
     * lowered cap follows it (design 26a §5.3, qa-26a-corrections M1). Main thread; null without a
     * player service.
     */
    @Volatile internal var capChanged: (() -> Unit)? = null

    fun positionMs(): Long? = position?.invoke()

    /**
     * The held set (design 30 §1.6): every item the phone's ExoPlayer may have a period open for - the
     * current item and the next in its timeline (it opens the next one in the current one's last ~50 s,
     * and a progressive period keeps the cache's hole lock while its source is open, paused included) -
     * plus what a queue being built has [claim]ed. Empty on a Sonos room or with nothing loaded. Never
     * pruned, evicted, swept or settled; its progressive items that aren't done are never fetched.
     * Immutable, written on the main thread, read anywhere - every removal reads it again right before
     * it removes.
     */
    @Volatile var heldLocal: Set<ItemRef> = emptySet()
        private set

    /** The player's timeline part of [heldLocal], and the claims of a queue not loaded yet (main thread). */
    private var timeline: Set<ItemRef> = emptySet()
    private var claims: Set<ItemRef> = emptySet()

    /** A new queue's start item and its next, held before the service reads rows for it (L4; main thread). */
    fun claim(refs: Set<ItemRef>) {
        claims = claims + refs
        heldLocal = timeline + claims
    }

    /**
     * The player's timeline holds [refs] now (main thread); the claims end once the queue they were made
     * for is loaded or dropped ([loaded]). Returns the items that left the held set: they may be settled
     * now (§1.5).
     */
    fun hold(refs: Set<ItemRef>, loaded: Boolean = false): Set<ItemRef> {
        val before = heldLocal
        timeline = refs
        if (loaded) claims = emptySet()
        heldLocal = timeline + claims
        return before - heldLocal
    }
}
