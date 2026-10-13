package com.kidslauncher.mdm.music

import android.app.admin.DevicePolicyManager
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media surfaces' and the volume cap's pure rules (design 21 §3, step 4). */
class MusicMediaPlanTest {
    private fun session(play: SessionPlay = SessionPlay.PLAYING, position: Long = 60_000, at: Long = 1_000, duration: Long = 600_000, speed: Float = 1f) =
        MusicSession("me.vibb.music", play, "Reven og månen", "Kapittel 3: Månen", position, at, speed, duration)

    @Test
    fun `buffering and the skips are the intent to play, a stopped or failed session is none`() {
        for (s in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS)) {
            assertEquals(SessionPlay.PLAYING, sessionPlay(s))
        }
        assertEquals(SessionPlay.PAUSED, sessionPlay(PlaybackState.STATE_PAUSED))
        for (s in listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR, null)) {
            assertEquals(SessionPlay.STOPPED, sessionPlay(s))
        }
        assertTrue(session(SessionPlay.PAUSED).live)
        assertFalse(session(SessionPlay.PAUSED).playing)
        assertFalse(session(SessionPlay.STOPPED).live)
    }

    @Test
    fun `the position moves on while playing, within the item`() {
        assertEquals(65_000, positionNow(session(), nowElapsedMs = 6_000))
        assertEquals(70_000, positionNow(session(speed = 2f), nowElapsedMs = 6_000))
        assertEquals("paused stands still", 60_000, positionNow(session(SessionPlay.PAUSED), nowElapsedMs = 600_000))
        assertEquals("never past the end", 600_000, positionNow(session(), nowElapsedMs = 10_000_000))
        assertEquals("a clock behind the update adds nothing", 60_000, positionNow(session(), nowElapsedMs = 0))
        assertEquals("no update time: as reported", 60_000, positionNow(session(at = 0), nowElapsedMs = 6_000))
        assertEquals("unknown duration: no end", 9_060_000, positionNow(session(duration = 0), nowElapsedMs = 9_001_000))
    }

    @Test
    fun `no listener access, an unusable app or a stopped session shows nothing`() {
        val playing = session()
        assertSame(playing, shownSession(access = true, usable = true, session = playing))
        assertNull(shownSession(access = false, usable = true, session = playing))
        assertNull("suspended by a time rule, or not allowed", shownSession(access = true, usable = false, session = playing))
        assertNull(shownSession(access = true, usable = true, session = session(SessionPlay.STOPPED)))
        assertNull(shownSession(access = true, usable = true, session = null))
    }

    @Test
    fun `the card's line - item, position and duration as the mockup`() {
        val av: (String, String) -> String = { a, b -> "$a av $b" }
        assertEquals("Kapittel 3 · 7:46 av 18:30", progressLine("Kapittel 3", 466_000, 1_110_000, av))
        assertEquals("7:46 av 18:30", progressLine(null, 466_000, 1_110_000, av))
        assertEquals("7:46 av 18:30", progressLine("  ", 466_000, 1_110_000, av))
        assertEquals("Kapittel 3 · 7:46", progressLine("Kapittel 3", 466_000, 0, av))
        assertEquals("0:00", clockText(-5))
        assertEquals("0:59", clockText(59_999))
        assertEquals("59:59", clockText(3_599_000))
        assertEquals("1:02:03", clockText(3_723_000))
        assertEquals(420, progressPermille(42_000, 100_000))
        assertEquals(1000, progressPermille(200_000, 100_000))
        assertEquals(0, progressPermille(5_000, 0))
    }

    @Test
    fun `the volume cap - off by default, the parent's percentage of the stream, never mute`() {
        assertNull("off", volumeCapIndex(15, 0, null))
        assertEquals(13, volumeCapIndex(15, 0, 90))
        assertEquals(12, volumeCapIndex(15, 0, 80))
        assertEquals(10, volumeCapIndex(15, 0, 70))
        assertEquals(9, volumeCapIndex(15, 0, 60))
        assertEquals(15, volumeCapIndex(25, 0, 60))
        assertNull("100 % is no cap", volumeCapIndex(15, 0, 100))
        assertNull("a server fault is no cap, never mute", volumeCapIndex(15, 0, 0))
        assertNull(volumeCapIndex(15, 0, -10))
        assertNull("no stream range", volumeCapIndex(0, 0, 60))
        assertEquals("never below 1", 1, volumeCapIndex(1, 0, 60))
        assertEquals("never below the stream's minimum", 3, volumeCapIndex(4, 3, 60))
        // Set back only when above.
        assertEquals(9, volumeToSet(12, 9))
        assertNull(volumeToSet(9, 9))
        assertNull(volumeToSet(3, 9))
        assertNull(volumeToSet(15, null))
    }

    @Test
    fun `the cap holds while managed, and the override and the pause lift it`() {
        assertEquals(60, capPctInForce(60, managed = true, overrideActive = false))
        assertNull(capPctInForce(60, managed = true, overrideActive = true))
        assertNull(capPctInForce(60, managed = false, overrideActive = false))
        assertNull(capPctInForce(null, managed = true, overrideActive = false))
        // The cap comes from the policy's music object; an unreadable one is no cap and no failure.
        val policy = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"library_version": null, "mobile_data": false, "volume_cap_pct": 70, "storytel_generation": 0}""",
        )
        assertEquals(70, musicPolicy(policy)?.volumeCapPct)
    }

    @Test
    fun `a playing music app is paused before it is suspended, nothing else is`() {
        assertTrue(pauseBeforeSuspend("me.vibb.music", shouldBeSuspended = true, currentlySuspended = false))
        assertTrue(pauseBeforeSuspend("me.vibb.music.debug", shouldBeSuspended = true, currentlySuspended = false))
        assertFalse("already suspended", pauseBeforeSuspend("me.vibb.music", shouldBeSuspended = true, currentlySuspended = true))
        assertFalse("unsuspended", pauseBeforeSuspend("me.vibb.music", shouldBeSuspended = false, currentlySuspended = true))
        assertFalse("another app", pauseBeforeSuspend("org.other", shouldBeSuspended = true, currentlySuspended = false))
    }

    @Test
    fun `the device owner grants the Nytt notification while managed`() {
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED, notificationGrantState(managed = true))
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT, notificationGrantState(managed = false))
    }

    // Calls (design 26a §6.7).

    private fun calls(steps: List<Pair<Boolean, Boolean>>): List<CallMusicAction> {
        var state = CallHold()
        return steps.map { (inCall, playing) ->
            val (next, action) = callHoldStep(state, inCall, playing)
            state = next
            action
        }
    }

    @Test
    fun `a VoIP ring or call, our dialer's call or a system call is a call`() {
        assertTrue("Element X ringing: no audio mode yet", musicCallActive(com.kidslauncher.mdm.lock.VoipPhase.RINGING, ourCall = false, systemCall = false))
        assertTrue(musicCallActive(com.kidslauncher.mdm.lock.VoipPhase.IN_CALL, ourCall = false, systemCall = false))
        assertTrue(musicCallActive(com.kidslauncher.mdm.lock.VoipPhase.NONE, ourCall = true, systemCall = false))
        assertTrue(musicCallActive(com.kidslauncher.mdm.lock.VoipPhase.NONE, ourCall = false, systemCall = true))
        assertFalse(musicCallActive(com.kidslauncher.mdm.lock.VoipPhase.NONE, ourCall = false, systemCall = false))
        // a system call as the lock decides it: Telecom when known, else MODE_IN_CALL - never MODE_IN_COMMUNICATION
        assertFalse(com.kidslauncher.mdm.lock.managedCallActive(null, android.media.AudioManager.MODE_IN_COMMUNICATION))
    }

    @Test
    fun `music playing when a call starts gets a HOLD, and the RELEASE when it ends`() {
        assertEquals(listOf(CallMusicAction.HOLD, CallMusicAction.NONE, CallMusicAction.RELEASE), calls(listOf(true to true, true to false, false to false)))
        assertEquals("whatever the kid did during the call: the music app decides", CallMusicAction.RELEASE, calls(listOf(true to true, true to true, false to true)).last())
    }

    @Test
    fun `a pause made before the call gets nothing, and no call no look`() {
        assertEquals(listOf(CallMusicAction.NONE, CallMusicAction.NONE), calls(listOf(true to false, false to false)))
        assertEquals("playing only after the call began", listOf(CallMusicAction.NONE, CallMusicAction.NONE, CallMusicAction.NONE), calls(listOf(true to false, true to true, false to true)))
        assertEquals(listOf(CallMusicAction.NONE), calls(listOf(false to true)))
    }

    @Test
    fun `the call commands are the music app's, spelled once in the shared contract`() {
        assertEquals("me.vibb.music.CALL_HOLD", MUSIC_CALL_HOLD)
        assertEquals("me.vibb.music.CALL_RELEASE", MUSIC_CALL_RELEASE)
    }
}

/**
 * A suspended music app is never played from a launcher surface (qa-21-step4-code M1; the music app
 * refuses it too, `GuardsTest`): the controls look the app up at the tap, the surfaces re-render on
 * suspension events, and `apply()` pauses before it suspends.
 */
class MusicSuspensionGuardTest {
    private fun file(path: String) = listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path")
        .map { java.io.File(it) }.first { it.exists() }.readText()

    private fun body(source: String, start: String): String = source.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `play, next and previous look the app up at the tap, a pause always goes`() {
        val sessions = file("music/MusicSessions.kt")
        val playPause = body(sessions, "fun playPause(")
        assertTrue(playPause.contains("session?.playing == true -> controls.pause()"))
        assertTrue(playPause.contains("usable(context, c.packageName) -> controls.play()"))
        for (name in listOf("next", "previous")) {
            assertTrue(name, body(sessions, "fun $name(").contains("if (usable(context, c.packageName))"))
        }
    }

    @Test
    fun `a suspension or its end re-renders the surfaces`() {
        val app = file("Application.kt")
        for (callback in listOf("onPackagesSuspended", "onPackagesUnsuspended")) {
            val code = app.substringAfter("override fun $callback(").substringBefore("loadApps()")
            val changed = code.indexOf("MusicSessions.packagesChanged(packageNames?.toList())")
            assertTrue(callback, changed in 0 until code.indexOf("CameraLock.ownChange"))
        }
    }

    @Test
    fun `apply pauses a playing music app before it suspends it`() {
        val enforcer = file("server/AppEnforcer.kt")
        val block = enforcer.substringAfter("if (shouldBeSuspended != currentlySuspended) {").substringBefore("if (shouldBeHidden != currentlyHidden)")
        val pause = block.indexOf("MusicSessions.pauseBeforeSuspend(context, packageName)")
        val suspend = block.indexOf("dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)")
        assertTrue("pause at $pause, suspend at $suspend", pause in 0 until suspend)
    }

    @Test
    fun `the call rule watches every call the launcher sees, from process start`() {
        val calls = file("music/MusicCalls.kt")
        val init = body(calls, "fun init(")
        for (source in listOf("VoipCalls.addListener", "OngoingCalls.addListener", "addOnModeChangedListener")) assertTrue(source, init.contains(source))
        assertTrue(body(calls, "private fun evaluate(").contains("musicCallActive(VoipCalls.phase, OngoingCalls.hasLiveCall, systemCall(ctx))"))
        assertTrue(body(calls, "private fun systemCall(").contains("managedCallActive(managed, mode)"))
        // the music app pauses as its own pause (the sleep timer stays): never a plain session pause or play (qa-26a-step3b-code H1)
        val evaluate = body(calls, "private fun evaluate(")
        assertTrue(evaluate.contains("session?.transportControls?.sendCustomAction(command, Bundle())"))
        assertFalse(calls.contains("transportControls?.pause()"))
        assertFalse(calls.contains("transportControls?.play()"))
        assertTrue(file("music/MusicSessions.kt").contains("VolumeCap.init(app)\n            MusicCalls.init(app)"))
    }

    @Test
    fun `the lock's music tile and Home's card show a Sonos room's session as the phone's - one session, any playback type`() {
        // Design 26a (the merges of main): the music app's one MediaSession wraps whichever renderer
        // plays, so on a room the surfaces read the same platform session (PLAYBACK_TYPE_REMOTE, its
        // position anchored at the speaker's poll) - nothing filters on the playback type, and the tile
        // takes its art from the session's metadata, which design 27's late picture swaps in place.
        val sessions = file("music/MusicSessions.kt")
        val lock = file("music/MusicLockPanel.kt")
        for (source in listOf(sessions, lock)) {
            assertFalse(source.contains("playbackInfo"))
            assertFalse(source.contains("PLAYBACK_TYPE"))
        }
        assertTrue(sessions.contains("val music = controllers.firstOrNull { it.packageName in MUSIC_PACKAGES }"))
        assertTrue(sessions.contains("override fun onMetadataChanged(metadata: MediaMetadata?) = update(newArt = true)"))
        assertTrue(lock.contains("val art = MusicSessions.reading.art"))
        assertTrue(lock.contains("val position = positionNow(s, SystemClock.elapsedRealtime())"))
        // a room's poll-anchored position: PLAYING from the poll's measuredAt, paused stands still
        val room = MusicSession("me.vibb.music", SessionPlay.PLAYING, "Abels tårn", "Episode 12", 120_000, 50_000, 1f, 1_800_000)
        assertEquals(125_000, positionNow(room, nowElapsedMs = 55_000))
        assertEquals(120_000, positionNow(room.copy(play = SessionPlay.PAUSED), nowElapsedMs = 55_000))
    }

    @Test
    fun `the end of play is fed by the bridge`() {
        // Design 28 §2.1/§4: every setNowPlaying and the token's death, stamped before the surfaces re-render.
        val bridge = file("music/MusicBridgeService.kt")
        assertTrue(body(bridge, "fun store(context: Context, nowPlaying: Bundle?)").contains("MusicListening.observePlay(context, wasPlaying, playing)\n        changed()"))
        assertTrue(body(bridge, "fun playerGone(context: Context)").contains("MusicListening.observePlay(context, wasPlaying, playing = false)\n        changed()"))
        assertEquals(2, Regex("MusicListening\\.observePlay\\(").findAll(bridge).count())
    }

    @Test
    fun `the headset watch starts with the process, its baseline read before the callback, the stamps loaded on first use`() {
        // Design 28 §2.3/§4 (QA L4): after the call rule (the pin above), in its own try; the state never waits for it.
        assertTrue(file("music/MusicSessions.kt").contains("MusicCalls.init(app)\n            MusicListening.init(app)"))
        val listening = file("music/MusicListening.kt")
        val init = body(listening, "fun init(")
        val baseline = init.indexOf("am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)")
        val register = init.indexOf("am.registerAudioDeviceCallback(")
        assertTrue("baseline at $baseline, register at $register", baseline in 0 until register)
        assertTrue(init.contains("} catch (e: Exception) {"))
        assertFalse("init loads no stamps", init.contains("load("))
        for (entry in listOf("fun listening()", "fun observePlay(", "fun devicesAdded(")) {
            assertTrue(entry, listening.substringAfter(entry).substringBefore("\n\n").contains("load("))
        }
    }
}
