package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.badges.GlanceConversation
import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.SessionPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
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

    /** §1.2's model at 320x569 dp (LockGlanceLayoutTest pins it from the XML; the tiles with r6 §2's
     * time line). */
    private val model = GlanceHeights(
        fixed = 106f, clockBig = 78.4f, clockSmall = 56f, date = 25.6f, tileFull = 233.8f, tileCompact = 107f, clockFull = 114f,
        rowsTop = 14f, rowsTopNoTile = 22f, row = 56f, rowGap = 8f, more = 24f,
    )
    private val bothBars = 569f - 48f - 48f - 24f
    private val gestureBar = 569f - 48f - 24f - 24f

    @Test
    fun `the fit's candidates in order`() {
        // 1: everything big.
        assertEquals(GlanceFit(1, true, true, GlanceTileSize.FULL, 0, 0, 443.8f), glanceFit(1000f, model, tile = true, rowCount = 0).copy(height = 443.8f))
        assertEquals(1, glanceFit(bothBars, model, tile = true, rowCount = 0).candidate)
        // Music + 1 row: the compact tile (3), with 24 dp navigation too.
        assertEquals(3, glanceFit(bothBars, model, tile = true, rowCount = 1).candidate)
        assertEquals(3, glanceFit(gestureBar, model, tile = true, rowCount = 1).candidate)
        // A taller screen keeps the full tile with a small clock (2).
        assertEquals(2, glanceFit(gestureBar + 20f, model, tile = true, rowCount = 1).let { assertFalse(it.bigClock); it.candidate })
        // Music + 2 rows: the small clock (4) - mockup A's big one (3) with 24 dp navigation.
        val two = glanceFit(bothBars, model, tile = true, rowCount = 2)
        assertEquals(listOf(4, 2, 0), listOf(two.candidate, two.rows, two.more))
        assertEquals(3, glanceFit(gestureBar, model, tile = true, rowCount = 2).candidate)
        // Music + 3 rows: 5 with one row and the "more" line.
        val three = glanceFit(bothBars, model, tile = true, rowCount = 3)
        assertEquals(listOf(5, 1, 2), listOf(three.candidate, three.rows, three.more))
        assertEquals(GlanceTileSize.COMPACT, three.tile)
        // No music, 4 rows: 3 rows and "+ 1".
        val four = glanceFit(bothBars, model, tile = false, rowCount = 4)
        assertEquals(listOf(1, 3, 1), listOf(four.candidate, four.rows, four.more))
        assertEquals(GlanceTileSize.NONE, four.tile)
        // At most three rows; the rest is counted.
        assertEquals(listOf(3, 7), glanceFit(10_000f, model, tile = false, rowCount = 10).let { listOf(it.rows, it.more) })
    }

    @Test
    fun `less room - rows go one by one, then the date, then every row`() {
        // 5 with one row: 106 + 56 + 25.6 + 107 + 14 + 56 + 24 = 388.6; 6 (no date): 363; 7 (no row): 307.
        val fifth = glanceFit(395f, model, tile = true, rowCount = 3)
        assertEquals(listOf(5, 1, 2), listOf(fifth.candidate, fifth.rows, fifth.more))
        assertTrue(fifth.date)
        val small = 310f
        val fit = glanceFit(small, model, tile = true, rowCount = 3)
        assertTrue(fit.height <= small)
        assertEquals(7, fit.candidate)
        val tiny = glanceFit(250f, model, tile = true, rowCount = 3)
        assertEquals(listOf(7, 0, 3), listOf(tiny.candidate, tiny.rows, tiny.more))
        assertFalse(tiny.date)
        val sixth = glanceFit(370f, model, tile = true, rowCount = 3)
        assertEquals(listOf(6, 1, 2), listOf(sixth.candidate, sixth.rows, sixth.more))
        assertFalse(sixth.date)
        // Nothing fits at all: the last candidate (never a scroll).
        assertEquals(7, glanceFit(10f, model, tile = true, rowCount = 3).candidate)
        // Without a tile the clock is the only step before the rows.
        val noTile = glanceFit(300f, model, tile = false, rowCount = 3)
        assertFalse(noTile.bigClock)
        assertEquals(GlanceTileSize.NONE, noTile.tile)
        // Without rows 1-2 always fit (nothing is counted).
        val alone = glanceFit(bothBars, model, tile = false, rowCount = 0)
        assertEquals(listOf(1, 0, 0), listOf(alone.candidate, alone.rows, alone.more))
    }

    @Test
    fun `a running timer - both tiles, then the music tile compact, then the rows fold, then the timer goes (design 42 §1_6)`() {
        // Room for everything: both full tiles and the rows.
        glanceFit(1000f, model, tile = true, rowCount = 2, clock = true).let {
            assertEquals(listOf(1, 2), listOf(it.candidate, it.rows)); assertTrue(it.clock); assertEquals(GlanceTileSize.FULL, it.tile)
        }
        // 600: the full music tile and the timer do not fit with two rows (691.8 / 669.4); compact does (565).
        glanceFit(600f, model, tile = true, rowCount = 2, clock = true).let {
            assertEquals(listOf(3, 2), listOf(it.candidate, it.rows)); assertTrue(it.clock); assertEquals(GlanceTileSize.COMPACT, it.tile)
        }
        // At 473 dp (both bars): the timer stays, the rows fold to the "more" line (5, 0 rows, 2 more).
        glanceFit(bothBars, model, tile = true, rowCount = 2, clock = true).let {
            assertEquals(listOf(5, 0, 2), listOf(it.candidate, it.rows, it.more)); assertTrue(it.clock)
            assertEquals(GlanceTileSize.COMPACT, it.tile)
        }
        // Too little for the timer with the compact music tile: it goes, then today's order.
        glanceFit(400f, model, tile = true, rowCount = 2, clock = true).let { assertFalse(it.clock) }
        // The timer alone (no music): the rows fold before it goes.
        glanceFit(bothBars, model, tile = false, rowCount = 3, clock = true).let {
            assertTrue(it.clock); assertEquals(GlanceTileSize.NONE, it.tile); assertEquals(1, it.rows)
        }
        // No timer: today's fit, unchanged.
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
    fun `the time line - position and length, the position alone without one (r6 §2)`() {
        assertEquals("4:48 · 25:36", glanceTimeLine(288_000, 1_536_000))
        assertEquals("1:02:03 · 2:00:00", glanceTimeLine(3_723_000, 7_200_000))
        assertEquals("a live stream or an unknown length", "12:00", glanceTimeLine(720_000, 0))
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
    fun `the page's touchables are the tile controls, the rows, Lås opp and Nødsamtale - each wired`() {
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
                "lock_glance_unlock", "lock_glance_emergency", "lock_glance_torch", "lock_timer_tile",
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
        for (id in listOf("lock_glance_unlock", "lock_glance_emergency", "lock_glance_torch", "lock_timer_tile")) {
            assertTrue("${binding(id)} is wired", glance.contains("glance.${binding(id)}.setOnClickListener"))
        }
        assertFalse(glance.contains("glance.root.setOnClickListener"))
        // The emergency button is the lock's own (its confirm dialog, PinLockRuntime.emergencyFlowStarted).
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("{ binding.pinEmergency.performClick() }"))
    }
}

