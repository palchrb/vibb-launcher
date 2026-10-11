package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.music.MusicSession
import com.kidslauncher.mdm.music.SessionPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The PIN lock's two stages with Vibb music (design 21 §3, mockup Lock.dc.html v12). */
class LockMediaTest {
    private fun session(play: SessionPlay) = MusicSession("me.vibb.music", play, "Reven og månen", "Kapittel 3: Månen", 0, 0, 1f, 0)
    private val playing = session(SessionPlay.PLAYING)
    private val paused = session(SessionPlay.PAUSED)

    @Test
    fun `the lock comes up on the media view only while music plays`() {
        assertEquals(LockStage.MEDIA, lockStageAtLockUp(playing))
        assertEquals("paused: the keypad as before", LockStage.KEYPAD, lockStageAtLockUp(paused))
        assertEquals(LockStage.KEYPAD, lockStageAtLockUp(null))
    }

    @Test
    fun `the stage is decided when the lock comes up with the screen on, not when it comes back`() {
        assertTrue("first time, screen on", lockStageDue(interactive = true, decidedAtScreenOffs = null, screenOffs = 3))
        assertFalse("started at a screen-off: decided at the screen-on", lockStageDue(interactive = false, decidedAtScreenOffs = null, screenOffs = 3))
        assertTrue("a screen-off since", lockStageDue(interactive = true, decidedAtScreenOffs = 3, screenOffs = 4))
        assertFalse("back from the emergency dialer or an alarm", lockStageDue(interactive = true, decidedAtScreenOffs = 4, screenOffs = 4))
    }

    @Test
    fun `the media view stays when the kid pauses and goes with the session`() {
        assertEquals(LockStage.MEDIA, lockStageAfterChange(LockStage.MEDIA, paused))
        assertEquals(LockStage.MEDIA, lockStageAfterChange(LockStage.MEDIA, playing))
        assertEquals(LockStage.KEYPAD, lockStageAfterChange(LockStage.MEDIA, null))
        assertEquals("the keypad never jumps back by itself", LockStage.KEYPAD, lockStageAfterChange(LockStage.KEYPAD, playing))
    }

    @Test
    fun `the keypad has the back arrow and the compact row only while a session is live`() {
        assertEquals(LockMediaUi(mediaView = true, backArrow = false, compactRow = false), lockMediaUi(LockStage.MEDIA, playing))
        assertEquals(LockMediaUi(mediaView = false, backArrow = true, compactRow = true), lockMediaUi(LockStage.KEYPAD, paused))
        val plain = LockMediaUi(mediaView = false, backArrow = false, compactRow = false)
        assertEquals("no session: exactly the old keypad", plain, lockMediaUi(LockStage.KEYPAD, null))
        assertEquals(plain, lockMediaUi(LockStage.MEDIA, null))
        assertTrue(lockMediaTicks(lockMediaUi(LockStage.MEDIA, playing), playing))
        assertFalse(lockMediaTicks(lockMediaUi(LockStage.MEDIA, paused), paused))
        assertFalse(lockMediaTicks(lockMediaUi(LockStage.KEYPAD, playing), playing))
    }
}

/**
 * The media view never weakens the PIN lock (design 21 step 4): its glue only plays, pauses and
 * skips through the session's transport controls, moves between the stages and calls the lock's own
 * emergency button - no app, session activity, setting, notification or intent of any kind.
 */
class LockMediaGuardTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private val panel = file("java/com/kidslauncher/mdm/music/MusicLockPanel.kt").readText()

    @Test
    fun `the lock's media glue opens nothing`() {
        val code = panel.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
        for (forbidden in listOf(
            "startActivity", "sessionActivity", "openSessionActivity", "openPlayerResuming", "PendingIntent", ".send(",
            "Intent", "Settings", "Notification", "transportControls", "dispatchMediaButton", "LaunchIntent",
        )) {
            assertFalse("MusicLockPanel must not use $forbidden", code.contains(forbidden))
        }
        val calls = Regex("MusicSessions\\.(\\w+)").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("addListener", "removeListener", "reading", "usable", "playPause", "next", "previous"), calls)
    }

    @Test
    fun `the session controls are only play, pause and skip`() {
        val sessions = file("java/com/kidslauncher/mdm/music/MusicSessions.kt").readText()
        val controls = Regex("(?:transportControls\\??|\\bcontrols)\\.(\\w+)\\(").findAll(sessions).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("play", "pause", "skipToNext", "skipToPrevious"), controls)
    }

    @Test
    fun `the media view's touchable parts are its controls, each wired in the glue`() {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file("res/layout/view_lock_media.xml")).documentElement
        fun all(e: Element): List<Element> = listOf(e) + (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().flatMap(::all)
        val touchable = all(root).filter { it.tagName in setOf("Button", "ImageButton") || it.getAttributeNS(ns, "clickable") == "true" }
            .map { it.getAttributeNS(ns, "id").substringAfter("/") }.toSet()
        assertEquals(
            setOf("lock_media_root", "lock_media_previous", "lock_media_play", "lock_media_next", "lock_media_unlock", "lock_media_emergency"),
            touchable,
        )
        // The root only blocks touches to the keypad below - no TalkBack node (qa-21-step4-code L7);
        // every control has its listener.
        assertEquals("no", root.getAttributeNS(ns, "importantForAccessibility"))
        for (id in touchable - "lock_media_root") {
            val binding = id.split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar(Char::uppercase) }.joinToString("")
            assertTrue("$binding is wired", panel.contains("media.$binding.setOnClickListener"))
        }
        assertFalse(panel.contains("media.root.setOnClickListener"))
        // The emergency button is the lock's own (its confirm dialog, PinLockRuntime.emergencyFlowStarted).
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("{ binding.pinEmergency.performClick() }"))
    }
}

/**
 * The media view fits 320x569 dp with a 48 dp status bar and 3-button navigation, its emergency
 * call included (a rough measure from the XML: text at 1.4 x its size, the item on two lines).
 */
class LockMediaLayoutTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private val root: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("res/layout/view_lock_media.xml")).documentElement

    private fun Element.dim(name: String): Float = getAttributeNS(ns, name).removeSuffix("dp").removeSuffix("sp").toFloatOrNull() ?: 0f
    private fun Element.kids() = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
        .filter { it.getAttributeNS(ns, "visibility") != "gone" }

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

    private fun parse(name: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("res/layout/$name.xml")).documentElement

    private fun Element.all(): List<Element> = listOf(this) + kids().flatMap { it.all() }

    /** The width of a horizontal row's fixed children with their margins (dp). */
    private fun fixedWidth(row: Element): Float = row.dim("paddingStart") + row.dim("paddingEnd") + 2 * row.dim("padding") +
        row.kids().sumOf { (it.dim("layout_marginStart") + it.dim("layout_marginEnd") + it.dim("layout_width")).toDouble() }.toFloat()

    @Test
    fun `nothing is squeezed at 320, 360 and 411 dp - the tile's controls, Home's card, the compact row`() {
        for (screen in listOf(320f, 360f, 411f)) {
            // The tile's previous / play / next, inside the view's and the tile's padding.
            val tile = root.all().first { it.getAttributeNS(ns, "id") == "@+id/lock_media_tile" }
            val controls = root.all().first { it.getAttributeNS(ns, "id") == "@+id/lock_media_play" }.parentNode as Element
            val room = screen - root.dim("paddingStart") - root.dim("paddingEnd") - 2 * tile.dim("padding")
            assertTrue("$screen: controls ${fixedWidth(controls)} in $room", fixedWidth(controls) <= room)
            // Home's card (16 dp sides on Home) and the keypad's compact row (16 dp sides on the lock) keep
            // at least 120 dp for their text.
            for (layout in listOf("view_home_music_card", "view_lock_media_row")) {
                val card = parse(layout)
                assertTrue("$screen: $layout", screen - 32f - fixedWidth(card) >= 120f)
            }
        }
    }

    @Test
    fun `the media view fits the Jelly Star with both bars at their largest`() {
        val needed = root.dim("paddingTop") + root.dim("paddingBottom") +
            root.kids().sumOf { (it.dim("layout_marginTop") + it.dim("layout_marginBottom") + height(it)).toDouble() }.toFloat()
        assertTrue("needs $needed dp", needed <= 569f - 48f - 48f)
        assertEquals("the emergency call is last, under Lås opp", listOf("lock_media_unlock", "lock_media_emergency"),
            root.kids().takeLast(2).map { it.getAttributeNS(ns, "id").substringAfter("/") })
    }
}
