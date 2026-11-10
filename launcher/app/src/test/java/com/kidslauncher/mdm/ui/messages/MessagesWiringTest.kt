package com.kidslauncher.mdm.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Code QA of design 77 step 2, L7 and M3: the wiring that carries the safety, read from the sources (the launcher has
 * no Robolectric) - each message screen sends the kid to the time-rule screen before anything else when it resumes
 * and watches the lock while in front; the notification opens the thread, lets the system add no actions, and stays
 * quiet for the thread in front; `smsto:` opens a thread only on the gate's Allow.
 */
class MessagesWiringTest {
    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path")).first { it.exists() }

    /** Code only: the doc comments may say what the code must do. */
    private fun code(path: String) = src(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue(signature, start >= 0)
        var depth = 0
        var i = text.indexOf('{', start)
        val open = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        return text.substring(open)
    }

    @Test
    fun `every message screen checks the time rule first when it resumes, and watches the lock`() {
        for (screen in listOf("MessagesActivity", "ThreadActivity", "PictureActivity")) {
            val text = code("ui/messages/$screen.kt")
            val resume = body(text, "override fun onResume()").lines().map { it.trim() }.filter { it.isNotEmpty() && it != "{" }
            assertEquals(screen, "super.onResume()", resume[0])
            assertEquals(screen, "if (MessagesScreens.redirectIfLocked(this)) return", resume[1])
            assertTrue(screen, text.contains("LockWatch(this) {") && text.contains("MessagesScreens.redirectIfLocked(it)"))
            assertTrue(screen, body(text, "override fun onPause()").contains("lockWatch.stop()"))
        }
    }

    @Test
    fun `the notification opens the thread, adds no system actions, and stays quiet for the thread in front`() {
        val notifier = code("sms/SmsNotifier.kt")
        // M3: every builder of a message notification turns the system's contextual actions off.
        assertEquals(
            Regex("NotificationCompat\\.Builder\\(").findAll(notifier).count() - 1, // the public version shows only "Ny melding"
            Regex("\\.setAllowSystemGeneratedContextualActions\\(false\\)").findAll(notifier).count(),
        )
        val open = body(notifier, "private fun openIntent(")
        assertTrue(open.contains("MessagesScreens.threadIntent(context, conversationId, null).setIdentifier(conversationId)"))
        assertTrue(open.contains("PendingIntent.getActivities(") && open.contains("FLAG_IMMUTABLE"))
        val runtime = code("sms/SmsRuntime.kt")
        assertTrue(body(runtime, "private fun notifyShown(").contains("if (shown.conversation.id == threadInFront) return"))
    }

    @Test
    fun `smsto opens a thread only on the gate's Allow, never sending`() {
        val compose = body(code("sms/SmsReceivers.kt"), "class ComposeActivity")
        assertEquals(1, Regex("MessagesScreens\\.startThread\\(").findAll(compose).count())
        val allow = compose.substringAfter("verdict is SmsRules.SendVerdict.Allow ->").substringBefore("verdict is SmsRules.SendVerdict.Refused")
        assertTrue(allow.contains("MessagesScreens.startThread(this, null, verdict.number, body)"))
        assertTrue(compose.contains("Refusal.TIME_RULE ->") && compose.contains("R.string.messages_compose_not_now"))
        for (banned in listOf("SmsRuntime.send", "SendCommand(")) assertTrue(banned, !compose.contains(banned))
    }

    /** Verification L8 and N9: the thread writes the conversation id it learned back into its intent (a rebuild finds
     * it and its draft), and drops a read started for another thread. */
    @Test
    fun `the thread keeps its id across a rebuild and drops a stale read`() {
        val thread = code("ui/messages/ThreadActivity.kt")
        assertTrue(body(thread, "private fun show(").contains("intent.putExtra(EXTRA_CONVERSATION, found.id.value)"))
        assertTrue(body(thread, "private fun take(").contains("generation++"))
        assertTrue(body(thread, "private fun take(").contains("keptDraft(drafts, conversationId, number)"))
        assertTrue(body(thread, "private fun loadNow(").contains("forThread != generation"))
    }

    /** Design 81's emulator pass: the picker reopened where the last visit left its grid, so "Nylig brukt" (its first
     * row) was out of sight. Every opening scrolls the grid to the top once its lines are in. */
    @Test
    fun `the reaction picker opens at the top, where Nylig brukt is`() {
        val picker = body(code("ui/messages/ThreadActivity.kt"), "private fun showPicker(")
        assertTrue(picker.contains("pickerAdapter.submitList(items) { grid.scrollToPosition(0) }"))
    }

    @Test
    fun `a retry goes through the gate under the failed message's own id`() {
        val thread = code("ui/messages/ThreadActivity.kt")
        val retry = body(thread, "private fun retry(")
        assertTrue(retry.contains("if (!retrying.add(failed.id.value)) return"))
        assertTrue(retry.contains("MessageActions.retry({ SmsRuntime.send(app, it) }"))
        assertTrue(!retry.contains("UUID"))
    }
}
