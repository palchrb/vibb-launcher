package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.appdisplay.AppDisplayEntry
import com.kidslauncher.mdm.appdisplay.appIconKey
import com.kidslauncher.mdm.appdisplay.appIconKeyApp
import com.kidslauncher.mdm.apps.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Launcher round 7 §4 (`docs/review/fossify-launcher-comparison.md`): icons evicted on an app's
 * update, Home pressed on Home goes to the top, one-tile grid updates, one app-list load at a time.
 * (The inherited Settings escape is `KidScreensEscapeTest`'s.)
 */
class HomeRound7Test {

    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }.readText()

    /** Code only: comments may name what the code does. */
    private fun code(path: String) = src(path)
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    // ---- (1) icons evicted on an update ---------------------------------------------------------

    @Test
    fun `an icon cache key gives back its app key, with or without the parent's choice`() {
        val key = AppInfo("org.example.app", "org.example.app.Main", 0).serialize()
        assertEquals(key, appIconKeyApp(appIconKey(key, 144, 320, null)))
        assertEquals(key, appIconKeyApp(appIconKey(key, 96, 480, AppDisplayEntry(label = null, icon = "own", color = "auto"))))
        assertEquals("not a cache key", "plain", appIconKeyApp("plain"))
    }

    @Test
    fun `the callbacks evict the package's icons before the list reloads`() {
        val app = code("Application.kt")
        for (callback in listOf("onPackageRemoved", "onPackageAdded", "onPackageChanged")) {
            val body = app.substringAfter("override fun $callback(").substringBefore("override fun ")
            val evict = body.indexOf("evictIcons(p0)")
            assertTrue(callback, evict >= 0 && evict < body.indexOf("loadApps()"))
        }
        assertTrue(app.contains("KidAvatars.evictPackage(it)"))
        val avatars = code("ui/home/KidAvatars.kt").substringAfter("fun evictPackage(")
        assertTrue(avatars.substringBefore("\n    }\n").contains("packageOf(appIconKeyApp(it)) == packageName"))
    }

    // ---- (2) Home pressed on Home -----------------------------------------------------------------

    @Test
    fun `a Home press scrolls to the top only when it is the kid's press on Home's shown content`() {
        assertTrue(homePressToTop(broughtByUs = false, contentWasUp = true, contentShown = true, darkLockUp = false, locked = false))
        assertFalse("our own start (the lock leaving, the boot, an update)", homePressToTop(true, true, true, false, false))
        assertFalse("Home wasn't up in front (a press from another app)", homePressToTop(false, false, true, false, false))
        assertFalse("the night ground", homePressToTop(false, true, false, false, false))
        assertFalse("a dark lock-up", homePressToTop(false, true, true, true, false))
        assertFalse("LOCKED", homePressToTop(false, true, true, false, true))
    }

    @Test
    fun `HomeFront marks its starts, and Home's onNewIntent reads the mark and the state`() {
        val front = code("lock/HomeFront.kt")
        assertTrue(front.substringAfter("fun intent(").substringBefore("fun bring(").contains(".putExtra(EXTRA_BROUGHT, true)"))
        val home = code("ui/HomeActivity.kt")
        val onNewIntent = home.substringAfter("override fun onNewIntent(intent: Intent) {").substringBefore("\n    }\n")
        for (needed in listOf(
            "intent.getBooleanExtra(HomeFront.EXTRA_BROUGHT, false)", "contentWasUp = contentUp", "contentShown = contentShown",
            "darkLockUp = PinLockRuntime.darkLockUp", "locked = PinLockRuntime.mode == LockMode.LOCKED",
            "if (!toTop) return", "closeOverlays()", "binding.homeGrid.smoothScrollToPosition(0)", "binding.homeContactsScroll.smoothScrollTo(0, 0)",
        )) {
            assertTrue(needed, onNewIntent.contains(needed))
        }
        // Up from a content resume until Home stops or the night ground comes.
        assertTrue(home.substringAfter("private fun resumeContent() {").substringBefore("\n    }\n").contains("contentUp = true"))
        assertTrue(home.substringAfter("override fun onStop() {").substringBefore("\n    }\n").contains("contentUp = false"))
        assertTrue(home.substringAfter("private fun showNight() {").substringBefore("\n    }\n").contains("contentUp = false"))
    }

    // ---- (3) one-tile grid updates ----------------------------------------------------------------

    @Test
    fun `a tile's id is its app key or its kind`() {
        val a = GridTile.App(GridApp("k1", "Chat", "org.chat"), 0)
        assertEquals("app:k1", gridTileId(a))
        assertEquals(gridTileId(a), gridTileId(a.copy(badge = 3)))
        assertEquals(gridTileId(GridTile.Flashlight(true)), gridTileId(GridTile.Flashlight(false)))
        val ids = listOf(GridTile.PhoneBook, a, GridTile.Flashlight(false), GridTile.Settings).map(::gridTileId)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a badge change rebinds the badge only, anything else the tile`() {
        val a = GridTile.App(GridApp("k1", "Chat", "org.chat"), 0)
        assertEquals(TileChange.SAME, tileChange(a, a, sameIcon = true))
        assertEquals(TileChange.BADGE, tileChange(a, a.copy(badge = 2), sameIcon = true))
        assertEquals("a new icon", TileChange.FULL, tileChange(a, a.copy(badge = 2), sameIcon = false))
        assertEquals("a new icon alone", TileChange.FULL, tileChange(a, a, sameIcon = false))
        assertEquals("a new name", TileChange.FULL, tileChange(a, a.copy(app = a.app.copy(label = "Prat")), sameIcon = true))
        assertEquals("the torch", TileChange.FULL, tileChange(GridTile.Flashlight(false), GridTile.Flashlight(true), sameIcon = true))
        assertEquals(TileChange.SAME, tileChange(GridTile.Settings, GridTile.Settings, sameIcon = true))
    }

    @Test
    fun `the adapter diffs with stable ids and a badge payload, and the grid has no item animator`() {
        val adapter = code("ui/home/HomeGridAdapter.kt")
        assertTrue(adapter.contains("setHasStableIds(true)"))
        assertTrue(adapter.contains("DiffUtil.calculateDiff("))
        assertTrue(adapter.contains("BADGE.takeIf { change(oldItemPosition, newItemPosition) == TileChange.BADGE }"))
        assertEquals("only for new sizes, ink or the first list", 1, Regex("notifyDataSetChanged\\(\\)").findAll(adapter).count())
        assertTrue(code("ui/HomeActivity.kt").contains("binding.homeGrid.itemAnimator = null"))
    }

    // ---- (4) one app-list load at a time ----------------------------------------------------------

    @Test
    fun `loadApps only asks a conflated channel, whose one consumer loads and posts`() {
        val app = code("Application.kt")
        val load = app.substringAfter("private fun loadApps() {").substringBefore("\n    }\n")
        assertEquals("appLoads.trySend(Unit)", load.trim())
        val loads = app.substringAfter("private val appLoads: Channel<Unit> by lazy {").substringBefore("\n    }\n")
        assertTrue(loads.contains("Channel<Unit>(Channel.CONFLATED)"))
        assertTrue(loads.contains("requests.consumeEach {"))
        assertTrue(loads.contains("apps.postValue(getApps(packageManager, applicationContext))"))
        assertEquals("the list is posted from the one consumer only", 1, Regex("apps\\.postValue\\(").findAll(app).count())
    }
}
