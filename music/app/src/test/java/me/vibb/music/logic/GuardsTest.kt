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
    fun `every command and playback resumption waits until the renderer is settled`() {
        // Design 26a §4.7: Home's RESUME after a restart must not start the phone under a playing speaker.
        val command = service.substringAfter("override fun onCustomCommand(").substringBefore("when (customCommand.customAction)")
        assertTrue(command.contains("scope.launch {\n                rendererReady.await()\n"))
        val resumption = service.substringAfter("override fun onPlaybackResumption(").substringBefore("val restored = restore()")
        assertTrue(resumption.contains("scope.launch {\n                rendererReady.await()\n"))
        assertEquals("completed once, in onCreate", 1, Regex("""rendererReady\.complete\(""").findAll(service).count())
    }

    @Test
    fun `the service reaches the player only through the renderer, which alone persists positions`() {
        // Design 26a §3.1 (QA H3): no direct ExoPlayer left in the service; saveNow is the one save.
        assertFalse(service.contains("exo."))
        assertFalse(service.contains("ExoPlayer"))
        assertFalse(service.contains("savePosition"))
        assertFalse("the display position is never stored", Regex("""positionMs\(\)[^\n]*Row\(""").containsMatchIn(service))
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
