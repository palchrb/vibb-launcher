package me.vibb.music.logic

import android.content.Intent
import androidx.media3.common.PlaybackException
import me.vibb.music.Fixtures
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.media.MediaCache
import me.vibb.music.media.StreamGateException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher's time rules and Home card as the music app honours them (qa-21-step4-code M1, L3, L4). */
class GuardsTest {
    private fun code(path: String) = moduleFile(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val service by lazy { code("src/main/java/me/vibb/music/playback/PlaybackService.kt") }

    @Test
    fun `nothing plays while the app is suspended, a pause always works`() {
        assertTrue(playAllowed(suspended = false))
        assertFalse(playAllowed(suspended = true))
    }

    @Test
    fun `every way to start playing asks first - controllers, the app's commands, playback resumption`() {
        val kid = service.substringAfter("private inner class KidPlayer").substringBefore("private val callback")
        assertTrue(kid.contains("override fun play() {\n            if (playAllowed(suspendedNow()) && !restartIfStale()) super.play()"))
        assertTrue(kid.contains("if (playWhenReady && playAllowed(suspendedNow()) && restartIfStale()) return"))
        assertTrue(kid.contains("if (!playWhenReady || playAllowed(suspendedNow())) super.setPlayWhenReady(playWhenReady)"))
        // qa-26c-emu-music-code N1: the session's play (bar, notification, headset, lock) of a flipped Spotify entry starts
        // its new queue through playEntry under the switch lock, as a cover tap does; RESUME goes through playEntry too
        val stale = service.substringAfter("private fun restartIfStale(): Boolean {").substringBefore("\n    }\n")
        assertTrue(stale.contains("loadedPlayStale(a.entry, spotifyEntriesNow[a.entry.id])"))
        assertTrue(stale.contains("switching.withLock { playEntry(a.entry.id) }"))
        assertTrue(service.substringAfter("private suspend fun resumeLast() {").substringBefore("\n    }\n").contains("if (isSpotify(current.entry)) return playEntry(current.entry.id)"))
        val command = service.substringAfter("override fun onCustomCommand(").substringBefore("when (customCommand.customAction)")
        // OUTPUT to a Sonos room starts the speaker (design 26a §3.3): the synchronous prefix reads its argument
        assertTrue(command.contains("val toRoom = customCommand.customAction == Commands.OUTPUT && Output.sonosUid(args.getString(Commands.ARG_OUTPUT)) != null"))
        assertTrue(command.contains("if ((customCommand.customAction in Commands.PLAYS || toRoom) && !playAllowed(suspendedNow())) {"))
        assertTrue("before the command coroutine", command.indexOf("!playAllowed(suspendedNow())") < command.indexOf("scope.launch {"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("val future")
        assertTrue(resumption.contains("if (!playAllowed(suspendedNow())) return"))
        // (Commands itself builds SessionCommands with Android Bundles: read, not loaded.)
        assertTrue(code("src/main/java/me/vibb/music/playback/PlayerHub.kt").contains("val PLAYS: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, RESUME, ROOM_JOIN)"))
        // The session is built on KidPlayer, so every controller goes through it - also after a
        // renderer switch (design 26a §0): every setPlayer wraps KidPlayer.
        assertTrue(service.contains("MediaSession.Builder(this, KidPlayer(renderer.player))"))
        assertEquals(1, Regex("""\.setPlayer\(""").findAll(service).count())
        assertTrue(service.contains("session?.setPlayer(KidPlayer(next.player))"))
    }

    @Test
    fun `a tap's command is waiting from its acceptance to its end, whatever ends it - the screens' switch holds on it`() {
        // The instant switch (logic/Switch.kt) holds longer while PlayerHub.State.waiting: a room switch's
        // hand-off or the look at a room delays the play by seconds (the merge of main, 2026-10-12).
        val command = service.substringAfter("override fun onCustomCommand(").substringBefore("return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))")
        assertTrue(command.contains("val moves = customCommand.customAction in Commands.MOVES"))
        assertTrue("counted before the coroutine", command.indexOf("if (moves) waitingMoves(+1)") < command.indexOf("scope.launch {"))
        assertTrue("and after it, also when cancelled", command.trimEnd().endsWith("}.invokeOnCompletion { if (moves) waitingMoves(-1) }"))
        assertTrue(service.substringAfter("private fun publish() {").substringBefore("\n    }\n").contains("waiting = waitingMoves.get() > 0,"))
        // design 27's swipe (SKIP_TO) waits behind a room switch like a tap (the merge of design 27)
        assertTrue(code("src/main/java/me/vibb/music/playback/PlayerHub.kt").contains("val MOVES: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, RESTORE, SKIP_TO)"))
    }

    @Test
    fun `the screens hold a tap's switch on the service's waiting and build it for the room that plays`() {
        // qa-26a-merge-code L1: without these the 3 s hold runs out into the old item on a room again.
        val root = code("src/main/java/me/vibb/music/ui/MusicRoot.kt")
        assertTrue(root.contains("requestStands(it.request, current?.ref, SystemClock.elapsedRealtime(), hub.waiting)"))
        assertTrue(root.contains("LaunchedEffect(switching, held != null, hub.waiting) {"))
        assertTrue(root.contains("delay(holdEndsAt(switching.request, hub.waiting) - SystemClock.elapsedRealtime())"))
        assertTrue(root.contains("val onRoom = hub.output.kind == OutputKind.SONOS"))
        assertTrue(root.contains("vm.switching = playSwitch(s, id, null, current, SystemClock.elapsedRealtime(), onRoom)"))
        assertTrue(root.contains("vm.switching = playSwitch(s, id, key, current, SystemClock.elapsedRealtime(), onRoom)"))
        assertTrue(root.contains("vm.switching = skipSwitch(s, n, forward, position, SystemClock.elapsedRealtime(), onRoom)"))
        // design 27's carousel swipe too (the merge of design 27): the room's queue, from 0 while it plays
        assertTrue(root.contains("swipeSwitch(s, n, target, current?.ref, held?.request, SystemClock.elapsedRealtime(), onRoom)"))
        assertEquals("every switch is built for the renderer", 4, Regex("""(playSwitch|skipSwitch|swipeSwitch)\(""").findAll(root).count())
    }

    @Test
    fun `the renderer changes in one place, which hands the service's events to the new one only`() {
        // qa-26a-step2-code M1: the old renderer is saved and paused before it is detached.
        val attach = service.substringAfter("private fun attach(next: Renderer) {").substringBefore("\n    }\n")
        assertTrue(attach.contains("old.saveNow(force = true)"))
        assertTrue(attach.indexOf("old.pause()") < attach.indexOf("old.onEvent = null"))
        assertTrue(attach.indexOf("next.onEvent = { onRendererEvent(it) }") < attach.indexOf("renderer = next"))
        assertTrue(attach.contains("session?.setPlayer(KidPlayer(next.player))"))
        // design 26c §5.10, qa-26c-step5b-code M1: a detached web player is told, after its events are cut
        assertTrue(attach.indexOf("old.onEvent = null") in 0 until attach.indexOf("if (old === spotify) spotify?.detached()"))
        assertEquals("onEvent is set only in attach (and cleared for the destroy tail, qa-26a-step3b-code L1)", 3, Regex("""\.onEvent = """).findAll(service).count())
        assertTrue(service.substringAfter("override fun onDestroy() {").contains("sonos.onEvent = null\n            sonos.saveNow(force = true)"))
        assertEquals(2, Regex("""\.onEvent = """).findAll(attach).count())
        assertEquals("renderer is assigned only in attach", 1, Regex("""\brenderer = """).findAll(service).count())
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onGetSession")
        assertTrue(onCreate.contains("attach(LocalRenderer("))
    }

    @Test
    fun `every command and playback resumption waits until the renderer is settled`() {
        // Design 26a §4.7: Home's RESUME after a restart must not start the phone under a playing speaker.
        val command = service.substringAfter("override fun onCustomCommand(").substringBefore("when (customCommand.customAction)")
        assertTrue(command.contains("scope.launch {\n                rendererReady.await()\n"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("val restored = restore()")
        assertTrue(resumption.contains("scope.launch {\n                rendererReady.await()\n"))
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onGetSession")
        assertTrue("settled in onCreate", onCreate.contains("rendererReady.settle()"))
        assertEquals("and nowhere else", 1, Regex("""rendererReady\.settle\(""").findAll(service).count())
        // step 3b: at once on the phone, after the look at a room - and always (finally); RendererGateTest
        assertTrue(onCreate.contains("if (startupLook(choice)) adopt(choice)\n            } finally {\n                rendererReady.settle()"))
        // the room scope read first (qa-26a-step3c-code L7), inside the try: the gate settles whatever happens
        val startup = onCreate.substringAfter("scope.launch {\n            try {").substringBefore("} finally {")
        assertTrue(startup.contains("roomScope = allowedRoomsNow()") && startup.contains("if (startupLook(choice)) adopt(choice)"))
        assertFalse("no reset of renderer.json at start any more", onCreate.contains("RendererChoice.local"))
    }

    @Test
    fun `adopt at start sends nothing and starts nothing - our live stream or the phone`() {
        // design 26a §4.7: zero transport commands, the bar comes back paused unless the speaker plays on
        val adopt = service.substringAfter("private suspend fun lookAtRoom(choice: RendererChoice) {").substringBefore("\n    }\n")
        assertTrue(service.contains("private suspend fun adopt(choice: RendererChoice) = switching.withLock { lookAtRoom(choice) }"))
        assertTrue(adopt.indexOf("SonosTail.join()") < adopt.indexOf("sonos.probe("))
        assertTrue(adopt.contains("val verdict = roomVerdict(choice, live, ofEntry)"))
        // qa-26a-step3b-code M1: back to the phone only on a definite answer; ours but not rebuildable: one Stop
        val notAdopted = adopt.substringAfter("if (queue == null || live == null || !renderer.kind.onPhone) {").substringBefore("\n        val (a, index) = queue")
        assertTrue(notAdopted.contains("if (verdict == RoomVerdict.OURS_LIVE) sonos.stopUnadopted()"))
        assertTrue(notAdopted.indexOf("if (verdict == RoomVerdict.NO_ANSWER) {") < notAdopted.indexOf("home.write(RendererChoice.local(home.choice()))"))
        for (start in listOf(".play()", "start(", "resumeLast", "playTile", "playPause")) assertFalse(start, adopt.contains(start))
        // a recorded room is looked at again before the phone plays, and by the suspension
        val command = service.substringAfter("override fun onCustomCommand(").substringAfter("switching.withLock {").substringBefore("when (customCommand.customAction)")
        assertTrue(command.contains("if (customCommand.customAction in Commands.PLAYS) lookAgainBeforePlaying()"))
        assertTrue(service.substringAfter("override fun onPlaybackResumption(").substringAfter("switching.withLock {").trimStart().startsWith("lookAgainBeforePlaying()"))
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        val probe = renderer.substringAfter("suspend fun probe(").substringBefore("\n    }\n")
        assertTrue(probe.contains("transport.adoptLive("))
        val transport = code("src/main/java/me/vibb/music/sonos/SonosTransport.kt")
        val adoptLive = transport.substringAfter("suspend fun adoptLive(").substringBefore("\n    }\n")
        for (verb in listOf(".play(", ".pause(", ".stop(", ".seek(", "setAvTransportUri", "setVolume", "setMute")) assertFalse(verb, adoptLive.contains(verb))
        // resumption on a room starts the speaker (the controller asks mayPlay), never Exo, after the gate
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("val restored = restore()")
        assertTrue(resumption.contains("if (!renderer.kind.onPhone) {"))
        assertTrue(resumption.substringAfter("if (!renderer.kind.onPhone) {").substringBefore("return@launch").contains("resumeLast()\n                        future.setException("))
    }

    @Test
    fun `the suspension stops a room with or without the service - the receiver's path`() {
        // design 26a §6.1 (QA H4, M5)
        val app = code("src/main/java/me/vibb/music/MusicApp.kt")
        val receiver = app.substringAfter("class SuspendedReceiver").substringBefore("private companion object")
        assertTrue(receiver.contains("val pending = goAsync()"))
        assertTrue(receiver.contains("if (service != null) service().join() else SonosSuspension.standalone(app)"))
        assertTrue(receiver.contains("withTimeoutOrNull(SUSPEND_BUDGET_MS)"))
        assertTrue(receiver.contains("} finally {\n                pending.finish()"))
        assertTrue(service.contains("PlayerHub.suspendStop = { scope.launch { suspendStop() } }"))
        val stop = service.substringAfter("private suspend fun suspendStop() {").substringBefore("\n    }\n")
        assertTrue(stop.indexOf("rendererReady.await()") < stop.indexOf("switching.withLock {"))
        assertTrue(stop.contains("withTimeoutOrNull(HANDOFF_MS) { sonos.park() } ?: run {"))
        assertTrue(stop.indexOf("sonos.park()") < stop.indexOf("comeHome(sonos, play = false)"))
        // the standalone path: the classify (retried in its budget, no aux), the bookmark from it, Stop and the park,
        // renderer.json -> the phone
        val lifecycle = code("src/main/java/me/vibb/music/sonos/SonosLifecycle.kt")
        val seq = lifecycle.substringAfter("suspend fun suspensionStop(").substringBefore("\n}\n")
        assertTrue(seq.contains("live = transport.adoptLive(uid, kindOfUri(uri), uri, budget, aux = false) { null }"))
        assertTrue(seq.contains("verdict = roomVerdict(choice, live, ofEntry)"))
        assertTrue(seq.indexOf("bookmark(live!!.copy(ours = true))") < seq.indexOf("transport.park(uid, left)"))
        val standalone = code("src/main/java/me/vibb/music/sonos/SonosSuspension.kt")
        assertTrue("a tail first (L2)", standalone.indexOf("SonosTail.join(TAIL_WAIT_MS)") < standalone.indexOf("val home = SonosHome(app)"))
        assertTrue("the phone only on a definite answer (M1)", standalone.contains("if (verdict != RoomVerdict.NO_ANSWER) home.write(RendererChoice.local(choice))"))
        val local = stop.substringAfter("if (sonos == null) {").substringBefore("return@withLock")
        assertTrue(local.contains("if (withContext(writer) { home.choice() }.sonos) SonosSuspension.standalone(this@PlaybackService)"))
        assertTrue("never more than was measured", standalone.contains("suspensionBookmark(itemKey, live, savedS, SystemClock.elapsedRealtime())"))
        val transport = code("src/main/java/me/vibb/music/sonos/SonosTransport.kt")
        val park = transport.substringAfter("suspend fun park(").substringBefore("\n    }\n")
        assertTrue(park.indexOf("it.stop()") < park.indexOf("it.setAvTransportUri(queueUri(uid), \"\")"))
        assertTrue(park.contains("session.disarm()"))
        // the full stop when the service goes (§6.2): the hand-off runs on, the speaker let go after it
        val destroy = service.substringAfter("override fun onDestroy() {").substringBefore("super.onDestroy()")
        assertTrue(destroy.contains("SonosTail.started(\n                tail.launch {\n                    try {\n                        handOff(sonos)\n                    } finally {\n                        sonos.release()"))
    }

    @Test
    fun `the service reaches the player only through the renderer, which alone persists positions`() {
        // Design 26a §3.1 (QA H3): no direct ExoPlayer left in the service; saveNow is the one save.
        assertFalse(service.contains("exo."))
        assertFalse(service.contains("ExoPlayer"))
        assertFalse(service.contains("savePosition"))
        // The display position is never stored (qa-26a-step2-code M2): its six readers are PlayerHub.position,
        // previous's 3 s rule, the resume overlap, the launcher's setNowPlaying, the seek back to where a
        // changed source failed (design 30 §1.6, playOnUncached) and the held window (qa-30-s3b-code L5) - no other.
        assertEquals(6, Regex("""positionMs\(\)""").findAll(service).count())
        assertTrue(service.contains("val remaining = length(current)?.let { (it - renderer.positionMs()).coerceAtLeast(0) }"))
        assertTrue(service.contains("val pos = renderer.positionMs()\n        val entryId = a.entry.id\n        a.uncached += item.key"))
        assertTrue(service.contains("PlayerHub.position = { renderer.positionMs() }"))
        assertTrue(service.contains("previousAction(renderer.currentIndex(), renderer.itemCount(), renderer.positionMs(), duration)"))
        assertTrue(service.contains("renderer.seekTo(overlapStart(renderer.positionMs(), overlap))"))
        assertTrue(service.contains("putLong(NowPlayingKeys.POSITION_MS, renderer.positionMs())"))
        assertFalse(Regex("""\.(currentPosition|contentPosition)\b""").containsMatchIn(service))
        // The service's own rows: the done mark (markDone) and the next item's bookmark at 0 (saveBookmark).
        assertEquals(1, Regex("""PositionRow\(""").findAll(service).count())
        assertTrue(service.substringAfter("private fun markDone(").substringBefore("\n    }\n").contains("PositionRow("))
        assertEquals(1, Regex("""NowPlayingRow\(""").findAll(service).count())
        assertEquals(1, Regex("""BookmarkRow\(""").findAll(service).count())
        val saveBookmark = service.substringAfter("private fun saveBookmark(").substringBefore("\n    }\n")
        assertTrue(saveBookmark.contains("NowPlayingRow(") && saveBookmark.contains("BookmarkRow(a.entry.key, item.key, 0, now)"))
        val local = code("src/main/java/me/vibb/music/playback/LocalRenderer.kt")
        assertTrue(local.substringAfter("override fun saveNow(force: Boolean)").contains("val pos = exo.currentPosition"))
    }

    @Test
    fun `design 27's swipe and late art go through the renderer - a room steps the speaker, the art is the player's only`() {
        // The merge of design 27 (design 26a): SKIP_TO counts in the renderer's queue (a room's is the
        // speaker-playable Active.items) and moves through goTo -> renderer.seekTo, a room's stepTo.
        val skipTo = service.substringAfter("private fun skipTo(args: Bundle) {").substringBefore("\n    }\n")
        assertTrue(skipTo.contains("if (renderer.itemCount() == 0) return"))
        assertTrue(skipTo.contains("swipeTarget(loaded, a.items.map { it.key }, renderer.currentIndex(), "))
        assertTrue(skipTo.contains("goTo(a, target, args.getBoolean(Commands.ARG_FORWARD, true))"))
        val goTo = service.substringAfter("private fun goTo(a: Active, index: Int, forward: Boolean) {").substringBefore("\n    }\n")
        assertTrue(goTo.contains("renderer.seekTo(target, startPos(a, target))"))
        val sonos = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue("another index is a step", sonos.contains("override fun seekTo(index: Int, ms: Long) = if (index == controller.index) controller.seek(ms) else controller.stepTo(index, ms)"))
        // THEN_SKIP_TO after a restore, like THEN_NEXT
        assertTrue(service.substringAfter("private suspend fun restoreThen(").substringBefore("\n    }\n").contains("Commands.THEN_SKIP_TO -> skipTo(args)"))
        // A late picture swaps the session player's item in place, on either renderer; the speaker gets nothing.
        val refresh = service.substringAfter("private fun refreshArt() {").substringBefore("\n    }\n")
        assertTrue(refresh.contains("renderer.replaceItem(index, "))
        assertTrue(refresh.contains("renderer.itemAt(it)?.mediaId == mediaId"))
        val replace = sonos.substringAfter("override fun replaceItem(index: Int, item: MediaItem) {").substringBefore("\n    }\n")
        assertTrue(replace.contains("mediaItems = mediaItems.toMutableList().also { it[index] = item }"))
        assertTrue(replace.contains("sbp.refresh()"))
        assertFalse("no SOAP for a picture", replace.contains("controller.") || replace.contains("actor") || replace.contains("transport"))
        assertTrue(code("src/main/java/me/vibb/music/playback/LocalRenderer.kt").contains("override fun replaceItem(index: Int, item: MediaItem) = exo.replaceMediaItem(index, item)"))
        // the want set's window and the item change follow the renderer's index
        assertTrue(service.contains("nowWindow(a.items, renderer.currentIndex(), reach = 1)"))
        val changed = service.substringAfter("private fun onItemChanged(index: Int, auto: Boolean) {").substringBefore("\n    }\n")
        assertTrue(changed.contains("wantArtSoon()\n        refreshArt()"))
    }

    @Test
    fun `a room switch finds the playing item by key - a late picture's copies can't make it start item 0`() {
        // qa-26a-merge27-code L1: refreshArt (not under `switching`) may replace Active.items with copies
        // (withItemArt) while toRoom waits for a hand-off; an object lookup then misses the item.
        val queue = listOf("k1", "k2", "k3").map { me.vibb.music.model.PlayItem(5, it, "Title $it", "https://cdn.example.org/$it.mp3", false, 600_000) }
        val captured = queue[1]
        val swapped = withItemArt(queue, "k2", "a".repeat(64))
        assertEquals("the object is gone", -1, swapped.indexOf(captured))
        assertEquals("the key still finds it", 1, swapped.indexOfFirst { it.key == captured.key })
        val toRoom = service.substringAfter("private suspend fun toRoom(uid: String) {").substringBefore("\n    }\n")
        assertTrue(toRoom.contains("a0.items.indexOfFirst { it.key == item.key }"))
        assertFalse(toRoom.contains("indexOf(item)"))
    }

    @Test
    fun `the Home card's resume is honoured once, never from Recents or a restored instance`() {
        assertTrue(resumeRequested(extra = true, flags = Intent.FLAG_ACTIVITY_NEW_TASK, restored = false))
        assertFalse("from Recents, also after a reboot", resumeRequested(true, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, false))
        assertFalse("a restored instance", resumeRequested(true, 0, restored = true))
        assertFalse("not asked", resumeRequested(false, 0, false))
        val activity = code("src/main/java/me/vibb/music/ui/MainActivity.kt")
        assertTrue(activity.contains("if (resumeRequested(intent.getBooleanExtra(EXTRA_RESUME, false), intent.flags, restored)) {\n            intent.removeExtra(EXTRA_RESUME)"))
        assertEquals("onCreate says whether it is restored", 1, Regex("open\\(intent, restored = savedInstanceState != null\\)").findAll(activity).count())
    }

    @Test
    fun `the launcher hears the position with every 15 s save`() {
        val tick = service.substringAfter("private val saveTick").substringBefore("private fun onRendererEvent")
        assertTrue(tick.contains("renderer.saveNow()"))
        assertTrue(tick.contains("reportNowPlaying(refresh = true)"))
        // and the held window moves with the position (design 30 §1.6, qa-30-s3b-code L5)
        assertTrue(tick.contains("updateHeld()"))
    }

    private val sonos by lazy { code("src/main/java/me/vibb/music/sonos/SonosController.kt") }

    @Test
    fun `every way onto a Sonos room is gated, and a switch home plays on only when allowed`() {
        // design 26a §3.3, §4.6: the room is reached only from the OUTPUT command (gated above)
        assertEquals("toRoom: defined once, called once", 2, Regex("""\btoRoom\(""").findAll(service).count())
        assertTrue(service.contains("if (uid != null) return toRoom(uid)"))
        assertEquals("output: defined once, called from OUTPUT only", 2, Regex("""\boutput\(""").findAll(service).count())
        assertTrue(service.contains("Commands.OUTPUT -> output(args.getString(Commands.ARG_OUTPUT) ?: Output.PHONE_KEY)"))
        // coming home plays on only when the speaker was PLAYING and nothing forbids it; a failed start never plays
        assertTrue(service.contains("comeHome(sonos, (resume ?: wasPlaying) && playAllowed(suspendedNow()))"))
        assertEquals(5, Regex("""\bcomeHome\(""").findAll(service).count() - 1)
        assertEquals("lost, nothing playable, the suspension, the room scope", 4, Regex("""comeHome\((old|sonos), play = false(, quiet = true)?\)""").findAll(service).count())
        // the renderer changes only in attach: the phone at start, an adopted room, a picked room, the phone again, and
        // the phone's renderer for an entry (design 26c §5.10: the phone's own player or Spotify's web player)
        assertEquals("attach: defined once, five callers", 6, Regex("""\battach\(""").findAll(service).count())
        assertTrue(service.substringAfter("private fun usePhoneRenderer(entry: Entry) {").substringBefore("\n    }\n").contains("attach(next)"))
        assertTrue(service.contains("attach(sonosRenderer(uid, name))"))
        assertTrue(service.contains("attach(sonos)\n        active = a"))
        assertEquals("every room's renderer holds the locks", 1, Regex("""SonosRenderer\(""").findAll(service).count())
        assertTrue(service.contains("onArmedPlaying = { locks.hold(owner, it) },"))
        assertTrue(service.contains("roomAllowed = { uid, accepted -> sessionAllowed(roomScope, home.rooms.state.value.rooms, uid, accepted) },"))
        assertTrue(service.substringAfter("private suspend fun comeHome(").substringBefore("sonos.release()\n        active = null").contains("if (renderer !== sonos) return\n        attach(local)"))
        // a headset arriving or leaving touches the output choice only, never the renderer (sonos_renderer.py 5)
        val devices = service.substringAfter("private val deviceCallback").substringBefore("private val saveTick")
        assertFalse(devices.contains("renderer."))
    }

    @Test
    fun `a Sonos position is persisted only by bookmarkNow, from the measured snapshot`() {
        // design 26a §4.4 (QA H3): saveNow is bookmarkNow; never a seek's target, the pause freeze or the extrapolation
        assertEquals(1, Regex("""store\.save\(""").findAll(sonos).count())
        val bookmark = sonos.substringAfter("fun bookmarkNow(force: Boolean) {").substringBefore("\n    }\n")
        assertTrue(bookmark.contains("store.save("))
        assertTrue(bookmark.contains("BookmarkInput(pl.itemKey, s.ours, s.transport, s.relS, s.durS, durFromListing, age)"))
        for (guess in listOf("optPos", "frozen", "positionAt", "spec(", "startPosMs")) assertFalse(guess, bookmark.contains(guess))
        assertTrue("the seed of our own play is not a measurement", bookmark.contains("if (seeded ||"))
        // design 53-d (qa-53d-code 1): the one other write is a live read's, before a step, a pick or a stop - the same
        // measured-only input (the speaker's own duration), through the store the controller was given or pinned
        val live = sonos.substringAfter("private fun bookmarkLive(").substringBefore("\n    }\n")
        assertEquals(1, Regex("""\bto\.save\(""").findAll(sonos).count())
        assertTrue(live.contains("BookmarkInput(pl.itemKey, true, f.transport, f.relS, f.durS, false, age)"))
        assertTrue(live.contains("if (closed || !f.reachable || !f.ours || normUri(f.uri) != normUri(pl.uri)) return"))
        for (guess in listOf("optPos", "frozen", "positionAt", "spec(", "startPosMs", "anchor")) assertFalse(guess, live.contains(guess))
        assertTrue(sonos.substringAfter("private fun bookmarkLiveFirst(").substringBefore("\n    }\n").contains("if (closed || !session || !ownsSpeaker || seeded || pending != null) return"))
        // the snapshot is only ever replaced by accept (a poll, a live classify) or the seed of a play
        assertEquals(2, Regex("""\bsnap = """).findAll(sonos).count())
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("override fun saveNow(force: Boolean) = controller.bookmarkNow(force)"))
        // one write behind both renderers' saveNow
        val main = moduleFile("src/main/java").walk().filter { it.isFile && it.extension == "kt" }
            .map { f -> f.name to f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "") }.toList()
        val calls = main.flatMap { (name, text) -> Regex("""persistPlace\(""").findAll(text).map { name }.toList() }
        // design 26c §5.4: Spotify's bookmark is the tracker's measurement, through the same write
        // (SonosRenderer's second: its pinned store, design 53-d - the old queue's place, its row only while it is the service's)
        assertEquals(listOf("LocalRenderer.kt", "LocalRenderer.kt", "SonosRenderer.kt", "SonosRenderer.kt", "SpotifyRenderer.kt"), calls.sorted())
        assertTrue(renderer.contains("withQueue = queue() === a)"))
        // ... and one write behind every place: persistPlace and the suspension's standalone bookmark (qa-26a-step3b-code L6)
        val writes = main.flatMap { (name, text) -> Regex("""writePlace\(""").findAll(text).map { name }.toList() }
        assertEquals(listOf("LocalRenderer.kt", "LocalRenderer.kt", "SonosSuspension.kt"), writes.sorted())
        for ((name, text) in main) {
            if (name != "LocalRenderer.kt" && name != "PlaybackService.kt" && name != "MusicDb.kt") {
                for (row in listOf("upsertPosition(", "upsertBookmark(")) assertFalse("$name writes $row", text.contains(row))
            }
        }
    }

    @Test
    fun `fix round - skips never start a paused speaker, every Sonos play asks playAllowed, switches are serialized`() {
        // qa-26a-step3a-code H1
        val step = sonos.substringAfter("fun stepTo(idx: Int, ms: Long) {").substringBefore("\n    }\n")
        assertTrue(step.contains("if (!session || effectiveTransport(now) != TransportState.PLAYING || !mayPlay()) {"))
        assertTrue(step.indexOf("startPending = true") < step.indexOf("pending = Pending("))
        val play = sonos.substringAfter("private fun playIndex(").substringBefore("\n    }\n")
        assertTrue("before anything is sent", play.indexOf("if (!mayPlay()) {") in 0 until play.indexOf("work.submit<PlayResult?>("))
        assertTrue(sonos.substringAfter("private fun resume(").substringBefore("\n    }\n").contains("if (!mayPlay()) return"))
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("mayPlay = { playAllowed(suspendedNow()) && roomAllowed(this@SonosRenderer.uid, controller.accepted) },"))
        // M2: the command coroutine and resumption hold the switch lock; lost() takes it too
        val command = service.substringAfter("override fun onCustomCommand(").substringAfter("rendererReady.await()").substringBefore("return Futures")
        assertTrue(command.trimStart().startsWith("switching.withLock {\n"))
        assertTrue(command.contains("when (customCommand.customAction) {"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringAfter("rendererReady.await()")
        assertTrue(resumption.trimStart().startsWith("switching.withLock {"))
        assertTrue(service.contains("private suspend fun lost() = switching.withLock {"))
        // M1: a hand-off that times out still stops the speaker; the release drains the actor
        assertTrue(service.contains("withTimeoutOrNull(HANDOFF_MS) { sonos.handOff() } ?: run {"))
        assertTrue(service.substringAfter("withTimeoutOrNull(HANDOFF_MS) { sonos.handOff() } ?: run {").substringBefore("}").contains("sonos.abandon()"))
        val release = renderer.substringAfter("override fun release() {").substringBefore("\n    }\n")
        assertTrue(release.contains("actor.close()"))
        assertFalse("never cancelled before the queued Stop", release.contains("cancel()"))
    }

    @Test
    fun `the parent's room scope - the sheet, the switch refused, a dropped room left at the sync, after adopt, on a regroup and a follow`() {
        // design 26a §5.5: the sheet lists only allowed rooms (a group only with every member allowed, RoomScopeTest)
        val publish = service.substringAfter("private fun publish() {").substringBefore("\n    }\n")
        // ... and our own row with the rooms the kids grouped into it by hand (design 53 §2.8, Q4)
        assertTrue(publish.contains("rooms = allowedRooms(home.rooms.state.value, roomScope, sonos?.uid, sonos?.accepted.orEmpty()),"))
        assertEquals("the sheet's rows in publish, the belt's groups, the join's and the leave's look", 4, Regex("""home\.rooms\.state\.value""").findAll(service).count())
        // OUTPUT to a room outside the scope (or grouped with one): refused with its own line before anything moves -
        // after the same-room shortcut (design 53 §2.8: a tap on our own row is the same-tile rule, whatever the kids grouped in)
        val toRoom = service.substringAfter("private suspend fun toRoom(uid: String) {").substringBefore("\n    }\n")
        val shortcut = toRoom.substringBefore("if (!groupAllowed(")
        assertTrue(shortcut.contains("if (old is SonosRenderer && old.uid == uid) {\n            old.play()"))
        assertTrue("the scope's check exists, after the shortcut", toRoom.indexOf("if (!groupAllowed(") > toRoom.indexOf("old.uid == uid"))
        val refusal = toRoom.substring(shortcut.length).substringBefore("val name = ")
        assertTrue(refusal.contains("if (!groupAllowed(allowedRoomsNow(), roomsNow(), uid)) {"))
        assertTrue(refusal.contains("PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.ROOM_NOT_ALLOWED)"))
        assertTrue(refusal.contains("return"))
        for (move in listOf("handOff(", "saveNow(", "home.write(", "attach(", "start(", ".play()")) assertFalse(move, refusal.contains(move))
        // a session on a room not allowed as grouped now: the bookmark and the ours-gated Stop (the hand-off) before the phone, paused
        val leave = service.substringAfter("private suspend fun leaveDisallowedRoom() {").substringBefore("\n    }\n")
        assertTrue(leave.contains("if (!mustLeaveRoom(sonos.uid, roomsNow(), allowedRoomsNow(), sonos.accepted)) return"))
        assertTrue(leave.contains("PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.ROOM_NOT_ALLOWED)"))
        assertFalse("its own line (qa-26a-step3c-code L1)", leave.contains("CANT_PLAY_ON_SONOS"))
        assertTrue(leave.indexOf("handOff(sonos)") in 0 until leave.indexOf("comeHome(sonos, play = false, quiet = true)"))
        val gate = service.substringAfter("private suspend fun leaveIfNotAllowed() {").substringBefore("\n    }\n")
        assertTrue(gate.contains("rendererReady.await()\n        switching.withLock { leaveDisallowedRoom() }"))
        // ... at the next sync, when the groups change (M1), and after a follow (M1)
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onGetSession")
        val sync = onCreate.substringAfter("store.libraries().map { it?.sonosRooms }.distinctUntilChanged()")
        assertTrue(sync.contains("roomScope = rooms\n                publish()\n                leaveIfNotAllowed()"))
        // design 53 §2.8: the renderer hears the members first (a room grouped in by hand accepted and capped), then the sheet and the look
        val collector = onCreate.substringAfter("home.rooms.state.collect { view ->").substringBefore("\n            }\n")
        assertTrue(collector.indexOf("(renderer as? SonosRenderer)?.topology(view.rooms)") in 0 until collector.indexOf("publish()"))
        assertTrue(collector.indexOf("publish()") < collector.indexOf("if (renderer is SonosRenderer) leaveIfNotAllowed()"))
        assertTrue(service.contains("onFollowed = { scope.launch { leaveIfNotAllowed() } },"))
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("writeChoice { it.followed(uid, name, entry, itemKey, uri) }\n            onFollowed()"))
        // the group watch (design 53 §2.8): while a room plays, under a limited scope or without a healthy event socket -
        // never with a discovery round
        val watch = onCreate.substringAfter("delay(SCOPE_WATCH_MS)").substringBefore("\n        publish()")
        assertTrue(watch.contains("if (sonos.showsPlaying && (roomScope != null || !sonos.socketHealthy)) withContext(Dispatchers.IO) { home.rooms.refreshQuietly() }"))
        val quietly = code("src/main/java/me/vibb/music/sonos/SpeakerCache.kt").substringAfter("fun refreshQuietly(): Boolean = try {").substringBefore("\n    }\n")
        assertFalse(quietly.contains("rescanInBackground") || quietly.contains("round("))
        // the scope is read before the startup look and the first rows (L7)
        val startup = onCreate.substringAfter("scope.launch {\n            try {").substringBefore("} finally {")
        assertTrue(startup.indexOf("roomScope = allowedRoomsNow()") in 0 until startup.indexOf("adopt(choice)"))
        // ... and after an adopt of our stream on such a room (the look holds the switch lock)
        val look = service.substringAfter("private suspend fun lookAtRoom(choice: RendererChoice) {").substringBefore("\n    }\n")
        assertTrue(look.trimEnd().endsWith("leaveDisallowedRoom()"))
        assertTrue(look.indexOf("sonos.adopt(") in 0 until look.indexOf("leaveDisallowedRoom()"))
        assertEquals("defined once; the gate and the look", 3, Regex("""\bleaveDisallowedRoom\(""").findAll(service).count())
        // the belt: every play the room's controller sends asks the scope too
        assertTrue(renderer.contains("&& roomAllowed(this@SonosRenderer.uid, controller.accepted) },"))
        assertTrue(service.contains("roomAllowed = { uid, accepted -> sessionAllowed(roomScope, home.rooms.state.value.rooms, uid, accepted) },"))
        // design 53 §2.7: the "+" and "−" belts - a join only where offered and in the scope, a leave only of a member (M21)
        val join = service.substringAfter("private fun roomJoin(sonos: SonosRenderer, row: String) {").substringBefore("\n    }\n")
        assertTrue(join.contains("if (target == null || !joinOffered(view, ours, c.liveSession(now), roomScope, row) || !playAllowed(suspendedNow())) {"))
        assertTrue(join.indexOf("!joinOffered(") < join.indexOf("home.verbs.join("))
        val leave2 = service.substringAfter("private fun roomLeave(sonos: SonosRenderer, member: String) {").substringBefore("\n    }\n")
        assertTrue(leave2.indexOf("if (!leaveOffered(view, c.uid, c.liveSession(SystemClock.elapsedRealtime()), member)) {") in 0 until leave2.indexOf("home.verbs.leave("))
        // the kid's line, both languages
        assertTrue(code("src/main/java/me/vibb/music/ui/MusicRoot.kt").contains("PlayerHub.Message.ROOM_NOT_ALLOWED -> R.string.room_not_allowed"))
    }

    @Test
    fun `the stream switch is read live from the managed config`() {
        // design 30 §1.1, §5.2: the player asks the streaming switch, the download job and the sync the downloads switch
        val store = code("src/main/java/me/vibb/music/data/Store.kt")
        assertTrue(store.contains("get() = Bridge.config(app).takeIf { it.managed }?.streamMobileData ?: unmanagedSwitch(RestrictionKeys.STREAM_MOBILE_DATA)"))
        assertTrue(store.contains("get() = Bridge.config(app).takeIf { it.managed }?.downloadMobileData ?: unmanagedSwitch(RestrictionKeys.DOWNLOAD_MOBILE_DATA)"))
        assertFalse("the one old switch is gone", Regex("\\bmobileData\\b").containsMatchIn(store))
        assertTrue(service.contains("streamMobileData: Boolean = store.streamMobileData"))
        assertTrue(service.contains("val streamMobileData = store.streamMobileData"))
        assertTrue(service.contains("!streamAllowed(netCaps(this@PlaybackService), store.streamMobileData)"))
        assertFalse(service.contains("downloadMobileData"))
        val downloads = code("src/main/java/me/vibb/music/sync/Downloads.kt")
        val sync = code("src/main/java/me/vibb/music/sync/Sync.kt")
        assertTrue(downloads.contains(".setRequiredNetworkType(if (downloadMobileData) NetworkType.NOT_ROAMING else NetworkType.UNMETERED)"))
        assertTrue(downloads.contains("downloadWaiting(netCaps(applicationContext), store.downloadMobileData, false)"))
        assertTrue(sync.contains("downloadWaiting(netCaps(context), store.downloadMobileData, storageLow)"))
        assertTrue(sync.contains("val downloadMobileData = store.downloadMobileData"))
        assertEquals(2, Regex("Downloads\\.enqueue\\(applicationContext, store\\.downloadMobileData").findAll(downloads).count())
        for (text in listOf(downloads, sync)) assertFalse(text.contains("streamMobileData"))
        // qa-30-s3a-code L1: the sync replaces a running job where the downloads switch now forbids it; the
        // worker's own re-enqueues never do (it would cancel itself mid-report)
        assertTrue(sync.contains("val stop = stopRunningDownload(netCaps(context), downloadMobileData)"))
        val enqueues = Regex("Downloads\\.enqueue\\(context, [^\\n]*").findAll(sync).map { it.value }.toList()
        assertEquals(2, enqueues.size)
        assertTrue(enqueues.toString(), enqueues.all { it.startsWith("Downloads.enqueue(context, downloadMobileData") && it.contains("stopRunning = stop)") })
        assertFalse(downloads.substringAfter("class DownloadWorker").contains("stopRunning"))
        val enqueue = downloads.substringAfter("fun enqueue(").substringBefore("\n    }\n")
        assertTrue(enqueue.contains("val running = !stopRunning && runCatching { wm.getWorkInfosForUniqueWork(WORK).get() }"))
        assertTrue(enqueue.contains("if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE"))
        // qa-30-s3a-code L2: unmanaged (the debug feed's prefs, same key names) the managed config's own rules ...
        assertTrue(store.contains("private fun unmanagedSwitch(key: String): Boolean = mobileSwitch(prefs.all, key)"))
        assertTrue(store.contains("return if (config.managed) config.storageLimitGb else storageLimit(prefs.all)"))
        // ... and the feed: a bare mobile_data sets both switches, then a key of its own wins
        val feed = code("src/debug/java/me/vibb/music/debugfeed/DebugFeedReceiver.kt")
        val bare = feed.indexOf("config?.get(\"mobile_data\")?.jsonPrimitive?.booleanOrNull?.let {\n                store.streamMobileData = it\n                store.downloadMobileData = it\n            }")
        assertTrue(bare >= 0)
        assertTrue(bare < feed.indexOf("config?.get(\"stream_mobile_data\")?.jsonPrimitive?.booleanOrNull?.let { store.streamMobileData = it }"))
        assertTrue(bare < feed.indexOf("config?.get(\"download_mobile_data\")?.jsonPrimitive?.booleanOrNull?.let { store.downloadMobileData = it }"))
        assertTrue(feed.contains("config?.get(\"storage_limit_gb\")?.jsonPrimitive?.intOrNull?.let { store.storageLimitGb = it }"))
    }

    @Test
    fun `the parent's volume cap reaches every volume set of our own on a Sonos room`() {
        // design 26a §5.3: read live from the managed config, as the mobile-data switches
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("cap = { Bridge.config(context).volumeCapPct },"))
        val set = sonos.substringAfter("fun setVolume(volume: Int) {").substringBefore("\n    }\n")
        assertTrue(set.contains("val v = volumeTarget(volume, cap())"))
        assertTrue(sonos.substringAfter("private fun stepVolume(base: Int, delta: Int) {").substringBefore("\n    }\n").contains("setVolume(base + delta)"))
        assertEquals(2, Regex("""stepVolume\(""").findAll(sonos.substringAfter("fun adjustVolume(delta: Int) {").substringBefore("\n    }\n")).count())
        assertTrue(renderer.contains("handling { controller.setDeviceVolume(deviceVolume) }"))
        // design 53 §2.12: a lone room as before; a group's every room through groupMove/groupStep, each clamped to the cap
        val device = sonos.substringAfter("fun setDeviceVolume(volume: Int) {").substringBefore("\n    }\n")
        assertTrue(device.contains("if (!groupLive()) return setVolume(volume)"))
        assertTrue(device.contains("setRooms(groupMove(deviceBase, volume, cap()))"))
        assertTrue(sonos.substringAfter("fun adjustVolume(delta: Int) {").substringBefore("\n    }\n").contains("groupStep(roomVolumes(), delta, cap())"))
        assertTrue(sonos.substringAfter("fun setRoomVolume(room: String, volume: Int) {").substringBefore("\n    }\n").contains("volumeTarget(volume, cap())"))
    }

    @Test
    fun `a Sonos room holds no audio focus, and the call backstop resumes only what it paused`() {
        // design 26a §6.7: the phone's player stands down to IDLE (a paused Exo keeps its focus)
        assertTrue(service.contains("if (old is SonosRenderer) old.release() else local.standDown()"))
        val local = code("src/main/java/me/vibb/music/playback/LocalRenderer.kt")
        assertTrue(local.substringAfter("fun standDown() {").substringBefore("\n    }\n").contains("exo.stop()"))
        assertTrue(service.contains("audio.addOnModeChangedListener(mainExecutor, modeListener)"))
        assertTrue(service.contains("audio.removeOnModeChangedListener(modeListener)"))
        val step = service.substringAfter("private fun callStep() {").substringBefore("\n    }\n")
        // the audio mode counts on a room and on the web player (`callOn`, emulator round 2026-10-14 N3)
        assertTrue(step.contains("val inCall = callOn(launcherInCall, modeInCall, r.kind)"))
        assertTrue(service.contains("modeInCall = callMode(audio.mode)\n        audio.addOnModeChangedListener(mainExecutor, modeListener)"))
        assertTrue(step.contains("callPauseStep(callPause, inCall, playing, paused, playAllowed(suspendedNow()))"))
        // the same entry point as the session's play (qa-26a-step3b-code L7)
        assertTrue(step.contains("CallAction.PLAY -> if (callRenderer === r) r.player.play()"))
    }

    @Test
    fun `a call never cancels the sleep timer, and a release never plays after the timer ended`() {
        // qa-26a-step3b-code H1: the launcher's hold is the service's own pause, not a session pause (USER)
        val step = service.substringAfter("private fun callStep() {").substringBefore("\n    }\n")
        assertTrue(step.contains("selfPausing = true\n                r.pause()\n                selfPausing = false"))
        val events = service.substringAfter("private fun onRendererEvent(").substringBefore("\n    }\n")
        assertTrue(events.contains("PauseCause.USER -> if (!selfPausing && (sleepEndsAt != null || sleepAtEnd)) setSleep(0)"))
        assertTrue(service.substringAfter("private fun sleepEnded() {").substringBefore("\n    }\n").contains("callPause = callTimerEnded(callPause)"))
        // the launcher's two commands, granted to a launcher signed like this app only, never a play gate bypass
        val connect = service.substringAfter("override fun onConnect(").substringBefore("override fun onCustomCommand(")
        assertTrue(connect.contains("controller.packageName in LAUNCHER_PACKAGES && Bridge.sameSigner(this@PlaybackService, controller.packageName)"))
        assertTrue(connect.contains("Commands.LAUNCHER.forEach { add(it) }"))
        assertTrue(service.contains("Commands.CALL_HOLD -> launcherCall(true)"))
        assertTrue(service.contains("Commands.CALL_RELEASE -> launcherCall(false)"))
        assertFalse(code("src/main/java/me/vibb/music/playback/PlayerHub.kt").substringAfter("val PLAYS").substringBefore("\n").contains("CALL"))
    }

    // Storytel (design 26b §4.2-§4.4, qa-26b-step1-code's notes for step 3)

    private fun mainSources() = moduleFile("src/main/java").walk().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `a Storytel book plays from its copy only - no upstream, never a request`() {
        val media = service.substringAfter("private fun mediaItem(a: Active, item: PlayItem): MediaItem {").substringBefore("val meta =")
        val storytel = media.substringAfter("} else if (a.entry.source == Source.STORYTEL) {").substringBefore("} else {")
        assertTrue(storytel.contains("b.setUri(sourceId(a.entry, item)).setCustomCacheKey(cacheKey)"))
        assertTrue(storytel.contains("b.setTag(ItemTag(cacheKey, hls = false, publicOnly = true, copyOnly = true))"))
        assertTrue("before the NRK/RSS branch, whose uri would be the row's", media.indexOf("Source.STORYTEL") < media.indexOf("download?.url"))
        val factory = code("src/main/java/me/vibb/music/media/ItemMediaSourceFactory.kt")
        assertTrue(factory.contains("MediaCache.playbackFactory(app, tag.publicOnly, keys, copyOnly = tag.copyOnly, through = through)"))
        val cache = code("src/main/java/me/vibb/music/media/MediaCache.kt")
        assertTrue(cache.contains(".setUpstreamDataSourceFactory(if (copyOnly) null else gated(context, http(context, publicOnly)))"))
        // and no sink: a book is never streamed, so never written by the player (design 30 §1.4)
        assertTrue(cache.contains(".setCacheWriteDataSinkFactory(if (copyOnly) null else through)"))
        // a copy plays without holding Wi-Fi (phase 1's rule, also for a book)
        assertTrue(service.contains("renderer.setWakeMode(if (hasCopy(a, item)) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK)"))
    }

    @Test
    fun `the mint runs in the download worker and a room's play job only, on IO, through the process's one StorytelApi`() {
        val sources = mainSources()
        val texts = sources.associate { f ->
            f.name to f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")
        }
        fun callers(token: String) = texts.filterValues { it.contains(token) }.keys
        assertEquals("assetUrl is called by the account only", setOf("StorytelApi.kt", "StorytelAccount.kt"), callers("assetUrl("))
        assertEquals(
            "a mint is the download run's and a room's play job's (design 26b §5.1)",
            setOf("StorytelAccount.kt", "DownloadRun.kt", "Downloads.kt", "SonosController.kt", "SonosRenderer.kt"),
            callers(".mint(") + callers("fun mint("),
        )
        assertEquals("one StorytelApi for the process", setOf("StorytelApi.kt", "StorytelAccount.kt"), callers("StorytelApi("))
        assertEquals(setOf("Downloads.kt", "Sync.kt", "SonosRenderer.kt"), callers("StorytelAccount.get("))
        val account = code("src/main/java/me/vibb/music/storytel/StorytelAccount.kt")
        // On IO and interruptible (the main session's review of step 3, Low 2; StorytelApiTest's cancelled mints).
        assertTrue(account.contains("suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint =\n        runInterruptible(Dispatchers.IO) { api.assetUrl(cid, timeoutMs, deadlineMs) }"))
        assertEquals("built in one place, kept while the switch says the same", 1, Regex("StorytelApi\\(").findAll(account).count())
        assertTrue(account.contains("instance?.takeIf { builtFor == key } ?: build("))
        val downloads = code("src/main/java/me/vibb/music/sync/Downloads.kt")
        assertTrue("a download's mint: 15 s, the whole within 60 s", downloads.contains("StorytelAccount.get(context).mint(cid, MINT_TIMEOUT_MS, SystemClock.elapsedRealtime() + MINT_BUDGET_MS)"))
        assertTrue(downloads.contains("const val MINT_TIMEOUT_MS = 15_000L") && downloads.contains("const val MINT_BUDGET_MS = 60_000L"))
        assertTrue("the worker runs on IO", downloads.contains("override suspend fun doWork(): Result = withContext(Dispatchers.IO) {"))
        // A room (design 26b §5.1): the renderer hands the controller the account's one signature ...
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("minter = { cid, timeoutMs, deadlineMs -> StorytelAccount.get(context).mint(cid, timeoutMs, deadlineMs) },"))
        // ... which mints only inside the play's actor job (IO), between the two token checks (M6), 6 s / 10 s
        val play = sonos.substringAfter("private fun playIndex(").substringBefore("\n    }\n")
        val job = play.substringAfter("work.submit<PlayResult?>({").substringBefore("}) { r ->")
        assertTrue(play.contains("val gate: () -> Boolean = if (cid != null) ({ stillWanted(g) }) else ({ !closed && mayPlay() })"))
        assertTrue("every play job asks first (L2)", job.trimStart().startsWith("if (!gate()) return@submit null\n"))
        assertTrue(job.contains("b = body.copy(uri = mintFor(cid), mint = null)\n                if (!gate()) return@submit null"))
        assertTrue("the mint before the transport's play (the cap and the hush inside it)", job.indexOf("mintFor(cid)") < job.indexOf("transport.play(b, startCap, room, gate)"))
        // ... and the token once more right before SetAVTransportURI, inside the play (L1)
        val transport = code("src/main/java/me/vibb/music/sonos/SonosTransport.kt").substringAfter("suspend fun play(").substringBefore("\n    }\n")
        assertEquals(2, Regex("""if \(!gate\(\)\) return@withLock null\n\s*spk\.setAvTransportUri\(""").findAll(transport).count())
        assertEquals(2, Regex("""setAvTransportUri\(""").findAll(transport).count())
        assertEquals("the job is the only caller", 2, Regex("""\bmintFor\(""").findAll(sonos).count())
        assertEquals(1, Regex("""minter\.mint\(""").findAll(sonos).count())
        assertTrue(sonos.substringAfter("private suspend fun mintFor(").contains("minter.mint(cid, MINT_TIMEOUT_MS, elapsedMs() + MINT_BUDGET_MS)"))
        assertTrue(sonos.contains("const val MINT_TIMEOUT_MS = 6_000L") && sonos.contains("const val MINT_BUDGET_MS = 10_000L"))
        assertTrue("the token reads thread-safe state", sonos.contains("private fun stillWanted(g: Long): Boolean = !closed && generation.get() == g && mayPlay()"))
        assertTrue(sonos.contains("@Volatile\n    var closed: Boolean = false"))
        // the hand-off can queue behind a mint: 25 s (design 26b §5.1), the tail and the switch's hold with it
        assertTrue(service.contains("const val HANDOFF_MS = 25_000L"))
    }

    @Test
    fun `every call site reads the snapshot of the renderer section 5_6 names (QA H2)`() {
        // design 26b §5.6: the queue for whoever plays now; a room's look, adopt and switch the room's view;
        // coming home the phone's; the standalone suspension the room's; the screens the shown renderer's
        assertTrue(service.contains("private suspend fun loadForRenderer(): Pair<Snapshot, List<DownloadRow>> = load(remote = !renderer.kind.onPhone)"))
        assertTrue(service.contains("store.snapshot().forRenderer(remote) to store.db.downloads()"))
        fun body(start: String) = service.substringAfter(start).substringBefore("\n    }\n")
        for (f in listOf("private suspend fun playEntry(", "private suspend fun playItem(", "private suspend fun restore(")) {
            assertTrue(f, body(f).contains("= loadForRenderer()"))
        }
        assertTrue(body("private suspend fun lookAtRoom(").contains("val (snap, rows) = load(remote = true)"))
        assertTrue(body("private suspend fun toRoom(").contains("val (snap, rows) = load(remote = true)"))
        assertTrue(body("private suspend fun comeHome(").contains("val (snap, rows) = load(remote = false)"))
        assertEquals("the six call sites and loadForRenderer's own, no other read", 7, Regex("""(?<![.\w])load(ForRenderer)?\(""").findAll(service).count() - 2)
        val suspension = code("src/main/java/me/vibb/music/sonos/SonosSuspension.kt")
        assertEquals("entryMatcher and the bookmark: the stored room's view", 2, Regex("""store\.snapshot\(\)\.forRenderer\(true\)""").findAll(suspension).count())
        assertEquals(2, Regex("""store\.snapshot\(\)""").findAll(suspension).count())
        val vm = code("src/main/java/me/vibb/music/ui/MusicViewModel.kt")
        assertTrue(vm.contains("combine(store.snapshots(), PlayerHub.state.map { it.output.kind == OutputKind.SONOS }.distinctUntilChanged()) { snap, onRoom ->\n        snap.forRenderer(onRoom)"))
        assertTrue("the Nytt notification is the phone's", code("src/main/java/me/vibb/music/sync/NewNotifier.kt").contains("val n = count(snap.forRenderer(false))"))
        // A tap's switch reads the renderer's view itself (26b §5.6; the merge of music-1b-sonos).
        val switch = code("src/main/java/me/vibb/music/ui/UiModel.kt").substringAfter("fun playSwitch(").substringBefore("\n}\n")
        assertTrue(switch.contains("val shown = snap.forRenderer(onRoom)"))
        assertTrue(switch.contains("val entry = shown.entries.firstOrNull { it.id == entryId } ?: return null"))
        assertTrue(switch.contains("val all = shown.order(entry)"))
        assertFalse("no other view of the entry in the switch", Regex("""snap\.(entries|order|visible)""").containsMatchIn(switch))
        // phone -> room re-plans a Storytel series over the room's order at the current book; other entries keep a0.items
        val toRoom = body("private suspend fun toRoom(")
        assertTrue(toRoom.contains("if (a0.entry.source == Source.STORYTEL) {\n"))
        assertTrue(toRoom.contains("a0.entry to homePlan(snap.order(a0.entry).map { it.key }, item.key, resumeFrom(a0.positions[item.key]))"))
        // room -> phone with a book not on the phone: nothing starts, "Ikke lastet ned"
        val home = body("private suspend fun comeHome(")
        val absent = home.substringAfter("if (a0.entry.source == Source.STORYTEL && item.key !in order) {").substringBefore("}")
        assertTrue(absent.contains("if (!quiet) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)"))
        assertTrue(absent.contains("return"))
        assertFalse(absent.contains("start("))
        assertTrue(home.indexOf("item.key !in order") < home.indexOf("start(a, plan, play = play)"))
    }

    @Test
    fun `on the phone a Storytel place on a book that isn't downloaded starts nothing - every path (qa-26b-step4b-code M1)`() {
        fun body(start: String) = service.substringAfter(start).substringBefore("\n    }\n")
        // the cover tap, before any plan (coverTapPlan would start book 1 from 0)
        val entry = body("private suspend fun playEntry(")
        assertTrue(entry.contains("if (awaitsBook(snap, entryId) { entry, shown, listed -> coverTapAwaitsBook(entry, snap.bookmarks[entry.key], shown, listed) }) return"))
        assertTrue(entry.indexOf("awaitsBook(") < entry.indexOf("coverTapPlan("))
        // the stored queue (restoredQueue would skip to the next book there), for every caller
        val restore = body("private suspend fun restore(): Restored {")
        assertTrue(restore.contains("if (awaitsBook(snap, row.entryId) { entry, shown, listed -> storedRowAwaitsBook(entry, stored, row.index, shown, listed) }) return Restored.NotOnPhone"))
        assertTrue(restore.indexOf("awaitsBook(") < restore.indexOf("storedPlan("))
        assertTrue("RESUME", body("private suspend fun resumeLast() {").contains("restore() as? Restored.Queue ?: return"))
        assertTrue("restoreThen", body("private suspend fun restoreThen(").contains("restore() as? Restored.Queue ?: return"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("return future")
        val notOnPhone = resumption.substringAfter("if (restored == Restored.NotOnPhone) {").substringBefore("} else if")
        assertTrue("a headset's play", notOnPhone.contains("future.setException("))
        assertFalse("the stored queue stays resumable", notOnPhone.contains("Resumable.set"))
        assertEquals("restore's three callers", 3, Regex("""\brestore\(\)""").findAll(service).count() - 1)
        // the one place that says it: "Ikke lastet ned", nothing started or saved
        val awaits = body("private fun awaitsBook(")
        assertTrue(awaits.contains("PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)"))
        for (move in listOf("start(", "saveNow(", "persistPlace", ".play()")) assertFalse(move, awaits.contains(move))
        // the screens agree: no switch for such a cover tap
        val switch = code("src/main/java/me/vibb/music/ui/UiModel.kt").substringAfter("fun playSwitch(").substringBefore("\n}\n")
        assertTrue(switch.contains("if (itemKey == null && coverTapAwaitsBook(entry, snap.bookmarks[entry.key], all.map { it.key }, shown.listed[entry.id].orEmpty().map { it.key })) return null"))
    }

    @Test
    fun `every write of renderer json's uri goes through storedUri - a Storytel book's marker, never its signed URL (M1)`() {
        // design 26b §5.3: RendererChoice's three writers go through storedUri; nothing else constructs or copies one with a uri
        val choice = code("src/main/java/me/vibb/music/sonos/RendererChoice.kt")
        for (w in listOf("fun played(", "fun followed(", "fun adopted(")) {
            assertTrue(w, choice.substringAfter(w).substringBefore("\n\n").contains("storedUri("))
        }
        for (f in mainSources()) {
            val text = code(f.path.substringAfter("music/app/"))
            for (line in text.lines()) {
                val writes = Regex("""(RendererChoice\(|\.copy\()[^\n]*\buri = """).containsMatchIn(line)
                if (writes && f.name != "SonosController.kt") assertTrue("${f.name}: $line", line.contains("storedUri("))
            }
        }
        // the controller's only uri copy is the PlayBody the job hands the speaker (in memory)
        assertTrue(sonos.contains("b = body.copy(uri = mintFor(cid), mint = null)"))
        assertEquals(1, Regex("""\.copy\([^)\n]*\buri = """).findAll(sonos).count())
        // the callers: a landed play, a follow, the adopt's rewrite (lookAtRoom, both branches)
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("writeChoice { it.played(entry, itemKey, uri) }"))
        assertTrue(service.contains("val adopted = choice.adopted(a.entry, a.items[index].key, uri)\n        if (adopted != choice) withContext(writer) { home.write(adopted) }"))
    }

    /** qa-26b-step3-code M1, L4: the lines that wire step 3's pure rules into the workers. */
    @Test
    fun `the workers wire Storytel's news, report and cooldown, and the download job refreshes the Nytt notification`() {
        val sync = code("src/main/java/me/vibb/music/sync/Sync.kt")
        // A new book is news though url-less: listedOf's eligible set (NewItemsTest d29 12), built per entry here.
        assertTrue(sync.contains("e.id to listedOf(e, snap.listed[e.id].orEmpty(), snap.onPhone[e.id].orEmpty(), missing, errors[e.id].orEmpty())"))
        assertTrue(sync.contains("val storytelEntries = snap.library?.entries.orEmpty().filter { it.source == Source.STORYTEL }.map { it.id }.toSet()"))
        assertTrue(sync.contains("val states = reportWaiting(plan.states, storytelEntries, waiting, storytel, Downloads.freeBytes(context))"))
        assertTrue(sync.contains("val storytel = StorytelAccount.get(context).state()"))
        val downloads = code("src/main/java/me/vibb/music/sync/Downloads.kt")
        assertTrue("a refused login's job comes back at the cooldown's end", downloads.contains("val back = listOfNotNull(nextRetryAt, run.storytelRefusedUntil).minOrNull()"))
        val work = downloads.substringAfter("override suspend fun doWork(): Result").substringBefore("private class WorkerIo")
        val reports = Regex("""Sync\.writeReport\(applicationContext\)""").findAll(work).count()
        assertEquals(2, reports)
        assertEquals("a new book's notification right after its download, beside each report", reports, Regex("""NewNotifier\.update\(applicationContext, store\.snapshot\(\)\)\n\s*Sync\.writeReport\(applicationContext\)""").findAll(work).count())
    }

    /** Design 29 §1.6 and §4 (QA M2-M4): the order the pure rules assume, and the tray's writers serialised. */
    @Test
    fun `design 29's wiring - phase A before the listings, phase B before the prune, one sync at a time, one-statement tray writes`() {
        val sync = code("src/main/java/me/vibb/music/sync/Sync.kt")
        assertTrue(sync.contains("runLock.withLock { runLocked(context) }"))
        val order = listOf(
            "val reset = dao.newsBeforeTx(lib.keyById)",
            "for (file in store.inboxDir.listFiles().orEmpty())",
            // after the loop: the snapshot holds this run's listings (qa-29-code M1 A)
            "val current = newsListed(lib, store)",
            "val applied = newsApplied(appliedIds, lib.entries, current)",
            "dao.newsAfterTx(applied, current, System.currentTimeMillis())",
            "for ((file, text) in read) if (runCatching { file.readText() }.getOrNull() == text) file.delete()",
            "dao.pruneNews(ids)",
        ).map { sync.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 } && order == order.sorted())
        assertEquals("built once", 1, Regex("""newsListed\(lib, store\)""").findAll(sync).count())
        assertFalse("no tray write outside the DAO's transactions", sync.contains("replaceNews"))
        // The DAO's half: a reset drops every per-id row (qa-29-code M1 C, QA H2's flood), and phase B reads the baseline.
        val db = code("src/main/java/me/vibb/music/data/MusicDb.kt")
        assertTrue(db.contains("for (id in r.reset) resetEntry(id)"))
        assertTrue(
            db.contains(
                "suspend fun resetEntry(entryId: Long) {\n        deleteItems(entryId)\n        deleteListing(entryId)\n" +
                    "        deleteSeen(entryId)\n        deleteNewsOf(entryId)\n        deleteItemErrors(entryId)\n    }",
            ),
        )
        assertTrue(db.contains("val open = newsBaselineClosed() != true\n        val r = newsAfter(known, tray, seen, applied, current, now, open)"))
        // A permanent failure of a tray item asks for the re-pick's sync (qa-29-code L4).
        assertTrue(sync.contains("if (repickWanted(dao.news().map { NewsItem(it.entryId, it.key, it.seq) }, entryId, itemKey, code)) Sync.nudge(context)"))
        assertTrue(service.contains("if (store.db.deleteNews(entryId, key) > 0) NewNotifier.update(this@PlaybackService, store.snapshot())"))
        assertFalse(service.contains("replaceNews"))
        val fetched = code("src/main/java/me/vibb/music/sync/Downloads.kt").substringAfter("run.fetchOne(w)").trimStart()
        assertTrue(
            "each Nytt item's copy updates the notification",
            fetched.startsWith("if (dao.news().any { it.entryId == w.entry.id && it.key == w.item.key }) {\n                    NewNotifier.update(applicationContext, store.snapshot())"),
        )
    }

    // Spotify in the service (design 26c §5.10, step 5b): every site of the table; the rules are RendererRulesTest's

    private fun serviceBody(start: String) = service.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `26c 5_10 - which renderer - a Spotify entry's start attaches the web player, made once, before the queue changes`() {
        val start = serviceBody("private fun start(a: Active, plan: QueuePlan, play: Boolean = true, newChoice: Boolean = false) {")
        assertTrue(start.contains("usePhoneRenderer(a.entry)\n        active = a"))
        assertTrue("before the queue loads", start.indexOf("usePhoneRenderer(a.entry)") in 0 until start.indexOf("renderer.load("))
        assertTrue("after the old renderer's own save", start.indexOf("renderer.saveNow()") < start.indexOf("usePhoneRenderer("))
        val use = serviceBody("private fun usePhoneRenderer(entry: Entry) {")
        assertTrue("a room keeps its renderer", use.contains("if (!renderer.kind.onPhone) return"))
        assertTrue(use.contains("val next = if (phoneRendererFor(entry) == RendererKind.SPOTIFY) spotifyRenderer() else local"))
        assertTrue("through attach (saved, paused), the Exo stood down (§5.6, QA L1)", use.contains("attach(next)\n        if (next !== local) local.standDown()"))
        // made once for the service's life; its queue only while it renders; the outcome to the store; a debug-only log
        assertEquals(1, Regex("""SpotifyRenderer\.create\(""").findAll(service).count())
        assertTrue(service.contains("private fun spotifyRenderer(): SpotifyRenderer = spotify ?: SpotifyRenderer.create("))
        assertTrue(service.contains("queue = { active.takeIf { renderer === spotify } },"))
        assertTrue(
            "the word written on the writer; a change writes the report and hands it to the launcher, no whole sync (F7, QA L1)",
            service.contains("scope.launch(writer) { if (store.recordSpotifyOutcome(word, System.currentTimeMillis())) reportSpotifyWord() }") &&
                serviceBody("private suspend fun reportSpotifyWord() {").contains("Sync.writeReport(this)") &&
                !serviceBody("private suspend fun reportSpotifyWord() {").contains("Sync.nudge"),
        )
        assertTrue(service.contains("log = ::spotifyLog,"))
        assertEquals("the service's one log line: the web player's, debug builds only (qa-26c-step5a-code L8)", 1, Regex("""\bLog\.""").findAll(service).count())
        assertTrue(serviceBody("private fun spotifyLog(message: String) {").contains("if (BuildConfig.DEBUG) Log.d(SPOTIFY_LOG_TAG, message)"))
        assertEquals("spotify is assigned only where it is made", 1, Regex("""\bspotify = """).findAll(service).count())
        assertEquals(1, Regex("""\.also \{ spotify = it \}""").findAll(service).count())
        // the renderer and the Exo: every renderer comparison read by §5.10's rule
        val kinds = service.lines().filter { it.contains("RendererKind.") }.map { it.trim() }
        assertEquals(
            listOf(
                // design 30 (merged from main 571f86f): the changed-source reload, the played mark and the held set are
                // the phone's own player's (LOCAL) - never Spotify's web player, never a room; the claim is claimsQueue's
                "val next = if (phoneRendererFor(entry) == RendererKind.SPOTIFY) spotifyRenderer() else local",
                // design 50 §1.2 (code QA 8): only the phone's ExoPlayer knows at Ready that an item has no duration (live)
                "if (sleepAtEnd && renderer.kind == RendererKind.LOCAL && renderer.durationMs() == null) {",
                "renderer.kind == RendererKind.LOCAL && a.entry.source != Source.STORYTEL",
                "if (renderer.kind != RendererKind.LOCAL || item.fileId != null || a.entry.source == Source.STORYTEL) return",
                "val held = if (a == null || renderer.kind != RendererKind.LOCAL || renderer.itemCount() == 0) {",
                "val a = activeFor(snap, rows, entry, plan.keys, plan.shuffled, RendererKind.SONOS) ?: return null",
                "val queue = place?.let { (entry, plan) -> queueFor(snap, rows, entry, plan, shuffled = false, RendererKind.SONOS)?.let { entry to it } }",
            ),
            kinds,
        )
        val locals = service.lines().filter { Regex("""[!=]== local\b""").containsMatchIn(it) }.map { it.trim() }
        assertEquals(
            listOf("if (renderer !== local) local.release()", "if (next !== local) local.standDown()", "if (renderer !== local) return"),
            locals,
        )
        assertTrue("the room's filters by onPhone", service.contains(".filter { kind.onPhone || PlayBodies.speakerPlayable(entry, it) }"))
        assertTrue(service.contains("val p = if (!kind.onPhone) {"))
    }

    @Test
    fun `26c 5_10 - playable, playableFrom, start, goTo and onItemChanged - the network gate, never NOT_DOWNLOADED, never another item`() {
        // design 30: the streaming switch - a Spotify stream's too - never the downloads one (the old single switch is gone)
        assertTrue(serviceBody("private fun playable(").contains("streamMobileData: Boolean = store.streamMobileData): Boolean {"))
        assertTrue(serviceBody("private fun playable(").contains("return itemPlayable(renderer.kind, a.entry, hasCopy(a, item), item.url != null, caps, streamMobileData)"))
        assertTrue(serviceBody("private fun playableFrom(").contains("return playableIndex(a.entry, a.items.size, index, forward, wrap) { playable(a, it, caps, streamMobileData) }"))
        val start = serviceBody("private fun start(a: Active, plan: QueuePlan, play: Boolean = true, newChoice: Boolean = false) {")
        assertTrue("NOT_DOWNLOADED only for another source", start.contains("if (first == null && !isSpotify(a.entry)) {\n            if (play) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)\n            updateHeld(loaded = true) \n            return"))
        assertTrue(start.contains("if (first != null && first != index) {"))
        assertTrue("refused: loaded paused with the line, no play", start.contains("if (play && (first != null || !refuseSpotify())) {\n            renderer.play()"))
        val refuse = serviceBody("private fun refuseSpotify(): Boolean {")
        assertTrue(refuse.contains("val line = spotifyNetworkLine(netCaps(this), store.streamMobileData) ?: return false"))
        assertTrue(
            "the web player's own gate: the streaming switch too",
            code("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt").contains("override fun networkLine(): SpotifyLine? = spotifyNetworkLine(netCaps(app), store.streamMobileData)"),
        )
        for (f in listOf("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt", "src/main/java/me/vibb/music/logic/Spotify.kt", "src/main/java/me/vibb/music/playback/RendererRules.kt")) {
            assertFalse("$f: never the downloads switch", code(f).contains("downloadMobileData"))
            assertFalse("$f: never the storage limit", code(f).contains("storageLimit"))
        }
        assertTrue(refuse.contains("(renderer as? SpotifyRenderer)?.refused(line)"))
        for (move in listOf("seekTo(", ".play()", "NOT_DOWNLOADED", "saveBookmark(")) assertFalse(move, refuse.contains(move))
        val goTo = serviceBody("private fun goTo(a: Active, index: Int, forward: Boolean) {")
        assertTrue(
            "Spotify refused: its line, no jump, and the screens drop the step they showed (§6l A, F3)",
            goTo.contains("if (isSpotify(a.entry)) {\n                refuseSpotify()\n                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.STAYED)\n            } else {\n                PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.NOT_DOWNLOADED)"),
        )
        val changed = serviceBody("private fun onItemChanged(index: Int, auto: Boolean) {")
        assertTrue(changed.contains("if (isSpotify(a.entry)) {\n            "))
        assertTrue("only Spotify's own advance is refused; never moved on", changed.contains("if (auto && !playable(a, index)) refuseSpotify()\n        } else if (!playable(a, index)) {"))
        assertTrue("the natural-advance seek stays the Exo's (QA M4)", changed.contains("if (resumesOnAdvance(auto, renderer.kind)) {"))
        // the room's queue drops Spotify items (§4.2): toRoom from a Spotify entry keeps the phone playing (QA L1)
        val spotify = Fixtures.entry(6, me.vibb.music.model.Source.SPOTIFY, target = "spotify:playlist:FAKEid6", cache = 0).copy(spotifyMode = "list")
        val track = me.vibb.music.model.PlayItem(6, "t1", "Song — Artist", null, false, 180_000)
        assertEquals("spotify", me.vibb.music.sonos.PlayBodies.speakerDropReason(spotify, track))
        assertFalse(me.vibb.music.sonos.PlayBodies.speakerPlayable(spotify, track))
    }

    @Test
    fun `26c 5_10 - onError records unplayable and stays synchronous on main, the move-on the web player's own`() {
        val events = serviceBody("private fun onRendererEvent(event: RendererEvent) {")
        assertTrue("called directly from the renderer's event", events.contains("is RendererEvent.Error -> onError(event.code, event.gated)"))
        val onError = serviceBody("private fun onError(code: String, gated: Boolean = false) {")
        assertTrue(onError.contains("recordsItemError(a.entry, hasUrl = item.url != null) ->\n                scope.launch(writer) { ItemErrors.record(this@PlaybackService, entryId, item.key, code) }"))
        assertTrue("an own file and a copy first, each by its own rule", onError.indexOf("item.fileId != null || gated -> Unit") < onError.indexOf("copy != null -> if (code == \"bad_media\")") && onError.indexOf("copy != null ->") < onError.indexOf("recordsItemError("))
        assertTrue(onError.contains("val offline = gated ||\n            !isSpotify(a.entry) && copy == null"))
        // design 30's changed-source reload (playOnUncached) is the phone's own player's only, never Spotify's
        assertTrue(onError.contains("renderer.kind == RendererKind.LOCAL && a.entry.source != Source.STORYTEL"))
        // qa-26c-step5a-code (deviation 6): the seekTo/prepare/play inside the renderer's Error("unplayable") are its move-on
        val moveOn = onError.substringAfter("renderer.saveNow()")
        assertTrue(moveOn.contains("renderer.seekTo(nextIndex, startPos(a, nextIndex))\n            renderer.prepare()\n            renderer.play()"))
        for (async in listOf("launch", "post", "withContext", "async", "handler.", "delay(")) assertFalse(async, moveOn.contains(async))
        assertEquals("only the record is launched", 2, Regex("""scope\.launch""").findAll(onError).count())
    }

    @Test
    fun `26c 5_10 - mediaItem - a Spotify item's uri, no ItemTag, no cache key - and it never reaches ItemMediaSourceFactory`() {
        val media = service.substringAfter("private fun mediaItem(a: Active, item: PlayItem): MediaItem {").substringBefore("val meta =")
        val branch = media.substringAfter("} else if (isSpotify(a.entry)) {").substringBefore("} else if (a.entry.source == Source.STORYTEL) {")
        assertTrue(branch.contains("spotifyItemUri(a.entry, item.key)?.let { b.setUri(it) }"))
        for (cache in listOf("setTag", "setCustomCacheKey", "ItemTag", "item.url", "download")) assertFalse(cache, branch.contains(cache))
        assertTrue("before Storytel's and the feeds' branches", media.indexOf("isSpotify(a.entry)") in 0 until media.indexOf("Source.STORYTEL"))
        // design 30 (the merge of main 571f86f): cache-through, the played mark and the held set are the phone's own
        // player's - Spotify's web player (RendererKind.SPOTIFY) writes no cache, marks nothing played, holds nothing
        assertTrue(media.contains("val through = a.entry.source == Source.NRK || a.entry.source == Source.RSS"))
        assertTrue(serviceBody("private fun markPlayed(a: Active, item: PlayItem) {").contains("if (renderer.kind != RendererKind.LOCAL || item.fileId != null"))
        assertTrue(serviceBody("private fun updateHeld(loaded: Boolean = false) {").contains("renderer.kind != RendererKind.LOCAL || renderer.itemCount() == 0"))
        assertTrue(serviceBody("private suspend fun activeFor(").contains("if (claimsQueue(kind, entry)) {"))
        // qa-26c-merge-code M1: a new queue's kind is the renderer that will play it, never the attached one
        assertTrue(serviceBody("private suspend fun playEntry(entryId: Long) {").contains("queueFor(snap, rows, entry, plan, shuffle, queueKind(renderer.kind, entry))"))
        assertTrue(serviceBody("private suspend fun playItem(entryId: Long, key: String) {").contains("queueFor(snap, rows, entry, plan, shuffle, queueKind(renderer.kind, entry))"))
        assertTrue(serviceBody("private suspend fun restore(): Restored {").contains("queueFor(snap, rows, entry, plan, row.shuffled, queueKind(renderer.kind, entry))"))
        assertEquals(3, Regex("""queueKind\(renderer\.kind, entry\)""").findAll(service).count())
        val queueCalls = Regex("""(queueFor|activeFor)\([^\n]*""").findAll(service).map { it.value }.toList()
        assertTrue(queueCalls.toString(), queueCalls.isNotEmpty() && queueCalls.none { Regex("""[(,] ?renderer\.kind\)""").containsMatchIn(it) })
        // only the phone's own player has the media source factory ...
        val factory = mainSources().filter { code(it.path.substringAfter("music/app/")).contains("ItemMediaSourceFactory(") }.map { it.name }.sorted()
        assertEquals(listOf("ItemMediaSourceFactory.kt", "LocalRenderer.kt"), factory)
        // ... a queue loads into a renderer only in start, after the renderer for its entry is chosen, and a room's
        // adopted queue is built for the room (the speaker filter drops Spotify); resumption hands no Spotify item to Media3
        // (design 30 adds the second: playOnUncached reloads the phone's own queue, onError's LOCAL-only path)
        assertEquals(2, Regex("""renderer\.load\(""").findAll(service).count())
        assertTrue(serviceBody("private fun playOnUncached(a: Active, index: Int, item: PlayItem) {").contains("renderer.load("))
        assertTrue(service.contains("val a = activeFor(snap, rows, entry, plan.keys, plan.shuffled, RendererKind.SONOS) ?: return null"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("return future")
        val spotify = resumption.substringAfter("} else if (isSpotify(restored.active.entry)) {").substringBefore("} else {")
        assertTrue(spotify.contains("start(a, QueuePlan(a.items.map { it.key }, index, pos))\n                        future.setException(UnsupportedOperationException(\"playing on Spotify\"))"))
        assertFalse(spotify.contains("future.set("))
        val local = resumption.substringAfter("} else if (isSpotify(restored.active.entry)) {").substringAfter("} else {")
        assertTrue("Media3's items go to the phone's own player", local.indexOf("usePhoneRenderer(a.entry)") in 0 until local.indexOf("future.set("))
        assertEquals("future.set only there", 1, Regex("""future\.set\(""").findAll(resumption).count())
        // the wake mode: the web player keeps its own locks
        assertTrue(code("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt").contains("override fun setWakeMode(wakeMode: Int) = Unit"))
    }

    @Test
    fun `26c 5_10 - onDestroy, the suspension, the room looks, comeHome, callStep, and the clearing at a library without Spotify`() {
        val destroy = service.substringAfter("override fun onDestroy() {").substringBefore("super.onDestroy()")
        assertTrue(destroy.contains("spotify?.let { if (it !== renderer) it.release() }"))
        assertTrue("released before the writer closes", destroy.indexOf("spotify?.let { if (it !== renderer) it.release() }") < destroy.indexOf("writer.close()"))
        assertTrue("the current one through renderer.release()", destroy.contains("renderer.release()\n            home.release()\n            writer.close()"))
        val stop = serviceBody("private suspend fun suspendStop() {")
        assertTrue("the page goes whatever the state (§5.7, QA L11)", stop.indexOf("spotify?.suspended()") in 0 until stop.indexOf("val sonos = renderer as? SonosRenderer"))
        assertTrue(stop.contains("if (renderer !== spotify) renderer.pause()"))
        assertTrue(serviceBody("private suspend fun lookAtRoom(").contains("if (!renderer.kind.onPhone) return"))
        assertTrue(serviceBody("private suspend fun lookAgainBeforePlaying() {").contains("if (!renderer.kind.onPhone || SystemClock.elapsedRealtime() - lastRoomLook < ROOM_LOOK_MS) return"))
        val home = serviceBody("private suspend fun comeHome(")
        assertTrue(home.contains("activeFor(snap, rows, a0.entry, plan.keys, shuffled = false, phoneRendererFor(a0.entry), plan.startIndex)"))
        assertTrue("start chooses the phone's renderer", home.trimEnd().endsWith("start(a, plan, play = play)"))
        val step = serviceBody("private fun callStep() {")
        assertTrue(step.contains("val paused = pausedForCall(playing, r.itemCount(), r.rendererState)"))
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onGetSession")
        assertTrue(onCreate.contains("store.libraries().map { lib -> lib != null && lib.entries.none { isSpotify(it) } }.distinctUntilChanged()"))
        assertTrue(onCreate.contains("if (none && (spotify != null || withContext(Dispatchers.IO) { SpotifyRenderer.pageAccountFile(this@PlaybackService).exists() })) {\n                    spotifyRenderer().forget()"))
        // the token's `off` is the renderer's own (SpotifyProblem.OFF: the page cleared, the marker gone, `none`)
        val renderer = code("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt")
        assertTrue(renderer.contains("SpotifyProblem.OFF -> forgetAccount()"))
        // design 28: the listening window reads showsPlaying through setNowPlaying, unchanged for every renderer
        assertTrue(service.contains("val playing = !forcePaused && renderer.showsPlaying"))
        // the report's word from the file; the screens' tiles from the store
        assertTrue(code("src/main/java/me/vibb/music/sync/Sync.kt").contains("spotify = SpotifyOutcome.reported(runCatching { store.spotifyStateFile.readText() }.getOrNull()),"))
        val root = code("src/main/java/me/vibb/music/ui/MusicRoot.kt")
        assertTrue(root.contains("entries = shown.map { entryUi(s, it, hub.entryId, hub.rendererState, spotifyOutcome, net) },"))
        assertTrue("no offline summary for Spotify", root.contains("if (isSpotify(n.entry)) null else offlineSummary("))
        assertTrue(code("src/main/java/me/vibb/music/ui/MusicViewModel.kt").contains("store.spotifyOutcomes().map { it?.outcome }"))
        assertTrue(code("src/main/java/me/vibb/music/ui/LibraryScreen.kt").contains("val line = e.state?.let { stringResource(rendererStateText(it)) }"))
        // step 8: no source line under the title - the player looks the same for every source
        assertFalse(code("src/main/java/me/vibb/music/ui/PlayerScreen.kt").contains("kind_spotify"))
        assertTrue(code("src/main/java/me/vibb/music/ui/PlayerScreen.kt").contains("Kind.MUSIC -> R.string.track_of"))
        // the fake's tokens only without a launcher (§9.3): a managed phone asks the bridge
        assertTrue(renderer.contains("if (fakeTokens != null && withContext(Dispatchers.IO) { !Bridge.config(app).managed }) {"))
    }

    /**
     * Design 26c §5.9, step 6: a Spotify context's synthetic item is saved with no duration and never done, its end drops its
     * place and its live track, next and previous are the web player's, the setNowPlaying title is the live track's, and
     * spotify_context.json is the store's alone (the snapshot and the renderer read it through the store).
     */
    @Test
    fun `26c step 6 - a context's item has no duration and is never done, its end drops the live track, its steps are the SDK's`() {
        val ready = service.substringAfter("RendererEvent.Ready -> active?.let { a ->").substringBefore("RendererEvent.Ended ->")
        assertTrue(ready.contains("if (item != null && duration != null && keepsDuration(a.entry, item.key)) a.durations[item.key] = duration"))
        val ended = serviceBody("private fun queueEnded() {")
        assertTrue(ended.contains("a.items.getOrNull(renderer.currentIndex())?.takeIf { keepsDuration(a.entry, it.key) }?.let { markDone(a, it) }"))
        assertTrue("the context's end drops its live track", ended.contains("if (context) store.recordSpotifyContext(entryKey, null)"))
        assertTrue(ended.contains("store.db.clearPartialPositions(entryKey)"))
        assertTrue(serviceBody("private fun skipNext() {").contains("if (stepsOnWebPlayer(a.entry)) return stepOnWebPlayer(forward = true)"))
        assertTrue(serviceBody("private fun skipPrevious() {").contains("if (stepsOnWebPlayer(a.entry)) return stepOnWebPlayer(forward = false)"))
        val step = serviceBody("private fun stepOnWebPlayer(forward: Boolean) {")
        assertTrue(
            "gated as every step, the screens told it stayed (F3)",
            step.contains("if (refuseSpotify()) {\n            PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.STAYED)\n            return\n        }") &&
                step.indexOf("refuseSpotify()") < step.indexOf("web.step(forward)"),
        )
        val call = serviceBody("private fun callStep() {")
        assertTrue("a web player the WebView paused is the call rule's (QA M1)", call.contains("val playing = r.showsPlaying || (r as? SpotifyRenderer)?.heldRecently() == true"))
        // the web player hears the launcher's call (QA L2) and the audio mode's (emulator round 2026-10-14 N3), set wherever
        // either changes and when the renderer is made
        val hears = "spotify?.inCall = spotifyInCall(launcherInCall, modeInCall)"
        assertTrue(serviceBody("private fun launcherCall(inCall: Boolean) {").contains(hears))
        assertTrue(service.substringAfter("private val modeListener = AudioManager.OnModeChangedListener { mode ->").substringBefore("\n    }\n").contains(hears))
        assertTrue(service.contains(".apply { inCall = spotifyInCall(launcherInCall, modeInCall) }.also { spotify = it }"))
        assertEquals("inCall set nowhere else", 3, Regex("""inCall = spotifyInCall\(""").findAll(service).count())
        // N5: a cover tap on the loaded Spotify entry goes on with it only while the library lists it the same way
        val tap = serviceBody("private suspend fun playEntry(entryId: Long) {")
        assertTrue(tap.contains("if (!loadedPlayStale(current.entry, now)) {\n                renderer.play()"))
        assertTrue(tap.contains("store.library()?.entries?.firstOrNull { it.id == entryId }"))
        val root = code("src/main/java/me/vibb/music/ui/MusicRoot.kt")
        assertTrue("every message ends the hold; STAYED shows nothing more", root.substringAfter("PlayerHub.messages.collect {").substringBefore("message = it").let { it.contains("vm.switching = null") && it.contains("if (it == PlayerHub.Message.STAYED) return@collect") })
        val now = serviceBody("private fun reportNowPlaying(")
        assertTrue(now.contains("putString(NowPlayingKeys.ITEM, title)"))
        assertTrue("a track change is a new report", now.contains("val key = Triple(a?.entry?.id, item?.key?.let { \"\$it\\n\$title\" }, playing)"))
        // the renderer: a context's save carries no duration; the move-on after an unplayable plays only with the intent
        val renderer = code("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt")
        assertTrue(renderer.contains("val duration = if (contextUri != null) null else stateDuration ?: a.durations[item.key] ?: item.durationMs"))
        val moveOn = renderer.substringAfter("private fun moveOnTo(").substringBefore("\n    }\n")
        assertTrue(moveOn.contains("if (pwr && ports.mayPlay()) request("))
        // the file: written by the store alone; the snapshot gets it in every build of one
        val files = moduleFile("src/main/java").walk().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "").contains("spotify_context.json") }
            .map { it.name }.toList()
        assertEquals(listOf("Store.kt"), files)
        val store = code("src/main/java/me/vibb/music/data/Store.kt")
        assertTrue(store.contains("val next = SpotifyContexts.next(spotifyContexts(), entryKey, track) ?: return"))
        assertTrue("tmp + rename", store.contains("writeAtomic(spotifyContextFile, SpotifyContexts.encode(next).toByteArray())"))
        assertTrue(store.contains("spotifyContextFlow(),\n        db.favouritesFlow(),\n    ) { lib, (items, downloads, listings), rows, contexts, favourites ->\n        snapshot(lib, items, downloads, listings, rows, contexts, favourites)"))
        assertTrue(store.contains("return buildSnapshot(lib, items, downloads, listings, ownCopies(), positions, rows.bookmarks, rows.news, shuffle, contexts, flips, hearts, repeat)"))
        assertTrue(code("src/main/java/me/vibb/music/data/Snapshot.kt").contains("listed[e.id] = listOf(contextItem(e, spotifyContexts[e.key]))"))
        // the UI: no number and no instant step for a context; the list says the order is picked (no source, step 8)
        assertTrue(code("src/main/java/me/vibb/music/ui/PlayerScreen.kt").contains("if (showsNumber(now)) Line(stringResource(numberText, numberOf(item), of), 13, 700, Palette.sub)"))
        val list = code("src/main/java/me/vibb/music/ui/ListScreens.kt")
        assertTrue(list.contains("} else if (isSpotifyContext(entry)) {\n            \n            Line(stringResource(R.string.hint_spotify_picks, kindText(kindOf(entry))), 12, 800, Palette.sub, Modifier.fillMaxWidth(), center = true)\n        }"))
        assertFalse("no source line in a list-mode entry's place", list.contains("kind_spotify"))
        // the fix round (qa-26c-step6-code): L4 - a parsed library prunes the rows of entries not in context mode, and the
        // renderer's main-thread read never takes the store's lock once loaded; L2 - one rule for an artist's place
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onGetSession")
        assertTrue(onCreate.contains("store.libraries().map { lib -> lib?.entries?.filter { isSpotifyContext(it) }?.map { it.key }?.toSet() }.distinctUntilChanged()"))
        assertTrue(onCreate.contains("if (keys != null) withContext(writer) { store.pruneSpotifyContexts(keys) }"))
        assertTrue(store.contains("if (spotifyContextLoaded) spotifyContextState.value else loadSpotifyContexts()"))
        assertTrue(renderer.contains("contextOffsets = a != null && contextResumes(a.entry)"))
        val ui = code("src/main/java/me/vibb/music/ui/UiModel.kt")
        assertTrue(ui.contains("if (!contextResumes(entry)) pos = 0") && ui.contains("val pos = if (contextResumes(entry)) row.posMs else 0"))
        // M3: one more play, without the offset, only after the play call itself was refused
        val prepare = renderer.substringAfter("private suspend fun prepareAndCall(s: Start): StartEnd = coroutineScope {").substringBefore("\n    }\n")
        assertEquals("the first call and one fallback", 2, Regex("""\bcall\(""").findAll(prepare).count() - 1)
        assertTrue(prepare.contains("if (ctx != null && refusesContext(result) && playSentAt != Long.MAX_VALUE) {"))
        assertTrue(prepare.contains("contextKey?.let { ports.storeContextTrack(it, null) }"))
    }

    /** Design 29 step 4's glue (qa-29-code L2): the pure rules in OwnFilesTest, wired here. */
    @Test
    fun `own-file copies - the record written on arrival, judged at every pull, the report from the rule`() {
        val inbox = code("src/main/java/me/vibb/music/sync/Inbox.kt")
        assertTrue("G", inbox.contains("{ size, sha -> size == file.size && sha == file.sha256 }\n            .also { if (it) store.recordOwnSha(file.id, file.sha256) }"))
        val bridge = code("src/main/java/me/vibb/music/bridge/BridgeSync.kt")
        val pull = bridge.substringAfter("private fun pullFiles(").substringBefore("private suspend fun pullStorytel(")
        assertTrue("F", pull.contains("val plan = ownFetchPlan(lib.files, store.ownCopies()) { keptCopy(store, it) }"))
        assertTrue(pull.contains("for (id in plan.refetch) {"))
        assertTrue(pull.contains("store.deleteOwnCopy(id)"))
        assertTrue(pull.contains("for (file in plan.fetch) {"))
        assertTrue(bridge.contains("keepOwnCopy(file.sha256, store.ownSha(file.id), { Inbox.sha256(store.ownFile(file.id)) }, { store.recordOwnSha(file.id, it) })"))
        assertTrue("E", bridge.contains("val held = heldOwnFiles(lib?.files.orEmpty(), store.ownCopies()) { store.ownSha(it) }"))
        val sync = code("src/main/java/me/vibb/music/sync/Sync.kt")
        assertTrue("a pruned copy takes its record", sync.contains("store.ownCopies().filter { it !in lib.fileIds }.forEach { store.deleteOwnCopy(it) }"))
    }

    // Design 30 step 3b: the glue of the stream written as it plays (the rules: OfflineTest, CacheKeysTest).

    private fun body(text: String, start: String) = text.substringAfter(start).substringBefore("\n    }\n")

    private val media by lazy { code("src/main/java/me/vibb/music/media/MediaCache.kt") }
    private val through by lazy { code("src/main/java/me/vibb/music/sync/CacheThrough.kt") }

    /** §1.4, §1.6, §1.7, QA M2: the sink decides at each open, a cache error never fails playback, the gate and the guard. */
    @Test
    fun `playback writes the cache only through ThroughSink, with FLAG_IGNORE_CACHE_ON_ERROR`() {
        val playback = media.substringAfter("fun playbackFactory(").substringBefore("\n\n")
        assertTrue(playback.contains("through: ThroughSink.Factory? = null"))
        assertTrue(playback.contains(".setCacheWriteDataSinkFactory(if (copyOnly) null else through)"))
        assertTrue(playback.contains(".setFlags(if (copyOnly) 0 else CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)"))
        assertTrue(playback.contains(".setUpstreamDataSourceFactory(if (copyOnly) null else gated(context, http(context, publicOnly)))"))
        assertTrue("copy-only reads: no sink", media.substringAfter("fun copyOnlyFactory(").substringBefore("\n\n").contains(".setCacheWriteDataSinkFactory(null)"))
        assertEquals("playback's and copy-only's, nothing else", 2, Regex("setCacheWriteDataSinkFactory\\(").findAll(media).count())
        // No other sink in the app: the download factory's default one is the job's.
        val sinks = mainSources().filter { it.name != "MediaCache.kt" && it.readText().contains("CacheDataSink(") }.map { it.name }
        assertTrue(sinks.toString(), sinks.isEmpty())
        // The sink's two live inputs and its row, made before the first write.
        val sinkOf = body(media, "fun throughSink(")
        assertTrue(sinkOf.contains("decide = { throughWrites(through, store.library()?.entry(ref.entryId)?.cache, freeBytes(app)) },"))
        assertTrue(sinkOf.contains("firstWrite = { store.db.insertThrough(DownloadRow(ref.entryId, ref.itemKey, cacheKey, url, hls, false, 0, null, null)) },"))
        // The tag: NRK and RSS only, the stale ones uncached; a Storytel book and an own file never write.
        val factory = code("src/main/java/me/vibb/music/media/ItemMediaSourceFactory.kt")
        assertTrue(factory.contains("tag.uncached -> DefaultMediaSourceFactory(MediaCache.uncachedFactory(app, tag.publicOnly))"))
        assertTrue(factory.contains("val through = tag.ref?.takeIf { tag.through && !tag.copyOnly }?.let { MediaCache.throughSink(app, it, tag.through, tag.cacheKey, url, tag.hls) }"))
        val item = service.substringAfter("private fun mediaItem(a: Active, item: PlayItem): MediaItem {").substringBefore("val meta =")
        assertTrue(item.contains("val through = a.entry.source == Source.NRK || a.entry.source == Source.RSS"))
        assertTrue(item.contains("b.setTag(ItemTag(cacheKey, hls, publicOnly(a.entry), through = through, uncached = uncached, ref = ItemRef(a.entry.id, item.key)))"))
        assertTrue(item.contains("val uncached = item.key in a.uncached"))
        // An uncached item carries no cache key: no request names the stale copy's key (the guard would trip again).
        assertTrue(item.contains("b.setUri(url)\n"))
        assertTrue(item.contains("if (!uncached) b.setCustomCacheKey(cacheKey)"))
        assertEquals("only the NRK/RSS branch sets through", 1, Regex("through = through").findAll(item).count())
        // The gate on every player upstream (not the job's), the length guard on both factories' http.
        assertTrue(media.contains("DataSource.Factory { StreamGate(upstream.createDataSource()) { streamAllowed(netCaps(app), store.streamMobileData) } }"))
        assertTrue(media.contains("return DataSource.Factory { LengthGuard(okhttp.createDataSource()) { key -> storedLength(cache, key) } }"))
        val download = media.substringAfter("fun downloadFactory(").substringBefore("\n\n")
        assertTrue(download.contains(".setUpstreamDataSourceFactory(http(context, publicOnly, followRedirects))"))
        assertFalse("the job has its own gate", download.contains("gated("))
        assertEquals("a playback and the uncached one", 2, Regex("gated\\(context, http\\(context, publicOnly\\)\\)").findAll(media).count())
        // §1.7: the stream plays the rendition the download keeps.
        val local = code("src/main/java/me/vibb/music/playback/LocalRenderer.kt")
        assertTrue(local.contains(".setMaxAudioBitrate(HLS_MAX_BITRATE)\n                .setForceHighestSupportedBitrate(true)"))
        // The gate's refusal: "Ikke lastet ned", no item error.
        assertTrue(local.contains("emit(RendererEvent.Error(MediaCache.errorCode(error), MediaCache.gated(error)))"))
        assertTrue(service.contains("is RendererEvent.Error -> onError(event.code, event.gated)"))
        val onError = body(service, "private fun onError(code: String, gated: Boolean = false) {")
        assertTrue(onError.contains("item.fileId != null || gated -> Unit"))
        // design 26c: a Spotify item's offline isn't the item's (its web player never reports `gated`)
        assertTrue(onError.contains("val offline = gated ||\n            !isSpotify(a.entry) && copy == null && item.fileId == null && !streamAllowed("))
        assertTrue(MediaCache.gated(PlaybackException("x", StreamGateException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertFalse(MediaCache.gated(PlaybackException("x", IOException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        // The length guard's 416 on the phone: the row goes, the item plays on uncached where it was, no item error.
        assertTrue(onError.indexOf("playOnUncached(a, index, item)") < onError.indexOf("ItemErrors.record"))
        assertTrue(onError.contains("if (code == \"http_416\" && !gated && copy == null && item.url != null && item.key !in a.uncached &&"))
        val uncached = body(service, "private fun playOnUncached(")
        assertTrue(uncached.contains("a.uncached += item.key"))
        assertTrue(uncached.contains("store.db.deleteDownload(entryId, item.key)"))
        // qa-30-s3b-code H1: a new source, never replaceItem - Media3's replaceMediaItem keeps the source when
        // canUpdateMediaItem says yes (the uri, the cache key or stream keys; never the tag), so the item would
        // stay on the cache-backed source and trip the guard again.
        assertTrue(uncached.contains("renderer.load(a.items.map { mediaItem(a, it) }, index, pos, playNow = false)"))
        assertFalse(uncached.contains("replaceItem"))
        assertTrue(code("src/main/java/me/vibb/music/playback/LocalRenderer.kt").contains("exo.setMediaItems(items, startIndex, startMs)"))
        assertFalse("never starts playback by itself", uncached.contains("play()"))
    }

    /** §1.5, QA H1: a settle never blocks the service's writer or the next sync. */
    @Test
    fun `settle and catchUp never run on writer, and catchUp runs outside runLock`() {
        val sync = code("src/main/java/me/vibb/music/sync/Sync.kt")
        assertTrue(sync.contains("suspend fun run(context: Context) = withContext(Dispatchers.IO) {\n        CacheThrough.catchUp(context)\n        runLock.withLock { runLocked(context) }\n    }"))
        assertFalse(body(sync, "private suspend fun runLocked(").contains("catchUp"))
        assertEquals(1, Regex("CacheThrough\\.catchUp\\(").findAll(sync).count())
        // The service: a left item is settled on IO in its scope; its only other call reads a queue's rows.
        val held = body(service, "private fun updateHeld(loaded: Boolean = false) {")
        assertTrue(held.contains("scope.launch(Dispatchers.IO) {\n                val done = CacheThrough.settle(this@PlaybackService, ref) ?: return@launch"))
        assertEquals(setOf("settle", "queueRows"), Regex("CacheThrough\\.(\\w+)").findAll(service).map { it.groupValues[1] }.toSet())
        assertFalse(Regex("(launch|withContext)\\(writer\\)[^\\n]*CacheThrough").containsMatchIn(service))
        // Every item and timeline change goes through publish, which sets the held set; a load ends the claims.
        assertTrue(body(service, "private fun publish() {").trimStart().startsWith("updateHeld()"))
        assertTrue(service.contains("renderer.load(a.items.map { mediaItem(a, it) }, index, pos, playNow = false)\n        updateHeld(loaded = true)"))
        // Every other end of a queue's claims (qa-30-s3b-code L6): start() giving up, playback resumption once the
        // session has its items, and an exception between the claim and the queue.
        val start = body(service, "private fun start(a: Active, plan: QueuePlan, play: Boolean = true, newChoice: Boolean = false) {")
        assertTrue(start.substringAfter("if (first == null && !isSpotify(a.entry)) {").substringBefore("return").contains("updateHeld(loaded = true)"))
        assertTrue(Regex("future\\.set\\(MediaSession\\.MediaItemsWithStartPosition\\(a\\.items\\.map \\{ mediaItem\\(a, it\\) \\}, index, pos\\)\\)\\s*handler\\.post \\{ updateHeld\\(loaded = true\\) \\}").containsMatchIn(service))
        val active = body(service, "private suspend fun activeFor(")
        assertTrue(active.indexOf("PlayerHub.claim(") in 0 until active.indexOf("claimed = true"))
        assertTrue(Regex("""\} catch \(e: Throwable\) \{\s*if \(claimed\) updateHeld\(loaded = true\)\s*throw e""").containsMatchIn(active))
        // The release never settles (its scope ends): the next sync's catch-up does.
        val destroy = body(service, "override fun onDestroy() {")
        assertTrue(destroy.contains("PlayerHub.hold(emptySet(), loaded = true)\n        scope.cancel()"))
        assertFalse(destroy.contains("settle"))
        // Non-blocking: a span check, never the downloader or a blocking cache read.
        assertTrue(through.contains("suspend fun settle(context: Context, ref: ItemRef): DownloadRow? = withContext(Dispatchers.IO) {"))
        assertTrue(through.contains("suspend fun catchUp(context: Context) = withContext(Dispatchers.IO) {"))
        for (blocking in listOf("Downloader", "createDataSourceForDownloading", "FLAG_BLOCK_ON_CACHE", "runBlocking", "Downloads.fetch")) {
            assertFalse(blocking, through.contains(blocking))
        }
        assertTrue(through.contains("ParsingLoadable.load(copy.createDataSource(), HlsPlaylistParser(), url, C.DATA_TYPE_MANIFEST)"))
        assertTrue(media.substringAfter("fun copyOnlyFactory(").substringBefore("\n\n").contains(".setUpstreamDataSourceFactory(null)"))
        // Never for a held item; catch-up enqueues the job once.
        assertTrue(body(through, "private suspend fun settleLocked(").contains("if (ItemRef(row.entryId, row.itemKey) in PlayerHub.heldLocal) return null"))
        // qa-30-s3b-code L3: catch-up starts no job (the sync's plan does, for what is wanted); L4: an item let go
        // half played asks for one.
        assertFalse(body(through, "suspend fun catchUp(").contains("Downloads.enqueue"))
        val settle = body(through, "suspend fun settle(")
        assertTrue(settle.contains("incomplete = it == null && row.playedAt != null && ref !in PlayerHub.heldLocal"))
        assertTrue(settle.contains("if (incomplete) Downloads.enqueue(context, store.downloadMobileData)"))
    }

    /** §1.6 (L7): the pure plan takes the held set; the worker and the sync must pass the player's. */
    @Test
    fun `DownloadWorker and Sync plan with PlayerHub's held set`() {
        val downloads = code("src/main/java/me/vibb/music/sync/Downloads.kt")
        val sync = code("src/main/java/me/vibb/music/sync/Sync.kt")
        val art = code("src/main/java/me/vibb/music/sync/ArtPlan.kt")
        val calls = (Regex("downloadPlan\\([^\\n]*").findAll(downloads + sync + art + service).map { it.value }).toList()
        assertEquals(calls.toString(), 4, calls.size)
        assertTrue(calls.toString(), calls.all { it.endsWith("PlayerHub.heldLocal)") || it.endsWith("PlayerHub.heldLocal).extras") })
        // The worker evicts each round before it fetches, with Storytel's floor only while the login works.
        val work = body(downloads, "override suspend fun doWork(): Result")
        assertTrue(
            work.contains(
                "val plan = downloadPlan(store.snapshot(), dao.downloads(), System.currentTimeMillis(), PlayerHub.heldLocal)\n" +
                    "            CacheThrough.evict(applicationContext, plan, storageLimitBytes(store.storageLimitGb), extrasFloor(plan.wantsStorytel, StorytelAccount.get(applicationContext).state() == \"ok\"))",
            ),
        )
        assertTrue(work.indexOf("CacheThrough.evict(") < work.indexOf("run.fetchOne(w)"))
        // The sync: prune, sweep, evict - in that order (qa-30-s3b-code L2: orphans never cost an extra), inside
        // runLock, before the downloads.
        val order = listOf(
            "val plan = downloadPlan(store.snapshot(), dao.downloads(), System.currentTimeMillis(), PlayerHub.heldLocal)",
            "CacheThrough.remove(context, plan.prune)",
            "CacheThrough.sweepOrphans(context)",
            "CacheThrough.evict(context, plan, storageLimitBytes(store.storageLimitGb), extrasFloor(plan.wantsStorytel, StorytelAccount.get(context).state() == \"ok\"))",
            "val downloadMobileData = store.downloadMobileData",
        ).map { body(sync, "private suspend fun runLocked(").indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 } && order == order.sorted())
        // The report's extra_bytes from its own plan, so the job's report isn't 0 (L1).
        assertTrue(sync.contains("val plan = downloadPlan(snap, store.db.downloads(), System.currentTimeMillis(), PlayerHub.heldLocal)"))
        assertTrue(sync.contains("val extraBytes = CacheThrough.extraBytes(context, plan)"))
        // Eviction counts the held extras as staying (qa-30-s3b-code N1): freed bytes are only the unheld ones'.
        assertTrue(body(through, "suspend fun evict(").contains("extrasToEvict(extras, PlayerHub.heldLocal, MediaCache.totalBytes(context), limitBytes, MediaCache.freeBytes(context), floorBytes)"))
        // The sweep reads the cache's keys before the rows (§1.8).
        val sweep = body(through, "suspend fun sweepOrphans(")
        assertTrue(sweep.indexOf("val keys = MediaCache.resourceKeys(context)") < sweep.indexOf("val rows = store.db.downloads()"))
    }

    /** §1.6 (L4): a plan computed earlier is not enough - every removal asks the player again. */
    @Test
    fun `every MediaCache remove outside PlaybackService's own load re-reads heldLocal`() {
        val removers = mainSources().filter { Regex("MediaCache\\.remove(Resource)?\\(").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("CacheThrough.kt", "PlaybackService.kt"), removers)
        // The service's own: the broken copy of the item that just failed (it is moving on), and its queue's stale bytes.
        assertEquals(1, Regex("MediaCache\\.remove\\(").findAll(service).count())
        assertTrue(body(service, "private fun onError(").contains("MediaCache.remove(this@PlaybackService, copy.cacheKey, copy.hls)"))
        // CacheThrough: every function that removes reads the held set right before it does.
        val functions = through.split(Regex("\\n    (suspend )?fun ")).drop(1)
        val removing = functions.filter { Regex("MediaCache\\.remove(Resource)?\\(").containsMatchIn(it) }
        assertEquals(4, removing.size)
        for (f in removing) {
            val at = Regex("MediaCache\\.remove(Resource)?\\(").find(f)!!.range.first
            val check = listOf("in PlayerHub.heldLocal) return@withLock", "heldCacheKeys(store, rows)) != null) continue", "!in before) MediaCache.remove", "in heldCacheKeys(store, store.db.downloads())) return@withLock")
            assertTrue(f.substringBefore("\n"), check.any { c -> f.indexOf(c).let { it in 0..at } })
        }
        assertTrue("queueRows skips what the previous queue held", body(through, "suspend fun queueRows(").contains("if (ItemRef(entry.id, item.key) !in before) MediaCache.remove("))
        // The sync and the job remove only through it.
        assertTrue(code("src/main/java/me/vibb/music/sync/Downloads.kt").contains("override suspend fun removeCopy(cacheKey: String, hls: Boolean) = CacheThrough.removeCopy(context, cacheKey, hls)"))
    }

    /** §1.5: a stream's row and its played time; a Storytel book, an own file and a Sonos room never mark one. */
    @Test
    fun `the phone's player marks what it played, an existing row only`() {
        val mark = body(service, "private fun markPlayed(a: Active, item: PlayItem) {")
        assertTrue(mark.contains("if (renderer.kind != RendererKind.LOCAL || item.fileId != null || a.entry.source == Source.STORYTEL) return"))
        assertTrue(mark.contains("scope.launch(writer) { store.db.markPlayed(entryId, item.key, now) }"))
        assertTrue(body(service, "private fun startedPlaying(").contains("markPlayed(a, a.items[index])"))
        assertTrue("when it really plays: the sink's row exists by then", body(service, "private fun onIsPlayingChanged(").contains("markPlayed(a, item)"))
        // The new queue: claim, then the rows read again, then the stale ones (L4).
        val active = body(service, "private suspend fun activeFor(")
        val order = listOf(
            "val before = PlayerHub.heldLocal",
            "PlayerHub.claim(heldItems(entry.id, keys, start, start + 1))",
            "val fresh = CacheThrough.queueRows(this, entry, if (through) items else emptyList(), before)",
        ).map { active.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 } && order == order.sorted())
        assertTrue(active.contains("if (claimsQueue(kind, entry)) {"))
    }
}
