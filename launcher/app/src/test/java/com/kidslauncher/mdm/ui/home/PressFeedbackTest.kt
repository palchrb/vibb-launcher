package com.kidslauncher.mdm.ui.home

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/** Design 34 P2: the press never takes an event, never animates with animations off; the quiet badge. */
class PressFeedbackTest {
    private class FakeHost(
        override var animatorsEnabled: Boolean = true,
        override var tapDelayMs: Long = 0,
    ) : PressMotion.Host {
        val calls = mutableListOf<String>()
        var posted: Runnable? = null

        override fun jump(to: Float) {
            calls += "jump $to"
        }

        override fun animate(to: Float, durationMs: Long, then: (() -> Unit)?) {
            calls += "animate $to $durationMs"
            then?.invoke() // the animation ends at once in the fake
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
    }

    private val everyAction = listOf(
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL,
        MotionEvent.ACTION_OUTSIDE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
    )

    @Test
    fun `no action is ever consumed, DOWN included, animators on or off, delayed or not`() {
        for (animators in listOf(true, false)) {
            for (delay in listOf(0L, 100L)) {
                val motion = PressMotion(FakeHost(animators, delay))
                // Every order: each action after each other one.
                for (first in everyAction) {
                    for (second in everyAction) {
                        assertFalse(motion.onAction(first))
                        assertFalse(motion.onAction(second))
                    }
                }
            }
        }
    }

    @Test
    fun `the touch listener hands back what onAction answers`() {
        val source = listOf(
            "src/main/java/com/kidslauncher/mdm/ui/home/PressFeedback.kt",
            "app/src/main/java/com/kidslauncher/mdm/ui/home/PressFeedback.kt",
        ).map(::File).first { it.exists() }.readText()
        val listeners = Regex("""setOnTouchListener\s*\{[^}]*}""").findAll(source).map { it.value }.toList()
        assertEquals(1, listeners.size)
        assertTrue(listeners[0], listeners[0].trimEnd('}').trim().endsWith("motion.onAction(event.actionMasked)"))
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
    }

    @Test
    fun `nothing animates when the animator duration scale is 0`() {
        val host = FakeHost(animatorsEnabled = false)
        val motion = PressMotion(host)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_UP)
        motion.onAction(MotionEvent.ACTION_DOWN)
        motion.onAction(MotionEvent.ACTION_CANCEL)
        assertTrue(host.calls.none { it.startsWith("animate") })
        assertEquals(listOf("jump 1.0", "jump 0.0", "jump 1.0", "jump 0.0"), host.calls)
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
        assertEquals(listOf("post 100", "remove", "animate 0.0 140"), host.calls)
        assertEquals(null, host.posted)

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
    fun `the press is small and quick`() {
        assertEquals(0.94f, PressMotion.PRESSED_SCALE)
        assertEquals(0.10f, PressMotion.PRESSED_SHADE)
        assertTrue(PressMotion.PRESS_MS <= 150 && PressMotion.RELEASE_MS <= 150)
    }

    private val colours: Map<String, String> = run {
        val file = listOf("src/main/res/values/colors_kid.xml", "app/src/main/res/values/colors_kid.xml").map(::File).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("color")
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
