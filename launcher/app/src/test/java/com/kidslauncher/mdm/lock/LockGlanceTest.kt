package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.badges.GlanceConversation
import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.SessionPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Design 25 §1: the lock's stages, the music tile and the fit (pure, lock/LockGlance.kt). */
class LockGlanceTest {
    private fun session(play: SessionPlay) = MusicSession("me.vibb.music", play, "Reven og månen", "Kapittel 3: Månen", 0, 0, 1f, 0)
    private val playing = session(SessionPlay.PLAYING)
    private val paused = session(SessionPlay.PAUSED)
    private val conversation = GlanceTarget.Conversation(GlanceConversation("io.element.android.x", "!room:x.org"), "key-1", 100)

    @Test
    fun `every lock-up is the glance page, without a target - music or not`() {
        for (from in listOf(
            GlanceStageState.GLANCE,
            GlanceStageState(LockStage.KEYPAD, null),
            GlanceStageState(LockStage.KEYPAD, conversation),
            GlanceStageState(LockStage.KEYPAD, GlanceTarget.MissedCalls("+4790000002")),
        )) {
            assertEquals(GlanceStageState.GLANCE, glanceStageStep(from, GlanceStageEvent.LockUp))
        }
    }

    @Test
    fun `Lås opp and a row lead to the keypad, the arrow back drops the target`() {
        assertEquals(GlanceStageState(LockStage.KEYPAD, null), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Unlock))
        assertEquals(GlanceStageState(LockStage.KEYPAD, conversation), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Row(conversation)))
        assertEquals("no target may be taken", GlanceStageState(LockStage.KEYPAD, null), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Row(null)))
        val keypad = GlanceStageState(LockStage.KEYPAD, conversation)
        assertEquals(GlanceStageState.GLANCE, glanceStageStep(keypad, GlanceStageEvent.Back))
        assertEquals(GlanceStageState.GLANCE, glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Back))
        // "Lås opp" after a row's tap (the arrow first) is the plain keypad.
        assertEquals(GlanceStageState(LockStage.KEYPAD, null), glanceStageStep(glanceStageStep(keypad, GlanceStageEvent.Back), GlanceStageEvent.Unlock))
    }

    /** Design 59d step B (Q59d-6): as Android's face the ask comes from the glance page - the ASK stage. */
    @Test
    fun `as the face Lås opp and a row lead to ASK, every way back is GLANCE without the target`() {
        val face = true
        assertEquals(GlanceStageState(LockStage.ASK, null), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Unlock, face))
        val ask = glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Row(conversation), face)
        assertEquals(GlanceStageState(LockStage.ASK, conversation), ask)
        val missed = GlanceTarget.MissedCalls(null)
        assertEquals("a row tap in ASK replaces the target", GlanceStageState(LockStage.ASK, missed), glanceStageStep(ask, GlanceStageEvent.Row(missed), face))
        // Back stands for the pad's cancel, an error, the dead ask's recovery and STAY; LockUp for a screen-off.
        assertEquals(GlanceStageState.GLANCE, glanceStageStep(ask, GlanceStageEvent.Back, face))
        assertEquals(GlanceStageState.GLANCE, glanceStageStep(ask, GlanceStageEvent.LockUp, face))
        assertEquals("OWN_KEYPAD: our keys, the target kept", GlanceStageState(LockStage.KEYPAD, conversation), glanceStageStep(ask, GlanceStageEvent.OwnKeypad, face))
        assertEquals(GlanceStageState.GLANCE, glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.OwnKeypad, face))
    }

    @Test
    fun `own mode never enters ASK - its rows unchanged`() {
        val states = listOf(GlanceStageState.GLANCE, GlanceStageState(LockStage.KEYPAD, null), GlanceStageState(LockStage.KEYPAD, conversation))
        val events = listOf(GlanceStageEvent.LockUp, GlanceStageEvent.Unlock, GlanceStageEvent.Row(conversation), GlanceStageEvent.Row(null), GlanceStageEvent.Back, GlanceStageEvent.OwnKeypad)
        for (state in states) for (event in events) {
            val next = glanceStageStep(state, event, face = false)
            assertEquals(glanceStageStep(state, event), next)
            assertTrue(next.stage != LockStage.ASK)
        }
    }

    /** Design 59d step B (the user's rc.2 recording): the page stays under the pad, only the leave hides its content. */
    @Test
    fun `LEAVE hides the page's content, ASK shows it`() {
        val ask = glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Unlock, face = true)
        assertEquals(LockStage.ASK, ask.stage)
        assertTrue("the pad over our own lock", glancePageShown(ask.stage) && glanceContentShown(leaving = false))
        assertFalse("the leave: only the ground", glanceContentShown(leaving = true))
        assertTrue(glancePageShown(ask.stage))
    }

    /** Design 59d step J: the swipe up anywhere on the page is "Lås opp" (the page 457 dp: a third is 152.3 dp). */
    @Test
    fun `a swipe up past a third of the page, or a fling of 48 dp, asks - also before the lift`() {
        val page = 457f
        assertEquals(GlanceSwipe.UNLOCK, glanceSwipeStep(dy = 153f, vy = 0f, startedOnTappable = false, page = page, lifted = false))
        assertEquals(GlanceSwipe.UNLOCK, glanceSwipeStep(dy = 200f, vy = 100f, startedOnTappable = false, page = page, lifted = true))
        assertEquals("short of a third, still moving", GlanceSwipe.NONE, glanceSwipeStep(dy = 150f, vy = 0f, startedOnTappable = false, page = page, lifted = false))
        // The fling: 48 dp at 500 dp/s, only at the lift.
        assertEquals(GlanceSwipe.UNLOCK, glanceSwipeStep(dy = 48f, vy = 500f, startedOnTappable = false, page = page, lifted = true))
        assertEquals("a fast 47 dp is no fling", GlanceSwipe.NONE, glanceSwipeStep(dy = 47f, vy = 3000f, startedOnTappable = false, page = page, lifted = true))
        assertEquals("a slow 100 dp is no fling", GlanceSwipe.NONE, glanceSwipeStep(dy = 100f, vy = 499f, startedOnTappable = false, page = page, lifted = true))
        assertEquals("the speed counts at the lift only", GlanceSwipe.NONE, glanceSwipeStep(dy = 100f, vy = 3000f, startedOnTappable = false, page = page, lifted = false))
        // The emulator's `input swipe 240 600 240 300 150` at 240 dpi: 200 dp in 0.15 s.
        assertEquals(GlanceSwipe.UNLOCK, glanceSwipeStep(dy = 200f, vy = 1333f, startedOnTappable = false, page = page, lifted = true))
    }

    @Test
    fun `only a mostly vertical swipe upwards, never one that started on a control`() {
        val page = 457f
        assertEquals("down", GlanceSwipe.NONE, glanceSwipeStep(dy = -300f, vy = -2000f, startedOnTappable = false, page = page, lifted = true))
        assertEquals("sideways", GlanceSwipe.NONE, glanceSwipeStep(dy = 160f, vy = 2000f, startedOnTappable = false, dx = 170f, page = page, lifted = true))
        assertEquals("diagonal but mostly up", GlanceSwipe.UNLOCK, glanceSwipeStep(dy = 160f, vy = 2000f, startedOnTappable = false, dx = 100f, page = page, lifted = true))
        assertEquals("a tap", GlanceSwipe.NONE, glanceSwipeStep(dy = 0f, vy = 0f, startedOnTappable = false, page = page, lifted = true))
        for (lifted in listOf(false, true)) {
            assertEquals(GlanceSwipe.NONE, glanceSwipeStep(dy = 400f, vy = 4000f, startedOnTappable = true, page = page, lifted = lifted))
        }
    }

    @Test
    fun `the swipe never starts on a control, in a scroll that can scroll or in the bottom edge's gesture inset`() {
        // A page 685 px high (457 dp at 1.5) with a 48 px gesture inset: the edge starts at 637 px.
        assertFalse(glanceSwipeBlocked(onControl = false, scrollTakes = false, downY = 400f, height = 685f, edgeInset = 48f))
        assertFalse(glanceSwipeBlocked(onControl = false, scrollTakes = false, downY = 636f, height = 685f, edgeInset = 48f))
        assertTrue("the system's Home gesture", glanceSwipeBlocked(onControl = false, scrollTakes = false, downY = 637f, height = 685f, edgeInset = 48f))
        assertTrue("the music's play, the torch, Nødsamtale", glanceSwipeBlocked(onControl = true, scrollTakes = false, downY = 400f, height = 685f, edgeInset = 48f))
        assertTrue("the rows scroll", glanceSwipeBlocked(onControl = false, scrollTakes = true, downY = 400f, height = 685f, edgeInset = 48f))
        assertFalse("no inset (3-button navigation reports its bar as padding)", glanceSwipeBlocked(onControl = false, scrollTakes = false, downY = 684f, height = 685f, edgeInset = 0f))
    }

    /** Design 59d step L: Android's small clock's geometry by phone, measured at 240 dpi; else the centred clock. */
    @Test
    fun `the clock profile - the emulator's Pixel and the Jelly Star at 240 dpi, else none`() {
        assertEquals(EMULATOR_PIXEL_CLOCK, glanceClockProfile("sdk_gphone64_x86_64", "Google", 1.5f))
        assertEquals(EMULATOR_PIXEL_CLOCK, glanceClockProfile("sdk_gphone16k_arm64", "Google", 1.5f))
        assertEquals("Jelly Star", glanceClockProfile("Jelly Star", "Unihertz", 1.5f)?.name)
        // The Jelly Star is a placeholder: the emulator's numbers until its frame is measured (TODO in LockGlance.kt).
        assertEquals(EMULATOR_PIXEL_CLOCK.copy(name = "Jelly Star"), JELLY_STAR_CLOCK)
        assertNull("the AVD's own 320 dpi: not measured", glanceClockProfile("sdk_gphone64_x86_64", "Google", 2f))
        assertNull("a real Pixel", glanceClockProfile("Pixel 7", "Google", 1.5f))
        assertNull("another maker's emulator name", glanceClockProfile("sdk_gphone64_x86_64", "Unihertz", 1.5f))
        assertNull(glanceClockProfile(null, null, 1.5f))
        // The emulator Pixel's numbers (59d §8.11): Android's digits 92 px high on a baseline at 273 px, the first
        // digit's ink at x 49-53 ("1"-"5"); the date's at x 50 on a baseline at 329 px.
        val p = EMULATOR_PIXEL_CLOCK
        assertEquals(listOf(34f, 182f, 86f, 32f, 219f, 16f), listOf(p.clockInkStartDp, p.clockBaselineDp, p.clockDp, p.dateStartDp, p.dateBaselineDp, p.dateSp))
        assertEquals("the ink's target is within 2 px of Android's \"1\" and \"5\"", 51f, p.clockInkStartDp * 1.5f, 0.01f)
        assertEquals("Nunito's digits are 0.712 em: 92 px at 1.5", 92f, 0.712f * p.clockDp * 1.5f, 1f)
    }

    @Test
    fun `the profile's date is Android's short date, the first letter capitalised`() {
        assertEquals("EEEdMMM", glanceDateSkeleton(profile = true))
        assertEquals("the centred clock keeps the long date", "EEEEdMMMM", glanceDateSkeleton(profile = false))
        assertEquals("Fre. 23. okt.", glanceDateCapitalised("fre. 23. okt.", Locale.forLanguageTag("nb")))
        assertEquals("Fri, Oct 23", glanceDateCapitalised("Fri, Oct 23", Locale.US))
        assertEquals("", glanceDateCapitalised("", Locale.US))
    }

    @Test
    fun `the profile's margins put a baseline and a start where the profile says`() {
        // The clock's baseline at 273 px; its box would start at 84 px with its baseline 130 px into it.
        assertEquals(59, baselineMarginPx(273f, 84f, 130f))
        assertEquals("never above the content", 0, baselineMarginPx(100f, 120f, 10f))
        // The date may climb into the clock's descent (no ink there), never above its baseline.
        assertEquals(-16, baselineMarginPx(328.5f, 318f, 26.5f, floorPx = -46f))
        assertEquals(-46, baselineMarginPx(200f, 318f, 26.5f, floorPx = -46f))
        assertEquals(20, startMarginPx(43.5f, 24f))
        assertEquals("never left of the content", 0, startMarginPx(10f, 24f))
    }

    @Test
    fun `the stage is decided when the lock comes up with the screen on, not when it comes back`() {
        assertTrue("first time, screen on", lockStageDue(interactive = true, decidedAtScreenOffs = null, screenOffs = 3))
        assertFalse("started at a screen-off: decided at the screen-on", lockStageDue(interactive = false, decidedAtScreenOffs = null, screenOffs = 3))
        assertTrue("a screen-off since", lockStageDue(interactive = true, decidedAtScreenOffs = 3, screenOffs = 4))
        assertFalse("back from the emergency dialer, an alarm or a call: stage and target kept", lockStageDue(interactive = true, decidedAtScreenOffs = 4, screenOffs = 4))
    }

    @Test
    fun `the tile - whenever there is a session, playing or paused, also at a lock-up (25b-G)`() {
        assertTrue(glanceTile(playing))
        assertTrue("paused - also at a lock-up (user decision 2026-10-12)", glanceTile(paused))
        assertFalse("no session (no listener access, suspended, gone)", glanceTile(null))
        assertTrue(glanceTicks(tileShown = true, pageShown = true, session = playing))
        assertFalse(glanceTicks(tileShown = true, pageShown = true, session = paused))
        assertFalse(glanceTicks(tileShown = false, pageShown = true, session = playing))
        // Behind the keypad stage the hidden tile doesn't tick (code QA Low 7).
        assertFalse(glanceTicks(tileShown = true, pageShown = false, session = playing))
    }

    /** The fit's model at 320x569 dp (LockGlanceLayoutTest pins it from the XML; the full tile is r9 §3's compact
     * player, the compact tile without a time line; the fade is the scroll's 16 dp less a row's 8 dp bottom padding). */
    private val model = GlanceHeights(
        fixed = 116f, clockBig = 78.4f, clockSmall = 56f, date = 25.6f, tileFull = 174.8f, tileCompact = 90f, clockFull = 114f,
        rowsTop = 14f, rowsTopNoTile = 22f, row = 56f, rowGap = 8f, more = 24f, fade = 8f,
    )
    // The page's paddings: 8 dp top, 8 dp bottom; a 48 dp status bar (the Jelly Star's) and 48 dp (3-button) or
    // 24 dp (gesture) navigation: 473 and 497 dp, less the paddings.
    private val bothBars = 569f - 48f - 48f - 16f
    private val gestureBar = 569f - 48f - 24f - 16f

    @Test
    fun `the fit's candidates in order - every row counted, or one with the rest scrolling (r9 §2)`() {
        // 1: everything big.
        val big = glanceFit(1000f, model, tile = true, rowCount = 0)
        assertEquals(GlanceFit(1, true, true, GlanceTileSize.FULL, 0, 0, 0, 394.8f), big.copy(height = 394.8f))
        assertEquals(394.8f, big.height, 0.01f)
        assertEquals(1, glanceFit(bothBars, model, tile = true, rowCount = 0).candidate)
        // Music + 1 row (the user's case): the full tile with the small clock (2); the big one with gesture navigation.
        assertEquals(2, glanceFit(bothBars, model, tile = true, rowCount = 1).candidate)
        assertEquals(1, glanceFit(gestureBar, model, tile = true, rowCount = 1).candidate)
        // Music + 2 rows (the mockup): the full tile stays, one row visible, the second below it in the scroll (3).
        val two = glanceFit(bothBars, model, tile = true, rowCount = 2)
        assertEquals(listOf(3, 2, 1, 0), listOf(two.candidate, two.rows, two.visibleRows, two.more))
        assertEquals(GlanceTileSize.FULL, two.tile)
        assertEquals("with the fade below the one visible row", 450.4f, two.height, 0.01f)
        // Music + 5 rows: three bound, the "more" line counts two - it sits in the scroll, not counted on screen.
        val five = glanceFit(bothBars, model, tile = true, rowCount = 5)
        assertEquals(listOf(3, 3, 1, 2), listOf(five.candidate, five.rows, five.visibleRows, five.more))
        assertEquals(two.height, five.height, 0.01f)
        // No music, 4 rows: every row, 3 rows and "+ 1".
        val four = glanceFit(bothBars, model, tile = false, rowCount = 4)
        assertEquals(listOf(1, 3, 3, 1), listOf(four.candidate, four.rows, four.visibleRows, four.more))
        assertEquals(GlanceTileSize.NONE, four.tile)
        // At most three rows; the rest is counted.
        assertEquals(listOf(3, 7), glanceFit(10_000f, model, tile = false, rowCount = 10).let { listOf(it.rows, it.more) })
    }

    @Test
    fun `less room - one row visible, then the compact tile, then no date, then only the more line`() {
        // Music + 3 rows: 1 592.8, 2 570.4, 3 450.4, 4 485.6, 5 365.6, 6 340, 7 300 (one visible: + the 8 dp fade).
        fun at(room: Float) = glanceFit(room, model, tile = true, rowCount = 3)
        assertEquals(listOf(3, 1), at(451f).let { listOf(it.candidate, it.visibleRows) })
        assertEquals(GlanceTileSize.FULL, at(451f).tile)
        // qa-r9-code R9C-1: from 442.4 dp (the row alone) to 450.4 (the row and the fade) candidate 3 no longer fits.
        assertEquals(5, at(446f).candidate)
        assertEquals(listOf(5, 1), at(420f).let { listOf(it.candidate, it.visibleRows) })
        assertTrue(at(420f).date)
        assertEquals(GlanceTileSize.COMPACT, at(420f).tile)
        at(345f).let { assertEquals(6, it.candidate); assertFalse(it.date) }
        // Only the "more" line, counting all three (design 25's candidate 7, never a faded sliver of a row).
        assertEquals(listOf(7, 0, 0, 3), at(310f).let { listOf(it.candidate, it.rows, it.visibleRows, it.more) })
        assertEquals(300f, at(310f).height, 0.01f)
        // Nothing fits at all: the last candidate.
        assertEquals(7, at(10f).candidate)
        // A row is never folded into the "more" line to make room: every candidate but 7 binds min(n, 3).
        for (room in listOf(1000f, 451f, 420f, 345f)) assertEquals(listOf(3, 0), at(room).let { listOf(it.rows, it.more) })
        // Without a tile 4 and 5 are 2 and 3 again: the small clock, every row, then one.
        assertEquals(listOf(2, 3), glanceFit(410f, model, tile = false, rowCount = 3).let { listOf(it.candidate, it.visibleRows) })
        assertEquals(listOf(3, 1), glanceFit(300f, model, tile = false, rowCount = 3).let { listOf(it.candidate, it.visibleRows) })
        // Without rows nothing is counted below the tiles.
        val alone = glanceFit(bothBars, model, tile = false, rowCount = 0)
        assertEquals(listOf(1, 0, 0, 0), listOf(alone.candidate, alone.rows, alone.visibleRows, alone.more))
        assertEquals(116f + 78.4f + 25.6f, alone.height, 0.01f)
    }

    @Test
    fun `the one visible row stays clear of the scroll's fade wherever a one-row candidate wins (qa-r9-code R9C-1)`() {
        // The scroll gets what the candidate's parts above it leave; its bottom 16 dp fade must not reach the row's
        // content (the row less its 8 dp bottom padding). Swept across the boundaries, with and without the timer.
        var oneVisible = 0
        for (rows in listOf(2, 3, 5)) for (clock in listOf(false, true)) for (tenth in 2_500..6_000) {
            val room = tenth / 10f
            val fit = glanceFit(room, model, tile = true, rowCount = rows, clock = clock)
            if (fit.height > room || fit.visibleRows != 1) continue
            oneVisible++
            val above = fit.height - (model.rowsTop + model.row + model.fade)
            val scroll = room - above - model.rowsTop
            assertTrue("$rows rows, clock $clock, $room dp: scroll $scroll", scroll - 16f >= model.row - 8f - 0.01f)
        }
        assertTrue(oneVisible > 1000)
    }

    @Test
    fun `the rows' scroll shows with any row, the more line alone too (N1)`() {
        assertFalse(glanceFit(1000f, model, tile = true, rowCount = 0).scrollShown)
        assertTrue(glanceFit(1000f, model, tile = true, rowCount = 1).scrollShown)
        glanceFit(310f, model, tile = true, rowCount = 3).let { assertEquals(listOf(7, 0), listOf(it.candidate, it.rows)); assertTrue(it.scrollShown) }
    }

    @Test
    fun `a running timer - its seven candidates first, so it outlives the date (design 42 §1_6 as changed by r9 §2)`() {
        // Room for everything: both full tiles and every row.
        glanceFit(1000f, model, tile = true, rowCount = 2, clock = true).let {
            assertEquals(listOf(1, 2), listOf(it.candidate, it.visibleRows)); assertTrue(it.clock); assertEquals(GlanceTileSize.FULL, it.tile)
        }
        // 497 dp: the timer and the compact music tile with one row visible, the date kept (5: 479.6).
        glanceFit(gestureBar, model, tile = true, rowCount = 2, clock = true).let {
            assertEquals(listOf(5, 1), listOf(it.candidate, it.visibleRows)); assertTrue(it.clock); assertTrue(it.date)
            assertEquals(GlanceTileSize.COMPACT, it.tile)
        }
        // 473 dp: the date goes before the timer (6: 454) - design 42 kept the date in every timer candidate.
        glanceFit(bothBars, model, tile = true, rowCount = 2, clock = true).let {
            assertEquals(listOf(6, 1), listOf(it.candidate, it.visibleRows)); assertTrue(it.clock); assertFalse(it.date)
        }
        // Too little for the timer even with only the "more" line (7: 414): it goes, then the seven without it.
        glanceFit(400f, model, tile = true, rowCount = 2, clock = true).let {
            assertFalse(it.clock); assertEquals(listOf(5, 1), listOf(it.candidate, it.visibleRows)); assertTrue(it.date)
        }
        // The timer alone (no music): one row visible before it goes (3: 389.6).
        glanceFit(bothBars, model, tile = false, rowCount = 3, clock = true).let {
            assertTrue(it.clock); assertEquals(GlanceTileSize.NONE, it.tile); assertEquals(listOf(3, 3, 1), listOf(it.candidate, it.rows, it.visibleRows))
        }
        // No timer: the fit without it, unchanged.
        assertEquals(glanceFit(bothBars, model, tile = true, rowCount = 2), glanceFit(bothBars, model, tile = true, rowCount = 2, clock = false))
    }

    @Test
    fun `the timer tile's tap - the keypad with the clock app, its hint, and a time rule's usable apps (design 42)`() {
        assertEquals(GlanceStageState(LockStage.KEYPAD, GlanceTarget.ClockApp), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Row(GlanceTarget.ClockApp)))
        assertEquals(GlancePrompt.OPEN_APP, glancePrompt(true, false, false, false, GlanceTarget.ClockApp))
        assertEquals("org.fossify.clock", GlanceTarget.ClockApp.appPackage)
        val rule = com.kidslauncher.mdm.timerules.TimeLock(com.kidslauncher.mdm.server.LockReason.BEDTIME, callsAllowed = true, usableApps = setOf("org.fossify.clock"))
        assertTrue(glanceUsableUnderLock(rule, GlanceTarget.ClockApp, callsManaged = true))
        val school = com.kidslauncher.mdm.timerules.TimeLock(com.kidslauncher.mdm.server.LockReason.SCHOOL, callsAllowed = true)
        assertFalse(glanceUsableUnderLock(school, GlanceTarget.ClockApp, callsManaged = true))
        // The clock app opens by its launch intent after the code - no content intent, no send.
        val facts = app.copy(hasIntent = false, intentIsActivity = false, intentFromApp = false)
        assertEquals(GlanceOpen.LAUNCH, glanceOpenStep(facts).open)
    }

    @Test
    fun `the progress row's times - elapsed and the time left, the elapsed time alone without a duration (r9 §3)`() {
        assertEquals(GlanceTimes("1:26", "−2:38"), glanceTimes(86_000, 244_000))
        // Whole seconds both, so they add up to the duration: 1:26.9 of 4:04.5 is 1:26 and 2:38.
        assertEquals(GlanceTimes("1:26", "−2:38"), glanceTimes(86_900, 244_500))
        assertEquals("past the end (a stale duration)", GlanceTimes("4:10", "−0:00"), glanceTimes(250_000, 244_000))
        assertEquals("over an hour", GlanceTimes("1:02:13", "−4:10:05"), glanceTimes(3_733_000, 18_738_000))
        assertEquals("a live stream or an unknown length", GlanceTimes("12:00", null), glanceTimes(720_000, 0))
        assertEquals("−", GLANCE_MINUS)
    }

    @Test
    fun `the times' widest text - the duration's shape, or the position's past a stale one (r9 §3, N6)`() {
        assertEquals("0:00", glanceTimeWidest(244_000))
        assertEquals("00:00", glanceTimeWidest(1_536_000))
        assertEquals("0:00:00", glanceTimeWidest(18_738_000))
        assertEquals("00:00:00", glanceTimeWidest(36_000_000))
        assertEquals("no duration: up to 9:59:59", "0:00:00", glanceTimeWidest(0, 720_000))
        assertEquals("a position past a stale 9:59", "00:00", glanceTimeWidest(599_000, 600_000))
        assertEquals("the position inside the duration's shape", "0:00", glanceTimeWidest(244_000, 250_000))
    }

    @Test
    fun `a session callback refits only when the tile came, went or changed height (r6 §2)`() {
        assertTrue("came", glanceRefitDue(tileWasUp = false, tileUp = true, fittedPx = null, measuredPx = 300))
        assertTrue("went", glanceRefitDue(tileWasUp = true, tileUp = false, fittedPx = 300, measuredPx = 300))
        assertFalse("play/pause, the same height", glanceRefitDue(tileWasUp = true, tileUp = true, fittedPx = 300, measuredPx = 300))
        assertTrue("a two-line title", glanceRefitDue(tileWasUp = true, tileUp = true, fittedPx = 300, measuredPx = 336))
        assertTrue("no fit with a tile yet", glanceRefitDue(tileWasUp = true, tileUp = true, fittedPx = null, measuredPx = null))
        assertFalse("no tile before or after", glanceRefitDue(tileWasUp = false, tileUp = false, fittedPx = null, measuredPx = null))
    }

    @Test
    fun `callbacks within one frame render once (r6 §2)`() {
        val frame = mutableListOf<() -> Unit>()
        var renders = 0
        val coalescer = GlanceCoalescer({ frame += it }) { renders++ }
        repeat(3) { coalescer.request() }
        assertEquals(1, frame.size)
        frame.removeAt(0)()
        assertEquals(1, renders)
        // The next frame's callback is a new render.
        coalescer.request()
        frame.removeAt(0)()
        assertEquals(2, renders)
    }

    @Test
    fun `the lock's flashlight button - none without a flash or after a refusal on the lock (design 41)`() {
        assertEquals(GlanceTorchButton.HIDDEN, glanceTorchButton(hasFlash = false, refusedWhileLocked = false, lit = false))
        assertEquals("CAMERA_DISABLED while LOCKED", GlanceTorchButton.HIDDEN, glanceTorchButton(hasFlash = true, refusedWhileLocked = true, lit = true))
        assertEquals(GlanceTorchButton.OFF, glanceTorchButton(hasFlash = true, refusedWhileLocked = false, lit = false))
        assertEquals(GlanceTorchButton.LIT, glanceTorchButton(hasFlash = true, refusedWhileLocked = false, lit = true))
    }

    // ---- 3b: the hint and the open ---------------------------------------------------------------

    @Test
    fun `the keypad's prompt - the usual texts first, then the target's hint`() {
        assertEquals(GlancePrompt.UNUSABLE, glancePrompt(usable = false, waiting = true, checking = true, wrong = true, target = conversation))
        assertEquals(GlancePrompt.WAIT, glancePrompt(true, waiting = true, checking = true, wrong = true, target = conversation))
        assertEquals(GlancePrompt.CHECKING, glancePrompt(true, false, checking = true, wrong = true, target = conversation))
        assertEquals(GlancePrompt.WRONG, glancePrompt(true, false, false, wrong = true, target = conversation))
        assertEquals(GlancePrompt.OPEN_APP, glancePrompt(true, false, false, false, conversation))
        assertEquals(GlancePrompt.SEE_MISSED, glancePrompt(true, false, false, false, GlanceTarget.MissedCalls(null)))
        assertEquals(GlancePrompt.PLAIN, glancePrompt(true, false, false, false, null))
    }

    @Test
    fun `the hint's label is cut to 20 characters (QA Low 5)`() {
        assertEquals("Element X", glancePromptLabel(" Element X "))
        assertEquals("x".repeat(20), glancePromptLabel("x".repeat(20)))
        assertEquals("Meldinger fra famil…", glancePromptLabel("Meldinger fra familien"))
        assertEquals(20, glancePromptLabel("y".repeat(100)).length)
        // Code points, not chars: an emoji is never cut in half.
        val emoji = "\uD83D\uDE00".repeat(25)
        assertEquals(20, glancePromptLabel(emoji).let { it.codePointCount(0, it.length) })
    }

    @Test
    fun `a row takes its target - under a time rule only when the rule leaves it usable (QA Q1)`() {
        assertTrue(glanceTargetTaken(timeRule = false, usableUnderRule = false))
        assertTrue(glanceTargetTaken(timeRule = true, usableUnderRule = true))
        assertFalse(glanceTargetTaken(timeRule = true, usableUnderRule = false))
    }

    private val app = GlanceOpenFacts(
        from = GlanceOpenFrom.LOCK, missedCalls = false, timeRule = false, usableUnderRule = false, lockWasRoot = false,
        kioskWithoutLockTask = false, installed = true, suspended = false, hidden = false, hasIntent = true, intentIsActivity = true, intentFromApp = true,
        sendFailed = false, hasLaunchIntent = true,
    )

    @Test
    fun `the open after the code - §1_4's table`() {
        assertEquals(GlanceOpen.SEND, glanceOpenStep(app).open)
        // A time rule: the time-rule screen comes first and opens it over itself (QA Q1).
        assertEquals(GlanceOpen.HAND_TO_TIME_RULE_SCREEN, glanceOpenStep(app.copy(timeRule = true)).open)
        assertEquals(GlanceOpen.HAND_TO_TIME_RULE_SCREEN, glanceOpenStep(app.copy(timeRule = true, missedCalls = true)).open)
        // From the time-rule screen: only what the rule leaves usable, then as from the lock.
        val screen = app.copy(from = GlanceOpenFrom.TIME_RULE_SCREEN, timeRule = true)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(screen).open)
        assertEquals(GlanceOpen.SEND, glanceOpenStep(screen.copy(usableUnderRule = true)).open)
        assertEquals(GlanceOpen.MISSED_CALLS, glanceOpenStep(screen.copy(usableUnderRule = true, missedCalls = true)).open)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(screen.copy(usableUnderRule = false, missedCalls = true)).open)
        // Kiosk on and the lock was the lock-task root: nothing (the app would cover Home) - also
        // through the hand-over (code QA Low 1), and before it.
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(lockWasRoot = true)).open)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(lockWasRoot = true, missedCalls = true)).open)
        assertEquals(GlanceOpenStep(GlanceOpen.NOTHING, "the lock was the lock-task root"), glanceOpenStep(app.copy(lockWasRoot = true, timeRule = true)))
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(screen.copy(usableUnderRule = true, lockWasRoot = true)).open)
        // Kiosk on but no lock task running at the open: nothing, from either place (code QA Low 1).
        assertEquals(GlanceOpenStep(GlanceOpen.NOTHING, "lock task not running"), glanceOpenStep(app.copy(kioskWithoutLockTask = true)))
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(screen.copy(usableUnderRule = true, kioskWithoutLockTask = true)).open)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(kioskWithoutLockTask = true, missedCalls = true)).open)
        // A rule still hands over first; the time-rule screen checks lock task when it opens.
        assertEquals(GlanceOpen.HAND_TO_TIME_RULE_SCREEN, glanceOpenStep(app.copy(timeRule = true, kioskWithoutLockTask = true)).open)
        // Missed calls: the contact's sheet or the phone book (no app checks).
        assertEquals(GlanceOpen.MISSED_CALLS, glanceOpenStep(app.copy(missedCalls = true, installed = false, hasLaunchIntent = false)).open)
        // Not installed, suspended, hidden: nothing.
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(installed = false)).open)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(suspended = true)).open)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(hidden = true)).open)
        // No intent, not an activity, another app's intent (QA Low 10), or the send threw: the app.
        assertEquals(GlanceOpen.LAUNCH, glanceOpenStep(app.copy(hasIntent = false)).open)
        assertEquals(GlanceOpen.LAUNCH, glanceOpenStep(app.copy(intentIsActivity = false)).open)
        assertEquals(GlanceOpen.LAUNCH, glanceOpenStep(app.copy(intentFromApp = false)).open)
        assertEquals(GlanceOpen.LAUNCH, glanceOpenStep(app.copy(sendFailed = true)).open)
        // No launch intent either (a broadcast intent, no launcher activity): nothing (QA Medium 2).
        assertEquals(GlanceOpenStep(GlanceOpen.NOTHING, "no launch intent"), glanceOpenStep(app.copy(intentIsActivity = false, hasLaunchIntent = false)))
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(sendFailed = true, hasLaunchIntent = false)).open)
        assertEquals(GlanceOpen.SEND, glanceOpenStep(app.copy(hasLaunchIntent = false)).open)
    }

    @Test
    fun `a send is checked afterwards - our window in front means refused (code QA Low 2)`() {
        assertEquals(GlanceSendCheck.REFUSED, glanceSendCheck(ourWindowInFront = true, confirmable = true))
        assertEquals("not through Home: Home behind the lock came back", GlanceSendCheck.REFUSED, glanceSendCheck(ourWindowInFront = true, confirmable = false))
        assertEquals(GlanceSendCheck.OPENED, glanceSendCheck(ourWindowInFront = false, confirmable = true))
        assertEquals("not through Home (a call): maybe what the kid had open", GlanceSendCheck.UNKNOWN, glanceSendCheck(ourWindowInFront = false, confirmable = false))
        // A refused send falls back to the launch intent - or nothing without one.
        assertEquals(GlanceOpen.LAUNCH, glanceOpenStep(app.copy(hasIntent = false, sendFailed = true)).open)
        assertEquals(GlanceOpen.NOTHING, glanceOpenStep(app.copy(hasIntent = false, sendFailed = true, hasLaunchIntent = false)).open)
        assertEquals(1_500L, GLANCE_SEND_CHECK_MS)
    }

    @Test
    fun `what a time rule leaves usable - the tap and the hand-over (QA Q1, code QA Low 4)`() {
        val element = conversation
        val missed = GlanceTarget.MissedCalls("+4790000002")
        val free = com.kidslauncher.mdm.timerules.UNLOCKED
        assertTrue(glanceUsableUnderLock(free, element, callsManaged = false))
        assertTrue(glanceUsableUnderLock(free, missed, callsManaged = false))
        // The budget: the contacts' messaging apps (and music) stay usable, calls too.
        val budget = com.kidslauncher.mdm.timerules.TimeLock(
            com.kidslauncher.mdm.server.LockReason.SCREEN_TIME, budgetExhausted = true, callsAllowed = true,
            usableApps = setOf("io.element.android.x", "me.vibb.music"),
        )
        assertTrue(glanceUsableUnderLock(budget, element, callsManaged = true))
        assertTrue(glanceUsableUnderLock(budget, missed, callsManaged = true))
        assertFalse("calls unmanaged: no phone book on the time-rule screen", glanceUsableUnderLock(budget, missed, callsManaged = false))
        val other = GlanceTarget.Conversation(GlanceConversation("no.example.school", "k"), "k", 1)
        assertFalse(glanceUsableUnderLock(budget, other, callsManaged = true))
        // Bedtime with Element X exempt but no calls; school with nothing exempt.
        val bedtime = com.kidslauncher.mdm.timerules.TimeLock(
            com.kidslauncher.mdm.server.LockReason.BEDTIME, callsAllowed = false, usableApps = setOf("io.element.android.x"),
        )
        assertTrue(glanceUsableUnderLock(bedtime, element, callsManaged = true))
        assertFalse(glanceUsableUnderLock(bedtime, missed, callsManaged = true))
        val school = com.kidslauncher.mdm.timerules.TimeLock(com.kidslauncher.mdm.server.LockReason.SCHOOL, callsAllowed = true)
        assertFalse(glanceUsableUnderLock(school, element, callsManaged = true))
        assertTrue(glanceUsableUnderLock(school, missed, callsManaged = true))
    }

    @Test
    fun `the targets' toString is redacted`() {
        for (printed in listOf(conversation.toString(), GlanceTarget.MissedCalls("+4790000002").toString())) {
            assertFalse(printed, printed.contains("!room") || printed.contains("key-1") || printed.contains("4790000002"))
        }
    }
}

