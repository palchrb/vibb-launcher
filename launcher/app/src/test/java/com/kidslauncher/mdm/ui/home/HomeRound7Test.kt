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
        assertTrue(avatars.substringBefore("\n    }\n").contains(".filter { iconKeyIsPackage(it, packageName) }"))
    }

    @Test
    fun `a cache key is the package's only by its decoded app key, and others are skipped before the decode`() {
        val app = AppInfo("org.example.app", "org.example.app.Main", 0).serialize()
        assertTrue(iconKeyIsPackage(appIconKey(app, 144, 320, null), "org.example.app"))
        assertTrue(iconKeyIsPackage(appIconKey(app, 96, 480, AppDisplayEntry(label = null, icon = "own", color = "auto")), "org.example.app"))
        assertFalse("a prefix of the name", iconKeyIsPackage(appIconKey(app, 144, 320, null), "org.example"))
        assertFalse("a longer name", iconKeyIsPackage(appIconKey(app, 144, 320, null), "org.example.app2"))
        // The name in another field (the activity of another package) still decodes to that other package.
        val other = AppInfo("org.other", "org.example.app", 0).serialize()
        assertFalse(iconKeyIsPackage(appIconKey(other, 144, 320, null), "org.example.app"))
        assertFalse("not an app key", iconKeyIsPackage("\"org.example.app\"|144|320", "org.example.app"))
        // The skip comes first: a key without the quoted name is never decoded.
        val rule = code("ui/home/KidAvatars.kt").substringAfter("internal fun iconKeyIsPackage(").substringBefore("\n}\n")
        val skip = rule.indexOf("if (!cacheKey.contains(\"\\\"\$packageName\\\"\")) return false")
        assertTrue(rule, skip >= 0 && skip < rule.indexOf("deserialize("))
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
        // A new wallpaper ink rebinds every label (qa-l7-a-code 2, mutation k).
        assertTrue(adapter.contains("oldTiles.isEmpty() || metrics != this.metrics || ink != this.ink"))
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
        assertTrue(loads.contains("com.kidslauncher.mdm.ui.home.runAppLoads("))
        assertTrue(loads.contains("next = { timeoutMs -> requests.nextAppLoad(timeoutMs) }"))
        assertTrue(loads.contains("now = { android.os.SystemClock.elapsedRealtime() }"))
        assertFalse("one consumer, through the debounce only", loads.contains("consumeEach"))
        // A failing load is logged and the consumer drains on (qa-appload-debounce-code Q-3).
        val body = loads.substringAfter("heldSince = { com.kidslauncher.mdm.ui.home.AppLoadHold.heldSince },")
        assertTrue(body.trimStart().startsWith(") {\n"))
        assertTrue(body.substringAfter(") {").trimStart().startsWith("try {"))
        assertTrue(body.contains("} catch (e: Exception) {"))
        assertTrue(loads.contains("val list = getApps(packageManager, applicationContext)"))
        assertEquals("the list is posted from the one consumer only", 1, Regex("apps\\.postValue\\(").findAll(app).count())
    }

    @Test
    fun `the packages are evicted again after the load, right before the list is posted (qa-l7-a-code 1)`() {
        val app = code("Application.kt")
        assertTrue(app.substringAfter("private fun evictIcons(").substringBefore("\n    }\n").contains("iconsToEvict += packageName"))
        val loads = app.substringAfter("private val appLoads: Channel<Unit> by lazy {").substringBefore("\n    }\n")
        val snapshot = loads.indexOf("iconsToEvict.toSet()")
        val load = loads.indexOf("getApps(packageManager, applicationContext)")
        val evict = loads.indexOf("evict.forEach { com.kidslauncher.mdm.ui.home.KidAvatars.evictPackage(it) }")
        val post = loads.indexOf("apps.postValue(list)")
        assertTrue(loads, snapshot in 0 until load && load < evict && evict < post)
    }

    // ---- the provisioning storm: one load per burst (qa-home-round-code Q-4) ----------------------

    @Test
    fun `a burst loads after 300 ms of quiet, at most 2 s after its first request`() {
        assertEquals(300L, APP_LOAD_QUIET_MS)
        assertEquals(2_000L, APP_LOAD_MAX_WAIT_MS)
        assertFalse(appLoadDue(now = 1_299, firstPendingAt = 1_000, lastEventAt = 1_000))
        assertTrue(appLoadDue(now = 1_300, firstPendingAt = 1_000, lastEventAt = 1_000))
        assertFalse("a new request moves the quiet period on", appLoadDue(now = 1_500, firstPendingAt = 1_000, lastEventAt = 1_250))
        assertTrue("never later than 2 s after the first", appLoadDue(now = 3_000, firstPendingAt = 1_000, lastEventAt = 2_950))
        assertEquals(300L, appLoadWaitMs(now = 1_000, firstPendingAt = 1_000, lastEventAt = 1_000))
        assertEquals(50L, appLoadWaitMs(now = 2_950, firstPendingAt = 1_000, lastEventAt = 2_900))
        assertEquals(0L, appLoadWaitMs(now = 1_300, firstPendingAt = 1_000, lastEventAt = 1_000))
        // Not due always means a wait > 0, so the consumer never spins.
        for (now in 1_000L..3_100L step 7) {
            for (last in 1_000L..now step 13) {
                assertEquals("$now $last", appLoadDue(now, 1_000, last), appLoadWaitMs(now, 1_000, last) == 0L)
            }
        }
    }

    /** The real consumer loop on a virtual clock: requests at [times] (ms), each load taking [loadMs]; the loads' times. */
    private fun loadsFor(
        times: List<Long>,
        loadMs: Long = 0,
        closeAfter: Boolean = false,
        held: (Long) -> Long? = { null },
    ): List<Long> {
        var now = 0L
        val pending = ArrayDeque(times.sorted())
        val loads = mutableListOf<Long>()
        // The conflated channel: every request already sent comes as one.
        fun take(): Boolean {
            if (pending.isEmpty() || pending.first() > now) return false
            while (pending.isNotEmpty() && pending.first() <= now) pending.removeFirst()
            return true
        }
        kotlinx.coroutines.runBlocking {
            runAppLoads(
                next = { timeoutMs ->
                    val until = if (timeoutMs == null) Long.MAX_VALUE else now + timeoutMs
                    when {
                        take() -> AppLoadNext.REQUEST
                        pending.isNotEmpty() && pending.first() <= until -> {
                            now = pending.first()
                            take()
                            AppLoadNext.REQUEST
                        }
                        timeoutMs == null || closeAfter -> AppLoadNext.CLOSED
                        else -> {
                            now = until
                            AppLoadNext.TIMEOUT
                        }
                    }
                },
                now = { now },
                heldSince = { held(now) },
            ) {
                loads += now
                now += loadMs
            }
        }
        return loads
    }

    @Test
    fun `the process's first request loads at once - Home's cold start never waits`() {
        assertEquals(listOf(5_000L), loadsFor(listOf(5_000L)))
    }

    @Test
    fun `a burst of 50 package events is one load, after the first of the process`() {
        val storm = (0 until 50).map { 10_000L + it * 20 }
        assertEquals(listOf(0L, 10_980L + 300), loadsFor(listOf(0L) + storm))
        // The storm beginning while the first load still runs: one load more, not 50.
        assertEquals(2, loadsFor(listOf(0L) + (0 until 50).map { 100L + it * 20 }, loadMs = 400).size)
    }

    @Test
    fun `a channel closed inside a burst still loads the pending burst`() {
        assertEquals(listOf(0L, 1_000L), loadsFor(listOf(0L, 1_000L), closeAfter = true))
    }

    @Test
    fun `a single event after quiet loads once, 300 ms later`() {
        assertEquals(listOf(0L, 60_300L), loadsFor(listOf(0L, 60_000L)))
    }

    @Test
    fun `a storm that never goes quiet still loads every 2 s`() {
        val storm = (0 until 250).map { 10_000L + it * 20 } // 5 s, a request every 20 ms
        assertEquals(listOf(0L, 12_000L, 14_020L, 15_280L), loadsFor(listOf(0L) + storm))
    }

    // ---- an apply holds the loads until it ends (the jelly36 storm run: 59 loads in two minutes) -----------------

    @Test
    fun `an apply holds the loads, at most 10 s`() {
        assertEquals(10_000L, APP_LOAD_HOLD_CAP_MS)
        assertFalse("no apply", appLoadHeld(now = 5_000, heldSince = null))
        assertTrue(appLoadHeld(now = 5_000, heldSince = 5_000))
        assertTrue(appLoadHeld(now = 14_999, heldSince = 5_000))
        assertFalse("the cap", appLoadHeld(now = 15_000, heldSince = 5_000))
        // apply holds at its start and releases in a finally; the consumer reads the hold.
        val enforcer = code("server/AppEnforcer.kt")
        val apply = enforcer.substringAfter("fun apply(context: Context, policy: PolicyResponse?").substringBefore("\n    }\n")
        val hold = apply.indexOf("AppLoadHold.hold(android.os.SystemClock.elapsedRealtime())")
        val body = apply.indexOf("applyHeld(context, policy, fromSync, parentChoice)")
        val release = apply.indexOf("} finally {\n            com.kidslauncher.mdm.ui.home.AppLoadHold.release()")
        assertTrue(apply, hold >= 0 && hold < apply.indexOf("try {") && apply.indexOf("try {") < body && body < release)
        assertEquals("the pass itself is applyHeld", 1, Regex("private fun applyHeld\\(").findAll(enforcer).count())
        val loads = code("Application.kt").substringAfter("private val appLoads: Channel<Unit> by lazy {").substringBefore("\n    }\n")
        assertTrue(loads.contains("heldSince = { com.kidslauncher.mdm.ui.home.AppLoadHold.heldSince },"))
    }

    @Test
    fun `the hold nests and ends only at the outer release`() {
        AppLoadHold.hold(1_000)
        AppLoadHold.hold(2_000)
        assertEquals(1_000L, AppLoadHold.heldSince)
        AppLoadHold.release()
        assertEquals(1_000L, AppLoadHold.heldSince)
        AppLoadHold.release()
        assertEquals(null, AppLoadHold.heldSince)
        AppLoadHold.release()
        assertEquals("an extra release is harmless", null, AppLoadHold.heldSince)
    }

    @Test
    fun `a 5 s apply with 50 callbacks is one load, at its release`() {
        val storm = (0 until 50).map { 10_000L + it * 100 } // 10.0 .. 14.9 s, more than 300 ms apart would not matter
        val apply: (Long) -> Long? = { t -> if (t in 10_000L until 15_000L) 10_000L else null }
        assertEquals(listOf(0L, 15_000L), loadsFor(listOf(0L) + storm, held = apply))
        // Callbacks 400 ms apart - each its own burst without the hold - still one load.
        val sparse = (0 until 12).map { 10_000L + it * 400 }
        assertEquals(listOf(0L, 15_000L), loadsFor(listOf(0L) + sparse, held = apply))
    }

    @Test
    fun `a hung apply holds the loads 10 s, then one load`() {
        val storm = (0 until 50).map { 10_000L + it * 100 }
        assertEquals(listOf(0L, 20_000L), loadsFor(listOf(0L) + storm, held = { 10_000L }))
    }

    @Test
    fun `no apply keeps the debounce, and the process's first request stays at once during an apply`() {
        val sparse = (0 until 12).map { 10_000L + it * 400 }
        assertEquals(listOf(0L) + sparse.map { it + 300 }, loadsFor(listOf(0L) + sparse))
        assertEquals(listOf(10_000L), loadsFor(listOf(10_000L), held = { 10_000L }))
    }

    @Test
    fun `the consumer's wait for the next request, on a real channel`() = kotlinx.coroutines.runBlocking {
        val channel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        assertEquals(AppLoadNext.TIMEOUT, channel.nextAppLoad(10))
        channel.trySend(Unit)
        channel.trySend(Unit)
        assertEquals(AppLoadNext.REQUEST, channel.nextAppLoad(10))
        assertEquals("conflated: two sends are one request", AppLoadNext.TIMEOUT, channel.nextAppLoad(10))
        channel.trySend(Unit)
        assertEquals(AppLoadNext.REQUEST, channel.nextAppLoad(null))
        channel.close()
        assertEquals(AppLoadNext.CLOSED, channel.nextAppLoad(null))
    }
}
