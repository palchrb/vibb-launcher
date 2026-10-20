package me.vibb.music.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.vibb.music.Fixtures
import me.vibb.music.spotify.SpotifyTracker.Event
import me.vibb.music.spotify.SpotifyTracker.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tracker (design 26c §5.4, QA H4, M5, L11): every sequence of `sdk_states/` replayed - the states
 * as the page posts them, through [SdkState.parse] - with the events each step must give, and the
 * synthetic edges around them. The same files are what scripts/fake-spotify.py's stub emits the shapes
 * of (its selftest checks their keys against player.js).
 */
class SpotifyTrackerTest {
    private val sequences: List<String> by lazy {
        val dir = listOf(File("src/test/resources/spotify/sdk_states"), File("app/src/test/resources/spotify/sdk_states")).first { it.isDirectory }
        dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted()
    }

    private fun word(e: Event): String = when (e) {
        is Event.Started -> "Started ${e.index}"
        is Event.ItemChanged -> "ItemChanged ${e.index}${if (e.auto) " auto" else ""}"
        is Event.Unplayable -> "Unplayable ${e.index}${if (e.stop) " stop" else ""}"
        is Event.Paused -> "Paused ${if (e.ours) "ours" else "spotify"}"
        Event.Resumed -> "Resumed"
        is Event.NotOurs -> "NotOurs ${if (e.playing) "playing" else "paused"}"
        Event.Ended -> "Ended"
        is Event.NewWindow -> "NewWindow ${e.index}${if (e.resume) " resume" else ""}${if (e.wrap) " wrap" else ""}"
        is Event.ShuffleOrRepeat -> "ShuffleOrRepeat ${e.index}"
        is Event.QueueAdd -> "QueueAdd ${e.index}"
        Event.Look -> "Look"
        Event.Elsewhere -> "Elsewhere"
        is Event.TrackChanged -> "TrackChanged ${e.uri}"
        is Event.LeftoverAdd -> "LeftoverAdd ${e.uri} ${if (e.playing) "playing" else "paused"}"
    }

