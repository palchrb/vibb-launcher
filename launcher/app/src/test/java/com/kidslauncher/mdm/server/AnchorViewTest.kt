package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.shortcuts.ShortcutItem
import com.kidslauncher.mdm.shortcuts.ShortcutKey
import com.kidslauncher.mdm.shortcuts.ShortcutsPanel
import com.kidslauncher.mdm.timerules.AskNote
import com.kidslauncher.mdm.timerules.AskPart
import com.kidslauncher.mdm.timerules.PLAIN_NOTICE
import com.kidslauncher.mdm.timerules.TimeLeftText
import com.kidslauncher.mdm.timerules.TimePart
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 100 §2.2, §2.5: the anchor notification's view ([anchorView]) and the poster's one queued refresh
 * ([AnchorPostQueue]). */
class AnchorViewTest {
    private val time = TimePart(TimeLeftText.Left(80), AskNote.Waiting(1234L), AskPart.Asked(1234L))
    private val shown = ShortcutsPanel(shown = true, hidden = false, items = listOf(ShortcutItem(ShortcutKey.TORCH)))

    @Test
    fun `the panel only while shown - otherwise the plain notice with the same time part, never nothing`() {
        assertEquals("no panel model (none yet, or it threw: Q100-M1)", AnchorView.Plain(time), anchorView(null, time))
        assertEquals(AnchorView.Plain(time), anchorView(shown.copy(shown = false), time))
        assertEquals("hidden by the kid", AnchorView.Plain(time), anchorView(ShortcutsPanel(false, true, emptyList()), time))
        assertEquals(AnchorView.Panel(shown, time), anchorView(shown, time))
        assertEquals("no budget today: the panel as design 99's", AnchorView.Panel(shown, PLAIN_NOTICE), anchorView(shown, PLAIN_NOTICE))
        // Equal inputs give equal views: an unchanged view posts nothing; a changed note is a new view (the answer).
        assertEquals(anchorView(shown, time), anchorView(shown.copy(), time.copy()))
        assertNotEquals(anchorView(shown, time), anchorView(shown, time.copy(note = AskNote.No("Pappa", 2000L))))
    }

    @Test
    fun `at most one post a second, and a change inside the second schedules exactly one later post`() {
        val queue = AnchorPostQueue()
        assertEquals("the first change posts at once", 0L, queue.request(10_000))
        assertNull("queued already", queue.request(10_000))
        queue.started()
        queue.posted(10_000)
        // Inside the second: one refresh at its end, and every later change rides on it (the trailing post).
        assertEquals(700L, queue.request(10_300))
        assertNull(queue.request(10_500))
        assertNull(queue.request(10_900))
        queue.started()
        queue.posted(11_000)
        // A change while the refresh runs (after it started) queues the next one.
        assertEquals(1_000L, queue.request(11_000))
        queue.started()
        // A quiet second: at once again; a clock that went back never waits.
        assertEquals(0L, queue.request(12_500))
        queue.started()
        queue.posted(20_000)
        assertEquals(0L, queue.request(5_000))
    }

    /** Emulator 2026-11-15: SystemUI inflates RemoteViews in a restricted context, where TextView ignores a font
     * resource - Nunito Bold drew as the default regular face. The panel's layouts use the shade's own faces. */
    @Test
    fun `the panel's layouts name no app font`() {
        val res = listOf("src", "app/src").map(::File).first { File(it, "main/res").isDirectory }
        for (name in listOf("shortcuts_panel_big.xml", "shortcuts_row.xml")) {
            val xml = File(res, "main/res/layout/$name").readText()
            assertFalse(name, "@font/" in xml)
        }
    }
}
