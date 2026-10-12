package me.vibb.music.logic

import android.content.Intent
import me.vibb.music.Fixtures.moduleFile
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
        assertTrue(kid.contains("override fun play() {\n            if (playAllowed(suspendedNow())) super.play()"))
        assertTrue(kid.contains("if (!playWhenReady || playAllowed(suspendedNow())) super.setPlayWhenReady(playWhenReady)"))
        val command = service.substringAfter("override fun onCustomCommand(").substringBefore("when (customCommand.customAction)")
        // OUTPUT to a Sonos room starts the speaker (design 26a §3.3): the synchronous prefix reads its argument
        assertTrue(command.contains("val toRoom = customCommand.customAction == Commands.OUTPUT && Output.sonosUid(args.getString(Commands.ARG_OUTPUT)) != null"))
        assertTrue(command.contains("if ((customCommand.customAction in Commands.PLAYS || toRoom) && !playAllowed(suspendedNow())) {"))
        assertTrue("before the command coroutine", command.indexOf("!playAllowed(suspendedNow())") < command.indexOf("scope.launch {"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("val future")
        assertTrue(resumption.contains("if (!playAllowed(suspendedNow())) return"))
        // (Commands itself builds SessionCommands with Android Bundles: read, not loaded.)
        assertTrue(code("src/main/java/me/vibb/music/playback/PlayerHub.kt").contains("val PLAYS: Set<String> = setOf(PLAY_ENTRY, PLAY_ITEM, RESUME)"))
        // The session is built on KidPlayer, so every controller goes through it - also after a
        // renderer switch (design 26a §0): every setPlayer wraps KidPlayer.
        assertTrue(service.contains("MediaSession.Builder(this, KidPlayer(renderer.player))"))
        assertEquals(1, Regex("""\.setPlayer\(""").findAll(service).count())
        assertTrue(service.contains("session?.setPlayer(KidPlayer(next.player))"))
    }

    @Test
    fun `the renderer changes in one place, which hands the service's events to the new one only`() {
        // qa-26a-step2-code M1: the old renderer is saved and paused before it is detached.
        val attach = service.substringAfter("private fun attach(next: Renderer) {").substringBefore("\n    }\n")
        assertTrue(attach.contains("old.saveNow(force = true)"))
        assertTrue(attach.indexOf("old.pause()") < attach.indexOf("old.onEvent = null"))
        assertTrue(attach.indexOf("next.onEvent = { onRendererEvent(it) }") < attach.indexOf("renderer = next"))
        assertTrue(attach.contains("session?.setPlayer(KidPlayer(next.player))"))
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
        val notAdopted = adopt.substringAfter("if (queue == null || live == null || renderer !== local) {").substringBefore("\n        val (a, index) = queue")
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
        assertTrue(resumption.contains("if (renderer.kind == RendererKind.SONOS) {"))
        assertTrue(resumption.substringAfter("if (renderer.kind == RendererKind.SONOS) {").substringBefore("return@launch").contains("resumeLast()\n                        future.setException("))
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
        // The display position is never stored (qa-26a-step2-code M2): its four readers are PlayerHub.position,
        // previous's 3 s rule, the resume overlap and the launcher's setNowPlaying - no other.
        assertEquals(4, Regex("""positionMs\(\)""").findAll(service).count())
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
        assertEquals("lost, nothing playable, the suspension, the room scope", 4, Regex("""comeHome\((old|sonos), play = false\)""").findAll(service).count())
        // the renderer changes only in attach: the phone at start, an adopted room, a picked room, the phone again
        assertEquals("attach: defined once, four callers", 5, Regex("""\battach\(""").findAll(service).count())
        assertTrue(service.contains("attach(sonosRenderer(uid, name))"))
        assertTrue(service.contains("attach(sonos)\n        active = a"))
        assertEquals("every room's renderer holds the locks", 1, Regex("""SonosRenderer\(""").findAll(service).count())
        assertTrue(service.contains("onArmedPlaying = { locks.hold(owner, it) },\n            roomAllowed = { groupAllowed(roomScope, home.rooms.state.value.rooms, it) },"))
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
        // the snapshot is only ever replaced by accept (a poll, a live classify) or the seed of a play
        assertEquals(2, Regex("""\bsnap = """).findAll(sonos).count())
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("override fun saveNow(force: Boolean) = controller.bookmarkNow(force)"))
        // one write behind both renderers' saveNow
        val main = moduleFile("src/main/java").walk().filter { it.isFile && it.extension == "kt" }
            .map { f -> f.name to f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "") }.toList()
        val calls = main.flatMap { (name, text) -> Regex("""persistPlace\(""").findAll(text).map { name }.toList() }
        assertEquals(listOf("LocalRenderer.kt", "LocalRenderer.kt", "SonosRenderer.kt"), calls.sorted())
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
        assertTrue("before anything is sent", play.indexOf("if (!mayPlay()) {") < play.indexOf("work.submit("))
        assertTrue(sonos.substringAfter("private fun resume(").substringBefore("\n    }\n").contains("if (!mayPlay()) return"))
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("mayPlay = { playAllowed(suspendedNow()) && roomAllowed(this@SonosRenderer.uid) },"))
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
        assertTrue(publish.contains("rooms = allowedRooms(home.rooms.state.value, roomScope),"))
        assertEquals("the sheet's rows in publish, the belt's groups", 2, Regex("""home\.rooms\.state\.value""").findAll(service).count())
        // OUTPUT to a room outside the scope (or grouped with one): refused with its own line before anything moves
        val toRoom = service.substringAfter("private suspend fun toRoom(uid: String) {").substringBefore("\n    }\n")
        val refusal = toRoom.substringBefore("val old = renderer")
        assertTrue(refusal.contains("if (!groupAllowed(allowedRoomsNow(), roomsNow(), uid)) {"))
        assertTrue(refusal.contains("PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.ROOM_NOT_ALLOWED)"))
        assertTrue(refusal.contains("return"))
        for (move in listOf("handOff(", "saveNow(", "home.write(", "attach(", "start(", ".play()")) assertFalse(move, refusal.contains(move))
        // a session on a room not allowed as grouped now: the bookmark and the ours-gated Stop (the hand-off) before the phone, paused
        val leave = service.substringAfter("private suspend fun leaveDisallowedRoom() {").substringBefore("\n    }\n")
        assertTrue(leave.contains("if (!mustLeaveRoom(sonos.uid, roomsNow(), allowedRoomsNow())) return"))
        assertTrue(leave.contains("PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.ROOM_NOT_ALLOWED)"))
        assertFalse("its own line (qa-26a-step3c-code L1)", leave.contains("CANT_PLAY_ON_SONOS"))
        assertTrue(leave.indexOf("handOff(sonos)") in 0 until leave.indexOf("comeHome(sonos, play = false)"))
        val gate = service.substringAfter("private suspend fun leaveIfNotAllowed() {").substringBefore("\n    }\n")
        assertTrue(gate.contains("rendererReady.await()\n        switching.withLock { leaveDisallowedRoom() }"))
        // ... at the next sync, when the groups change (M1), and after a follow (M1)
        val onCreate = service.substringAfter("override fun onCreate() {").substringBefore("override fun onGetSession")
        val sync = onCreate.substringAfter("store.libraries().map { it?.sonosRooms }.distinctUntilChanged()")
        assertTrue(sync.contains("roomScope = rooms\n                publish()\n                leaveIfNotAllowed()"))
        assertTrue(onCreate.contains("home.rooms.state.collect {\n                publish()\n                if (renderer is SonosRenderer) leaveIfNotAllowed()"))
        assertTrue(service.contains("onFollowed = { scope.launch { leaveIfNotAllowed() } },"))
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("writeChoice { it.copy(uid = uid, name = name) }\n            onFollowed()"))
        // the groups are watched only under a limited scope while a room plays, and never with a discovery round
        val watch = onCreate.substringAfter("delay(SCOPE_WATCH_MS)").substringBefore("\n        publish()")
        assertTrue(watch.contains("if (roomScope != null && sonos.showsPlaying) withContext(Dispatchers.IO) { home.rooms.refreshQuietly() }"))
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
        assertTrue(renderer.contains("&& roomAllowed(this@SonosRenderer.uid) },"))
        // the kid's line, both languages
        assertTrue(code("src/main/java/me/vibb/music/ui/MusicRoot.kt").contains("PlayerHub.Message.ROOM_NOT_ALLOWED -> R.string.room_not_allowed"))
    }

    @Test
    fun `the parent's volume cap reaches every volume set of our own on a Sonos room`() {
        // design 26a §5.3: read live from the managed config, as mobile_data
        val renderer = code("src/main/java/me/vibb/music/sonos/SonosRenderer.kt")
        assertTrue(renderer.contains("cap = { Bridge.config(context).volumeCapPct },"))
        val set = sonos.substringAfter("fun setVolume(volume: Int) {").substringBefore("\n    }\n")
        assertTrue(set.contains("val v = volumeTarget(volume, cap())"))
        assertTrue(sonos.substringAfter("fun adjustVolume(delta: Int) {").substringBefore("\n    }\n").contains("setVolume(base + delta)"))
        assertTrue(renderer.contains("handling { controller.setVolume(deviceVolume) }"))
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
        assertTrue(step.contains("val inCall = launcherInCall || (modeInCall && r.kind == RendererKind.SONOS)"))
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
        assertTrue(events.contains("PauseCause.USER -> if (!selfPausing && sleepEndsAt != null) setSleep(0)"))
        assertTrue(service.substringAfter("private fun sleepEnded() {").substringBefore("\n    }\n").contains("callPause = callTimerEnded(callPause)"))
        // the launcher's two commands, granted to a launcher signed like this app only, never a play gate bypass
        val connect = service.substringAfter("override fun onConnect(").substringBefore("override fun onCustomCommand(")
        assertTrue(connect.contains("controller.packageName in LAUNCHER_PACKAGES && Bridge.sameSigner(this@PlaybackService, controller.packageName)"))
        assertTrue(connect.contains("Commands.LAUNCHER.forEach { add(it) }"))
        assertTrue(service.contains("Commands.CALL_HOLD -> launcherCall(true)"))
        assertTrue(service.contains("Commands.CALL_RELEASE -> launcherCall(false)"))
        assertFalse(code("src/main/java/me/vibb/music/playback/PlayerHub.kt").substringAfter("val PLAYS").substringBefore("\n").contains("CALL"))
    }
}