/**
 * The glance page and the keypad's music row never weaken the PIN lock (design 21 step 4, design
 * 25): the music glue only plays, pauses and skips through the session's transport controls - no
 * app, session activity, setting, notification or intent of any kind; since design 28 only a live
 * session inside the listening window, never the snapshot.
 */
class LockMediaGuardTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private val panel = file("java/com/kidslauncher/mdm/music/MusicLockPanel.kt").readText()
    private fun code(text: String) =
        text.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")

    @Test
    fun `the lock's music glue opens nothing`() {
        val code = code(panel)
        for (forbidden in listOf(
            "startActivity", "sessionActivity", "openSessionActivity", "openPlayerResuming", "PendingIntent", ".send(",
            "Intent", "Settings", "Notification", "transportControls", "dispatchMediaButton", "LaunchIntent",
        )) {
            assertFalse("MusicLockPanel must not use $forbidden", code.contains(forbidden))
        }
        val calls = Regex("MusicSessions\\.(\\w+)").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("addListener", "removeListener", "reading", "usable", "playPause", "next", "previous"), calls)
        // Design 28: the listening window only narrows what shows - its stamps are read, never written.
        val window = Regex("MusicListening\\.(\\w+)").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("addListener", "removeListener", "listening", "bootCount"), window)
    }

    @Test
    fun `the lock's tile is a live session only - never the snapshot`() {
        // Design 28 §1: a snapshot has no session, so its play would open the player over the lock.
        val code = code(panel)
        for (forbidden in listOf("MusicNowPlaying", "snapshot", "Snapshot", "openPlayer", "EXTRA_RESUME", "musicCard")) {
            assertFalse("MusicLockPanel must not use $forbidden", code.contains(forbidden))
        }
        assertTrue(code.contains("lockTileSession(reading.access, usable, reading.session, MusicListening.listening(), now)"))
    }

    @Test
    fun `the session controls are only play, pause and skip`() {
        val sessions = file("java/com/kidslauncher/mdm/music/MusicSessions.kt").readText()
        val controls = Regex("(?:transportControls\\??|\\bcontrols)\\.(\\w+)\\(").findAll(sessions).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("play", "pause", "skipToNext", "skipToPrevious"), controls)
    }

    @Test
    fun `the page's touchables are the tile controls, the rows and Nødsamtale - each wired, the swipe and the hint's action ask`() {
        fun parse(name: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file("res/layout/$name.xml")).documentElement
        fun all(e: Element): List<Element> = listOf(e) + (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().flatMap(::all)
        fun touchable(root: Element) = all(root).filter { it.tagName in setOf("Button", "ImageButton") || it.getAttributeNS(ns, "clickable") == "true" }
            .map { it.getAttributeNS(ns, "id").substringAfter("/") }.toSet()
        val root = parse("view_lock_glance")
        val touchable = touchable(root)
        assertEquals(
            setOf(
                "lock_glance_root", "lock_media_previous", "lock_media_play", "lock_media_next", "lock_media_compact_play",
                "lock_glance_emergency", "lock_glance_torch", "lock_timer_tile", "lock_timer_stop",
            ),
            touchable,
        )
        // The rows container holds only the panel's rows; a row is one touchable.
        val rows = all(root).single { it.getAttributeNS(ns, "id") == "@+id/lock_glance_rows" }
        assertEquals(0, rows.childNodes.length.let { n -> (0 until n).count { rows.childNodes.item(it) is Element } })
        assertEquals(setOf("glance_row"), touchable(parse("view_lock_glance_row")))
        // The root only blocks touches to the keypad below - no TalkBack node (qa-21-step4-code L7).
        assertEquals("no", root.getAttributeNS(ns, "importantForAccessibility"))
        fun binding(id: String) = id.split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar(Char::uppercase) }.joinToString("")
        for (id in touchable.filter { it.startsWith("lock_media_") }) {
            assertTrue("${binding(id)} is wired", panel.contains("glance.${binding(id)}.setOnClickListener"))
        }
        val glance = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        for (id in listOf("lock_glance_emergency", "lock_glance_torch", "lock_timer_tile", "lock_timer_stop")) {
            assertTrue("${binding(id)} is wired", glance.contains("glance.${binding(id)}.setOnClickListener"))
        }
        assertFalse(glance.contains("glance.root.setOnClickListener"))
        // Design 59d step J: "Lås opp" went; the hint line is no button for touch, TalkBack's node for the unlock: its
        // click action, named "Lås opp", asks as the swipe does.
        assertTrue(all(root).none { it.getAttributeNS(ns, "id") == "@+id/lock_glance_unlock" })
        val hint = all(root).single { it.getAttributeNS(ns, "id") == "@+id/lock_glance_hint" }
        assertEquals("TextView", hint.tagName)
        assertEquals("@string/lock_glance_swipe_hint", hint.getAttributeNS(ns, "text"))
        assertTrue(glance.contains("hintAction(glance.lockGlanceHint)"))
        val action = glance.substringAfter("private fun hintAction(").substringBefore("\n    /**")
        assertTrue(action, action.contains("AccessibilityNodeInfo.ACTION_CLICK, activity.getString(R.string.lock_media_unlock)"))
        assertTrue(action, action.contains("step(GlanceStageEvent.Unlock)"))
        assertFalse(action, action.contains("setOnClickListener"))
        // The swipe's only step is Unlock - the same ask as the button was; the activity feeds it every touch first.
        val swipe = glance.substringAfter("private fun swipeNow(").substringBefore("private fun swipeReset(")
        assertEquals(listOf("step(GlanceStageEvent.Unlock)"), Regex("""\bstep\([^)]*\)""").findAll(swipe).map { it.value }.toList())
        assertTrue(activity().contains("panel.swipe(ev, covered = voipRing.root.visibility == View.VISIBLE)"))
        // The emergency button is the lock's own (its confirm dialog, PinLockRuntime.emergencyFlowStarted).
        assertTrue(activity().contains("{ binding.pinEmergency.performClick() }"))
    }

    private fun activity() = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
}

