package com.kidslauncher.mdm.ui.home

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/**
 * Design 34 P2: the press never takes an event, follows the finger, never animates with animations
 * off; the wiring and the badge z-order; the quiet badge.
 */
class PressFeedbackTest {
    private class FakeHost(
        override var animatorsEnabled: Boolean = true,
        override var tapDelayMs: Long = 0,
        /** false: an animation's `then` waits for [finishAnimation], as a running animator would. */
        val instantAnimations: Boolean = true,
    ) : PressMotion.Host {
        val calls = mutableListOf<String>()
        var posted: Runnable? = null
        var running: (() -> Unit)? = null

        override fun jump(to: Float) {
            running = null
            calls += "jump $to"
        }

        override fun animate(to: Float, durationMs: Long, then: (() -> Unit)?) {
            running = null // a new animation cancels the running one (its `then` never runs)
            calls += "animate $to $durationMs"
            if (instantAnimations) then?.invoke() else running = then
        }

        override fun postDelayed(action: Runnable, delayMs: Long) {
            calls += "post $delayMs"
            posted = action
        }

        override fun removeCallbacks(action: Runnable) {
            calls += "remove"
            if (posted === action) posted = null
        }

        fun runPosted() {
            posted?.also { posted = null }?.run()
        }

        fun finishAnimation() {
            running?.also { running = null }?.invoke()
        }
    }

    private val everyAction = listOf(
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_OUTSIDE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
    )

    private fun source(path: String): String =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()

    @Test
    fun `no action is ever consumed, DOWN included, inside or out, animators on or off, delayed or not`() {
        for (animators in listOf(true, false)) {
            for (delay in listOf(0L, 100L)) {
                val motion = PressMotion(FakeHost(animators, delay))
                // Every order: each action after each other one, inside and outside.
                for (first in everyAction) {
                    for (second in everyAction) {
                        for (inside in listOf(true, false)) {
                            assertFalse(motion.onAction(first, inside))
                            assertFalse(motion.onAction(second, !inside))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the touch listener is one expression handing back onAction's answer`() {
        val src = source("java/com/kidslauncher/mdm/ui/home/PressFeedback.kt")
        val listeners = Regex("""setOnTouchListener\s*\{([^}]*)}""").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(1, listeners.size)
        val body = listeners[0].substringAfter("->").trim()
        assertTrue(body, body.startsWith("motion.onAction(event.actionMasked,"))
        assertFalse(body, body.contains(";") || body.contains("\n") || body.contains("||") || body.contains("&&"))
    }

    @Test
    fun `the view host reads the animator setting and the tap timeout`() {
        val src = source("java/com/kidslauncher/mdm/ui/home/PressFeedback.kt")
        assertTrue(src.contains("override val animatorsEnabled get() = ValueAnimator.areAnimatorsEnabled()"))
        assertTrue(src.contains("override val tapDelayMs get() = PressMotion.tapDelay(scrolling, ViewConfiguration.getTapTimeout().toLong())"))
        assertEquals(100L, PressMotion.tapDelay(scrolling = true, tapTimeoutMs = 100))
        assertEquals(0L, PressMotion.tapDelay(scrolling = false, tapTimeoutMs = 100))
    }

    @Test
    fun `inside is the bounds test widened by the slop`() {
        assertTrue(PressMotion.inside(10f, 10f, 100, 80, 8f))
        assertTrue(PressMotion.inside(-7f, 87f, 100, 80, 8f))
        assertFalse(PressMotion.inside(-9f, 10f, 100, 80, 8f))
        assertFalse(PressMotion.inside(10f, 88f, 100, 80, 8f))
        assertFalse(PressMotion.inside(108f, 10f, 100, 80, 8f))
    }

    @Test
    fun `press and release animate in 90 and 140 ms`() {
        val host = FakeHost()
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_UP)
        assertEquals(listOf("animate 1.0 90", "animate 0.0 140"), host.calls)
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_CANCEL)
        assertEquals(listOf("animate 1.0 90", "animate 0.0 140"), host.calls)
        // At rest, a stray CANCEL or UP moves nothing (QA N1).
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_CANCEL)
        motion.onAction(MotionEvent.ACTION_UP)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `nothing animates when the animator duration scale is 0`() {
        val host = FakeHost(animatorsEnabled = false)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_UP)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_CANCEL)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_MOVE, inside = false)
        assertTrue(host.calls.none { it.startsWith("animate") })
        assertEquals(listOf("jump 1.0", "jump 0.0", "jump 1.0", "jump 0.0", "jump 1.0", "jump 0.0"), host.calls)
        // Delayed, a quick tap ends at rest without passing through the pressed state.
        val delayed = FakeHost(animatorsEnabled = false, tapDelayMs = 100)
        PressMotion(delayed).apply {
            onAction(MotionEvent.ACTION_DOWN)
            onAction(MotionEvent.ACTION_UP)
        }
        assertEquals(listOf("post 100", "remove", "jump 0.0"), delayed.calls)
    }

