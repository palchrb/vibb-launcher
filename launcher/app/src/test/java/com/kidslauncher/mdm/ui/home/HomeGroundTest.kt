package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.ui.wallpaper.GroundKind
import com.kidslauncher.mdm.ui.wallpaper.groundKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PLAN "A black flash behind Home after the Home button" (the user's videos 2026-10-16): Home draws its own wallpaper
 * copy while a transition into it can run - after a HOME intent from behind, never for Back, never at a lock-up.
 */
class HomeGroundTest {

    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }.readText()

    /** Code only: comments may name what the code does. */
    private fun code(path: String) = src(path)
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun body(text: String, head: String) = text.substringAfter(head).substringBefore("\n    }\n")

    private val arrived = HomeGroundState(arrivedAtMs = 10_000L)

    @Test
    fun `no HOME intent from behind - no copy (Back, a plain resume, a stopped Home)`() {
        assertFalse(homeGroundDue(HomeGroundState(), nowMs = 10_000L))
        assertFalse(homeGroundDue(HomeGroundState(enterDone = true), nowMs = 10_000L))
        assertNull(homeGroundRecheckInMs(HomeGroundState(), nowMs = 10_000L))
    }

    @Test
    fun `after a HOME intent - the floor, then until the enter animation ended, at most the cap`() {
        assertTrue(homeGroundDue(arrived, 10_000L))
        assertTrue(homeGroundDue(arrived.copy(enterDone = true), 10_000L + HOME_GROUND_MIN_MS - 1))
        assertFalse("the animation ended and the floor passed", homeGroundDue(arrived.copy(enterDone = true), 10_000L + HOME_GROUND_MIN_MS))
        assertTrue("still running", homeGroundDue(arrived, 10_000L + HOME_GROUND_MIN_MS))
        assertTrue(homeGroundDue(arrived, 10_000L + HOME_GROUND_MAX_MS - 1))
        assertFalse("no end came: the cap", homeGroundDue(arrived, 10_000L + HOME_GROUND_MAX_MS))
        assertFalse("Home left the front", homeGroundDue(arrived.copy(leftFront = true), 10_000L))
        assertTrue(HOME_GROUND_MIN_MS in 300L..1_000L)
        assertTrue(HOME_GROUND_MAX_MS in HOME_GROUND_MIN_MS..3_000L)
        // qa-home-round-code Q-6: the brief's numbers exactly - the floor the emulator run passed with, and the cap.
        assertEquals("the floor (700 ms)", 700L, HOME_GROUND_MIN_MS)
        assertEquals("the cap (2.5 s)", 2_500L, HOME_GROUND_MAX_MS)
    }

    @Test
    fun `the face's leave keeps the copy until the stop (design 59d step B)`() {
        val face = HomeGroundState(faceLeave = true)
        assertTrue(homeGroundDue(face, 0L))
        assertTrue(homeGroundDue(face.copy(arrivedAtMs = 0L, enterDone = true, leftFront = true), 1_000_000L))
        assertNull("only the stop ends it", homeGroundRecheckInMs(face, 0L))
    }

    @Test
    fun `the rechecks fall on the floor's and the cap's ends`() {
        assertEquals(HOME_GROUND_MIN_MS, homeGroundRecheckInMs(arrived, 10_000L))
        assertEquals(HOME_GROUND_MIN_MS - 200, homeGroundRecheckInMs(arrived.copy(enterDone = true), 10_200L))
        assertEquals(HOME_GROUND_MAX_MS - HOME_GROUND_MIN_MS, homeGroundRecheckInMs(arrived, 10_000L + HOME_GROUND_MIN_MS))
        assertNull(homeGroundRecheckInMs(arrived.copy(enterDone = true), 10_000L + HOME_GROUND_MIN_MS))
        assertNull(homeGroundRecheckInMs(arrived, 10_000L + HOME_GROUND_MAX_MS))
        assertNull(homeGroundRecheckInMs(arrived.copy(leftFront = true), 10_000L))
        // At each recheck the rule's answer has turned (or a later recheck is due).
        for (state in listOf(arrived, arrived.copy(enterDone = true))) {
            var now = 10_000L
            var steps = 0
            while (true) {
                val wait = homeGroundRecheckInMs(state, now) ?: break
                assertTrue(wait > 0)
                now += wait
                steps++
            }
            assertFalse(homeGroundDue(state, now))
            assertTrue(steps in 1..2)
        }
    }

    @Test
    fun `the copy is WallpaperGround's full ground - the placement the lock's ground and Android's bitmap share`() {
        assertEquals(GroundKind.FULL, groundKind(systemShowsOurs = true, scrimAlpha = 0, ownGround = true))
        assertEquals(GroundKind.FULL, groundKind(systemShowsOurs = true, scrimAlpha = 60, ownGround = true))
        assertEquals("steady state: transparent over Android's wallpaper", GroundKind.TRANSPARENT, groundKind(systemShowsOurs = true, scrimAlpha = 0))
        val ground = code("ui/wallpaper/WallpaperGround.kt")
        assertTrue(ground.contains("GroundKind.FULL -> WallpaperRender.GroundDrawable(state.current.fill, state.bitmap, scrim)"))
        val home = code("ui/HomeActivity.kt")
        val render = body(home, "private fun renderWallpaper() {")
        assertTrue(render.contains("WallpaperGround.apply(this, binding.root, ownGround = homeGroundDue(ground, now))"))
        assertTrue(render.contains("homeGroundRecheckInMs(ground, now)?.let { refreshHandler.postDelayed(groundRecheck, it + 1) }"))
        // Q-6: one recheck at a time - the pending one goes before the next is posted.
        val remove = render.indexOf("refreshHandler.removeCallbacks(groundRecheck)")
        assertTrue(render, remove in 0 until render.indexOf("refreshHandler.postDelayed(groundRecheck"))
        // Q-8: an unchanged ground keeps its drawable (no allocation, no layout request).
        val apply = ground.substringAfter("fun apply(")
        val skip = apply.indexOf("if (root.getTag(com.kidslauncher.mdm.R.id.wallpaper_ground_key) != key) {")
        assertTrue(apply, skip in 0 until apply.indexOf("root.background = when (kind) {"))
        assertTrue(apply.contains("root.setTag(com.kidslauncher.mdm.R.id.wallpaper_ground_key, key)"))
    }

    @Test
    fun `armed by a HOME intent from behind only, ended by the animation, a pause and the stop`() {
        val home = code("ui/HomeActivity.kt")
        // onNewIntent: not for a press on Home itself (Home up in front).
        assertTrue(body(home, "override fun onNewIntent(intent: Intent) {").contains("if (!contentUp && intent.hasCategory(Intent.CATEGORY_HOME)) groundArrived()"))
        assertTrue(body(home, "override fun onCreate(").contains(
            "if (savedInstanceState == null && intent?.hasCategory(Intent.CATEGORY_HOME) == true) groundArrived()",
        ))
        assertEquals("the two arrivals and the declaration", 3, Regex("groundArrived\\(\\)").findAll(home).count())
        val arrive = body(home, "private fun groundArrived() {")
        assertTrue(arrive.contains("ground = HomeGroundState(arrivedAtMs = SystemClock.elapsedRealtime(), faceLeave = ground.faceLeave)"))
        val enter = home.substringAfter("override fun onEnterAnimationComplete() {").substringBefore("\n    }\n")
        assertTrue(enter.contains("window.decorView.postOnAnimation(groundEnterDone)"))
        val enterDone = home.substringAfter("private val groundEnterDone = Runnable {").substringBefore("\n    }\n")
        assertTrue(enterDone.contains("ground = ground.copy(enterDone = true)"))
        // Q-8: a late end of an earlier transition is dropped by the next arrival.
        assertTrue(arrive.indexOf("window.decorView.removeCallbacks(groundEnterDone)") in 0 until arrive.indexOf("ground = HomeGroundState("))
        // Q-2: the pause's end is posted and skipped when Home resumed again in the same transaction (a new intent's
        // delivery to a resumed Home - a second Home press during the transition).
        val pause = body(home, "override fun onPause() {")
        assertTrue(pause.contains("if (ground.arrivedAtMs != null && !ground.leftFront) refreshHandler.post(groundLeftFront)"))
        assertFalse(pause.contains("ground = ground.copy(leftFront = true)"))
        val leftFront = home.substringAfter("private val groundLeftFront = Runnable {").substringBefore("\n    }\n")
        assertTrue(leftFront.contains("if (!resumedNow && ground.arrivedAtMs != null && !ground.leftFront) {"))
        assertTrue(leftFront.contains("ground = ground.copy(leftFront = true)"))
        val stop = body(home, "override fun onStop() {")
        assertTrue(stop.contains("ground = HomeGroundState()"))
        assertTrue(stop.contains("refreshHandler.removeCallbacks(groundRecheck)"))
        assertTrue(stop.contains("refreshHandler.removeCallbacks(groundLeftFront)"))
        assertTrue(stop.contains("window.decorView.removeCallbacks(groundEnterDone)"))
        // Back reveals Home with no intent: nothing in onStart or onResume arms it.
        assertFalse(body(home, "override fun onStart() {").contains("ground"))
        assertFalse(body(home, "override fun onResume() {").contains("groundArrived"))
    }

    /** Design 32: the copy is the content's background, and the content is never on the window at a lock-up. */
    @Test
    fun `never at a lock-up - the content's background only, the snapshot stays black`() {
        val home = code("ui/HomeActivity.kt")
        assertTrue(body(home, "private fun renderWallpaper() {").trimStart().startsWith("if (!contentShown) return"))
        assertEquals("the copy is drawn on the content's root only", 1, Regex("WallpaperGround\\.apply\\(").findAll(home).count())
        val night = body(home, "private fun showNight() {")
        assertTrue(night.contains("contentShown = false"))
        assertTrue(night.contains("setContentView(ground.root)"))
        val dark = body(home, "override fun onResume() {")
        assertTrue(dark.indexOf("if (darkUnderLock()) {") in 0 until dark.indexOf("startWork()"))
        val create = body(home, "override fun onCreate(")
        assertTrue(create.contains("setRecentsScreenshotEnabled(false)"))
        assertTrue(create.contains(".setBackgroundColor(Color.BLACK)"))
    }
}