/**
 * The glance page from its XML (design 25 §1.2, r9): the fit's expected candidates at 320x569 dp with a 48 dp
 * status bar and 3-button navigation (473 dp) or gesture navigation (497 dp), in the 1.4 x text model (Nunito's
 * line is about 1.36 em); the bottom block outside the top block that clips; nothing squeezed at 320, 360 and
 * 411 dp; round controls of fixed equal size.
 */
class LockGlanceLayoutTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun parse(name: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("res/layout/$name.xml")).documentElement

    private val root = parse("view_lock_glance")
    private val row = parse("view_lock_glance_row")

    private fun Element.dim(name: String): Float = getAttributeNS(ns, name).removeSuffix("dp").removeSuffix("sp").toFloatOrNull() ?: 0f
    private fun Element.kids() = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
        .filter { it.getAttributeNS(ns, "visibility") != "gone" }
    private fun Element.all(): List<Element> = listOf(this) + (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>().flatMap { it.all() }
    private fun Element.byId(id: String): Element = all().single { it.getAttributeNS(ns, "id") == "@+id/$id" }
    private fun Element.id() = getAttributeNS(ns, "id").substringAfter("/")

    /** An element's own height (its visibility aside), margins not included. */
    private fun height(e: Element): Float {
        val explicit = e.getAttributeNS(ns, "layout_height").takeIf { it.endsWith("dp") }?.removeSuffix("dp")?.toFloat()
        if (explicit != null) return explicit
        val padding = e.dim("paddingTop") + e.dim("paddingBottom") + 2 * e.dim("padding")
        val content = when (e.tagName) {
            "LinearLayout" -> if (e.getAttributeNS(ns, "orientation") == "vertical") {
                e.kids().sumOf { (it.dim("layout_marginTop") + it.dim("layout_marginBottom") + height(it)).toDouble() }.toFloat()
            } else {
                e.kids().maxOfOrNull { it.dim("layout_marginTop") + it.dim("layout_marginBottom") + height(it) } ?: 0f
            }
            "FrameLayout" -> e.kids().maxOfOrNull { height(it) } ?: 0f
            else -> e.dim("textSize") * 1.4f * (e.getAttributeNS(ns, "maxLines").toIntOrNull() ?: 1)
        }
        return maxOf(padding + content, e.dim("minHeight"))
    }

    private fun withMargins(id: String): Float = root.byId(id).let { it.dim("layout_marginTop") + it.dim("layout_marginBottom") + height(it) }

    /** The fit's parts as the panel measures them. */
    private val heights by lazy {
        val clock = root.byId("lock_glance_clock")
        GlanceHeights(
            fixed = withMargins("lock_glance_hint") + withMargins("lock_glance_bottom"),
            clockBig = clock.dim("textSize") * 1.4f,
            clockSmall = 40f * 1.4f,
            date = withMargins("lock_glance_date"),
            tileFull = withMargins("lock_media_tile"),
            tileCompact = withMargins("lock_media_compact"),
            clockFull = withMargins("lock_timer_tile"),
            rowsTop = root.byId("lock_glance_scroll").dim("layout_marginTop"),
            rowsTopNoTile = 22f,
            row = height(row),
            rowGap = 8f,
            more = root.byId("lock_glance_more").let { it.dim("layout_marginTop") + height(it) },
            fade = root.byId("lock_glance_scroll").dim("fadingEdgeLength") - row.dim("paddingBottom"),
        )
    }

    private fun available(navBar: Float) = 569f - 48f - navBar - root.dim("paddingTop") - root.dim("paddingBottom")

    @Test
    fun `the parts match the design's model`() {
        assertEquals(56f, root.byId("lock_glance_clock").dim("textSize"))
        assertEquals("rows of at least 56 dp, 8 dp vertical padding (QA Low 4)", 56f, height(row))
        assertEquals(8f, row.dim("paddingTop"))
        assertEquals(8f, row.dim("paddingBottom"))
        // r9 §3: 12 + the 48 dp cover + 10 + the times' line (12 sp) + 2 + the 56 dp play + 12, and its 18 dp margin
        // (r6's tile was 233.8); the compact tile keeps its 52 dp art row.
        assertEquals(174.8f, heights.tileFull, 0.01f)
        assertEquals(90f, heights.tileCompact, 0.01f)
        // Design 59d step J: the swipe hint (8 + 14 sp) + the bottom row 48 + its 4 dp (Lås opp 56 + 12 + 48 = 116 before).
        assertEquals(79.6f, heights.fixed, 0.01f)
        // Design 42: the timer tile - 12 dp padding, the label row, the 24 sp time, the text - and its margin.
        assertEquals(114f, heights.clockFull, 0.01f)
        assertEquals(14f, heights.rowsTop)
        // The "more" line: at least 24 dp, nothing above it (QA Low 4) - it grows with the font.
        val more = root.byId("lock_glance_more")
        assertEquals("24dp", more.getAttributeNS(ns, "minHeight"))
        assertEquals("wrap_content", more.getAttributeNS(ns, "layout_height"))
        assertEquals(0f, more.dim("layout_marginTop"))
        assertEquals(24f, heights.more, 0.01f)
        // The scroll's 16 dp fade less the row's 8 dp bottom padding (qa-r9-code R9C-1).
        assertEquals(8f, heights.fade, 0.01f)
    }

    @Test
    fun `the glance page's Nødsamtale is a 48 dp target and the fit's fixed parts didn't move (qa-34-s8-code N5)`() {
        val link = root.byId("lock_glance_emergency")
        assertTrue(link.dim("minHeight") >= 48f)
        assertEquals("@color/kid_emergency_text", link.getAttributeNS(ns, "textColor"))
        // The row's margin gave the 4 dp; since design 59d step J the swipe hint + the bottom row with their margins
        // are 79.6 dp (Lås opp + the row were 116).
        assertEquals(79.6f, withMargins("lock_glance_hint") + withMargins("lock_glance_bottom"), 0.01f)
        val hint = root.byId("lock_glance_hint")
        assertEquals(listOf(14f, 8f), listOf(hint.dim("textSize"), hint.dim("layout_marginTop")))
        assertEquals("@color/kid_core_dim", hint.getAttributeNS(ns, "textColor"))
    }

    @Test
    fun `the bottom row - Nødsamtale centred, the flashlight at its end adds nothing to the fit (design 41)`() {
        val bottom = root.byId("lock_glance_bottom")
        assertEquals("FrameLayout", bottom.tagName)
        assertEquals(listOf("lock_glance_emergency", "lock_glance_torch"), bottom.all().drop(1).map { it.id() })
        val link = root.byId("lock_glance_emergency")
        val torch = root.byId("lock_glance_torch")
        assertEquals("center", link.getAttributeNS(ns, "layout_gravity"))
        assertEquals("end|center_vertical", torch.getAttributeNS(ns, "layout_gravity"))
        // Room for the button on both sides, so the label stays centred and never runs under it.
        assertEquals(link.dim("layout_marginStart"), link.dim("layout_marginEnd"))
        assertTrue(link.dim("layout_marginEnd") >= torch.dim("layout_width"))
        // The button is no taller than Nødsamtale: the row's height (in fixed) is the same with it.
        assertTrue(torch.dim("layout_height") <= link.dim("minHeight"))
        val shown = torch.cloneNode(true) as Element
        shown.removeAttributeNS(ns, "visibility")
        assertEquals(48f, maxOf(height(link), height(shown)))
        assertEquals("gone until the panel knows there is a flash unit", "gone", torch.getAttributeNS(ns, "visibility"))
        assertEquals("@string/lock_torch", torch.getAttributeNS(ns, "contentDescription"))
    }

    @Test
    fun `the full tile's progress row - elapsed, the weighted bar, the time left, one line each of a fixed width (r9 §3)`() {
        val bar = root.byId("lock_media_progress")
        val progressRow = bar.parentNode as Element
        assertEquals("horizontal", progressRow.getAttributeNS(ns, "orientation"))
        assertEquals(listOf("lock_media_elapsed", "lock_media_progress", "lock_media_remaining"), progressRow.kids().map { it.id() })
        assertEquals("lock_media_tile", (progressRow.parentNode as Element).id())
        // The bar takes what the times leave: 4 dp, 0dp + weight 1.
        assertEquals(listOf("0dp", "1", "4dp"), listOf("layout_width", "layout_weight", "layout_height").map { bar.getAttributeNS(ns, it) })
        for (id in listOf("lock_media_elapsed", "lock_media_remaining")) {
            val time = root.byId(id)
            // A width the panel sets (an initial 40 dp, never wrap_content) and one line, never ellipsized: a pixel short
            // must never show "1:2…"; a tick swaps the text without asking for a layout.
            assertEquals(id, "40dp", time.getAttributeNS(ns, "layout_width"))
            assertEquals(id, "1", time.getAttributeNS(ns, "maxLines"))
            assertEquals(id, "", time.getAttributeNS(ns, "ellipsize"))
            assertEquals(id, "", time.getAttributeNS(ns, "singleLine"))
            assertEquals(id, 12f, time.dim("textSize"))
            assertEquals(id, "@color/kid_core_dim", time.getAttributeNS(ns, "textColor"))
            assertEquals("$id: the bar says it to TalkBack", "no", time.getAttributeNS(ns, "importantForAccessibility"))
        }
        assertEquals("start", root.byId("lock_media_elapsed").getAttributeNS(ns, "gravity"))
        assertEquals("end", root.byId("lock_media_remaining").getAttributeNS(ns, "gravity"))
        // The item and the entry one line each; r6's time line and the large art's drawable are gone.
        for (id in listOf("lock_media_item", "lock_media_entry")) assertEquals(id, "1", root.byId(id).getAttributeNS(ns, "maxLines"))
        assertTrue(root.all().none { it.id() == "lock_media_time" || it.id() == "lock_media_compact_time" })
        assertTrue(listOf("src/main/res/drawable", "app/src/main/res/drawable").none { File(it, "bg_music_art_large.xml").exists() })
        // The panel sets the widths from the shape's widest text at the view's paint, plus 1 dp, only when the px differ.
        val panel = file("java/com/kidslauncher/mdm/music/MusicLockPanel.kt").readText()
        assertTrue(panel.contains("Layout.getDesiredWidth(widest, view.paint)"))
        assertTrue(panel.contains("if (params.width != px)"))
        assertTrue(panel.contains("fixWidth(elapsed, glanceTimeWidest(s.durationMs, position))"))
        assertTrue(panel.contains("fixWidth(remaining, GLANCE_MINUS + glanceTimeWidest(s.durationMs))"))
    }

    @Test
    fun `the full tile's controls - plain glyphs in the ink, 48 56 48 dp targets 28 dp apart, an oval ripple (r9 §3)`() {
        val controls = root.byId("lock_media_play").parentNode as Element
        assertEquals(listOf("lock_media_previous", "lock_media_play", "lock_media_next"), controls.kids().map { it.id() })
        assertEquals(listOf(48f, 56f, 48f), controls.kids().map { it.dim("layout_width") })
        val play = root.byId("lock_media_play")
        assertEquals(listOf(28f, 28f), listOf(play.dim("layout_marginStart"), play.dim("layout_marginEnd")))
        for (button in controls.kids()) {
            assertEquals(button.id(), "?android:attr/selectableItemBackgroundBorderless", button.getAttributeNS(ns, "background"))
            assertEquals(button.id(), "@color/kid_ink", button.getAttributeNS(ns, "tint"))
            // ~26 dp glyphs in the 48 dp targets, ~34 dp in play's 56.
            assertEquals(button.id(), 11f, button.dim("padding"))
        }
        // The 48 dp cover and its 24 dp note glyph.
        assertEquals(48f, (root.byId("lock_media_art").parentNode as Element).dim("layout_width"))
        assertEquals(24f, root.byId("lock_media_glyph").dim("layout_width"))
    }

    @Test
    fun `the fit keys on the available height - the listener and fitNow both read it (r9 §1)`() {
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        val listener = panel.substringAfter("glance.root.addOnLayoutChangeListener").substringBefore("\n        }")
        assertTrue(listener, listener.contains("available(bottom - top) != fitFor"))
        assertFalse("the bare height no longer decides", listener.contains("bottom - top != fitFor"))
        val fitNow = panel.substringAfter("private fun fitNow()").substringBefore("val clock = ")
        assertTrue(fitNow, fitNow.contains("val room = available(root.height)"))
        assertTrue(fitNow, fitNow.contains("fitFor = room"))
        assertTrue(panel.contains("private fun available(height: Int): Int = glance.root.let { height - it.paddingTop - it.paddingBottom }"))
        // The log line comes with a new fit or a new available height.
        assertTrue(panel.contains("if (next != fit || room != loggedFor)"))
        // Every lock-up starts the rows at the top (r9 §2).
        assertTrue(panel.substringAfter("if (lockStageDue(").substringBefore("renderNow()").contains("glance.lockGlanceScroll.scrollTo(0, 0)"))
    }

    @Test
    fun `the expected fits at 473 and 497 dp (r9 §2, design 59d step J's bottom block)`() {
        fun fit(tile: Boolean, rows: Int, avail: Float) = glanceFit(avail, heights, tile, rows)
        fun total(f: GlanceFit) = f.height + root.dim("paddingTop") + root.dim("paddingBottom")
        // The scroll's height under a one-row candidate 3: what the clock, date and full tile leave, less its margin.
        fun scroll(avail: Float) = avail - (heights.fixed + heights.clockSmall + heights.date + heights.tileFull) - heights.rowsTop
        for (avail in listOf(available(48f), available(24f))) {
            // Full tile alone -> 1 (374.4; 410.8 with Lås opp).
            fit(true, 0, avail).let { assertEquals(1, it.candidate); assertEquals(374.4f, total(it), 0.5f) }
            // No music, 4 rows -> 1 with 3 rows + more (429.6).
            fit(false, 4, avail).let {
                assertEquals(listOf(1, 3, 3, 1), listOf(it.candidate, it.rows, it.visibleRows, it.more)); assertEquals(429.6f, total(it), 0.5f)
            }
            // Music + 1 row (the user's case): the big clock at both now (444.4; the small one at 473 with Lås opp).
            fit(true, 1, avail).let { assertEquals(1, it.candidate); assertEquals(444.4f, total(it), 0.5f) }
            // Music + 3 rows: the full tile, one row visible, the rest scrolls - with the 8 dp of fade below the row's
            // padding (qa-r9-code R9C-1).
            fit(true, 3, avail).let {
                assertEquals(listOf(3, 3, 1), listOf(it.candidate, it.rows, it.visibleRows))
                assertEquals(GlanceTileSize.FULL, it.tile)
                assertEquals(430f, total(it), 0.5f)
            }
            // The one counted row's content stays clear of the 16 dp fade at the scroll's bottom (107 / 131 dp).
            assertTrue(scroll(avail) - 16f >= heights.row - row.dim("paddingBottom"))
        }
        // Music + 2 rows (the mockup): one row visible at 473, both with the small clock at 497.
        fit(true, 2, available(48f)).let { assertEquals(listOf(3, 2, 1), listOf(it.candidate, it.rows, it.visibleRows)); assertEquals(430f, total(it), 0.5f) }
        fit(true, 2, available(24f)).let { assertEquals(listOf(2, 2, 2), listOf(it.candidate, it.rows, it.visibleRows)); assertEquals(486f, total(it), 0.5f) }
        assertEquals(107f, scroll(available(48f)), 0.5f)
        assertEquals(131f, scroll(available(24f)), 0.5f)
    }

    /**
     * Design 59d step L: the fit's parts with the emulator Pixel profile at 320x569 dp (a 48 dp status bar, so the page's
     * content starts at 56 dp): the clock's block runs from there to its descent - its margin puts the baseline at 182 dp -
     * and the date's from the clock's bottom to its own descent; Nunito's descent is 0.353 em. One clock size: candidates
     * 1 and 2 are the same height, so the rows and the tile give way first.
     */
    private val profileHeights by lazy {
        val p = EMULATOR_PIXEL_CLOCK
        val descent = 0.353f
        val clock = p.clockBaselineDp - (48f + root.dim("paddingTop")) + descent * p.clockDp
        val date = p.dateBaselineDp + descent * p.dateSp - (p.clockBaselineDp + descent * p.clockDp)
        heights.copy(clockBig = clock, clockSmall = clock, date = date)
    }

    @Test
    fun `the expected fits with the emulator Pixel's clock profile at 473 and 497 dp`() {
        assertEquals(156.4f, profileHeights.clockBig, 0.1f)
        assertEquals(12.3f, profileHeights.date, 0.1f)
        fun fit(tile: Boolean, rows: Int, avail: Float) = glanceFit(avail, profileHeights, tile, rows)
        for (avail in listOf(available(48f), available(24f))) {
            // The full tile alone keeps it (423 dp); music + 1 row takes the compact tile (Android's small clock is as tall).
            assertEquals(1, fit(true, 0, avail).candidate)
            assertEquals(GlanceTileSize.COMPACT, fit(true, 1, avail).tile)
            assertEquals(4, fit(true, 1, avail).candidate)
            fit(true, 3, avail).let { assertEquals(listOf(5, 3, 1), listOf(it.candidate, it.rows, it.visibleRows)) }
        }
        // No music, 4 rows: one visible at 473 (the rest scrolls), all three and the "more" line at 497 (478.3 dp).
        fit(false, 4, available(48f)).let { assertEquals(listOf(3, 3, 1, 1), listOf(it.candidate, it.rows, it.visibleRows, it.more)) }
        fit(false, 4, available(24f)).let { assertEquals(listOf(1, 3, 3, 1), listOf(it.candidate, it.rows, it.visibleRows, it.more)) }
        // Music + 2 rows: the compact tile, one row at 473, both at 497.
        assertEquals(listOf(5, 1), fit(true, 2, available(48f)).let { listOf(it.candidate, it.visibleRows) })
        assertEquals(listOf(4, 2), fit(true, 2, available(24f)).let { listOf(it.candidate, it.visibleRows) })
        // The panel measures the placed views: one size, the margins first (LockGlance.kt's helpers).
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        val fitNow = panel.substringAfter("private fun fitNow()").substringBefore("val shownRows")
        assertTrue(fitNow, fitNow.contains("clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, p.clockDp)\n            placeProfile(p, width)\n            clockBig = withMargins(clock, width)\n            clockSmall = clockBig"))
        assertTrue(panel.contains("if (profile == null) glance.lockGlanceClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fit.bigClock) CLOCK_BIG_SP else CLOCK_SMALL_SP)"))
    }

    @Test
    fun `the profile is the phone's own, in the lightest Nunito bundled, the date and alarm at its start`() {
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        assertTrue(panel.contains("glanceClockProfile(Build.MODEL, Build.MANUFACTURER, activity.resources.displayMetrics.density)"))
        // Nunito stays; no font is added: SemiBold (600) is the lightest weight in res/font.
        val fonts = listOf("src/main/res/font", "app/src/main/res/font").map(::File).first { it.exists() }.list()!!.filter { it.endsWith(".ttf") }.toSet()
        assertEquals(setOf("nunito_semibold.ttf", "nunito_bold.ttf", "nunito_extrabold.ttf"), fonts)
        val look = panel.substringAfter("private fun profileLook(").substringBefore("\n    /**")
        assertTrue(look, look.contains("R.font.nunito_semibold"))
        assertTrue(look, look.contains("date.transformationMethod = FirstCapital(locale)"))
        assertTrue(look, look.contains("listOf(clock, date, glance.lockGlanceAlarm)"))
        assertTrue(panel.contains("DateFormat.getBestDateTimePattern(locale, glanceDateSkeleton(profile != null))"))
        val place = panel.substringAfter("private fun placeProfile(").substringBefore("private fun margins(")
        assertTrue(place, place.contains("floorPx = -descent"))
        // The first digit's ink at the profile's start, whatever its side bearing - again when the text changes.
        assertTrue(place, place.contains("margins(clock, clockStartPx(p), clockTop)"))
        val start = panel.substringAfter("private fun clockStartPx(").substringBefore("\n    /**")
        assertTrue(start, start.contains("clock.paint.getTextBounds(text, 0, 1, first)"))
        assertTrue(start, start.contains("startMarginPx(p.clockInkStartDp * activity.resources.displayMetrics.density - first.left, contentStart)"))
        assertTrue(look, look.contains("clock.addTextChangedListener("))
        assertTrue(place, place.contains("margins(alarm, dateStart,"))
        // Without a profile the XML's centred clock is untouched (LockFontScaleTest pins its gravity).
        assertEquals("center_horizontal", root.byId("lock_glance_top").getAttributeNS(ns, "gravity"))
    }

    @Test
    fun `the alarm line under the date costs its height in the fit (qa-r6-b-code L7)`() {
        val alarm = withMargins("lock_glance_alarm")
        assertEquals("2 dp + 13 sp x 1.4", 20.2f, alarm, 0.01f)
        val withAlarm = heights.copy(date = heights.date + alarm)
        // Music + 2 rows at 497 dp: both rows with the small clock (470 dp), but with a morning alarm set only one is
        // counted (490.2 > 481; candidate 3 is 434.2).
        assertEquals(2, glanceFit(available(24f), heights, true, 2).candidate)
        glanceFit(available(24f), withAlarm, true, 2).let { assertEquals(listOf(3, 1), listOf(it.candidate, it.visibleRows)); assertEquals(434.2f, it.height, 0.01f) }
        // Music + 1 row keeps the big clock with the alarm at both heights (448.6).
        assertEquals(1, glanceFit(available(48f), withAlarm, true, 1).candidate)
        assertEquals(448.6f, glanceFit(available(48f), withAlarm, true, 1).height, 0.01f)
    }

    /** The width of a horizontal row's fixed children with their margins (dp); [text] for a
     * wrap_content text (the row's time: "yesterday" at 12 sp is about 60 dp). */
    private fun fixedWidth(row: Element, text: Float = 0f): Float = row.dim("paddingStart") + row.dim("paddingEnd") + 2 * row.dim("padding") +
        row.kids().sumOf {
            val w = it.dim("layout_width").takeIf { _ -> it.getAttributeNS(ns, "layout_width").endsWith("dp") }
                ?: if (it.getAttributeNS(ns, "layout_width") == "wrap_content") text else 0f
            (it.dim("layout_marginStart") + it.dim("layout_marginEnd") + w).toDouble()
        }.toFloat()

    @Test
    fun `nothing is squeezed at 320, 360 and 411 dp`() {
        val tile = root.byId("lock_media_tile")
        val controls = root.byId("lock_media_play").parentNode as Element
        assertEquals("the tile's controls", 208f, fixedWidth(controls))
        val compact = root.byId("lock_media_compact")
        assertEquals("the compact tile's fixed parts", 144f, fixedWidth(compact))
        assertEquals("a row's fixed parts with the widest time", 144f, fixedWidth(row, text = 60f))
        val bar = root.byId("lock_media_progress")
        for (screen in listOf(320f, 360f, 411f)) {
            val content = screen - root.dim("paddingStart") - root.dim("paddingEnd")
            assertTrue("$screen: tile controls", fixedWidth(controls) <= content - 2 * tile.dim("padding"))
            // A book over an hour at font scale 2.0: "0:00:00" and "−0:00:00" at 12 sp, 0.6 em a figure, still leave
            // a bar (32 dp at 320).
            val times = (7 + 8) * 12f * 2f * 0.6f
            val barMargins = bar.dim("layout_marginStart") + bar.dim("layout_marginEnd")
            assertTrue("$screen: the progress row at 2.0", content - 2 * tile.dim("padding") - barMargins - times >= 24f)
            assertTrue("$screen: compact tile", content - fixedWidth(compact) >= 120f)
            // Design 42: the timer's "8:88:88" at 24 sp and font scale 2.0 in the tile's full width.
            val timer = root.byId("lock_timer_tile")
            assertTrue("$screen: the timer's time", content - 2 * timer.dim("padding") >= "8:88:88".length * 24f * 2f * 0.6f)
            // Nødsamtale between the flashlight's room on both sides: "Emergency call" at 15 sp.
            val link = root.byId("lock_glance_emergency")
            val label = "Emergency call".length * 15f * 0.6f + link.dim("paddingStart") + link.dim("paddingEnd")
            assertTrue("$screen: Nødsamtale beside the flashlight", content - link.dim("layout_marginStart") - link.dim("layout_marginEnd") >= label)
            assertTrue("$screen: row", content - fixedWidth(row, text = 60f) >= 120f)
            // Home's card (16 dp sides on Home).
            assertTrue("$screen: Home's card", screen - 32f - fixedWidth(parse("view_home_music_card")) >= 120f)
        }
    }

    @Test
    fun `round controls have fixed equal sizes - never a weight`() {
        val round = setOf(
            "lock_media_previous", "lock_media_play", "lock_media_next", "lock_media_compact_play", "lock_glance_torch",
            "glance_row_icon", "glance_row_initial", "glance_row_badge",
        )
        val all = root.all() + row.all()
        for (id in round) {
            val e = all.single { it.id() == id }
            val w = e.getAttributeNS(ns, "layout_width")
            assertTrue("$id: $w", w.endsWith("dp"))
            assertEquals(id, w, e.getAttributeNS(ns, "layout_height"))
            assertTrue(id, e.getAttributeNS(ns, "layout_weight").isEmpty())
        }
        // The icon's frame and the art frames too (the bottom row is a row, not a round frame).
        for (frame in all.filter { it.tagName == "FrameLayout" && it.id() != "lock_glance_bottom" }) {
            assertEquals(frame.getAttributeNS(ns, "layout_width"), frame.getAttributeNS(ns, "layout_height"))
        }
    }

    @Test
    fun `the order - the top block with the rows' scroll last, then the bottom block (r9 §2)`() {
        val ids = root.all().map { it.id() }.filter { it.isNotEmpty() }
        val order = listOf(
            "lock_glance_top", "lock_glance_clock", "lock_glance_date", "lock_glance_alarm", "lock_media_tile", "lock_media_compact",
            "lock_timer_tile", "lock_glance_scroll", "lock_glance_list", "lock_glance_rows", "lock_glance_more", "lock_glance_hint",
            "lock_glance_bottom", "lock_glance_emergency", "lock_glance_torch",
        )
        assertEquals(order, ids.filter { it in order })
        // The root holds the top block and the bottom block, measured whole: nothing else, no Space.
        assertEquals(listOf("lock_glance_top", "lock_glance_hint", "lock_glance_bottom"), root.kids().map { it.id() })
        assertTrue(root.all().none { it.tagName == "Space" })
        // The top block and the rows' scroll take what is left (0dp + weight 1); the scroll is the top block's last
        // child and holds the list.
        val top = root.byId("lock_glance_top")
        val scroll = root.byId("lock_glance_scroll")
        for (e in listOf(top, scroll)) {
            assertEquals(e.id(), listOf("0dp", "1"), listOf(e.getAttributeNS(ns, "layout_height"), e.getAttributeNS(ns, "layout_weight")))
        }
        assertEquals("vertical", top.getAttributeNS(ns, "orientation"))
        assertEquals("lock_glance_scroll", top.kids().last().id())
        assertEquals("ScrollView", scroll.tagName)
        assertEquals(listOf("lock_glance_list"), scroll.kids().map { it.id() })
        // The fade only while there is more to scroll to: the contacts row's 16 dp (CONTACTS_FADE_DP).
        assertEquals("vertical", scroll.getAttributeNS(ns, "requiresFadingEdge"))
        assertEquals("16dp", scroll.getAttributeNS(ns, "fadingEdgeLength"))
        assertEquals(com.kidslauncher.mdm.ui.home.CONTACTS_FADE_DP, scroll.dim("fadingEdgeLength"))
        // A row: icon, title, subtitle, time; one line each, ellipsized.
        for (id in listOf("glance_row_title", "glance_row_subtitle")) {
            val text = row.all().single { it.id() == id }
            assertEquals("1", text.getAttributeNS(ns, "maxLines"))
            assertEquals("end", text.getAttributeNS(ns, "ellipsize"))
        }
        // The panel's margin and gone logic is the scroll's: gone only without any row (N1).
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        assertTrue(panel.contains("scroll.visibility = if (fit.scrollShown) View.VISIBLE else View.GONE"))
        assertFalse(panel.contains("lockGlanceList"))
    }
}