/**
 * The glance page from its XML (§1.2): the fit's expected candidates at 320x569 dp with both bars
 * at 48 dp (473 dp) and with gesture navigation (497 dp), in the 1.4 x text model (Nunito's line is
 * about 1.36 em); nothing squeezed at 320, 360 and 411 dp; round controls of fixed equal size.
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

    /** §1.2's parts as the panel measures them. */
    private val heights by lazy {
        val clock = root.byId("lock_glance_clock")
        GlanceHeights(
            fixed = withMargins("lock_glance_unlock") + withMargins("lock_glance_bottom"),
            clockBig = clock.dim("textSize") * 1.4f,
            clockSmall = 40f * 1.4f,
            date = withMargins("lock_glance_date"),
            tileFull = withMargins("lock_media_tile"),
            tileCompact = withMargins("lock_media_compact"),
            clockFull = withMargins("lock_timer_tile"),
            rowsTop = root.byId("lock_glance_list").dim("layout_marginTop"),
            rowsTopNoTile = 22f,
            row = height(row),
            rowGap = 8f,
            more = root.byId("lock_glance_more").let { it.dim("layout_marginTop") + height(it) },
        )
    }

    private fun available(navBar: Float) = 569f - 48f - navBar - root.dim("paddingTop") - root.dim("paddingBottom")

    @Test
    fun `the parts match the design's model`() {
        assertEquals(56f, root.byId("lock_glance_clock").dim("textSize"))
        assertEquals("rows of at least 56 dp, 8 dp vertical padding (QA Low 4)", 56f, height(row))
        assertEquals(8f, row.dim("paddingTop"))
        assertEquals(8f, row.dim("paddingBottom"))
        // r6 §2: the full tile grows by the time line and its margin (211 + 6 + 16.8); the compact
        // tile's text column (four lines now, 69 dp) outgrows its 52 dp art row (90 + 17).
        assertEquals(233.8f, heights.tileFull, 0.01f)
        assertEquals(107f, heights.tileCompact, 0.01f)
        assertEquals(106f, heights.fixed, 0.01f)
        // Design 42: the timer tile - 12 dp padding, the label row, the 24 sp time, the text - and its margin.
        assertEquals(114f, heights.clockFull, 0.01f)
        assertEquals(14f, heights.rowsTop)
        // The "more" line: at least 24 dp, nothing above it (QA Low 4) - it grows with the font.
        val more = root.byId("lock_glance_more")
        assertEquals("24dp", more.getAttributeNS(ns, "minHeight"))
        assertEquals("wrap_content", more.getAttributeNS(ns, "layout_height"))
        assertEquals(0f, more.dim("layout_marginTop"))
        assertEquals(24f, heights.more, 0.01f)
    }

    @Test
    fun `the glance page's Nødsamtale is a 48 dp target and the fit's fixed parts didn't move (qa-34-s8-code N5)`() {
        val link = root.byId("lock_glance_emergency")
        assertTrue(link.dim("minHeight") >= 48f)
        assertEquals("@color/kid_emergency_text", link.getAttributeNS(ns, "textColor"))
        // The row's margin gave the 4 dp: Lås opp + the bottom row with their margins stay 106 dp.
        assertEquals(106f, withMargins("lock_glance_unlock") + withMargins("lock_glance_bottom"), 0.01f)
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
    fun `the time line sits under each tile's bar, one line of tabular figures (r6 §2)`() {
        for ((bar, line) in listOf("lock_media_progress" to "lock_media_time", "lock_media_compact_progress" to "lock_media_compact_time")) {
            val barView = root.byId(bar)
            val siblings = (barView.parentNode as Element).kids()
            assertEquals("$line right under $bar", line, siblings[siblings.indexOf(barView) + 1].id())
            assertEquals("vertical", (barView.parentNode as Element).getAttributeNS(ns, "orientation"))
            val text = root.byId(line)
            // A width the parent fixes and one line: a tick swaps the text without asking for a layout.
            assertEquals("match_parent", text.getAttributeNS(ns, "layout_width"))
            assertEquals("1", text.getAttributeNS(ns, "maxLines"))
            assertEquals("tnum", text.getAttributeNS(ns, "fontFeatureSettings"))
            assertEquals("@color/kid_core_dim", text.getAttributeNS(ns, "textColor"))
            assertEquals("the bar says it to TalkBack", "no", text.getAttributeNS(ns, "importantForAccessibility"))
        }
    }

    @Test
    fun `the clock is the lighter 700 weight (design 34 P7)`() {
        // It was Nunito 800 (extra-bold), the heaviest thing on the calm lock; the size and fit stay.
        val clock = root.byId("lock_glance_clock")
        assertEquals("@font/nunito_bold", clock.getAttributeNS(ns, "fontFamily"))
        assertTrue(file("res/font/nunito.xml").readText().contains(Regex("""fontWeight="700"[^/]*@font/nunito_bold""")))
        // Nothing sets the clock's typeface in code, so the XML is what shows.
        assertFalse(file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText().contains(Regex("""lockGlanceClock\.(typeface|setTypeface)""")))
    }

    @Test
    fun `the expected fits at 473 and 497 dp (§1_2)`() {
        val bars = available(48f)
        fun fit(tile: Boolean, rows: Int, avail: Float = bars) = glanceFit(avail, heights, tile, rows)
        fun total(f: GlanceFit) = f.height + root.dim("paddingTop") + root.dim("paddingBottom")
        // Re-derived with r6 §2's time line (tileFull 233.8, tileCompact 107).
        // Full tile alone -> 1 (467.8).
        fit(true, 0).let { assertEquals(1, it.candidate); assertEquals(467.8f, total(it), 0.5f) }
        // 3 rows + more, no music -> 1 (464).
        fit(false, 4).let { assertEquals(listOf(1, 3, 1), listOf(it.candidate, it.rows, it.more)); assertEquals(464f, total(it), 0.5f) }
        // Music + 1 row -> 3 (411).
        fit(true, 1).let { assertEquals(3, it.candidate); assertEquals(411f, total(it), 0.5f) }
        // Music + 2 rows -> 4, the small clock (452.6; mockup A's big clock needs 475).
        fit(true, 2).let { assertEquals(listOf(4, 2), listOf(it.candidate, it.rows)); assertEquals(452.6f, total(it), 0.5f) }
        // Music + 3 rows -> 5 with 1 row + more (412.6).
        fit(true, 3).let { assertEquals(listOf(5, 1, 2), listOf(it.candidate, it.rows, it.more)); assertEquals(412.6f, total(it), 0.5f) }
        // 24 dp navigation: music + 1 row is the compact tile too (3, 411; the full one needs 515.4),
        // music + 2 rows mockup A (3, 475).
        fit(true, 1, available(24f)).let { assertEquals(3, it.candidate); assertEquals(GlanceTileSize.COMPACT, it.tile); assertEquals(411f, total(it), 0.5f) }
        fit(true, 2, available(24f)).let { assertEquals(listOf(3, 2), listOf(it.candidate, it.rows)); assertEquals(475f, total(it), 0.5f) }
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
        assertEquals("the tile's controls", 204f, fixedWidth(controls))
        val compact = root.byId("lock_media_compact")
        assertEquals("the compact tile's fixed parts", 144f, fixedWidth(compact))
        assertEquals("a row's fixed parts with the widest time", 144f, fixedWidth(row, text = 60f))
        for (screen in listOf(320f, 360f, 411f)) {
            val content = screen - root.dim("paddingStart") - root.dim("paddingEnd")
            assertTrue("$screen: tile controls", fixedWidth(controls) <= content - 2 * tile.dim("padding"))
            // The compact tile's column - its bar, with the time line under it, never beside it.
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
    fun `the order - clock, date, tiles, rows, more, Lås opp, the bottom row last`() {
        val ids = root.all().map { it.id() }.filter { it.isNotEmpty() }
        val order = listOf(
            "lock_glance_clock", "lock_glance_date", "lock_glance_alarm", "lock_media_tile", "lock_media_compact", "lock_timer_tile",
            "lock_glance_rows", "lock_glance_more", "lock_glance_unlock", "lock_glance_bottom", "lock_glance_emergency", "lock_glance_torch",
        )
        assertEquals(order, ids.filter { it in order })
        assertEquals(listOf("lock_glance_unlock", "lock_glance_bottom"), root.kids().takeLast(2).map { it.id() })
        // A row: icon, title, subtitle, time; one line each, ellipsized.
        for (id in listOf("glance_row_title", "glance_row_subtitle")) {
            val text = row.all().single { it.id() == id }
            assertEquals("1", text.getAttributeNS(ns, "maxLines"))
            assertEquals("end", text.getAttributeNS(ns, "ellipsize"))
        }
    }
}