    @Test
    fun `in a scrolling parent the press waits the tap timeout, and a scroll's CANCEL never presses`() {
        val host = FakeHost(tapDelayMs = 100)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_MOVE)
        motion.onAction(MotionEvent.ACTION_CANCEL)
        assertEquals(listOf("post 100", "remove"), host.calls)
        assertNull(host.posted)

        // A press held past the timeout, then released.
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_DOWN)
        host.runPosted()
        motion.onAction(MotionEvent.ACTION_UP)
        assertEquals(listOf("post 100", "animate 1.0 90", "animate 0.0 140"), host.calls)

        // A quick tap still gets the press and the release.
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_UP)
        assertEquals(listOf("post 100", "remove", "animate 1.0 90", "animate 0.0 140"), host.calls)
    }

    @Test
    fun `a finger leaving the cell before the timeout never presses, not even at the lift`() {
        val host = FakeHost(tapDelayMs = 100)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_MOVE, inside = true)
        motion.onAction(MotionEvent.ACTION_MOVE, inside = false)
        host.runPosted()
        motion.onAction(MotionEvent.ACTION_MOVE, inside = true) // back in: still nothing until the next DOWN
        motion.onAction(MotionEvent.ACTION_UP, inside = true)
        assertTrue(host.calls.toString(), host.calls.none { it == "animate 1.0 90" })
        assertEquals(listOf("post 100", "remove"), host.calls)
        assertNull(host.posted)
    }

    @Test
    fun `a finger leaving a pressed cell releases once, and the lift does nothing`() {
        val host = FakeHost(tapDelayMs = 100)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        host.runPosted()
        motion.onAction(MotionEvent.ACTION_MOVE, inside = false)
        motion.onAction(MotionEvent.ACTION_MOVE, inside = false)
        motion.onAction(MotionEvent.ACTION_UP, inside = false)
        assertEquals(listOf("post 100", "animate 1.0 90", "animate 0.0 140"), host.calls)
        // The next touch works as usual.
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_DOWN)
        host.runPosted()
        motion.onAction(MotionEvent.ACTION_UP)
        assertEquals(listOf("post 100", "animate 1.0 90", "animate 0.0 140"), host.calls)
    }

    @Test
    fun `reset goes to rest at once and leaves nothing posted`() {
        val host = FakeHost(tapDelayMs = 100)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        host.runPosted()
        motion.reset()
        assertEquals("jump 0.0", host.calls.last())
        assertNull(host.posted)
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.reset()
        assertEquals(listOf("post 100", "remove", "jump 0.0"), host.calls)
        assertNull(host.posted)
        // After a reset a release has nothing to do.
        host.calls.clear()
        motion.onAction(MotionEvent.ACTION_CANCEL)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `a new touch during a quick tap's press goes back to rest first`() {
        val host = FakeHost(tapDelayMs = 100, instantAnimations = false)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_UP) // the quick tap's 90 ms press is running
        motion.onAction(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("post 100", "remove", "animate 1.0 90", "animate 0.0 140", "post 100"), host.calls)
    }

    @Test
    fun `the press is small and quick`() {
        assertEquals(0.94f, PressMotion.PRESSED_SCALE)
        assertEquals(0.10f, PressMotion.PRESSED_SHADE)
        assertTrue(PressMotion.PRESS_MS <= 150 && PressMotion.RELEASE_MS <= 150)
    }

    private fun xml(path: String): org.w3c.dom.Document {
        val file = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    @Test
    fun `the badges sit at the icon's elevation without a shadow`() {
        val styles = xml("res/values/styles.xml").getElementsByTagName("style")
        val badge = (0 until styles.length).map { styles.item(it) as Element }.single { it.getAttribute("name") == "KidBadge" }
        val items = badge.getElementsByTagName("item").let { list ->
            (0 until list.length).map { list.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
        }
        assertEquals("${PressMotion.ELEVATION_DP.toInt()}dp", items["android:elevation"])
        assertEquals("none", items["android:outlineProvider"])
        // The call badge takes the avatar's elevation in code, with no outline.
        val src = source("java/com/kidslauncher/mdm/ui/home/PressFeedback.kt")
        assertTrue(src.contains("outlineProvider = null\n            elevation = avatar.elevation"))
    }

    @Test
    fun `the tile has no whole-cell ripple and doesn't clip the icon's shadow`() {
        val raw = listOf("src/main/res/layout/item_kid_tile.xml", "app/src/main/res/layout/item_kid_tile.xml")
            .map(::File).first { it.exists() }.readText()
        assertFalse(raw.contains("selectableItemBackground"))
        val frames = xml("res/layout/item_kid_tile.xml").getElementsByTagName("FrameLayout")
        val frame = (0 until frames.length).map { frames.item(it) as Element }
            .single { it.getAttribute("android:id") == "@+id/tile_frame" }
        assertEquals("false", frame.getAttribute("android:clipChildren"))
    }

    @Test
    fun `the grid, Home's contacts and the phone book are wired`() {
        val grid = source("java/com/kidslauncher/mdm/ui/home/HomeGridAdapter.kt")
        assertTrue(grid.contains("PressFeedback.depth(icon)"))
        assertTrue(grid.contains("PressFeedback.attach(view, icon, scrolling = true)"))
        assertTrue(grid.contains("PressFeedback.cellBackground(itemView, iconPx,"))
        assertTrue(grid.contains("PressFeedback.reset(holder.itemView)"))
        assertTrue(source("java/com/kidslauncher/mdm/ui/HomeActivity.kt").contains("PressFeedback.contact(item,"))
        val book = source("java/com/kidslauncher/mdm/calls/PhoneBookAdapter.kt")
        assertTrue(book.contains("PressFeedback.contact(view,"))
        assertTrue(book.contains("PressFeedback.reset(holder.itemView)"))
        // The contact helper does all four, the focus ring included.
        val helper = source("java/com/kidslauncher/mdm/ui/home/PressFeedback.kt").substringAfter("fun contact(").substringBefore("\n    }")
        for (call in listOf("depth(avatar)", "cellBackground(item, avatarPx,", "attach(item, avatar, scrolling = true)")) {
            assertTrue(call, helper.contains(call))
        }
    }

    @Test
    fun `focus has its own visible ring above the icon`() {
        val src = source("java/com/kidslauncher/mdm/ui/home/PressFeedback.kt").substringAfter("fun cellBackground(").substringBefore("\n    }")
        assertTrue(src.contains("cell.foreground = StateListDrawable()"))
        assertTrue(src.contains("android.R.attr.state_focused"))
        assertTrue(src.contains("iconPx + 2 * gap"))
    }

    private val colours: Map<String, String> = run {
        val nodes = xml("res/values/colors_kid.xml").getElementsByTagName("color")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").takeLast(6)
        return listOf(0, 2, 4).map { rgb.substring(it, it + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            .let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
    }

    @Test
    fun `the badge is the quiet terracotta, white on it at least 4_5 to 1`() {
        val badge = colours.getValue("kid_badge")
        assertTrue(badge.equals("#B4532C", ignoreCase = true))
        val ratio = (1.05) / (luminance(badge) + 0.05)
        assertTrue("white on kid_badge: $ratio", ratio >= 4.5)
    }
}
