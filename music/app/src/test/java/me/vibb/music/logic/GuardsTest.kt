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
        assertTrue(command.contains("customCommand.customAction in Commands.PLAYS && !playAllowed(suspendedNow())"))
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
        assertEquals("onEvent is set only in attach", 2, Regex("""\.onEvent = """).findAll(service).count())
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
        assertTrue("completed in onCreate", onCreate.contains("rendererReady.complete(Unit)"))
        assertEquals("and nowhere else", 1, Regex("""rendererReady\.complete\(""").findAll(service).count())
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
}
