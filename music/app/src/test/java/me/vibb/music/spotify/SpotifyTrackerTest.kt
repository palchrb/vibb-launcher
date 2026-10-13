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
        is Event.NewWindow -> "NewWindow ${e.index}${if (e.resume) " resume" else ""}"
        is Event.ShuffleOrRepeat -> "ShuffleOrRepeat ${e.index}"
        is Event.QueueAdd -> "QueueAdd ${e.index}"
        Event.Look -> "Look"
        Event.Elsewhere -> "Elsewhere"
    }

    private fun queueOf(doc: JsonObject) = doc["queue"]!!.jsonArray.map {
        val o = it.jsonObject
        SpotifyTrack(o["uri"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["duration_ms"]!!.jsonPrimitive.long)
    }

    /** Replays one sequence; returns the tracker for more checks. */
    private fun replay(name: String): SpotifyTracker {
        val doc = Json.parseToJsonElement(Fixtures.resource("spotify/sdk_states/$name.json")).jsonObject
        val tracker = SpotifyTracker()
        tracker.load(queueOf(doc), 0)
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
                "queued" in step -> tracker.queued(step["queued"]!!.jsonPrimitive.int)
                "pause_sent" in step -> emptyList<Event>().also { tracker.pauseSent() }
                "resume_sent" in step -> emptyList<Event>().also { tracker.resumeSent() }
                "not_ready" in step -> tracker.onNotReady()
                "error" in step -> tracker.onPlaybackError()
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
            "start_old_advance",
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
        assertEquals(listOf("Unplayable 0"), r.onPlaybackError().map(::word))
        r.played(1, 4, fresh = false)
        assertEquals(listOf("Unplayable 1"), r.onPlaybackError().map(::word))
        r.played(2, 4, fresh = true)
        assertEquals("a tap of the kid's: the count starts again", listOf("Unplayable 2"), r.onPlaybackError().map(::word))
        r.played(3, 4, fresh = false)
        assertEquals(listOf("Unplayable 3"), r.onPlaybackError().map(::word))
        r.played(4, 4, fresh = false)
        assertEquals(listOf("Unplayable 4 stop"), r.onPlaybackError().map(::word))
        assertEquals("one error per item, not two", emptyList<String>(), r.onPlaybackError().map(::word))
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
}
