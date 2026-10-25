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

/** Design 25 §1: the lock's stages, the music tile and the layout (pure, lock/LockGlance.kt; r11). */
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
    fun `the swipe never starts on a control or in a scroll that can scroll - the bottom edge is the page's (§8_13)`() {
        assertFalse(glanceSwipeBlocked(onControl = false, scrollTakes = false))
        assertTrue("the music's play, the torch, Nødsamtale", glanceSwipeBlocked(onControl = true, scrollTakes = false))
        assertTrue("the rows scroll", glanceSwipeBlocked(onControl = false, scrollTakes = true))
        // The handle and the gesture inset are no longer blocked: the swipe from the pill asks; the system taking the
        // gesture sends ACTION_CANCEL, which ends the touch with nothing asked (the glue's pin below).
        val panel = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/GlancePanel.kt") }.first { it.exists() }.readText()
        val swipe = panel.substringAfter("private fun swipeNow(").substringBefore("private fun swipeReset(")
        assertFalse(swipe, swipe.contains("Insets"))
        assertTrue(swipe, swipe.contains("if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {"))
        assertTrue(swipe.substringAfter("action == MotionEvent.ACTION_CANCEL) {").substringBefore("}").contains("return SwipeTouch.PASS"))
    }

    /** The user (2026-10-24): back on the glance from our keypad or Android's pad, the page starts at the top. */
    @Test
    fun `a step back onto the glance page starts it at the top - staying on it doesn't`() {
        assertTrue(glanceBackToTop(LockStage.KEYPAD, LockStage.GLANCE))
        assertTrue("the pad cancelled", glanceBackToTop(LockStage.ASK, LockStage.GLANCE))
        assertFalse("no move", glanceBackToTop(LockStage.GLANCE, LockStage.GLANCE))
        assertFalse("off the page", glanceBackToTop(LockStage.GLANCE, LockStage.KEYPAD))
        assertFalse(glanceBackToTop(LockStage.GLANCE, LockStage.ASK))
        assertFalse(glanceBackToTop(LockStage.ASK, LockStage.KEYPAD))
        // Every way back is one of those: the arrow and the pad's cancel (Back) from the keypad or ASK.
        for (from in listOf(GlanceStageState(LockStage.KEYPAD, null), GlanceStageState(LockStage.ASK, conversation))) {
            assertTrue(glanceBackToTop(from.stage, glanceStageStep(from, GlanceStageEvent.Back, face = true).stage))
        }
        // The "more" line's tap is a row without a target: the keypad, the plain prompt.
        assertEquals(GlanceStageState(LockStage.KEYPAD, null), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Row(null)))
        assertEquals(GlancePrompt.PLAIN, glancePrompt(true, false, false, false, null, androidFace = false))
    }

    /** Design r11 §3.2: the first movement past the touch slop decides the axis; a tie is vertical. */
    @Test
    fun `the touch's axis - none within the slop, then the larger movement, a tie vertical (r11 §3_2)`() {
        val slop = 8f
        assertEquals(GlanceAxis.NONE, glanceTouchAxis(0f, 0f, slop))
        assertEquals("within the slop either way", GlanceAxis.NONE, glanceTouchAxis(-8f, 8f, slop))
        assertEquals(GlanceAxis.HORIZONTAL, glanceTouchAxis(9f, 2f, slop))
        assertEquals("left counts the same", GlanceAxis.HORIZONTAL, glanceTouchAxis(-12f, -11f, slop))
        assertEquals(GlanceAxis.VERTICAL, glanceTouchAxis(1f, 9f, slop))
        assertEquals("down too", GlanceAxis.VERTICAL, glanceTouchAxis(3f, -20f, slop))
        assertEquals("a tie is vertical", GlanceAxis.VERTICAL, glanceTouchAxis(10f, -10f, slop))
        assertEquals("a diagonal start goes the way of its larger part", GlanceAxis.HORIZONTAL, glanceTouchAxis(9f, 8.9f, slop))
    }

    /** Design r11 §3.1: past 40 % of the row's width, or a fling of 500 dp/s in the drag's direction after 16 dp. */
    @Test
    fun `the dismiss - 40 percent or a fling, both directions, never before the lift (r11 §3_1)`() {
        val width = 288f
        assertEquals("not lifted: it follows", GlanceDismiss.FOLLOW, glanceDismissStep(200f, 3000f, width, lifted = false))
        assertEquals("39 %", GlanceDismiss.BACK, glanceDismissStep(0.39f * width, 0f, width, lifted = true))
        assertEquals("40 %", GlanceDismiss.DISMISS, glanceDismissStep(0.4f * width, 0f, width, lifted = true))
        assertEquals("40 % to the right", GlanceDismiss.DISMISS, glanceDismissStep(-0.4f * width, 0f, width, lifted = true))
        assertEquals("a fling left", GlanceDismiss.DISMISS, glanceDismissStep(-20f, -500f, width, lifted = true))
        assertEquals("a fling right", GlanceDismiss.DISMISS, glanceDismissStep(16f, 800f, width, lifted = true))
        assertEquals("a fling under 16 dp", GlanceDismiss.BACK, glanceDismissStep(15f, 3000f, width, lifted = true))
        assertEquals("a fling against the drag", GlanceDismiss.BACK, glanceDismissStep(50f, -3000f, width, lifted = true))
        assertEquals("too slow", GlanceDismiss.BACK, glanceDismissStep(50f, 499f, width, lifted = true))
        assertEquals("no width: only a fling", GlanceDismiss.BACK, glanceDismissStep(50f, 0f, 0f, lifted = true))
        assertEquals(0.4f, GLANCE_DISMISS_SHARE)
        assertEquals(500f, GLANCE_DISMISS_FLING_DPS)
        assertEquals(16f, GLANCE_DISMISS_FLING_DP)
    }

    @Test
    fun `the dragged row fades with the distance (r11 §3_1)`() {
        assertEquals(1f, glanceDismissAlpha(0f, 288f), 0.001f)
        assertEquals(0.5f, glanceDismissAlpha(144f, 288f), 0.001f)
        assertEquals(0.5f, glanceDismissAlpha(-144f, 288f), 0.001f)
        assertEquals("clamped", 0f, glanceDismissAlpha(400f, 288f), 0.001f)
        assertEquals("no width", 1f, glanceDismissAlpha(50f, 0f), 0.001f)
    }

    /** Design r11 §3.3: a dismissed missed-call row stays out until the notifier's notice changes. */
    @Test
    fun `the dismissed missed-call rows stay out, every other row is kept (r11 §3_3)`() {
        val message = com.kidslauncher.mdm.badges.GlanceMessageRow(
            GlanceConversation("io.element.android.x", "!room:x.org"), "key-1", com.kidslauncher.mdm.badges.GlanceLevel.SENDER,
            "Anna", null, 1, 300, 300,
        )
        val pappa = com.kidslauncher.mdm.badges.GlanceMissedRow("+4790000002", "Pappa", 1, 200)
        val mamma = com.kidslauncher.mdm.badges.GlanceMissedRow("+4790000001", "Mamma", 2, 100)
        val plain = com.kidslauncher.mdm.badges.GlanceMissedRow(null, null, 3, 50)
        val rows = listOf(message, pappa, mamma, plain)
        assertEquals(rows, withoutDismissed(rows, emptySet(), plain = false))
        assertEquals(listOf(message, mamma, plain), withoutDismissed(rows, setOf("+4790000002"), plain = false))
        assertEquals(listOf(message, pappa, mamma), withoutDismissed(rows, emptySet(), plain = true))
        assertEquals(listOf(message), withoutDismissed(rows, setOf("+4790000002", "+4790000001"), plain = true))
    }

    /** Design 59d §8.13: Android's gesture handle on our glance - the profile's, else Android's defaults. */
    @Test
    fun `the handle - SystemUI's measured on the emulator, else 108 x 4 dp, white at 80 percent, 8 dp off the bottom`() {
        assertEquals(GlanceHandle(108f, 4f, 8f, 0.8f), glanceHandle(null))
        assertEquals(DEFAULT_GLANCE_HANDLE, glanceHandle(null))
        assertEquals(GlanceHandle(108f, 4f, 10f, 0.92f), glanceHandle(EMULATOR_PIXEL_CLOCK))
        // AOSP SystemUI's handle measured pixel for pixel the Pixel image's (the AOSP emulator, aosp-lock-look).
        assertEquals(GlanceHandle(108f, 4f, 10f, 0.92f), glanceHandle(AOSP_EMULATOR_CLOCK))
        assertEquals(glanceHandle(AOSP_EMULATOR_CLOCK), glanceHandle(JELLY_STAR_CLOCK))
        // The view: the pill centred in the bottom inset - 24 dp on the emulator (its gesture inset), 20 dp by default.
        assertEquals(24f, glanceHandleViewHeightDp(glanceHandle(EMULATOR_PIXEL_CLOCK)), 0.01f)
        assertEquals(20f, glanceHandleViewHeightDp(DEFAULT_GLANCE_HANDLE), 0.01f)
    }
    /** Design 59d step L: Android's small clock's geometry by phone, measured at 240 dpi; else the centred clock. */
    @Test
    fun `the clock profile - the emulator's Pixel and the Jelly Star at 240 dpi, else none`() {
        assertEquals(EMULATOR_PIXEL_CLOCK, glanceClockProfile("sdk_gphone64_x86_64", "Google", 1.5f, 320))
        assertEquals(EMULATOR_PIXEL_CLOCK, glanceClockProfile("sdk_gphone16k_arm64", "Google", 1.5f, 320))
        // JL-6: the Jelly Star's model string isn't verified - spaces, underscores and case don't matter.
        for (model in listOf("Jelly Star", "JellyStar", "Jelly_Star", "jelly star", "JELLY-STAR")) {
            assertEquals(model, "Jelly Star", glanceClockProfile(model, "Unihertz", 1.5f, 320)?.name)
        }
        // The Jelly Star carries the AOSP emulator's numbers (AOSP SystemUI; confirm on the phone).
        assertEquals(AOSP_EMULATOR_CLOCK.copy(name = "Jelly Star"), JELLY_STAR_CLOCK)
        // The pure AOSP image (`default`, AVD aosp36): "Android SDK built for x86_64" by "unknown".
        assertEquals(AOSP_EMULATOR_CLOCK, glanceClockProfile("Android SDK built for x86_64", "unknown", 1.5f, 320))
        assertEquals(AOSP_EMULATOR_CLOCK, glanceClockProfile("Android SDK built for arm64", "unknown", 1.5f, 320))
        assertNull("AOSP's model by another maker", glanceClockProfile("Android SDK built for x86_64", "Google", 1.5f, 320))
        assertNull("the AOSP image at its own 160 dpi", glanceClockProfile("Android SDK built for x86_64", "unknown", 1f, 480))
        // The AOSP emulator's numbers: the digits' ink rows 104-196 (93, as Pixel's 180-272) on a baseline at 197 px
        // under a 36 px status bar (the keyguard's bar 60 px), the "1"'s ink at x 56; the date 56 px under the clock's
        // baseline (AOSP's face shows none).
        val a = AOSP_EMULATOR_CLOCK
        assertEquals(listOf(37.3f, 91.3f, 86f, 35.3f, 128.7f, 16f), listOf(a.clockInkStartDp, a.clockBaselineBelowBarDp, a.clockDp, a.dateStartDp, a.dateBaselineBelowBarDp, a.dateSp))
        assertEquals("the ink at x 56", 56f, a.clockInkStartDp * 1.5f, 0.1f)
        assertEquals("the baseline at 197 px", 197f, profileBaselinePx(keyguardStatusBarPx(36, 0, 1.5f), a.clockBaselineBelowBarDp, 1.5f), 0.1f)
        assertEquals("the date's baseline 56 px under the clock's", 56f, (a.dateBaselineBelowBarDp - a.clockBaselineBelowBarDp) * 1.5f, 0.1f)
        assertNull("the AVD's own 320 dpi: not measured", glanceClockProfile("sdk_gphone64_x86_64", "Google", 2f, 240))
        // JL-6: keyed on the width in dp too - the six-inch AVD at 240 dpi (411 dp) or a "Display size" change.
        assertNull("411 dp at 240 dpi", glanceClockProfile("sdk_gphone64_x86_64", "Google", 1.5f, 411))
        assertNull("320 dp at another density", glanceClockProfile("sdk_gphone64_x86_64", "Google", 2f, 320))
        assertNull("a real Pixel", glanceClockProfile("Pixel 7", "Google", 1.5f, 320))
        assertNull("another maker's emulator name", glanceClockProfile("sdk_gphone64_x86_64", "Unihertz", 1.5f, 320))
        assertNull(glanceClockProfile(null, null, 1.5f, 320))
        // The emulator Pixel's numbers (59d §8.11): Android's digits 92 px high on a baseline 137 px under the keyguard's
        // status bar (273 px on handy36), the first digit's ink at x 49-53 ("1"-"5"); the date's at x 50 on a baseline
        // 193 px under it (329 px).
        val p = EMULATOR_PIXEL_CLOCK
        assertEquals(listOf(34f, 91.3f, 86f, 32f, 128.7f, 16f), listOf(p.clockInkStartDp, p.clockBaselineBelowBarDp, p.clockDp, p.dateStartDp, p.dateBaselineBelowBarDp, p.dateSp))
        // Both SystemUIs put the small clock at the same place under that bar (the glance-topinset round).
        assertEquals(p.clockBaselineBelowBarDp, a.clockBaselineBelowBarDp)
        assertEquals(p.dateBaselineBelowBarDp, a.dateBaselineBelowBarDp)
        assertEquals("the ink's target is within 2 px of Android's \"1\" and \"5\"", 51f, p.clockInkStartDp * 1.5f, 0.01f)
        assertEquals("Nunito's digits are 0.712 em: 92 px at 1.5", 92f, 0.712f * p.clockDp * 1.5f, 1f)
    }

    /**
     * The glance-topinset round: the profile's baselines hang under SystemUI's keyguard status bar - the larger of the
     * window's status bar (a cutout's inset where larger) and 40 dp -, never from the screen's top. Measured: Android's
     * small clock's baseline at 273 px under handy36's 136 px status bar (pixel_7 skin), at 197 px under a 36 px one
     * (the user's jellystar2 and jelly36, Pixel SystemUI; aosp36, AOSP SystemUI).
     */
    @Test
    fun `the profile's baselines sit under the keyguard status bar, a 36 px and a 136 px status bar alike`() {
        assertEquals("a 24 dp status bar: the 40 dp floor", 60f, keyguardStatusBarPx(36, 0, 1.5f), 0.01f)
        assertEquals("handy36's cutout-sized status bar", 136f, keyguardStatusBarPx(136, 136, 1.5f), 0.01f)
        assertEquals("a cutout taller than the status bar's inset", 150f, keyguardStatusBarPx(36, 150, 1.5f), 0.01f)
        assertEquals("a 48 dp status bar (the Jelly Star's 56 dp top pad)", 72f, keyguardStatusBarPx(72, 0, 1.5f), 0.01f)
        assertEquals("no insets yet: the floor", 60f, keyguardStatusBarPx(0, 0, 1.5f), 0.01f)
        for (profile in listOf(EMULATOR_PIXEL_CLOCK, AOSP_EMULATOR_CLOCK, JELLY_STAR_CLOCK)) {
            for (statusBar in listOf(36, 136)) {
                val bar = keyguardStatusBarPx(statusBar, 0, 1.5f)
                assertEquals(bar + profile.clockBaselineBelowBarDp * 1.5f, profileBaselinePx(bar, profile.clockBaselineBelowBarDp, 1.5f), 0.01f)
            }
            assertEquals("${profile.name}, 36 px: Android's 197", 197f, profileBaselinePx(keyguardStatusBarPx(36, 0, 1.5f), profile.clockBaselineBelowBarDp, 1.5f), 0.5f)
            assertEquals("${profile.name}, 136 px: Android's 273", 273f, profileBaselinePx(keyguardStatusBarPx(136, 0, 1.5f), profile.clockBaselineBelowBarDp, 1.5f), 0.5f)
            assertEquals("${profile.name}, the date, 36 px: 253", 253f, profileBaselinePx(keyguardStatusBarPx(36, 0, 1.5f), profile.dateBaselineBelowBarDp, 1.5f), 0.5f)
            assertEquals("${profile.name}, the date, 136 px: 329", 329f, profileBaselinePx(keyguardStatusBarPx(136, 0, 1.5f), profile.dateBaselineBelowBarDp, 1.5f), 0.5f)
        }
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

    /** Design r11 §1.2-1.3: no fit - the sizes are rules, at most eight rows, the "more" line counts the rest. */
    @Test
    fun `the layout - every row up to eight, then the more line, the timer kept, nothing fitted (r11 §1)`() {
        assertEquals(8, GLANCE_MAX_ROWS)
        assertEquals(GlanceLayout(GlanceTileSize.FULL, 0, 0, false), glanceLayout(tile = true, rowCount = 0, timer = false))
        assertEquals(GlanceLayout(GlanceTileSize.NONE, 3, 0, false), glanceLayout(tile = false, rowCount = 3, timer = false))
        assertEquals("eight rows: no more line", GlanceLayout(GlanceTileSize.NONE, 8, 0, false), glanceLayout(tile = false, rowCount = 8, timer = false))
        assertEquals("nine: eight and + 1", listOf(8, 1), glanceLayout(tile = false, rowCount = 9, timer = false).let { listOf(it.rows, it.more) })
        assertEquals("twenty: eight and + 12", listOf(8, 12), glanceLayout(tile = true, rowCount = 20, timer = false).let { listOf(it.rows, it.more) })
        assertTrue("the timer tile is kept whatever else shows", glanceLayout(tile = true, rowCount = 20, timer = true).timer)
        assertEquals("a negative count is none", GlanceLayout(GlanceTileSize.NONE, 0, 0, true), glanceLayout(tile = false, rowCount = -3, timer = true))
    }

    /** Design r11 §2 (the user's ask 2): a rule, not a fit - full with 0 or 1 rows, compact from 2. */
    @Test
    fun `the music tile - full with 0 or 1 rows, compact from 2, none without a session (r11 §2)`() {
        assertEquals(2, GLANCE_COMPACT_FROM_ROWS)
        assertEquals(GlanceTileSize.FULL, glanceLayout(tile = true, rowCount = 0, timer = false).tile)
        assertEquals(GlanceTileSize.FULL, glanceLayout(tile = true, rowCount = 1, timer = false).tile)
        assertEquals(GlanceTileSize.COMPACT, glanceLayout(tile = true, rowCount = 2, timer = false).tile)
        assertEquals(GlanceTileSize.COMPACT, glanceLayout(tile = true, rowCount = 8, timer = false).tile)
        assertEquals("nine: eight rows, + 1, compact", GlanceLayout(GlanceTileSize.COMPACT, 8, 1, false), glanceLayout(tile = true, rowCount = 9, timer = false))
        for (rows in listOf(0, 1, 2, 9)) assertEquals("no session: no tile", GlanceTileSize.NONE, glanceLayout(tile = false, rowCount = rows, timer = false).tile)
        // A running timer doesn't change the rule (it is always full itself).
        for (rows in 0..3) {
            assertEquals(glanceLayout(tile = true, rowCount = rows, timer = false).tile, glanceLayout(tile = true, rowCount = rows, timer = true).tile)
        }
        // A dismissal from 2 rows to 1 brings the full tile back in the commit's render (no hysteresis).
        assertEquals(GlanceTileSize.FULL, glanceLayout(tile = true, rowCount = 2 - 1, timer = false).tile)
        // The panel takes the tile from the rule alone (shownTile), the rows counted as the store gives them.
        val panel = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/GlancePanel.kt") }.first { it.exists() }.readText()
        assertTrue(panel.contains("private fun shownTile(): GlanceTileSize = glanceLayout(tileUp, rows.size, clockUp, ruleCard != null).tile"))
    }

    /** Design r13 §1.3 (D4): the rule's card is one more part of the page - the compact rule still counts rows alone. */
    @Test
    fun `the rule card - kept in the layout, never counted toward the compact tile (r13 D4)`() {
        for (rows in 0..9) {
            for (timer in listOf(false, true)) {
                val without = glanceLayout(tile = true, rowCount = rows, timer = timer)
                val with = glanceLayout(tile = true, rowCount = rows, timer = timer, rule = true)
                assertEquals("$rows rows: only the card differs", without.copy(rule = true), with)
                assertTrue(with.rule)
                assertFalse(without.rule)
            }
        }
        assertEquals(GlanceTileSize.FULL, glanceLayout(tile = true, rowCount = 1, timer = false, rule = true).tile)
        assertEquals(GlanceTileSize.COMPACT, glanceLayout(tile = true, rowCount = 2, timer = false, rule = true).tile)
        assertEquals(GlanceTileSize.NONE, glanceLayout(tile = false, rowCount = 2, timer = false, rule = true).tile)
        // The panel passes its kept card everywhere it lays out, and the list sits 14 dp under the card.
        val panel = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/GlancePanel.kt") }.first { it.exists() }.readText()
        assertEquals(3, Regex("glanceLayout\\(tileUp, rows\\.size, clockUp, ruleCard != null\\)").findAll(panel).count())
        assertTrue(panel.contains("layout.tile == GlanceTileSize.NONE && !layout.timer && !layout.rule) ROWS_TOP_NO_TILE_DP else ROWS_TOP_DP"))
    }

    @Test
    fun `the timer tile's tap - the keypad with the clock app, its hint, and a time rule's usable apps (design 42)`() {
        assertEquals(GlanceStageState(LockStage.KEYPAD, GlanceTarget.ClockApp), glanceStageStep(GlanceStageState.GLANCE, GlanceStageEvent.Row(GlanceTarget.ClockApp)))
        assertEquals(GlancePrompt.OPEN_APP, glancePrompt(true, false, false, false, GlanceTarget.ClockApp, androidFace = false))
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
        assertEquals(GlancePrompt.UNUSABLE, glancePrompt(usable = false, waiting = true, checking = true, wrong = true, target = conversation, androidFace = false))
        assertEquals(GlancePrompt.WAIT, glancePrompt(true, waiting = true, checking = true, wrong = true, target = conversation, androidFace = false))
        assertEquals(GlancePrompt.CHECKING, glancePrompt(true, false, checking = true, wrong = true, target = conversation, androidFace = false))
        assertEquals(GlancePrompt.WRONG, glancePrompt(true, false, false, wrong = true, target = conversation, androidFace = false))
        assertEquals(GlancePrompt.OPEN_APP, glancePrompt(true, false, false, false, conversation, androidFace = false))
        assertEquals(GlancePrompt.SEE_MISSED, glancePrompt(true, false, false, false, GlanceTarget.MissedCalls(null), androidFace = false))
        assertEquals(GlancePrompt.PLAIN, glancePrompt(true, false, false, false, null, androidFace = false))
    }

    /** r10 run F1: as Android's face the kid PIN opens Android's pad, so an unusable kid hash is no "parent code only". */
    @Test
    fun `the unusable prompt only in own mode - Android's face shows the normal hint`() {
        assertEquals(GlancePrompt.PLAIN, glancePrompt(usable = false, waiting = false, checking = false, wrong = false, target = null, androidFace = true))
        assertEquals(GlancePrompt.OPEN_APP, glancePrompt(false, false, false, false, conversation, androidFace = true))
        assertEquals(GlancePrompt.SEE_MISSED, glancePrompt(false, false, false, false, GlanceTarget.MissedCalls(null), androidFace = true))
        assertEquals("own mode keeps it", GlancePrompt.UNUSABLE, glancePrompt(false, false, false, false, null, androidFace = false))
        val activity = listOf(File("src/main/java/com/kidslauncher/mdm/lock/PinLockActivity.kt"), File("app/src/main/java/com/kidslauncher/mdm/lock/PinLockActivity.kt"))
            .first { it.exists() }.readText()
        assertTrue(activity.contains("glancePrompt(PinLockRuntime.pinUsable, waitMs > 0L, checking, wrongShown, glance?.state?.target, androidFace = AndroidLock.faceOn)"))
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
    fun `the page's touchables are the tile controls, the rows and Nødsamtale - each wired, the swipe and the handle's action ask`() {
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
                // The user (2026-10-24): the "more" line leads to the keypad as a row without a target.
                "lock_glance_more",
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
        for (id in listOf("lock_glance_emergency", "lock_glance_torch", "lock_timer_tile", "lock_timer_stop", "lock_glance_more")) {
            assertTrue("${binding(id)} is wired", glance.contains("glance.${binding(id)}.setOnClickListener"))
        }
        assertFalse(glance.contains("glance.root.setOnClickListener"))
        // Design 59d step J and §8.13: "Lås opp" and the hint line went; the gesture handle is no button for touch,
        // TalkBack's node for the unlock: described and its click action named "Lås opp", which asks as the swipe does.
        assertTrue(all(root).none { it.getAttributeNS(ns, "id") in setOf("@+id/lock_glance_unlock", "@+id/lock_glance_hint") })
        assertTrue(glance.contains("handleAction(handle)"))
        val action = glance.substringAfter("private fun handleAction(").substringBefore("\n    /**")
        assertTrue(action, action.contains("view.contentDescription = activity.getString(R.string.lock_media_unlock)"))
        assertTrue(action, action.contains("AccessibilityNodeInfo.ACTION_CLICK, activity.getString(R.string.lock_media_unlock)"))
        assertTrue(action, action.contains("step(GlanceStageEvent.Unlock)"))
        assertFalse(action, action.contains("setOnClickListener"))
        // The handle is a view of the lock's frame, never clickable (its touches reach the page's swipe), shown with the
        // page's content only.
        val handle = glance.substringAfter("private val handle: View = ").substringBefore("\n    }\n")
        assertTrue(handle, handle.contains("frame.addView("))
        assertTrue(handle, handle.contains("Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL"))
        assertFalse(handle, handle.contains("isClickable") || handle.contains("setOnClickListener") || handle.contains("setOnTouchListener"))
        assertTrue(glance.contains("handle.visibility = if (root.visibility == View.VISIBLE && glanceContentShown(leaving)) View.VISIBLE else View.GONE"))
        // JL-4 (a): the controls that keep their touch are exactly the layout's touchables less the page itself, the
        // timer tile and the "more" line (both lead to the unlock, as a row) - a new control in the layout must join them.
        val controls = Regex("""glance\.(\w+)""").findAll(glance.substringAfter("private fun swipeControls(): List<View> = listOf(").substringBefore(")")).map { it.groupValues[1] }.toSet()
        assertEquals((touchable - setOf("lock_glance_root", "lock_timer_tile", "lock_glance_more")).map(::binding).toSet(), controls)
        // JL-1, JL-4 (b): the DOWN passes while leaving, covered or off the page; in ASK (the page shown under the pad) a
        // swipe asks again, as the button's second tap did.
        val down = glance.substringAfter("if (action == MotionEvent.ACTION_DOWN) {").substringBefore("return SwipeTouch.PASS")
        assertTrue(down, down.contains("covered || !started || leaving || !glancePageShown(state.stage)"))
        assertTrue(glancePageShown(LockStage.ASK) && !glancePageShown(LockStage.KEYPAD))
        // The swipe's only step is Unlock - the same ask as the button was; the activity feeds it every touch first.
        val swipe = glance.substringAfter("private fun swipeNow(").substringBefore("private fun swipeReset(")
        assertEquals(listOf("step(GlanceStageEvent.Unlock)"), Regex("""\bstep\([^)]*\)""").findAll(swipe).map { it.value }.toList())
        assertTrue(activity().contains("panel.swipe(ev, covered = voipRing.root.visibility == View.VISIBLE)"))
        // JL-4 (c): the recognised swipe cancels the views (no row tap after it), and the activity sends that cancel.
        assertTrue(swipe, swipe.substringAfter("glance.root.post { step(GlanceStageEvent.Unlock) }").contains("return SwipeTouch.CANCEL_VIEWS"))
        val dispatch = activity().substringAfter("override fun dispatchTouchEvent(").substringBefore("override fun onUserInteraction(")
        assertTrue(dispatch, dispatch.contains("GlancePanel.SwipeTouch.CANCEL_VIEWS -> {"))
        assertTrue(dispatch, dispatch.contains("cancel.action = android.view.MotionEvent.ACTION_CANCEL"))
        assertTrue(dispatch, dispatch.contains("super.dispatchTouchEvent(cancel)"))
        // The emergency button is the lock's own (its confirm dialog, PinLockRuntime.emergencyFlowStarted).
        assertTrue(activity().contains("{ binding.pinEmergency.performClick() }"))
    }

    private fun activity() = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
}

/**
 * The glance page from its XML (design 25 §1.2, r9, r11): the parts' heights in the 1.4 x text model (Nunito's line is
 * about 1.36 em) and what scrolls at 320x569 dp with a 48 dp status bar and 3-button navigation (473 dp) or gesture
 * navigation (497 dp) - r11 §1.2's table; the scroller over the anchored bottom block; nothing squeezed at 320, 360 and
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

    /** The parts as the page lays them out (r11 §1.2: measured by nothing any more - a model of what scrolls). */
    private data class Parts(
        val bottom: Float, val clock: Float, val date: Float, val alarm: Float, val tileFull: Float, val tileCompact: Float,
        val timer: Float, val rowsTop: Float, val rowsTopNoTile: Float, val row: Float, val rowGap: Float, val more: Float,
        val rule: Float,
    )

    private val parts by lazy {
        Parts(
            bottom = withMargins("lock_glance_bottom"),
            clock = root.byId("lock_glance_clock").dim("textSize") * 1.4f,
            date = withMargins("lock_glance_date"),
            alarm = withMargins("lock_glance_alarm"),
            tileFull = withMargins("lock_media_tile"),
            tileCompact = withMargins("lock_media_compact"),
            timer = withMargins("lock_timer_tile"),
            rowsTop = root.byId("lock_glance_list").dim("layout_marginTop"),
            rowsTopNoTile = 22f,
            row = height(row),
            rowGap = 8f,
            more = root.byId("lock_glance_more").let { it.dim("layout_marginTop") + height(it) },
            rule = withMargins("lock_rule_card"),
        )
    }

    /** The scroller's content for [layout] (the top block has no vertical padding). */
    private fun content(layout: GlanceLayout, p: Parts = parts, alarm: Boolean = false): Float {
        val tile = when (layout.tile) {
            GlanceTileSize.NONE -> 0f
            GlanceTileSize.FULL -> p.tileFull
            GlanceTileSize.COMPACT -> p.tileCompact
        }
        val list = if (layout.rows == 0) {
            0f
        } else {
            (if (layout.tile == GlanceTileSize.NONE && !layout.timer && !layout.rule) p.rowsTopNoTile else p.rowsTop) +
                layout.rows * p.row + (layout.rows - 1) * p.rowGap + (if (layout.more > 0) p.more else 0f)
        }
        return p.clock + p.date + (if (alarm) p.alarm else 0f) + (if (layout.rule) p.rule else 0f) + tile +
            (if (layout.timer) p.timer else 0f) + list
    }

    private fun available(navBar: Float) = 569f - 48f - navBar - root.dim("paddingTop") - root.dim("paddingBottom")

    /** The scroller's height: what the anchored bottom block leaves. */
    private fun scroller(navBar: Float) = available(navBar) - parts.bottom

    @Test
    fun `the parts match the design's model`() {
        assertEquals(56f, root.byId("lock_glance_clock").dim("textSize"))
        assertEquals(78.4f, parts.clock, 0.01f)
        assertEquals(25.6f, parts.date, 0.01f)
        assertEquals("rows of at least 56 dp, 8 dp vertical padding (QA Low 4)", 56f, height(row))
        assertEquals(8f, row.dim("paddingTop"))
        assertEquals(8f, row.dim("paddingBottom"))
        // r9 §3: 12 + the 48 dp cover + 10 + the times' line (12 sp) + 2 + the 56 dp play + 12, and its 18 dp margin
        // (r6's tile was 233.8); the compact tile keeps its 52 dp art row.
        assertEquals(174.8f, parts.tileFull, 0.01f)
        assertEquals(90f, parts.tileCompact, 0.01f)
        // Design 59d §8.13: the bottom row 48 + its 8 dp (the hint line went - 79.6 with it; Lås opp + the row 116 before).
        assertEquals(56f, parts.bottom, 0.01f)
        // Design 42: the timer tile - 12 dp padding, the label row, the 24 sp time, the text - and its margin.
        assertEquals(114f, parts.timer, 0.01f)
        assertEquals(14f, parts.rowsTop)
        // The "more" line: nothing above it (QA Low 4), it grows with the font; since the user's 2026-10-24 ask a tap
        // target of at least 48 dp (24 before).
        val more = root.byId("lock_glance_more")
        assertEquals("48dp", more.getAttributeNS(ns, "minHeight"))
        assertEquals("wrap_content", more.getAttributeNS(ns, "layout_height"))
        assertEquals(0f, more.dim("layout_marginTop"))
        assertEquals(48f, parts.more, 0.01f)
        assertEquals("true", more.getAttributeNS(ns, "clickable"))
        // The alarm line under the date (qa-r6-b-code L7): always shown while there is an alarm (r11 §1.2).
        assertEquals("2 dp + 13 sp x 1.4", 20.2f, parts.alarm, 0.01f)
        // The scroller over the anchored bottom block: 401 dp with 3-button navigation, 425 dp with gestures.
        assertEquals(457f, available(48f), 0.01f)
        assertEquals(481f, available(24f), 0.01f)
        assertEquals(401f, scroller(48f), 0.01f)
        assertEquals(425f, scroller(24f), 0.01f)
        // Design r13 §1.3: the rule's card - 12 + max(40, 21 + 18.2) + 12 = 64 dp, 82 with its 18 dp margin.
        assertEquals(82f, parts.rule, 0.01f)
    }

    /** Design r13 §1.3: the card is information only - no tap, one TalkBack stop without a keyboard focus stop. */
    @Test
    fun `the rule card - not clickable, one TalkBack stop, the disc, the compact tile's ground (r13 §1_3)`() {
        val card = root.byId("lock_rule_card")
        assertEquals("LinearLayout", card.tagName)
        assertEquals("horizontal", card.getAttributeNS(ns, "orientation"))
        assertEquals("", card.getAttributeNS(ns, "clickable"))
        assertEquals("", card.getAttributeNS(ns, "focusable"))
        assertEquals("yes", card.getAttributeNS(ns, "importantForAccessibility"))
        assertEquals("true", card.getAttributeNS(ns, "screenReaderFocusable"))
        assertEquals("gone", card.getAttributeNS(ns, "visibility"))
        assertEquals("@drawable/bg_glance_compact_tile", card.getAttributeNS(ns, "background"))
        assertEquals(12f, card.dim("padding"))
        assertEquals(18f, card.dim("layout_marginTop"))
        val glyph = root.byId("lock_rule_card_glyph")
        assertEquals(listOf(40f, 40f), listOf(glyph.dim("layout_width"), glyph.dim("layout_height")))
        assertEquals("@drawable/bg_lock_kind_disc", glyph.getAttributeNS(ns, "background"))
        assertEquals("@color/kid_accent", glyph.getAttributeNS(ns, "tint"))
        assertEquals(9f, glyph.dim("padding"))
        for (id in listOf("lock_rule_card_glyph", "lock_rule_card_title", "lock_rule_card_until")) {
            assertEquals(id, "no", root.byId(id).getAttributeNS(ns, "importantForAccessibility"))
        }
        for ((id, size) in listOf("lock_rule_card_title" to 15f, "lock_rule_card_until" to 13f)) {
            val text = root.byId(id)
            assertEquals(id, size, text.dim("textSize"))
            assertEquals(id, "1", text.getAttributeNS(ns, "maxLines"))
            assertEquals(id, "end", text.getAttributeNS(ns, "ellipsize"))
        }
        // Nothing in the card is a control.
        assertTrue(card.all().none { it.getAttributeNS(ns, "clickable") == "true" || it.tagName.endsWith("Button") })
        // The panel never makes it clickable or sensitive: the label is the parent's own (r13 §1.3).
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        assertFalse(panel.contains("lockRuleCard.setOnClickListener"))
        assertFalse(panel.contains("sensitive(glance.lockRuleCard"))
        assertTrue(panel.contains("view.contentDescription = if (until != null) activity.getString(R.string.glance_rule_card_description, label, until) else label"))
    }

    @Test
    fun `the glance page's Nødsamtale is a 48 dp target and the bottom block didn't move (qa-34-s8-code N5)`() {
        val link = root.byId("lock_glance_emergency")
        assertTrue(link.dim("minHeight") >= 48f)
        assertEquals("@color/kid_emergency_text", link.getAttributeNS(ns, "textColor"))
        // The row's margin gave the 4 dp; since design 59d §8.13 the bottom row with its 8 dp margin is the whole
        // bottom block, 56 dp (the swipe hint + the row were 79.6, Lås opp + the row 116); the handle is in the inset.
        assertEquals(56f, withMargins("lock_glance_bottom"), 0.01f)
        assertEquals(8f, root.byId("lock_glance_bottom").dim("layout_marginTop"))
    }

    @Test
    fun `the bottom row - Nødsamtale centred, the flashlight at its end adds nothing to its height (design 41)`() {
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
        // The button is no taller than Nødsamtale: the row's height is the same with it.
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
    fun `the layout keys on the available height - the listener and layoutNow both read it (r9 §1, r11 §1_2)`() {
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        val listener = panel.substringAfter("glance.root.addOnLayoutChangeListener").substringBefore("\n        }")
        assertTrue(listener, listener.contains("available(bottom - top) != placedFor"))
        assertFalse("the bare height no longer decides", listener.contains("bottom - top != placedFor"))
        val layoutNow = panel.substringAfter("private fun layoutNow()").substringBefore("val clock = ")
        assertTrue(layoutNow, layoutNow.contains("val room = available(root.height)"))
        assertTrue(layoutNow, layoutNow.contains("placedFor = room"))
        assertTrue(panel.contains("private fun available(height: Int): Int = glance.root.let { height - it.paddingTop - it.paddingBottom }"))
        // The log line comes with a new layout or a new available height.
        assertTrue(panel.contains("if (layout != laidOut || room != loggedFor)"))
        // Nothing measures to make room: no fit, no small clock, no refit rule.
        for (gone in listOf("glanceFit", "GlanceHeights", "CLOCK_SMALL_SP", "glanceRefitDue", "fitTilePx", "fitClockPx", "withMargins(")) {
            assertFalse(gone, panel.contains(gone))
        }
        // Every lock-up starts the page at the top (r9 §2): the scroller is the whole top block now.
        assertTrue(panel.substringAfter("if (lockStageDue(").substringBefore("renderNow()").contains("glance.lockGlanceScroll.scrollTo(0, 0)"))
    }

    /** r11 §1.2: what scrolls at 473 and 497 dp - the content against the scroller, nothing fitted or clipped. */
    @Test
    fun `what scrolls at 473 and 497 dp (r11 §1_2)`() {
        fun at(tile: Boolean, rows: Int) = content(glanceLayout(tile, rows, timer = false))
        // r11 §1.2's table: the content, then how far it scrolls in the 401 / 425 dp scroller (0 = it fits).
        val table = listOf(
            Triple("the full tile alone", at(true, 0), 278.8f),
            Triple("music + 1 row (full)", at(true, 1), 348.8f),
            Triple("music + 2 rows (compact)", at(true, 2), 328f),
            Triple("music + 3 rows (compact)", at(true, 3), 392f),
            Triple("music + 4 rows", at(true, 4), 456f),
            Triple("music + 8 rows", at(true, 8), 712f),
            Triple("no music, 5 rows", at(false, 5), 438f),
        )
        for ((case, content, expected) in table) assertEquals(case, expected, content, 0.01f)
        val scrolls = mapOf(
            "music + 4 rows" to (55f to 31f), "music + 8 rows" to (311f to 287f), "no music, 5 rows" to (37f to 13f),
        )
        for ((case, content, _) in table) {
            val (at473, at497) = scrolls[case] ?: (0f to 0f)
            assertEquals("$case at 473", at473, maxOf(0f, content - scroller(48f)), 0.01f)
            assertEquals("$case at 497", at497, maxOf(0f, content - scroller(24f)), 0.01f)
        }
        // Design r13 §1.3's table: the rule's card on the page, with rowsTop under it; how far it scrolls at 473 / 497.
        fun ruled(tile: Boolean, rows: Int) = content(glanceLayout(tile, rows, timer = false, rule = true))
        val ruleTable = listOf(
            Triple("the card alone", ruled(false, 0), 186f to (0f to 0f)),
            Triple("the card + 1 row", ruled(false, 1), 256f to (0f to 0f)),
            Triple("the card + 3 rows", ruled(false, 3), 384f to (0f to 0f)),
            Triple("the card + 4 rows", ruled(false, 4), 448f to (47f to 23f)),
            Triple("the card + full music", ruled(true, 0), 360.8f to (0f to 0f)),
            Triple("the card + full music + 1 row", ruled(true, 1), 430.8f to (29.8f to 5.8f)),
            Triple("the card + compact music + 2 rows", ruled(true, 2), 410f to (9f to 0f)),
        )
        for ((case, content, expected) in ruleTable) {
            val (height, scrolls) = expected
            assertEquals(case, height, content, 0.01f)
            assertEquals("$case at 473", scrolls.first, maxOf(0f, content - scroller(48f)), 0.01f)
            assertEquals("$case at 497", scrolls.second, maxOf(0f, content - scroller(24f)), 0.01f)
        }
        val withAlarm = content(glanceLayout(tile = true, rowCount = 1, timer = false, rule = true), alarm = true)
        assertEquals("frame D + the alarm line", 451f, withAlarm, 0.01f)
        assertEquals(50f, withAlarm - scroller(48f), 0.01f)
        assertEquals(26f, withAlarm - scroller(24f), 0.01f)
        // Nine rows and more: eight and the "more" line.
        assertEquals(at(false, 8) + parts.more, at(false, 9), 0.01f)
        assertEquals(at(false, 9), at(false, 30), 0.01f)
        // The user's case (their screenshot): the profile's clock and date, the alarm line, music + 2 rows - compact,
        // and it fits (370.6 dp) at both.
        val users = content(glanceLayout(tile = true, rowCount = 2, timer = false), profileParts, alarm = true)
        assertEquals(370.6f, users, 0.1f)
        assertTrue(users <= scroller(48f) && users <= scroller(24f))
        // ... and with one row the full tile, on every bar (59d §8.15's "compact no longer for music + 1 row" is the
        // rule's first line now).
        assertEquals(GlanceTileSize.FULL, glanceLayout(tile = true, rowCount = 1, timer = false).tile)
    }

    /**
     * Design 59d step L: the parts with the emulator Pixel profile at 320x569 dp (a 48 dp status bar, so the page's
     * content starts at 56 dp): the clock's block runs from there to its descent - its margin puts the baseline 91.3 dp
     * under the keyguard's status bar (48 dp here, the glance-topinset round), at 139.3 dp - and the date's from the
     * clock's bottom to its own descent; Nunito's descent is 0.353 em.
     */
    private val profileParts by lazy {
        val p = EMULATOR_PIXEL_CLOCK
        val descent = 0.353f
        val bar = keyguardStatusBarPx(72, 0, 1.5f) / 1.5f
        val clock = bar + p.clockBaselineBelowBarDp - (48f + root.dim("paddingTop")) + descent * p.clockDp
        val date = p.dateBaselineBelowBarDp + descent * p.dateSp - (p.clockBaselineBelowBarDp + descent * p.clockDp)
        parts.copy(clock = clock, date = date)
    }

    @Test
    fun `the profile's clock is placed, one size, measured by nothing else (design 59d step L)`() {
        assertEquals(113.7f, profileParts.clock, 0.1f)
        assertEquals(12.7f, profileParts.date, 0.1f)
        // The panel places the profile's clock (its margins under the keyguard status bar), else the 56 sp clock.
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        val layoutNow = panel.substringAfter("private fun layoutNow()").substringBefore("val layout = ")
        assertTrue(layoutNow, layoutNow.contains("clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, p.clockDp)\n            moved = placeProfile(p, width)"))
        assertTrue(layoutNow, layoutNow.contains("clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_BIG_SP)"))
    }

    @Test
    fun `the profile is the phone's own, its clock in Nunito Light, the date and alarm at its start`() {
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        assertTrue(panel.contains("glanceClockProfile(Build.MODEL, Build.MANUFACTURER, density, widthDp)"))
        assertTrue(panel.contains("val widthDp = activity.resources.configuration.screenWidthDp"))
        // JL-6: no profile is said once, with what was looked at.
        assertTrue(panel.contains("if (it == null) Log.i(LOG_TAG, \"Glance clock: no profile (model"))
        // JL-2: Nunito Light (300) is bundled for this clock only - the family keeps 600/700/800, so a normal (400)
        // request still gets SemiBold.
        val fonts = listOf("src/main/res/font", "app/src/main/res/font").map(::File).first { it.exists() }.list()!!.filter { it.endsWith(".ttf") }.toSet()
        assertEquals(setOf("nunito_light.ttf", "nunito_semibold.ttf", "nunito_bold.ttf", "nunito_extrabold.ttf"), fonts)
        val family = file("res/font/nunito.xml").readText()
        assertFalse(family, family.contains("nunito_light") || family.contains("\"300\""))
        val look = panel.substringAfter("private fun profileLook(").substringBefore("\n    /**")
        assertTrue(look, look.contains("R.font.nunito_light"))
        assertEquals("only the profile's clock is light", 1, Regex("R\\.font\\.nunito_light").findAll(panel).count())
        assertTrue(look, look.contains("date.transformationMethod = FirstCapital(locale)"))
        assertTrue(look, look.contains("listOf(clock, date, glance.lockGlanceAlarm)"))
        assertTrue(panel.contains("DateFormat.getBestDateTimePattern(locale, glanceDateSkeleton(profile != null))"))
        val place = panel.substringAfter("private fun placeProfile(").substringBefore("private fun margins(")
        assertTrue(place, place.contains("floorPx = -descent"))
        // The glance-topinset round: the baselines under the keyguard's status bar, read from the window's own insets
        // (the status bar whatever its visibility, the cutout) at every placing.
        assertTrue(place, place.contains("val bar = keyguardBarPx(density)"))
        // Whole lines (qa-glance-topinset-code Q-1): the clock's target from the clock's offset, the date's from the date's.
        assertTrue(place, place.contains("val clockTarget = profileBaselinePx(bar, p.clockBaselineBelowBarDp, density)\n"))
        assertTrue(place, place.contains("val dateTarget = profileBaselinePx(bar, p.dateBaselineBelowBarDp, density)\n"))
        assertTrue(place, place.contains("baselineMarginPx(clockTarget, contentTop, clock.baseline.toFloat(),"))
        assertTrue(place, place.contains("baselineMarginPx(dateTarget, clockBottom, date.baseline.toFloat(),"))
        assertTrue(place, place.contains("insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top"))
        assertTrue(place, place.contains("insets?.getInsets(WindowInsets.Type.displayCutout())?.top"))
        // The first digit's ink at the profile's start, whatever its side bearing - again when the text changes. The
        // content starts after the root's and the top block's side padding (r11 §1.1: the top block's now).
        assertTrue(place, place.contains("margins(clock, clockStartPx(p), clockTop)"))
        assertTrue(place, place.contains("val contentStart = (at[0] + glance.root.paddingStart + glance.lockGlanceTop.paddingStart).toFloat()"))
        val start = panel.substringAfter("private fun clockStartPx(").substringBefore("\n    /**")
        assertTrue(start, start.contains("clock.paint.getTextBounds(text, 0, 1, first)"))
        assertTrue(start, start.contains("startMarginPx(p.clockInkStartDp * activity.resources.displayMetrics.density - first.left, contentStart)"))
        assertTrue(start, start.contains("val contentStart = (at[0] + glance.root.paddingStart + glance.lockGlanceTop.paddingStart).toFloat()"))
        assertTrue(look, look.contains("clock.addTextChangedListener("))
        assertTrue(place, place.contains("margins(alarm, dateStart,"))
        // Without a profile the XML's centred clock is untouched (LockFontScaleTest pins its gravity).
        assertEquals("center_horizontal", root.byId("lock_glance_top").getAttributeNS(ns, "gravity"))
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
        val top = root.byId("lock_glance_top")
        val controls = root.byId("lock_media_play").parentNode as Element
        assertEquals("the tile's controls", 208f, fixedWidth(controls))
        val compact = root.byId("lock_media_compact")
        assertEquals("the compact tile's fixed parts", 144f, fixedWidth(compact))
        assertEquals("a row's fixed parts with the widest time", 144f, fixedWidth(row, text = 60f))
        val bar = root.byId("lock_media_progress")
        // r11 §1.1: the content width is the top block's (its 16 dp sides), the root has none: 288 dp at 320 as before.
        assertEquals(288f, 320f - root.dim("paddingStart") - root.dim("paddingEnd") - top.dim("paddingStart") - top.dim("paddingEnd"))
        for (screen in listOf(320f, 360f, 411f)) {
            val content = screen - root.dim("paddingStart") - root.dim("paddingEnd") - top.dim("paddingStart") - top.dim("paddingEnd")
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
            // Nødsamtale between the flashlight's room on both sides: "Emergency call" at 15 sp; the bottom block's own
            // 16 dp side margins.
            val bottom = root.byId("lock_glance_bottom")
            val bottomContent = screen - bottom.dim("layout_marginStart") - bottom.dim("layout_marginEnd")
            assertEquals(content, bottomContent)
            val link = root.byId("lock_glance_emergency")
            val label = "Emergency call".length * 15f * 0.6f + link.dim("paddingStart") + link.dim("paddingEnd")
            assertTrue("$screen: Nødsamtale beside the flashlight", bottomContent - link.dim("layout_marginStart") - link.dim("layout_marginEnd") >= label)
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
    fun `the order - the scroller with the whole top block, then the anchored bottom block (r11 §1_1)`() {
        val ids = root.all().map { it.id() }.filter { it.isNotEmpty() }
        val order = listOf(
            "lock_glance_scroll", "lock_glance_top", "lock_glance_clock", "lock_glance_date", "lock_glance_alarm", "lock_rule_card", "lock_media_tile",
            "lock_media_compact", "lock_timer_tile", "lock_glance_list", "lock_glance_rows", "lock_glance_more",
            "lock_glance_bottom", "lock_glance_emergency", "lock_glance_torch",
        )
        assertEquals(order, ids.filter { it in order })
        // The root holds the scroller and the bottom block, measured whole: nothing else, no Space.
        assertEquals(listOf("lock_glance_scroll", "lock_glance_bottom"), root.kids().map { it.id() })
        assertTrue(root.all().none { it.tagName == "Space" })
        // The scroller takes what the bottom block leaves (0dp + weight 1) and holds the top block, as tall as its
        // content; the list is the top block's last child.
        val scroll = root.byId("lock_glance_scroll")
        val top = root.byId("lock_glance_top")
        assertEquals("ScrollView", scroll.tagName)
        assertEquals(listOf("0dp", "1"), listOf(scroll.getAttributeNS(ns, "layout_height"), scroll.getAttributeNS(ns, "layout_weight")))
        assertEquals(listOf("lock_glance_top"), scroll.kids().map { it.id() })
        assertEquals("wrap_content", top.getAttributeNS(ns, "layout_height"))
        assertEquals("vertical", top.getAttributeNS(ns, "orientation"))
        assertEquals("lock_glance_list", (0 until top.childNodes.length).map { top.childNodes.item(it) }.filterIsInstance<Element>().last().id())
        // The fade only at an edge with more: the contacts row's 16 dp (CONTACTS_FADE_DP).
        assertEquals("vertical", scroll.getAttributeNS(ns, "requiresFadingEdge"))
        assertEquals("16dp", scroll.getAttributeNS(ns, "fadingEdgeLength"))
        assertEquals(com.kidslauncher.mdm.ui.home.CONTACTS_FADE_DP, scroll.dim("fadingEdgeLength"))
        // r11 §1.1 (F6): the side padding left the root for the top block and the bottom block, so the scroller is the
        // screen's width and a row slid sideways is clipped only at the screen's edge: nothing between clips it.
        assertEquals(listOf(0f, 0f, 0f), listOf(root.dim("paddingStart"), root.dim("paddingEnd"), root.dim("padding")))
        assertEquals(listOf(8f, 8f), listOf(root.dim("paddingTop"), root.dim("paddingBottom")))
        assertEquals(listOf(16f, 16f), listOf(top.dim("paddingStart"), top.dim("paddingEnd")))
        assertEquals(listOf("false", "false"), listOf(top.getAttributeNS(ns, "clipChildren"), top.getAttributeNS(ns, "clipToPadding")))
        for (id in listOf("lock_glance_list", "lock_glance_rows")) assertEquals(id, "false", root.byId(id).getAttributeNS(ns, "clipChildren"))
        val bottom = root.byId("lock_glance_bottom")
        assertEquals(listOf(16f, 16f), listOf(bottom.dim("layout_marginStart"), bottom.dim("layout_marginEnd")))
        // A row: icon, title, subtitle, time; one line each, ellipsized.
        for (id in listOf("glance_row_title", "glance_row_subtitle")) {
            val text = row.all().single { it.id() == id }
            assertEquals("1", text.getAttributeNS(ns, "maxLines"))
            assertEquals("end", text.getAttributeNS(ns, "ellipsize"))
        }
        // qa-launcher-glance-b-code Q-7: the name line wraps its content; only the name (weight 1) is cut, its suffix
        // ("· Pappa", "· 3 nye") stays whole after it.
        val line = row.all().single { it.id() == "glance_row_name_line" }
        assertEquals(listOf("wrap_content", "horizontal"), listOf(line.getAttributeNS(ns, "layout_width"), line.getAttributeNS(ns, "orientation")))
        val (name, suffix) = line.kids().let { _ -> (0 until line.childNodes.length).map { line.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>() }
        assertEquals(listOf("glance_row_title", "glance_row_suffix"), listOf(name.id(), suffix.id()))
        assertEquals(listOf("wrap_content", "1"), listOf(name.getAttributeNS(ns, "layout_width"), name.getAttributeNS(ns, "layout_weight")))
        assertEquals(listOf("wrap_content", "", "", "1"), listOf("layout_width", "layout_weight", "ellipsize", "maxLines").map { suffix.getAttributeNS(ns, it) })
        // The panel's margin and gone logic is the list's: gone only without any row (N1); the scroller is never gone.
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        assertTrue(panel.contains("list.visibility = if (layout.rows > 0) View.VISIBLE else View.GONE"))
        assertFalse(panel.contains("lockGlanceScroll.visibility") || panel.contains("scroll.visibility"))
        assertEquals("gone until the panel binds a row", "gone", root.byId("lock_glance_list").getAttributeNS(ns, "visibility"))
    }
}