    private fun queueOf(doc: JsonObject) = doc["queue"]!!.jsonArray.map {
        val o = it.jsonObject
        SpotifyTrack(o["uri"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["duration_ms"]!!.jsonPrimitive.long)
    }

    /** Replays one sequence; returns the tracker for more checks. */
    private fun replay(name: String): SpotifyTracker {
        val doc = Json.parseToJsonElement(Fixtures.resource("spotify/sdk_states/$name.json")).jsonObject
        val tracker = SpotifyTracker()
        // a context-mode sequence (§5.9, step 6) names the context and the stored live track instead of a queue
        val context = (doc["context"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (context != null) {
            tracker.loadContext(context, (doc["live"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content)
        } else {
            tracker.load(queueOf(doc), 0)
        }
        for ((i, stepElement) in doc["steps"]!!.jsonArray.withIndex()) {
            val step = stepElement.jsonObject
            val at = step["at"]!!.jsonPrimitive.long
            val where = "$name step $i (at $at)"
            val events: List<Event> = when {
                "played" in step -> step["played"]!!.jsonObject.let { p ->
                    tracker.played(
                        p["index"]!!.jsonPrimitive.int, p["known"]!!.jsonPrimitive.int,
                        positionMs = p["position"]?.jsonPrimitive?.long ?: 0, fresh = p["fresh"]?.jsonPrimitive?.boolean ?: true,
                    )
                }
                "played_context" in step -> step["played_context"]!!.jsonObject.let { p ->
                    tracker.playedContext(
                        (p["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content,
                        positionMs = p["position"]?.jsonPrimitive?.long ?: 0, fresh = p["fresh"]?.jsonPrimitive?.boolean ?: true,
                    )
                }
                "load_context" in step -> emptyList<Event>().also {
                    val c = step["load_context"]!!.jsonObject
                    tracker.loadContext(c["context"]!!.jsonPrimitive.content, (c["live"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content)
                }
                "queued" in step -> tracker.queued(step["queued"]!!.jsonPrimitive.int)
                "pause_sent" in step -> emptyList<Event>().also { tracker.pauseSent() }
                "resume_sent" in step -> emptyList<Event>().also { tracker.resumeSent() }
                "not_ready" in step -> tracker.onNotReady()
                "error" in step -> tracker.onPlaybackError(at)
                "state" in step -> {
                    val posted = step["state"]!!.let { if (it is JsonNull) "null" else it.toString() }
                    val snapshot = SdkState.parse(posted)
                    assertTrue("$where: the posted state parses", snapshot != null)
                    tracker.onState(snapshot!!, at)
                }
                else -> error("$where: an unknown step $step")
            }
            val expected = (step["expect"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals(where, expected, events.map(::word))
        }
        (doc["end"] as? JsonObject)?.let { end ->
            assertEquals("$name: the index", end["index"]!!.jsonPrimitive.int, tracker.index)
            assertEquals("$name: the mode", Mode.valueOf(end["mode"]!!.jsonPrimitive.content), tracker.mode)
            (end["measured"] as? JsonObject)?.let { m ->
                assertEquals(
                    "$name: measured as received, never from a foreign state",
                    SpotifyTracker.Measured(
                        m["index"]!!.jsonPrimitive.int, m["position"]!!.jsonPrimitive.long, m["at"]!!.jsonPrimitive.long,
                        m["playing"]!!.jsonPrimitive.boolean,
                    ),
                    tracker.measured,
                )
            }
        }
        return tracker
    }

    @Test
    fun `every sequence of sdk_states gives its events`() {
        val wanted = setOf(
            "start", "advance", "end_of_window", "end_of_window_gap", "end_of_queue", "pause", "elsewhere", "elsewhere_not_ready",
            "relinked", "relinked_not_by_name", "relinked_other_artist", "unplayable", "unplayable_reset", "autoplay",
            "autoplay_window", "transfer_in", "queue_leftover", "step_back_stale", "restart_stale", "trackless", "shuffled",
            "start_old_advance", "context_artist", "context_playlist", "context_foreign", "context_autoplay", "context_leftover_add",
            "end_paused_at_duration", "unplayable_at_boundary",
        )
        assertTrue("the sequences §9.2 and the QA fix round name: ${wanted - sequences.toSet()}", sequences.containsAll(wanted))
        for (name in sequences) replay(name)
    }

    @Test
    fun `autoplay after the window and after the queue is paused and never measured (H4)`() {
        val afterQueue = replay("autoplay")
        assertEquals("the last state of ours", 15_000L, afterQueue.measured!!.positionMs)
        assertEquals(Mode.ENDED, afterQueue.mode)
        val afterWindow = replay("autoplay_window")
        assertEquals(1, afterWindow.measured!!.index)
        assertEquals(Mode.LOST, afterWindow.mode)
    }

    @Test
    fun `a relink matches by uri, linked or title and duration - never by name alone`() {
        assertEquals(3, replay("relinked").index)
        assertEquals("5 s longer is another recording", Mode.LOST, replay("relinked_not_by_name").mode)
        assertEquals("another artist is another song", 0, replay("relinked_other_artist").index)
    }

    @Test
    fun `three unplayable in a row stop, and the stop pauses whatever plays next`() {
        val t = replay("unplayable")
        assertEquals(Mode.LOST, t.mode)
        assertEquals(listOf("NotOurs playing"), t.onState(state(4, 2_000), 3_000).map(::word))
        // A fresh play of the kid's starts the count again; the renderer moving on by itself doesn't.
        val q = (1..5).map { track(it) }
        val r = SpotifyTracker().apply { load(q, 0) }
        r.played(0, 4)
        assertEquals(listOf("Unplayable 0"), r.onPlaybackError(0).map(::word))
        r.played(1, 4, fresh = false)
        assertEquals(listOf("Unplayable 1"), r.onPlaybackError(0).map(::word))
        r.played(2, 4, fresh = true)
        assertEquals("a tap of the kid's: the count starts again", listOf("Unplayable 2"), r.onPlaybackError(0).map(::word))
        r.played(3, 4, fresh = false)
        assertEquals(listOf("Unplayable 3"), r.onPlaybackError(0).map(::word))
        r.played(4, 4, fresh = false)
        assertEquals(listOf("Unplayable 4 stop"), r.onPlaybackError(0).map(::word))
        assertEquals("one error per item, not two", emptyList<String>(), r.onPlaybackError(0).map(::word))
    }

    @Test
    fun `a null is confirmed only after the 2 s look or a not_ready`() {
        val t = SpotifyTracker().apply { load(listOf(track(1), track(2)), 0) }
        t.played(0, 1)
        assertEquals(listOf("Started 0"), t.onState(state(1, 0), 0).map(::word))
        assertEquals(listOf("Look"), t.onState(Inactive, 1_000).map(::word))
        assertEquals("1.9 s later: not yet", emptyList<String>(), t.onState(Inactive, 2_900).map(::word))
        assertEquals(listOf("Elsewhere"), t.onState(Inactive, 3_000).map(::word))
        assertEquals("after it, nothing of ours plays by itself", listOf("NotOurs playing"), t.onState(state(1, 3_000), 4_000).map(::word))
        // A not_ready without a null being looked at is no confirmation.
        val u = SpotifyTracker().apply { load(listOf(track(1)), 0) }
        u.played(0, 0)
        u.onState(state(1, 0), 0)
        assertEquals(emptyList<String>(), u.onNotReady().map(::word))
        assertEquals(Mode.OURS, u.mode)
    }

    @Test
    fun `nothing of ours is ever measured from a start, a stale or a foreign state`() {
        val t = SpotifyTracker().apply { load(listOf(track(1, 200_000), track(2, 200_000)), 0) }
        t.played(0, 1)
        assertNull("the start position is the bookmark, not a measurement", t.measured)
        t.onState(state(2, 50_000, duration = 200_000), 100) // stale: what played before
        t.onState(state(1, 0, paused = true, duration = 200_000), 200) // loaded, not playing yet
        assertNull(t.measured)
        t.onState(state(1, 0, duration = 200_000), 300)
        assertEquals(SpotifyTracker.Measured(0, 0, 300, true), t.measured)
        t.onState(foreign(), 400)
        assertEquals("a foreign state measures nothing", SpotifyTracker.Measured(0, 0, 300, true), t.measured)
    }

    @Test
    fun `the queue add is one track ahead and asked once`() {
        val q = (1..5).map { track(it) }
        val t = SpotifyTracker().apply { load(q, 0) }
        t.played(0, 0) // a window of one
        assertEquals(listOf("Started 0", "QueueAdd 1"), t.onState(state(1, 0), 0).map(::word))
        assertEquals("asked once", emptyList<String>(), t.onState(state(1, 5_000), 5_000).map(::word))
        assertEquals("added: Spotify knows item 1, nothing more to ask yet", emptyList<String>(), t.queued(1).map(::word))
        assertEquals(listOf("ItemChanged 1 auto", "QueueAdd 2"), t.onState(state(2, 0), 20_100).map(::word))
        assertEquals("a repeated add answer is nothing", emptyList<String>(), t.queued(1).map(::word))
        // The add's 204 can come after Spotify moved on to the item: playing it is knowing it.
        assertEquals(listOf("ItemChanged 2 auto", "QueueAdd 3"), t.onState(state(3, 0), 40_200).map(::word))
        assertEquals("a late answer is nothing", emptyList<String>(), t.queued(2).map(::word))
        assertEquals(emptyList<String>(), t.queued(3).map(::word))
        assertEquals(listOf("ItemChanged 3 auto", "QueueAdd 4"), t.onState(state(4, 0), 60_300).map(::word))
        assertEquals("the queue's last item: no add after it", emptyList<String>(), t.queued(4).map(::word))
    }

    @Test
    fun `an idle or a let-go player pauses whatever plays, and ignores what is paused`() {
        val t = SpotifyTracker().apply { load(listOf(track(1)), 0) }
        assertEquals(Mode.IDLE, t.mode)
        assertEquals("our own uri too: nothing was sent", listOf("NotOurs playing"), t.onState(state(1, 0), 0).map(::word))
        assertEquals(emptyList<String>(), t.onState(state(1, 0, paused = true), 10).map(::word))
        assertEquals(emptyList<String>(), t.onState(Inactive, 20).map(::word))
        t.played(0, 0)
        t.onState(state(1, 0), 100)
        t.lost()
        assertEquals(listOf("NotOurs playing"), t.onState(state(1, 1_000), 1_100).map(::word))
    }

    @Test
    fun `an episode relinked by its name - the server lists an episode by its name alone`() {
        val episode = SpotifyTrack("spotify:episode:FAKEepisode00000000001", "Episode 3: Trollet", 900_000)
        val t = SpotifyTracker().apply { load(listOf(SpotifyTrack("spotify:episode:FAKEepisode00000000000", "Episode 2", 900_000), episode), 0) }
        t.played(0, 1)
        t.onState(state(0, 0, uri = "spotify:episode:FAKEepisode00000000000", duration = 900_000), 0)
        val moved = """{"paused":false,"position":0,"duration":900500,"uri":"spotify:episode:FAKEother000000000001","linked":null,"type":"episode",""" +
            """"name":"Episode 3: Trollet","artists":["Eventyrstund"],"next":[],"prev":[],"context":null}"""
        assertEquals(listOf("ItemChanged 1 auto"), t.onState(SdkState.parse(moved)!!, 900_100).map(::word))
    }

    private fun uri(n: Int) = "spotify:track:FAKEtrack%013d".format(n)

    private fun track(n: Int, duration: Long = 20_000) = SpotifyTrack(uri(n), "Fake track $n — Fake Artist", duration)

    private fun state(
        n: Int, position: Long, paused: Boolean = false, duration: Long = 20_000, uri: String = uri(n), prev: List<Int> = emptyList(),
        shuffle: Boolean = false, repeat: Int = 0,
    ) = SdkState(paused, position, duration, uri, null, emptyList(), prev.map(::uri), "track", "Fake track $n", listOf("Fake Artist"), null, shuffle, repeat)

    private fun foreign() =
        SdkState(false, 0, 180_000, "spotify:track:FAKEautoplay0000000001", null, emptyList(), emptyList(), "track", "Picked", listOf("Someone"), null)

    // --- the emulator round of 2026-10-14 (N1, N2) ------------------------------------------------------------

    @Test
    fun `N1 - a pause at a track's end is the end, a pause before it or of ours is a pause`() {
        // Spotify's pause at the full position after it ran out: no Paused, the next track is the advance, still playing
        val t = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        t.played(0, 2)
        assertEquals(listOf("Started 0"), t.onState(state(1, 0), 0).map(::word))
        t.onState(state(1, 15_000), 15_000)
        assertEquals("the end, held for the next state: the page asked again", listOf("Look"), t.onState(state(1, 20_000, paused = true), 20_000).map(::word))
        assertEquals(Mode.OURS, t.mode)
        assertEquals("measured stays the last playing state", 15_000L, t.measured!!.positionMs)
        assertEquals(listOf("ItemChanged 1 auto"), t.onState(state(2, 0, prev = listOf(1)), 20_100).map(::word))
        // the kid's pause right at the end stays the kid's
        val k = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        k.played(0, 2)
        k.onState(state(1, 0), 0)
        k.onState(state(1, 15_000), 15_000)
        k.pauseSent()
        assertEquals(listOf("Paused ours"), k.onState(state(1, 19_900, paused = true), 19_950).map(::word))
        // a pause Spotify posts 2 s before the end (a focus loss) is a pause, not the end
        val f = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        f.played(0, 2)
        f.onState(state(1, 0), 0)
        f.onState(state(1, 13_000), 13_000)
        assertEquals(listOf("Paused spotify"), f.onState(state(1, 18_000, paused = true), 18_000).map(::word))
        // a pause at the duration of a track that hadn't run out (a seek to its very end): a pause
        val g = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        g.played(0, 2)
        g.onState(state(1, 0), 0)
        assertEquals(listOf("Paused spotify"), g.onState(state(1, 20_000, paused = true), 1_000).map(::word))
    }

    @Test
    fun `N1 - Spotify's advance after a pause that wasn't ours plays again, after our pause it stays quiet`() {
        val t = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        t.played(0, 2)
        t.onState(state(1, 0), 0)
        t.onState(state(1, 13_000), 13_000)
        assertEquals(listOf("Paused spotify"), t.onState(state(1, 17_000, paused = true), 17_000).map(::word))
        assertEquals("playing again with the next track", listOf("ItemChanged 1 auto", "Resumed"), t.onState(state(2, 0, prev = listOf(1)), 21_000).map(::word))
        // our own pause pending: no resume of ours to show
        val u = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        u.played(0, 2)
        u.onState(state(1, 0), 0)
        u.onState(state(1, 18_000), 18_000)
        u.pauseSent()
        assertEquals(listOf("ItemChanged 1 auto"), u.onState(state(2, 0, prev = listOf(1)), 20_000).map(::word))
    }

    @Test
    fun `N1 - the last item's pause at its end is the end, a window's end without its add the next window`() {
        val t = SpotifyTracker().apply { load((1..2).map { track(it) }, 1) }
        t.played(1, 1)
        t.onState(state(2, 0), 0)
        t.onState(state(2, 15_000), 15_000)
        assertEquals(listOf("Ended"), t.onState(state(2, 20_000, paused = true), 20_000).map(::word))
        // a window of one whose add never landed: the gap's new window, as the reset to 0 gives it
        val w = SpotifyTracker().apply { load((1..5).map { track(it) }, 0) }
        w.played(0, 0)
        assertEquals(listOf("Started 0", "QueueAdd 1"), w.onState(state(1, 0), 0).map(::word))
        w.onState(state(1, 15_000), 15_000)
        assertEquals(listOf("NewWindow 1"), w.onState(state(1, 20_000, paused = true), 20_000).map(::word))
    }

    @Test
    fun `L2 - a pause at the end Spotify never follows is the end after a second look, never a stall`() {
        val t = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        t.played(0, 2)
        t.onState(state(1, 0), 0)
        t.onState(state(1, 15_000), 15_000)
        assertEquals(listOf("Look"), t.onState(state(1, 20_000, paused = true), 20_000).map(::word))
        assertEquals("a repeat before the look is nothing new", emptyList<String>(), t.onState(state(1, 20_000, paused = true), 21_000).map(::word))
        assertEquals("the page's answer: still there, the end - the next window", listOf("NewWindow 1"), t.onState(state(1, 20_000, paused = true), 22_100).map(::word))
        // a context whose last track paused at its end: the context's end
        val c = SpotifyTracker().apply { loadContext("spotify:playlist:FAKEplaylist0000000001", null) }
        c.playedContext(null)
        fun cs(pos: Long, paused: Boolean = false) = SdkState(paused, pos, 20_000, uri(1), null, emptyList(), emptyList(), "track", "Fake track 1", listOf("Fake Artist"), "spotify:playlist:FAKEplaylist0000000001")
        c.onState(cs(0), 0)
        c.onState(cs(15_000), 15_000)
        assertEquals(listOf("Look"), c.onState(cs(20_000, paused = true), 20_000).map(::word))
        assertEquals(listOf("Ended"), c.onState(cs(20_000, paused = true), 22_000).map(::word))
        // a playing state in between starts the wait again
        val p = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        p.played(0, 2)
        p.onState(state(1, 0), 0)
        p.onState(state(1, 15_000), 15_000)
        p.onState(state(1, 20_000, paused = true), 20_000)
        assertEquals(listOf("ItemChanged 1 auto"), p.onState(state(2, 0, prev = listOf(1)), 20_500).map(::word))
    }

    @Test
    fun `N2 - an error at a boundary names the next item, mid-track or at the queue's end the current one`() {
        assertEquals(3, replay("unplayable_at_boundary").index)
        // mid-track: the current one, as ever
        val m = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        m.played(0, 2)
        m.onState(state(1, 0), 0)
        m.onState(state(1, 5_000), 5_000)
        assertEquals(listOf("Unplayable 0"), m.onPlaybackError(6_000).map(::word))
        // the queue's last item has no next: the current one
        val l = SpotifyTracker().apply { load((1..2).map { track(it) }, 1) }
        l.played(1, 1)
        l.onState(state(2, 0), 0)
        l.onState(state(2, 15_000), 15_000)
        assertEquals(listOf("Unplayable 1"), l.onPlaybackError(20_000).map(::word))
        // beyond what Spotify knows (no add yet) neither: the current one
        val k = SpotifyTracker().apply { load((1..3).map { track(it) }, 0) }
        k.played(0, 0)
        k.onState(state(1, 0), 0)
        k.onState(state(1, 15_000), 15_000)
        assertEquals(listOf("Unplayable 0"), k.onPlaybackError(20_000).map(::word))
    }

    // --- qa-26c-step4-code's five counter-examples (H1 twice, M1, L1, L2), red on a10e7fe -----------------

    @Test
    fun `QA H1 - our own queue add left in Spotify's queue is no skip - no false unplayable, no stop`() {
        val t = SpotifyTracker().apply { load((0..9).map { track(it) }, 0) }
        t.played(0, 4) // a window of five
        t.onState(state(0, 0), 0); t.onState(state(1, 0, prev = listOf(0)), 20_000)
        t.onState(state(2, 0, prev = listOf(0, 1)), 40_000); t.onState(state(3, 0, prev = listOf(0, 1, 2)), 60_000)
        assertEquals(listOf("ItemChanged 4 auto", "QueueAdd 5"), t.onState(state(4, 0, prev = listOf(1, 2, 3)), 80_000).map(::word))
        t.queued(5) // Spotify's queue holds item 5
        t.played(1, 5) // the kid picks item 1: a window 1..5
        assertEquals(listOf("Started 1"), t.onState(state(1, 0, prev = listOf(2, 3, 4)), 90_000).map(::word))
        val events = t.onState(state(5, 0, prev = listOf(3, 4, 1)), 110_000).map(::word) // Spotify plays its queue first
        assertTrue("no Unplayable: $events", events.none { it.startsWith("Unplayable") })
        assertEquals("the leftover paused, our list goes on from item 2", listOf("NotOurs playing", "NewWindow 2"), events)
    }

    @Test
    fun `QA H1 - a stale state after previous is no skip and never pauses our track`() {
        val t = SpotifyTracker().apply { load((0..9).map { track(it) }, 0) }
        t.played(0, 5); t.onState(state(0, 0), 0); t.onState(state(1, 0, prev = listOf(0)), 20_000)
        t.onState(state(2, 0, prev = listOf(0, 1)), 40_000)
        t.played(1, 5) // "previous"
        assertEquals("stale: passed over", emptyList<String>(), t.onState(state(2, 3_000, prev = listOf(0, 1)), 43_100).map(::word))
        assertEquals("the real start", listOf("Started 1"), t.onState(state(1, 0, prev = listOf(0)), 43_400).map(::word))
        assertEquals(Mode.OURS, t.mode)
    }

    @Test
    fun `QA M1 - with shuffle on the window is read for nothing, and one ShuffleOrRepeat asks to fix it`() {
        val t = SpotifyTracker().apply { load((0..9).map { track(it) }, 0) }
        t.played(0, 4); t.onState(state(0, 0), 0)
        assertEquals(listOf("ShuffleOrRepeat 0"), t.onState(state(3, 0, prev = listOf(0), shuffle = true), 20_000).map(::word))
        assertEquals("nothing else read until the next play", emptyList<String>(), t.onState(state(1, 0, prev = listOf(0, 3), shuffle = true), 40_000).map(::word))
        assertEquals(SpotifyTracker.Measured(0, 0, 0, true), t.measured)
        t.played(0, 4, positionMs = 0)
        assertEquals("repeat 2 too", listOf("ShuffleOrRepeat 0"), t.onState(state(0, 0, repeat = 2), 41_000).map(::word))
        t.played(0, 4, positionMs = 0)
        assertEquals(listOf("Started 0"), t.onState(state(0, 200), 42_000).map(::word))
    }

    @Test
    fun `QA L1 - a stale state of the same uri at a restart is no measurement`() {
        val t = SpotifyTracker().apply { load((0..2).map { track(it, 200_000) }, 0) }
        t.played(1, 2, positionMs = 120_000)
        t.onState(state(1, 120_000, duration = 200_000), 0)
        t.onState(state(1, 125_000, duration = 200_000), 5_000)
        t.played(1, 2, positionMs = 0) // "previous" deep in the track: a restart from 0
        assertEquals(emptyList<String>(), t.onState(state(1, 125_000, duration = 200_000), 5_100).map(::word))
        assertNull("the old play's tick measures nothing", t.measured)
        assertEquals(listOf("Started 1"), t.onState(state(1, 1_000, duration = 200_000), 5_400).map(::word))
        assertEquals(1_000L, t.measured!!.positionMs)
    }

    @Test
    fun `QA L2 - a state without a track does not pause ours`() {
        val t = SpotifyTracker().apply { load((0..2).map { track(it) }, 0) }
        t.played(0, 2)
        t.onState(state(0, 0), 0)
        val trackless = SdkState(false, 1_000, null, null, null, emptyList(), emptyList(), null, null, emptyList(), null)
        assertEquals(emptyList<String>(), t.onState(trackless, 1_000).map(::word))
        assertEquals(Mode.OURS, t.mode)
        assertEquals("not at a start either", emptyList<String>(), SpotifyTracker().apply { load(listOf(track(0)), 0); played(0, 0) }.onState(trackless, 0).map(::word))
    }

    @Test
    fun `a skip of more items than prev can name, or one prev doesn't name, is no skip - but a proven one is`() {
        val t = SpotifyTracker().apply { load((0..9).map { track(it) }, 0) }
        t.played(0, 9); t.onState(state(0, 0), 0); t.onState(state(0, 5_000), 5_000)
        // item 4 while 0 plays, prev naming only 2 and 3: items 1 is unproven - not ours, our list goes on at item 0
        assertEquals(listOf("NotOurs playing", "NewWindow 0 resume"), t.onState(state(4, 0, prev = listOf(2, 3)), 6_000).map(::word))
        t.played(0, 9, positionMs = 5_000)
        t.onState(state(0, 5_100), 6_500)
        assertEquals(listOf("Unplayable 1", "Unplayable 2", "ItemChanged 3 auto"), t.onState(state(3, 0, prev = listOf(0, 1, 2)), 26_000).map(::word))
    }

    @Test
    fun `d39 L10 - a flip keeps the adds - the old add surfacing after the new play is paused, the new order goes on`() {
        val t = SpotifyTracker().apply { load((0..4).map { track(it) }, 0) }
        t.played(0, 0) // a window of one
        assertEquals(listOf("Started 0", "QueueAdd 1"), t.onState(state(0, 0), 0).map(::word))
        t.queued(1) // Spotify's queue holds item 1, the old order's next
        t.onState(state(0, 5_000), 5_000)
        // the kid flips: 0 4 3 2 1, item 0 plays on; mode and the measurement stay, at the current item's new place
        t.reorder(listOf(0, 4, 3, 2, 1).map { track(it) }, 0)
        assertEquals(Mode.OURS, t.mode)
        assertEquals(SpotifyTracker.Measured(0, 5_000, 5_000, true), t.measured)
        // the renderer's fresh start of the new order from the measured place
        t.played(0, 4, positionMs = 5_000)
        assertEquals(listOf("Started 0"), t.onState(state(0, 5_100), 5_400).map(::word))
        // track 0 ends and Spotify plays its own queue first: the old add - never a skip or an unplayable
        val events = t.onState(state(1, 0, prev = listOf(0)), 20_100).map(::word)
        assertEquals("paused, and our list goes on in the new order (item 1 is track 4)", listOf("NotOurs playing", "NewWindow 1"), events)
    }

    @Test
    fun `a new device forgets the adds - its queue is empty`() {
        fun session(newDevice: Boolean): List<String> {
            val t = SpotifyTracker().apply { load((0..4).map { track(it) }, 0) }
            t.played(0, 0)
            assertEquals(listOf("Started 0", "QueueAdd 1"), t.onState(state(0, 0), 0).map(::word))
            if (newDevice) t.newDevice()
            t.played(0, 3)
            t.onState(state(0, 0), 100)
            return t.onState(state(2, 0, prev = listOf(0, 1)), 1_000).map(::word)
        }
        assertEquals("our add may be in the old device's queue: no evidence", listOf("NotOurs playing", "NewWindow 0 resume"), session(newDevice = false))
        assertEquals("a new device's queue is empty: a proven skip", listOf("Unplayable 0", "Unplayable 1", "ItemChanged 2 auto"), session(newDevice = true))
    }

    // --- context mode (design 26c §5.9, step 6) ---------------------------------------------------------------

    private val artist = "spotify:artist:FAKEartist000000000001"

    private fun inContext(n: Int, position: Long, context: String? = artist, paused: Boolean = false, name: String? = "Fake track $n") =
        SdkState(paused, position, 20_000, uri(n), null, emptyList(), emptyList(), "track", name, listOf("Fake Artist"), context)

    @Test
    fun `context mode - every track of our context is ours, its title the listing's, nothing of a queue`() {
        val t = SpotifyTracker().apply { loadContext(artist, null) }
        assertEquals(artist, t.context)
        assertEquals("a list-mode play means nothing here", emptyList<String>(), t.played(0, 0).map(::word))
        assertEquals(Mode.IDLE, t.mode)
        t.playedContext(null)
        val started = t.onState(inContext(5, 0), 100)
        assertEquals(listOf("Started 0", "TrackChanged ${uri(5)}"), started.map(::word))
        assertEquals("the server's listing title", "Fake track 5 — Fake Artist", (started[1] as Event.TrackChanged).title)
        assertEquals(uri(5), t.liveUri)
        assertEquals(0, t.index)
        assertEquals("Spotify picks: any track of the context", listOf("TrackChanged ${uri(9)}"), t.onState(inContext(9, 0), 5_000).map(::word))
        assertNull("a state with no name: no title (the renderer keeps the entry's)", (t.onState(inContext(10, 0, name = null), 6_000).single() as Event.TrackChanged).title)
        assertEquals("no unplayable item in a context: Spotify skips it", emptyList<String>(), t.onPlaybackError(0).map(::word))
        assertEquals(SpotifyTracker.Measured(0, 0, 6_000, true), t.measured)
    }

    @Test
    fun `context mode - a step while paused that Spotify plays is a resume, one it keeps paused is only the track`() {
        val t = SpotifyTracker().apply { loadContext(artist, uri(1)) }
        t.playedContext(null)
        assertEquals("the stored live track again: no change", listOf("Started 0"), t.onState(inContext(1, 0), 0).map(::word))
        t.pauseSent()
        assertEquals(listOf("Paused ours"), t.onState(inContext(1, 2_000, paused = true), 2_100).map(::word))
        assertEquals("next while paused, Spotify keeps it paused", listOf("TrackChanged ${uri(2)}"), t.onState(inContext(2, 0, paused = true), 3_000).map(::word))
        assertEquals("next again, and Spotify plays it", listOf("TrackChanged ${uri(3)}", "Resumed"), t.onState(inContext(3, 0), 4_000).map(::word))
        assertEquals("playing, then the next one paused by Spotify", listOf("TrackChanged ${uri(4)}", "Paused spotify"), t.onState(inContext(4, 0, paused = true), 5_000).map(::word))
    }

    @Test
    fun `context mode - a state of no context, of another, or our list's leftover add is not ours`() {
        val t = SpotifyTracker().apply { load((0..2).map { track(it) }, 0) }
        t.played(0, 0)
        assertEquals(listOf("Started 0", "QueueAdd 1"), t.onState(state(0, 0), 0).map(::word)) // an add of ours in Spotify's queue
        t.loadContext(artist, null)
        t.playedContext(null)
        assertEquals("the add plays first: no start of ours", emptyList<String>(), t.onState(inContext(1, 0), 100).map(::word))
        assertEquals(listOf("Started 0", "TrackChanged ${uri(7)}"), t.onState(inContext(7, 0), 20_200).map(::word))
        assertEquals("a uri list (no context)", listOf("NotOurs playing"), t.onState(inContext(8, 0, context = null), 21_000).map(::word))
        assertEquals(Mode.LOST, t.mode)
        t.playedContext(null)
        t.onState(inContext(7, 1_000), 21_500)
        assertEquals(listOf("NotOurs playing"), t.onState(inContext(8, 0, context = "spotify:playlist:FAKEforeignList000001"), 22_000).map(::word))
    }

    @Test
    fun `context mode - a stale state of the stored track far from the place is passed over at a start`() {
        val t = SpotifyTracker().apply { loadContext("spotify:playlist:FAKEplaylist0000000001", uri(2)) }
        val pl = "spotify:playlist:FAKEplaylist0000000001"
        t.onState(inContext(2, 90_000, context = pl, paused = true), 0) // the page's last state of an earlier session
        t.playedContext(uri(2), positionMs = 30_000)
        assertEquals(emptyList<String>(), t.onState(inContext(2, 90_100, context = pl), 100).map(::word))
        assertNull(t.measured)
        assertEquals(listOf("Started 0"), t.onState(inContext(2, 30_500, context = pl), 300).map(::word))
        assertEquals(30_500L, t.measured!!.positionMs)
        // an artist has no offset: Spotify's start, near 0
        val a = SpotifyTracker().apply { loadContext(artist, null) }
        a.onState(inContext(4, 50_000, paused = true), 0)
        a.playedContext(null, positionMs = 50_000)
        assertEquals("no position is sent without an offset", emptyList<String>(), a.onState(inContext(4, 50_100), 100).map(::word))
        assertEquals(listOf("Started 0", "TrackChanged ${uri(4)}"), a.onState(inContext(4, 200), 300).map(::word))
    }

    // --- qa-26c-step6-code's counter-examples (M1 three times, M2), red on 74c4942, and the fix round's edges -----------

    private val pl = "spotify:playlist:FAKEplaylist0000000001"

    /** A context state of track [n] (20 s), naming [next] next (the SDK's `next_tracks`). */
    private fun cs(n: Int, pos: Long, ctx: String? = pl, paused: Boolean = false, next: List<Int> = listOf(n + 1), prev: List<Int> = emptyList()) =
        SdkState(paused, pos, 20_000, uri(n), null, next.map(::uri), prev.map(::uri), "track", "Fake track $n", listOf("Fake Artist"), ctx)

    @Test
    fun `QA M1 - our list's leftover add right after a context track ran out is no end - it is skipped, the context goes on`() {
        val t = SpotifyTracker().apply { load((0..2).map { track(it) }, 0) }
        t.played(0, 0); t.onState(state(0, 0), 0) // QueueAdd 1: uri(1) is in Spotify's queue
        t.loadContext(pl, null); t.playedContext(null)
        t.onState(cs(7, 0), 100); t.onState(cs(7, 15_000), 15_100)
        val events = t.onState(cs(1, 0), 20_200).map(::word)
        assertEquals(listOf("LeftoverAdd ${uri(1)} playing"), events)
        assertEquals(Mode.OURS, t.mode)
        assertEquals("its later states are still it", listOf("LeftoverAdd ${uri(1)} playing"), t.onState(cs(1, 500), 20_700).map(::word))
        assertEquals("the context goes on past it", listOf("TrackChanged ${uri(8)}"), t.onState(cs(8, 0), 21_000).map(::word))
        assertEquals("used up: the same track later in a context is the context's", listOf("TrackChanged ${uri(1)}"), t.onState(cs(1, 0), 40_000).map(::word))
    }

    @Test
    fun `QA M1 - a null context at the track change after a run out is no end - it is the track our last state named`() {
        val u = SpotifyTracker().apply { loadContext(pl, null) }; u.playedContext(null)
        u.onState(cs(1, 0), 0); u.onState(cs(1, 15_000), 15_000)
        val events = u.onState(cs(2, 0, ctx = null, prev = listOf(1)), 20_100).map(::word)
        assertFalse(events.toString(), "Ended" in events)
        assertEquals(listOf("TrackChanged ${uri(2)}"), events)
        assertEquals(Mode.OURS, u.mode)
    }

    @Test
    fun `QA M1 - the next track Spotify named, posted paused at 0, is no end`() {
        val v = SpotifyTracker().apply { loadContext(pl, null) }; v.playedContext(null)
        v.onState(cs(1, 0), 0); v.onState(cs(1, 15_000), 15_000) // next = [2]
        val events = v.onState(cs(2, 0, paused = true, prev = listOf(1)), 20_100).map(::word)
        assertFalse(events.toString(), "Ended" in events)
        assertEquals(listOf("TrackChanged ${uri(2)}", "Paused spotify"), events)
        assertEquals("it plays then: our track again", listOf("Resumed"), v.onState(cs(2, 300, prev = listOf(1)), 20_500).map(::word))
    }

    @Test
    fun `QA L4 - Spotify back at another of the context's tracks, paused at 0, not named next, after the last ran out, is the end`() {
        val t = SpotifyTracker().apply { loadContext(pl, null) }; t.playedContext(null)
        t.onState(cs(3, 0, next = emptyList()), 0); t.onState(cs(3, 15_000, next = emptyList()), 15_000)
        assertEquals(listOf("Ended"), t.onState(cs(1, 0, paused = true, next = listOf(2)), 20_100).map(::word))
        assertEquals(Mode.ENDED, t.mode)
        // ...and not before the track ran out: Spotify paused at another track mid-way is a track change, paused
        val u = SpotifyTracker().apply { loadContext(pl, null) }; u.playedContext(null)
        u.onState(cs(3, 0, next = emptyList()), 0)
        assertEquals(listOf("TrackChanged ${uri(1)}", "Paused spotify"), u.onState(cs(1, 0, paused = true), 5_000).map(::word))
    }

    @Test
    fun `QA M2 - a null context on our own live track never pauses it`() {
        val t = SpotifyTracker().apply { loadContext(pl, null) }; t.playedContext(null)
        t.onState(cs(1, 0), 0)
        assertEquals(emptyList<String>(), t.onState(cs(1, 5_000, ctx = null), 5_000).map(::word))
        assertEquals(Mode.OURS, t.mode)
        assertEquals(SpotifyTracker.Measured(0, 5_000, 5_000, true), t.measured)
        t.pauseSent()
        assertEquals("our pause, context-less", listOf("Paused ours"), t.onState(cs(1, 6_000, ctx = null, paused = true), 6_100).map(::word))
    }

    @Test
    fun `M2 - a context-less track nobody named is a uri list - not ours, and never the end even after a run out`() {
        val t = SpotifyTracker().apply { loadContext(pl, null) }; t.playedContext(null)
        t.onState(cs(1, 0, next = listOf(2)), 0); t.onState(cs(1, 15_000, next = listOf(2)), 15_000)
        assertEquals(listOf("NotOurs playing"), t.onState(cs(9, 0, ctx = null), 20_100).map(::word))
        assertEquals(Mode.LOST, t.mode)
        // another, non-null context then is Autoplay after the end
        val u = SpotifyTracker().apply { loadContext(pl, null) }; u.playedContext(null)
        u.onState(cs(1, 0), 0); u.onState(cs(1, 15_000), 15_000)
        assertEquals(listOf("NotOurs playing", "Ended"), u.onState(cs(9, 0, ctx = "spotify:station:FAKEautoplay000000001"), 20_100).map(::word))
    }

    @Test
    fun `M2 - a start counts a context-less state of the sent offset at its place`() {
        val t = SpotifyTracker().apply { loadContext(pl, uri(4)) }
        t.playedContext(uri(4), positionMs = 30_000)
        assertEquals("not the offset: what played before", emptyList<String>(), t.onState(cs(5, 0, ctx = null), 100).map(::word))
        assertEquals("the offset far from its place", emptyList<String>(), t.onState(cs(4, 90_000, ctx = null), 200).map(::word))
        assertEquals(listOf("Started 0"), t.onState(cs(4, 30_400, ctx = null), 300).map(::word))
    }

    @Test
    fun `M1 - the kid's next landing on our leftover add mid-track does not pause the context`() {
        val t = SpotifyTracker().apply { load((0..2).map { track(it) }, 0) }
        t.played(0, 0); t.onState(state(0, 0), 0) // QueueAdd 1
        t.loadContext(pl, null); t.playedContext(null)
        t.onState(cs(7, 0), 100); t.onState(cs(7, 5_000), 5_100)
        // vibb.next(): Spotify plays its queue first - our add
        assertEquals(listOf("LeftoverAdd ${uri(1)} playing"), t.onState(cs(1, 0, prev = listOf(7)), 6_000).map(::word))
        assertEquals("never NotOurs: the context isn't lost", Mode.OURS, t.mode)
        assertEquals(SpotifyTracker.Measured(0, 5_000, 5_100, true), t.measured)
        assertEquals(listOf("TrackChanged ${uri(8)}"), t.onState(cs(8, 0, prev = listOf(7, 1)), 6_500).map(::word))
    }

    // --- design 49: shuffle and repeat ---------------------------------------------------------------------------

    @Test
    fun `d49 - a state differing from the wanted shuffle and repeat is corrected, one as wanted is read`() {
        val t = SpotifyTracker().apply { load((0..4).map { track(it) }, 0); setModes(SpotifyModes(repeat = 2), wrap = false) }
        t.played(0, 4)
        assertEquals("repeat off where the entry wants track", listOf("ShuffleOrRepeat 0"), t.onState(state(0, 0), 0).map(::word))
        t.played(0, 4)
        assertEquals(listOf("Started 0"), t.onState(state(0, 0, repeat = 2), 1_000).map(::word))
        assertEquals("shuffle on in list mode is never wanted", listOf("ShuffleOrRepeat 0"), t.onState(state(0, 2_000, shuffle = true, repeat = 2), 2_000).map(::word))
    }

    @Test
    fun `d49 - repeat one is Spotify's repeat track - the same uri again is no end, a stop under it plays it again`() {
        val t = SpotifyTracker().apply { load((1..3).map { track(it) }, 0); setModes(SpotifyModes(repeat = 2), wrap = false) }
        t.played(0, 2)
        t.onState(state(1, 0, repeat = 2), 0)
        t.onState(state(1, 15_000, repeat = 2), 15_000)
        assertEquals("the loop goes on playing: nothing", emptyList<String>(), t.onState(state(1, 400, repeat = 2), 20_400).map(::word))
        t.onState(state(1, 15_000, repeat = 2), 35_000)
        assertEquals("paused at 0 after it ran out: asked again, never the end", listOf("Look"), t.onState(state(1, 0, paused = true, repeat = 2), 40_000).map(::word))
        assertEquals("and playing again: the repeat", emptyList<String>(), t.onState(state(1, 300, repeat = 2), 40_300).map(::word))
        assertEquals(Mode.OURS, t.mode)
        t.onState(state(1, 15_000, repeat = 2), 55_000)
        assertEquals(listOf("Look"), t.onState(state(1, 20_000, paused = true, repeat = 2), 60_000).map(::word))
        assertEquals("still paused 2 s later: Spotify stopped - the item again from 0", listOf("NewWindow 0"), t.onState(state(1, 20_000, paused = true, repeat = 2), 62_100).map(::word))
    }

    @Test
    fun `d49 - list mode's repeat all is a new window from the first item at the list's end, before Ended`() {
        val t = SpotifyTracker().apply { load((1..2).map { track(it) }, 1); setModes(SpotifyModes(), wrap = true) }
        t.played(1, 1)
        t.onState(state(2, 0), 0)
        t.onState(state(2, 15_000), 15_000)
        assertEquals(listOf("NewWindow 0 wrap"), t.onState(state(2, 0, paused = true), 20_100).map(::word))
        assertEquals("never the end", Mode.LOST, t.mode)
        // Autoplay after the last track is the same end: paused, and the list again
        val u = SpotifyTracker().apply { load((1..2).map { track(it) }, 1); setModes(SpotifyModes(), wrap = true) }
        u.played(1, 1)
        u.onState(state(2, 0), 0)
        u.onState(state(2, 15_000), 15_000)
        assertEquals(listOf("NotOurs playing", "NewWindow 0 wrap"), u.onState(foreign(), 20_100).map(::word))
        // repeat off: the end as before
        val v = SpotifyTracker().apply { load((1..2).map { track(it) }, 1) }
        v.played(1, 1)
        v.onState(state(2, 0), 0)
        v.onState(state(2, 15_000), 15_000)
        assertEquals(listOf("Ended"), v.onState(state(2, 0, paused = true), 20_100).map(::word))
    }

    @Test
    fun `d49 - a shuffled window keeps ours exact - the current and its next in our order only`() {
        val t = SpotifyTracker().apply { load((0..4).map { track(it) }, 0) }
        val shuffled = listOf(0, 3, 1, 4, 2).map { track(it) }
        t.reorder(shuffled, 0)
        t.played(0, 4)
        t.onState(state(0, 0), 0)
        t.onState(state(0, 15_000), 15_000)
        assertEquals("the shuffled next is ours", listOf("ItemChanged 1 auto"), t.onState(state(3, 0, prev = listOf(0)), 20_100).map(::word))
        assertEquals("an item of ours out of the shuffled order is not ours (no skip evidence)", "NotOurs playing", word(t.onState(state(4, 2_000), 21_000).first()))
    }

    @Test
    fun `d49 - a context with repeat on - a track that ran out is never the end, Autoplay still is`() {
        val t = SpotifyTracker().apply { loadContext(pl, null); setModes(SpotifyModes(shuffle = true, repeat = 1), wrap = false) }
        t.playedContext(null)
        t.onState(cs(3, 0, next = emptyList()).copy(shuffle = true, repeat = 1), 0)
        t.onState(cs(3, 15_000, next = emptyList()).copy(shuffle = true, repeat = 1), 15_000)
        assertEquals("paused at 0 after it ran out: Spotify's repeat - asked again", listOf("Look"), t.onState(cs(3, 0, paused = true).copy(shuffle = true, repeat = 1), 20_100).map(::word))
        assertEquals("the context from its top: a track change", listOf("TrackChanged ${uri(1)}"), t.onState(cs(1, 0).copy(shuffle = true, repeat = 1), 20_500).map(::word))
        assertEquals(Mode.OURS, t.mode)
        t.onState(cs(1, 15_000).copy(shuffle = true, repeat = 1), 35_000)
        val auto = SdkState(false, 0, 180_000, "spotify:track:FAKEautoplay0000000001", null, emptyList(), emptyList(), "track", "Picked", listOf("Someone"), "spotify:playlist:FAKEradio000000000001", true, 1)
        assertEquals("another context after ours ran out: Autoplay, the end", listOf("NotOurs playing", "Ended"), t.onState(auto, 40_100).map(::word))
    }
}
