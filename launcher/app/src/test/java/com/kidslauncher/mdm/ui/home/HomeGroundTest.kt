package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.ui.wallpaper.GroundKind
import com.kidslauncher.mdm.ui.wallpaper.groundKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PLAN "A black flash behind Home after the Home button" (the user's videos 2026-10-16) and "Home's wallpaper copy: a
 * one-frame zoom step at the hand-over" (2026-10-25): Home draws its own wallpaper copy whenever its content is on the
 * window - no hand-over to Android's wallpaper, so no zoom step - and never at a lock-up.
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

    @Test
    fun `the copy is always drawn while the content is shown - never on the night ground`() {
        assertTrue(homeGroundDue(contentShown = true))
        assertFalse(homeGroundDue(contentShown = false))
    }

    @Test
    fun `the copy is WallpaperGround's full ground - the placement the lock's ground and Android's bitmap share`() {
        assertEquals(GroundKind.FULL, groundKind(systemShowsOurs = true, scrimAlpha = 0, ownGround = true))
        assertEquals(GroundKind.FULL, groundKind(systemShowsOurs = true, scrimAlpha = 60, ownGround = true))
        assertEquals("without ownGround: transparent over Android's wallpaper", GroundKind.TRANSPARENT, groundKind(systemShowsOurs = true, scrimAlpha = 0))
        // qa-launcher-small-a-code Q-1: the phone book and kid Settings draw the same copy, so no switch between them
        // and Home swaps it for Android's (slightly differently scaled) render.
        assertTrue(code("calls/PhoneBookActivity.kt").contains("val ink = WallpaperGround.apply(this, binding.root, ownGround = true).ink"))
        assertTrue(code("ui/kidsettings/KidSettingsActivity.kt").contains("val wallpaper = WallpaperGround.apply(this, binding.root, ownGround = true)"))
        // ...and they are, with Home, every screen that shows Android's wallpaper.
        val showsWallpaper = listOf("calls/PhoneBookActivity.kt", "ui/kidsettings/KidSettingsActivity.kt", "ui/HomeActivity.kt")
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        val found = root.walk().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { Regex("override fun (showsSystemWallpaper|isHomeScreen)\\(").containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }.sorted().toList()
        assertEquals(showsWallpaper.sorted(), found)
        for (path in showsWallpaper) {
            assertEquals(path, 0, Regex("WallpaperGround\\.apply\\(this, binding\\.root\\)").findAll(code(path)).count())
        }
        val ground = code("ui/wallpaper/WallpaperGround.kt")
        assertTrue(ground.contains("GroundKind.FULL -> WallpaperRender.GroundDrawable(state.current.fill, state.bitmap, scrim)"))
        val home = code("ui/HomeActivity.kt")
        val render = body(home, "private fun renderWallpaper() {")
        assertTrue(render.contains("WallpaperGround.apply(this, binding.root, ownGround = homeGroundDue(contentShown))"))
        // Q-8: an unchanged ground keeps its drawable (no allocation, no layout request).
        val apply = ground.substringAfter("fun apply(")
        val skip = apply.indexOf("if (root.getTag(com.kidslauncher.mdm.R.id.wallpaper_ground_key) != key) {")
        assertTrue(apply, skip in 0 until apply.indexOf("root.background = when (kind) {"))
        assertTrue(apply.contains("root.setTag(com.kidslauncher.mdm.R.id.wallpaper_ground_key, key)"))
    }

    @Test
    fun `no hand-over - nothing arms, ends or rechecks the copy`() {
        val home = code("ui/HomeActivity.kt")
        // The 2026-10-24 window (a HOME intent from behind, the 700 ms floor, the 2.5 s cap, the pause's end) is gone.
        val gone = listOf(
            "groundArrived", "groundLeftFront", "groundEnterDone", "groundRecheck", "HomeGroundState",
            "onEnterAnimationComplete", "takeOwnGroundDue",
        )
        for (name in gone) assertFalse(name, home.contains(name))
        assertFalse(code("lock/HomeFront.kt").contains("ownGround"))
        assertEquals("the one call", 1, Regex("homeGroundDue\\(").findAll(home).count())
        // Back is unchanged: no lifecycle step touches the ground.
        for (head in listOf("override fun onStart() {", "override fun onResume() {", "override fun onPause() {", "override fun onStop() {")) {
            assertFalse(head, body(home, head).contains("ground ="))
        }
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
