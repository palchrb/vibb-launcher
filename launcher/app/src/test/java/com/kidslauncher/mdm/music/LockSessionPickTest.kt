package com.kidslauncher.mdm.music

import android.content.pm.ApplicationInfo
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PIN lock's media tile for any allowed app (2026-11-04): the pick, who may show, and another app's text. */
class LockSessionPickTest {
    private val boot = 7
    private val now = BootStamp(3_600_000L, boot)
    private val none = Listening(null, null)
    private fun session(pkg: String, play: SessionPlay, changedAt: Long = now.elapsedMs - 60_000) =
        MusicSession(pkg, play, "Album", "Title", 60_000, changedAt, 1f, 600_000)
    private fun vibb(play: SessionPlay, usable: Boolean = true) = LockCandidate(session("me.vibb.music", play), vibb = true, usable = usable)
    private fun other(pkg: String, play: SessionPlay, usable: Boolean = true, changedAt: Long = now.elapsedMs - 60_000) =
        LockCandidate(session(pkg, play, changedAt), vibb = false, usable = usable)
    private fun picked(candidates: List<LockCandidate>, listening: Listening = none, access: Boolean = true) =
        lockTilePick(access, candidates, listening, now)?.let { candidates[it.index].session.packageName }

    @Test
    fun `the playing one first, Vibb music first among equals, then the platform's order`() {
        assertEquals("another app plays, Vibb music paused", "org.player", picked(listOf(vibb(SessionPlay.PAUSED), other("org.player", SessionPlay.PLAYING))))
        assertEquals("both play", "me.vibb.music", picked(listOf(other("org.player", SessionPlay.PLAYING), vibb(SessionPlay.PLAYING))))
        assertEquals("both paused (inside their windows)", "me.vibb.music",
            picked(listOf(other("org.player", SessionPlay.PAUSED), vibb(SessionPlay.PAUSED)), Listening(BootStamp(now.elapsedMs - 1, boot), null)))
        assertEquals("two other apps playing: the first active", "org.a", picked(listOf(other("org.a", SessionPlay.PLAYING), other("org.b", SessionPlay.PLAYING))))
        assertNull("a stopped session is nothing", picked(listOf(other("org.player", SessionPlay.STOPPED))))
        assertNull("no listener access", picked(listOf(other("org.player", SessionPlay.PLAYING)), access = false))
    }

    @Test
    fun `an app that isn't usable never shows - and doesn't hide the next one`() {
        assertNull(picked(listOf(other("org.player", SessionPlay.PLAYING, usable = false))))
        assertEquals("Vibb music paused shows when the playing app is suspended", "me.vibb.music",
            picked(listOf(other("org.player", SessionPlay.PLAYING, usable = false), vibb(SessionPlay.PAUSED)), Listening(BootStamp(now.elapsedMs - 1, boot), null)))
        assertEquals("Vibb music suspended", "org.player", picked(listOf(vibb(SessionPlay.PLAYING, usable = false), other("org.player", SessionPlay.PLAYING))))
    }

    @Test
    fun `another app's pause keeps its tile for the window from its own last change, the bridge's stamp is Vibb music's`() {
        val paused = other("org.player", SessionPlay.PAUSED, changedAt = now.elapsedMs - MUSIC_LINGER_MS + 1)
        val pick = lockTilePick(true, listOf(paused), none, now)!!
        assertEquals(now.elapsedMs + 1, pick.tile.hideAtElapsedMs)
        assertNull("its window over", picked(listOf(other("org.player", SessionPlay.PAUSED, changedAt = now.elapsedMs - MUSIC_LINGER_MS))))
        assertNull("a change stamped in the future opens nothing", picked(listOf(other("org.player", SessionPlay.PAUSED, changedAt = now.elapsedMs + 1))))
        val vibbPlayedLately = Listening(BootStamp(now.elapsedMs - 1, boot), null)
        assertNull("Vibb music's end of play keeps no other app",
            picked(listOf(other("org.player", SessionPlay.PAUSED, changedAt = now.elapsedMs - MUSIC_LINGER_MS)), vibbPlayedLately))
        val headset = Listening(null, BootStamp(now.elapsedMs - 1, boot))
        assertNull("a fresh headset brings no old paused session of another app back (code QA L5)",
            picked(listOf(other("org.player", SessionPlay.PAUSED, changedAt = now.elapsedMs - MUSIC_LINGER_MS)), headset))
        assertEquals("Vibb music keeps the headset window", "me.vibb.music", picked(listOf(vibb(SessionPlay.PAUSED)), headset))
        assertEquals("a paused app inside its window beats Vibb music paused outside its own", "org.player",
            picked(listOf(vibb(SessionPlay.PAUSED), other("org.player", SessionPlay.PAUSED))))
    }

