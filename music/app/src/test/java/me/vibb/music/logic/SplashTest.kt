package me.vibb.music.logic

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The launch splash: the app's first frame is the system splash plus the wordmark, until the first content frame. */
class SplashTest {
    private fun res(path: String) = moduleFile("src/main/res/$path").readText()
    private fun attr(xml: String, name: String) = Regex("""$name="([^"]+)"""").find(xml)!!.groupValues[1]

    @Test
    fun `the cap is a backstop, never a hang`() {
        assertTrue("the cap is $SPLASH_CAP_MS ms", SPLASH_CAP_MS in 1_000L..4_000L)
    }

    @Test
    fun `the first frame's mark is the system splash's - the drawable unscaled, centred on the window`() {
        val theme = res("values/themes.xml")
        // Android's geometry holds for an icon with no icon background, and there is no branding image to move
        assertTrue(theme.contains("""name="android:windowSplashScreenAnimatedIcon">@drawable/splash_vibb_mark<"""))
        assertFalse(theme.contains("windowSplashScreenIconBackgroundColor"))
        assertFalse(theme.contains("windowSplashScreenBrandingImage"))
        // WMShell: the 160 dp icon x 1.2 without a background = the 192 dp view, its drawable 1.5 x that
        assertEquals(160f * 1.2f * 1.5f, SPLASH_MARK_DP, 0.001f)
        val mark = res("drawable/splash_vibb_mark.xml")
        assertEquals("${SPLASH_MARK_DP.toInt()}dp", attr(mark, "android:width"))
        assertEquals("${SPLASH_MARK_DP.toInt()}dp", attr(mark, "android:height"))
        assertEquals(SPLASH_MARK_DP, attr(mark, "android:viewportWidth").toFloat(), 0f)
        assertEquals("the rings' centre is the canvas's", SPLASH_MARK_DP / 2, attr(mark, "android:translateX").toFloat(), 0f)
        assertEquals(SPLASH_MARK_DP / 2, attr(mark, "android:translateY").toFloat(), 0f)
    }

    @Test
    fun `the rings' edge is read from the drawable and lies inside the splash's clip circle`() {
        val mark = res("drawable/splash_vibb_mark.xml")
        // the mark's group (3.3645), then each ring's own (0.94)
        val (scale, ring) = Regex("""android:scaleX="([\d.]+)"""").findAll(mark).map { it.groupValues[1].toFloat() }.take(2).toList()
        val points = Regex("""[ML](-?[\d.]+) (-?[\d.]+)""").findAll(mark).map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()
        val stroke = attr(mark, "android:strokeWidth").toFloat()
        val edge = (points.maxOf { (x, y) -> hypot(x, y) } + stroke / 2) * ring * scale
        assertEquals(SPLASH_RINGS_RADIUS_DP, edge, 0.05f)
        // nothing reaches the smallest AOSP icon mask (pebble's 92 dp), so the app's unclipped drawing is the splash's
        assertTrue(edge < 92f)
        // Q-9: "under the rings" is from the rings' lowest ink (y down), not their widest radius
        val bottom = (points.maxOf { it.second } + stroke / 2) * ring * scale
        assertEquals(SPLASH_RINGS_BOTTOM_DP, bottom, 0.05f)
    }

    @Test
    fun `vibb music sits 24 dp under the rings' lowest ink, its ink centred in its box`() {
        val word = res("drawable/splash_wordmark.xml")
        val ys = Regex("""(-?[\d.]+),(-?[\d.]+)""").findAll(word).map { it.groupValues[2].toFloat() }.toList()
        val box = attr(word, "android:viewportHeight").toFloat()
        assertEquals(SPLASH_WORDMARK_INK_DP, ys.max() - ys.min(), 0.02f)
        assertEquals("the ink centred in the box", box / 2, (ys.max() + ys.min()) / 2, 0.02f)
        // the box's centre is placed SPLASH_WORDMARK_BELOW_DP under the window's centre = the rings' centre
        val inkTop = SPLASH_WORDMARK_BELOW_DP - SPLASH_WORDMARK_INK_DP / 2
        assertEquals(24f, inkTop - SPLASH_RINGS_BOTTOM_DP, 0.001f)
    }

    @Test
    fun `the app splash fades only after the system splash's fade, or with none handed over (Q-3)`() {
        for (system in SystemSplash.entries) for (wait in listOf(false, true)) {
            assertFalse("no content, no fade", splashFadeDue(contentShown = false, system, wait))
        }
        assertFalse("the system splash still fading: the wordmark stays whole", splashFadeDue(true, SystemSplash.FADING, handOverWaitOver = true))
        assertTrue("its fade over", splashFadeDue(true, SystemSplash.GONE, handOverWaitOver = false))
        assertFalse("a hand-over may still come", splashFadeDue(true, SystemSplash.UNKNOWN, handOverWaitOver = false))
        assertTrue("none came: a start without a system splash", splashFadeDue(true, SystemSplash.UNKNOWN, handOverWaitOver = true))
    }

    @Test
    fun `the system splash's exit always removes the view, then says gone (Q-5)`() {
        val log = mutableListOf<String>()
        var end: (() -> Unit)? = null
        systemSplashExit(fade = { end = it; log += "fade" }, remove = { log += "remove" }, state = { log += it.name })
        assertEquals(listOf("FADING", "fade"), log)
        end!!() // the animator's end - a cancel's too
        assertEquals(listOf("FADING", "fade", "remove", "GONE"), log)
        // a remove that throws still lets the app splash fade
        val states = mutableListOf<SystemSplash>()
        runCatching { systemSplashExit(fade = { it() }, remove = { error("gone") }, state = { states += it }) }
        assertEquals(listOf(SystemSplash.FADING, SystemSplash.GONE), states)
    }

    @Test
    fun `the glue - the listener before the first frame, the splash owed by the view model, the geometry's constants (Q-1, Q-5)`() {
        val main = moduleFile("src/main/java/me/vibb/music/ui/MainActivity.kt").readText()
        val create = main.substringAfter("override fun onCreate(").substringBefore("\n    }\n")
        assertTrue(create.indexOf("fadeSystemSplash()") in 0 until create.indexOf("setContent {"))
        assertTrue(create.contains("val splashOwed = !vm.contentShown"))
        assertTrue(create.contains("MusicRoot(vm, actions, onContent = { vm.contentShown = true })"))
        assertTrue(create.contains("if (splashOwed) LaunchSplash(vm.contentShown, systemSplash)"))
        assertFalse("not by the saved state: a process death restores it too", create.substringAfter("splashOwed").contains("savedInstanceState"))
        val listener = main.substringAfter("private fun fadeSystemSplash() {").substringBefore("\n    }\n")
        assertTrue(listener.contains("splashScreen.setOnExitAnimationListener { view ->\n            systemSplashExit("))
        assertTrue(listener.contains("ObjectAnimator.ofFloat(view, View.ALPHA, 0f)") && listener.contains("doOnEnd { onEnd() }"))
        assertTrue(listener.contains("remove = view::remove,") && listener.contains("state = { systemSplash = it },"))
        val splash = moduleFile("src/main/java/me/vibb/music/ui/LaunchSplash.kt").readText()
        assertTrue(splash.contains("Box(Modifier.fillMaxSize().graphicsLayer"))
        assertFalse("the whole window, as the system splash", splash.contains("windowInsets") || splash.contains("Padding"))
        assertTrue(splash.contains("Image(painterResource(R.drawable.splash_vibb_mark), null, Modifier.align(Alignment.Center).requiredSize(SPLASH_MARK_DP.dp))"))
        assertTrue(splash.contains("Image(painterResource(R.drawable.splash_wordmark), null, Modifier.align(Alignment.Center).offset(y = SPLASH_WORDMARK_BELOW_DP.dp))"))
        assertTrue(splash.contains("val fade = splashFadeDue(contentShown, system, waitOver)"))
    }

    @Test
    fun `the first content frame waits for the stored row and the carousel's start tile (Q-10)`() {
        assertFalse("no snapshot", contentReady(snapshot = false, entries = true, rowKnown = true, rowStored = false, startSettled = false, capped = true))
        assertFalse("the row not read yet", contentReady(snapshot = true, entries = true, rowKnown = false, rowStored = false, startSettled = false, capped = false))
        assertFalse("a stored row before the start tile", contentReady(snapshot = true, entries = true, rowKnown = true, rowStored = true, startSettled = false, capped = false))
        assertTrue("on the last played tile", contentReady(snapshot = true, entries = true, rowKnown = true, rowStored = true, startSettled = true, capped = false))
        assertTrue("the cap, the backstop: whatever there is", contentReady(snapshot = true, entries = true, rowKnown = false, rowStored = false, startSettled = false, capped = true))
    }

    @Test
    fun `an empty library's empty state follows the splash at once, whatever the row (the r13 emulator run)`() {
        // the run held the splash to the 3 s cap on an empty library: nothing to wait for without a carousel
        assertTrue("no entries, the row not read yet", contentReady(snapshot = true, entries = false, rowKnown = false, rowStored = false, startSettled = false, capped = false))
        assertTrue("no entries, a stored row whose start never settles", contentReady(snapshot = true, entries = false, rowKnown = true, rowStored = true, startSettled = false, capped = false))
    }

    @Test
    fun `entries but no stored row - the library at tile 0 at once, no start to wait for`() {
        assertTrue(contentReady(snapshot = true, entries = true, rowKnown = true, rowStored = false, startSettled = false, capped = false))
    }
}
