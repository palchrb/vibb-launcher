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
            assertTrue(callback, evict >= 0 && evict < body.indexOf("loadApps(listOfNotNull(p0))"))
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
        val load = app.substringAfter("private fun loadApps(packages: Collection<String>? = null) {").substringBefore("\n    }\n")
        assertEquals(
            "com.kidslauncher.mdm.ui.home.AppLoadHold.noteRequest(packages, android.os.SystemClock.elapsedRealtime())\n" +
                "        appLoads.trySend(Unit)",
            load.trim(),
        )
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

    /** The real consumer loop on a virtual clock: requests at [times] (ms), each load taking [loadMs]; the loads' times.
     * The requests at the times in [trail] are a pass's trail, every other one a normal request. */
    private fun loadsFor(
        times: List<Long>,
        loadMs: Long = 0,
        closeAfter: Boolean = false,
        held: (Long) -> Long? = { null },
        trail: Set<Long> = emptySet(),
    ): List<Long> {
        var now = 0L
        val pending = ArrayDeque(times.sorted())
        val loads = mutableListOf<Long>()
        var normal = false
        // The conflated channel: every request already sent comes as one.
        fun take(): Boolean {
            if (pending.isEmpty() || pending.first() > now) return false
            while (pending.isNotEmpty() && pending.first() <= now) {
                if (pending.removeFirst() !in trail) normal = true
            }
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
                takeNormal = { normal.also { normal = false } },
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
    fun `an apply holds the loads, at most 10 s from its start or the burst's`() {
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
        val release = apply.indexOf("} finally {\n            com.kidslauncher.mdm.ui.home.AppLoadHold.release(android.os.SystemClock.elapsedRealtime())")
        assertTrue(apply, hold >= 0 && hold < apply.indexOf("try {") && apply.indexOf("try {") < body && body < release)
        assertEquals("the pass itself is applyHeld", 1, Regex("private fun applyHeld\\(").findAll(enforcer).count())
        val loads = code("Application.kt").substringAfter("private val appLoads: Channel<Unit> by lazy {").substringBefore("\n    }\n")
        assertTrue(loads.contains("heldSince = { com.kidslauncher.mdm.ui.home.AppLoadHold.heldSince },"))
    }

    @Test
    fun `the hold's wait pairs with appLoadHeld - held always waits, never past a cap`() {
        assertEquals(100L, APP_LOAD_HOLD_POLL_MS)
        assertEquals(0L, appLoadHoldWaitMs(now = 5_000, heldSince = null))
        assertEquals(100L, appLoadHoldWaitMs(now = 5_000, heldSince = 5_000))
        assertEquals("the apply's cap", 40L, appLoadHoldWaitMs(now = 14_960, heldSince = 5_000, burstSince = 14_000))
        assertEquals("the burst's cap", 30L, appLoadHoldWaitMs(now = 14_970, heldSince = 14_000, burstSince = 5_000))
        assertFalse("a burst waits at most 10 s whatever apply runs", appLoadHeld(now = 15_000, heldSince = 14_000, burstSince = 5_000))
        for (now in 5_000L..16_000L step 37) {
            for (since in 5_000L..now step 211) {
                for (burst in 5_000L..now step 307) {
                    assertEquals("$now $since $burst", appLoadHeld(now, since, burst), appLoadHoldWaitMs(now, since, burst) > 0)
                }
            }
        }
    }

    @Test
    fun `the hold nests and ends only at the outer release`() {
        try {
            AppLoadHold.hold(1_000)
            AppLoadHold.hold(2_000)
            assertEquals(1_000L, AppLoadHold.heldSince)
            AppLoadHold.release(3_000)
            assertEquals(1_000L, AppLoadHold.heldSince)
            AppLoadHold.release(3_000)
            assertEquals(null, AppLoadHold.heldSince)
            AppLoadHold.release(3_000)
            assertEquals("an extra release is harmless", null, AppLoadHold.heldSince)
        } finally {
            // The real, process-wide object: never leave it held for the rest of the test JVM (Q-5).
            AppLoadHold.resetForTest()
        }
    }

    // ---- the pass's trail: 3 s quiet / 30 s cap for callbacks about the packages it touched -------------------------

    @Test
    fun `a callback trails a pass only when the pass touched every package, within 2 min of the release`() {
        assertEquals(3_000L, APP_LOAD_TRAIL_QUIET_MS)
        assertEquals(30_000L, APP_LOAD_TRAIL_MAX_WAIT_MS)
        assertEquals(120_000L, APP_LOAD_TRAIL_WINDOW_MS)
        val trail = AppLoadTrail(setOf("a", "b"), releasedAt = 10_000)
        assertTrue(appLoadTrails(listOf("a"), 10_000, emptySet(), trail))
        assertTrue(appLoadTrails(listOf("a", "b"), 129_999, emptySet(), trail))
        assertFalse("the window ended", appLoadTrails(listOf("a"), 130_000, emptySet(), trail))
        assertFalse("one package the pass didn't touch", appLoadTrails(listOf("a", "c"), 11_000, emptySet(), trail))
        assertFalse("no package", appLoadTrails(emptyList(), 11_000, emptySet(), trail))
        assertFalse("no pass", appLoadTrails(listOf("a"), 11_000, emptySet(), null))
        assertTrue("the running pass's", appLoadTrails(listOf("c"), 11_000, setOf("c"), trail))
        // A chain of passes keeps the earlier one's packages while its window runs, counted from the last release.
        val chained = appLoadTrailAfterRelease(setOf("c"), 20_000, trail)
        assertEquals(AppLoadTrail(setOf("a", "b", "c"), 20_000), chained)
        assertEquals(AppLoadTrail(setOf("c"), 200_000), appLoadTrailAfterRelease(setOf("c"), 200_000, trail))
        assertEquals("nothing touched, nothing kept", null, appLoadTrailAfterRelease(emptySet(), 200_000, trail))
        // Q-3: a pass that touched nothing keeps the earlier trail as it was - its release time too, so a steady
        // stream of such passes never keeps a trail alive past its own 2 minutes.
        assertEquals(trail, appLoadTrailAfterRelease(emptySet(), 100_000, trail))
        var kept: AppLoadTrail? = trail
        for (t in 20_000L..200_000L step 20_000) kept = appLoadTrailAfterRelease(emptySet(), t, kept)
        assertEquals("expired by its own window", null, kept)
        // The burst's rule: due exactly when its wait is 0.
        for (now in 1_000L..40_000L step 97) {
            for (last in 1_000L..now step 433) {
                for (normal in listOf(null, 1_000L, last)) {
                    assertEquals("$now $last $normal", appLoadBurstDue(now, 1_000, last, normal), appLoadBurstWaitMs(now, 1_000, last, normal) == 0L)
                }
            }
        }
    }

    @Test
    fun `the hold records what the pass touched, and the requests are classed by it`() {
        try {
            AppLoadHold.resetForTest()
            AppLoadHold.touch("outside")
            assertFalse("no pass, no trail", AppLoadHold.trails(listOf("outside"), 1_000))
            AppLoadHold.hold(1_000)
            AppLoadHold.touch("x")
            assertTrue(AppLoadHold.trails(listOf("x"), 1_500))
            AppLoadHold.release(2_000)
            assertFalse(AppLoadHold.takeNormal())
            assertTrue(AppLoadHold.noteRequest(listOf("x"), 5_000))
            assertFalse("the trail alone", AppLoadHold.takeNormal())
            assertFalse(AppLoadHold.noteRequest(listOf("y"), 5_000))
            assertTrue(AppLoadHold.takeNormal())
            assertFalse("taken once", AppLoadHold.takeNormal())
            assertFalse("not about packages: normal", AppLoadHold.noteRequest(null, 5_000))
            assertTrue(AppLoadHold.takeNormal())
            assertFalse("after the window", AppLoadHold.noteRequest(listOf("x"), 122_000))
        } finally {
            AppLoadHold.resetForTest()
        }
        // The pass touches every package it changes, inside the hold.
        val enforcer = code("server/AppEnforcer.kt")
        val loop = enforcer.substringAfter("if (shouldBeSuspended == currentlySuspended && shouldBeHidden == currentlyHidden) continue")
        assertTrue(loop.trimStart().startsWith("com.kidslauncher.mdm.ui.home.AppLoadHold.touch(packageName)"))
        // Every LauncherApps callback names its packages; the consumer takes the class.
        val app = code("Application.kt")
        assertEquals(3, Regex("loadApps\\(listOfNotNull\\(p0\\)\\)").findAll(app).count())
        assertEquals(2, Regex("loadApps\\(packageNames\\?\\.toList\\(\\)\\.orEmpty\\(\\)\\)").findAll(app).count())
        assertTrue(app.contains("takeNormal = { com.kidslauncher.mdm.ui.home.AppLoadHold.takeNormal() },"))
    }

    @Test
    fun `the stragglers after a pass fold into one or two loads, not one each`() {
        // The jelly36 run: a pass 10-17 s, then the system's late callbacks 1-3 s apart until 77 s (60 s after it).
        val pass: (Long) -> Long? = { t -> if (t in 10_000L until 17_000L) 10_000L else null }
        val stragglers = (0 until 30).map { 18_000L + it * 2_000 } // 18 .. 76 s
        val loads = loadsFor(listOf(0L) + stragglers, held = pass, trail = stragglers.toSet())
        assertEquals("the first, the 30 s cap, then 3 s after the last", listOf(0L, 48_000L, 79_000L), loads)
        // The same callbacks as normal requests: one load each.
        assertEquals(31, loadsFor(listOf(0L) + stragglers, held = pass).size)
    }

    /** qa-launcher-small-a-code Q-2: the pass's own result shows 300 ms after it, as before the trail; only the
     * stragglers that come later wait 3 s quiet / 30 s. */
    @Test
    fun `a pass's own load is 300 ms after its release, the later stragglers fold`() {
        val pass: (Long) -> Long? = { t -> if (t in 10_000L until 15_000L) 10_000L else null }
        val during = (0 until 50).map { 10_000L + it * 100 } // the pass's callbacks, all its trail
        val after = (0 until 15).map { 17_000L + it * 2_000 } // 17 .. 45 s, the system's late broadcasts
        val loads = loadsFor(listOf(0L) + during + after, held = pass, trail = (during + after).toSet())
        assertEquals("the pass's own load at release + 300 ms, then the stragglers' one (the 30 s cap)", listOf(0L, 15_300L, 47_000L), loads)
    }

    @Test
    fun `another request in a trail burst brings back 300 ms from its arrival`() {
        val stragglers = (0 until 10).map { 20_000L + it * 1_000 } // 20 .. 29 s, 1 s apart
        val install = 22_500L
        val loads = loadsFor(listOf(0L) + stragglers + install, trail = stragglers.toSet())
        assertEquals("the install's 300 ms, then the trail's 3 s quiet", listOf(0L, 22_800L, 32_000L), loads)
        // A quiet trail alone: 3 s after its last callback.
        assertEquals(listOf(0L, 23_000L), loadsFor(listOf(0L, 20_000L), trail = setOf(20_000L)))
    }

    private val fiveSecondApply: (Long) -> Long? = { t -> if (t in 10_000L until 15_000L) 10_000L else null }

    @Test
    fun `a 5 s apply with 50 callbacks is one load, 300 ms after its release`() {
        val storm = (0 until 50).map { 10_000L + it * 100 } // 10.0 .. 14.9 s
        assertEquals(listOf(0L, 15_300L), loadsFor(listOf(0L) + storm, held = fiveSecondApply))
        // Callbacks 400 ms apart - each its own burst without the hold - still one load.
        val sparse = (0 until 12).map { 10_000L + it * 400 }
        assertEquals(listOf(0L, 15_300L), loadsFor(listOf(0L) + sparse, held = fiveSecondApply))
    }

    @Test
    fun `the callbacks that trail the release join the same load (Q-2)`() {
        val storm = (0 until 50).map { 10_000L + it * 100 } + listOf(15_150L, 15_250L)
        assertEquals(listOf(0L, 15_550L), loadsFor(listOf(0L) + storm, held = fiveSecondApply))
    }

    @Test
    fun `a short apply's end is the request the burst loads from (wasHeld, Q-3)`() {
        // A 500 ms apply, one callback 50 ms before its end: the release (seen at the next poll, 10 550) counts as a
        // request, 300 ms later the load - not 300 ms after the callback.
        assertEquals(listOf(0L, 10_850L), loadsFor(listOf(0L, 10_450L), held = { t -> if (t in 10_000L until 10_500L) 10_000L else null }))
    }

    @Test
    fun `a hung apply holds the loads 10 s, then one load`() {
        val storm = (0 until 50).map { 10_000L + it * 100 }
        assertEquals(listOf(0L, 20_300L), loadsFor(listOf(0L) + storm, held = { 10_000L }))
    }

    @Test
    fun `a chain of applies holds a burst 10 s at most, never the whole chain (Q-1)`() {
        // Three 8 s applies back to back (10-18, 18-26, 26-34 s), a callback every 500 ms through them.
        val chain: (Long) -> Long? = { t ->
            when {
                t < 10_000L -> null
                t < 18_000L -> 10_000L
                t < 26_000L -> 18_000L
                t < 34_000L -> 26_000L
                else -> null
            }
        }
        val callbacks = (0 until 48).map { 10_000L + it * 500 } // 10.0 .. 33.5 s
        assertEquals(listOf(0L, 20_300L, 30_800L, 34_300L), loadsFor(listOf(0L) + callbacks, held = chain))
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