    @Test
    fun `who may show on the lock - suspended never, another app only allowed, visible and not ours`() {
        val list = listOf("org.player")
        assertTrue(lockAppUsable("org.player", vibb = false, suspended = false, hidden = false, allowlist = list, ownPackage = "me.vibb.launcher", mediaApp = true))
        assertFalse("suspended (a rule, the budget)", lockAppUsable("org.player", false, suspended = true, hidden = false, allowlist = list, ownPackage = "me.vibb.launcher", mediaApp = true))
        assertFalse("not on the allowlist", lockAppUsable("org.other", false, false, false, list, "me.vibb.launcher", mediaApp = true))
        assertFalse("hidden by the device owner", lockAppUsable("org.player", false, false, hidden = true, allowlist = list, ownPackage = "me.vibb.launcher", mediaApp = true))
        assertTrue("no allowlist: an unmanaged phone", lockAppUsable("org.other", false, false, false, null, "me.vibb.launcher", mediaApp = true))
        assertFalse("never our own", lockAppUsable("me.vibb.launcher", false, false, false, null, "me.vibb.launcher", mediaApp = true))
        assertFalse("not a media app (a messenger's voice message)", lockAppUsable("org.player", false, false, false, list, "me.vibb.launcher", mediaApp = false))
        assertTrue("Vibb music: only suspension counts", lockAppUsable("me.vibb.music", vibb = true, suspended = false, hidden = false, allowlist = emptyList(), ownPackage = "me.vibb.launcher", mediaApp = false))
        assertFalse(lockAppUsable("me.vibb.music", vibb = true, suspended = true, hidden = false, allowlist = null, ownPackage = "me.vibb.launcher", mediaApp = true))
    }

    @Test
    fun `only music, video and browsers are media apps for the lock - the user, 2026-11-04`() {
        assertTrue(lockMediaApp(ApplicationInfo.CATEGORY_AUDIO, browser = false))
        assertTrue(lockMediaApp(ApplicationInfo.CATEGORY_VIDEO, browser = false))
        assertTrue("a browser, whatever it declares", lockMediaApp(ApplicationInfo.CATEGORY_UNDEFINED, browser = true))
        for (category in listOf(
            ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_GAME, ApplicationInfo.CATEGORY_UNDEFINED, ApplicationInfo.CATEGORY_PRODUCTIVITY,
            ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_MAPS, ApplicationInfo.CATEGORY_IMAGE, ApplicationInfo.CATEGORY_ACCESSIBILITY,
        )) {
            assertFalse("category $category", lockMediaApp(category, browser = false))
        }
    }

    /** QA M2 of lock-media-apps-2: a browser opens both a plain https and a plain http link; an app with only a host-less
     * https filter is no browser. The probes are exactly those two links. */
    @Test
    fun `a browser answers both the https and the http probe`() {
        val https = setOf("org.mozilla.firefox", "com.android.chrome", "com.example.fakebrowser")
        val http = setOf("org.mozilla.firefox", "com.android.chrome", "com.example.httponly")
        assertEquals(setOf("org.mozilla.firefox", "com.android.chrome"), lockBrowsers(https, http))
        assertEquals(emptySet<String>(), lockBrowsers(https, emptySet()))
        val sessions = java.io.File("src/main/java/com/kidslauncher/mdm/music/MusicSessions.kt").readText()
        assertTrue(sessions.contains("lockBrowsers(handlers(BROWSER_PROBE_HTTPS), handlers(BROWSER_PROBE_HTTP))"))
        assertTrue(sessions.contains("const val BROWSER_PROBE_HTTPS = \"https://example.invalid/\""))
        assertTrue(sessions.contains("const val BROWSER_PROBE_HTTP = \"http://example.invalid/\""))
    }

    @Test
    fun `another app's text is one cut line and its times are bounded`() {
        assertEquals("Line one line two", foreignText("  Line one\nline\ttwo\u0000"))
        assertEquals("bidi controls, separators and format characters are spaces (code QA L2)", "a b c d e f",
            foreignText("a\u202Eb\u2066c\u2028d\u200Be\u2029f"))
        assertEquals("a pile of combining marks is cut to three", "e\u0301\u0301\u0301x", foreignText("e" + "\u0301".repeat(100) + "x"))
        assertEquals("ordinary marks stay", "Tiếng Việt", foreignText("Tiếng Việt"))
        assertNull(foreignText(" \n "))
        assertEquals(FOREIGN_TEXT_MAX, foreignText("x".repeat(10_000))!!.length)
        val emoji = "a".repeat(FOREIGN_TEXT_MAX - 1) + "🎵"
        assertEquals("no half surrogate pair", "a".repeat(FOREIGN_TEXT_MAX - 1), foreignText(emoji))
        val bogus = foreignSession(MusicSession("org.player", SessionPlay.PLAYING, null, "t", Long.MAX_VALUE, 0, 1f, -5))
        assertEquals(FOREIGN_TIME_MAX_MS, bogus.positionMs)
        assertEquals(0L, bogus.durationMs)
        val stream = MusicSession("org.player", SessionPlay.PLAYING, null, "t", 0, 1, 4f, 0)
        assertEquals("a carried-forward stream stops at the bound (code QA L3)", FOREIGN_TIME_MAX_MS, lockPositionNow(stream, nowElapsedMs = 5 * 24 * 3_600_000L))
    }

    @Test
    fun `another app's transport goes as its own media keys, Vibb music's by its controls, and only play or pause again`() {
        TileAction.values().forEach { assertNull("Vibb music: transport controls ($it)", tileMediaKey(vibb = true, it)) }
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, tileMediaKey(vibb = false, TileAction.PLAY))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, tileMediaKey(vibb = false, TileAction.PAUSE))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, tileMediaKey(vibb = false, TileAction.NEXT))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, tileMediaKey(vibb = false, TileAction.PREVIOUS))
        assertTrue("a pause the app didn't follow", tileFallback(TileAction.PAUSE, playingNow = true))
        assertFalse(tileFallback(TileAction.PAUSE, playingNow = false))
        assertTrue("a play the app didn't follow", tileFallback(TileAction.PLAY, playingNow = false))
        assertFalse(tileFallback(TileAction.PLAY, playingNow = true))
        assertFalse("a skip is never sent twice", tileFallback(TileAction.NEXT, playingNow = true) || tileFallback(TileAction.PREVIOUS, playingNow = false))
    }
}
