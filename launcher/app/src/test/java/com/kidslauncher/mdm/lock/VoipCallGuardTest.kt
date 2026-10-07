package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 17 source guards: the VoIP reader reads no text (privacy, like ElementDmReader), the
 * lock never sends the ring's content intent (Element's answer intent - QA #3; since 17b the answer
 * comes only from the CallStyle answer extra/actions, picked without titles), every send passes
 * the other-call guard (17b QA #5), and the lock's "system call" yield never uses `isInCall`
 * (self-managed calls held the lock open - QA #7).
 */
class VoipCallGuardTest {
    private fun file(path: String) =
        listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path").map(::File).first { it.exists() }

    /** Code only: comments may name what the code must never do. */
    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the reader reads no title, text, person or message and logs nothing`() {
        val reader = code(file("badges/VoipCallReader.kt"))
        for (banned in listOf(
            "EXTRA_TITLE", "EXTRA_TEXT", "EXTRA_BIG_TEXT", "EXTRA_SUB_TEXT", "EXTRA_INFO_TEXT", "EXTRA_SUMMARY_TEXT",
            "EXTRA_MESSAGES", "EXTRA_CONVERSATION_TITLE", "EXTRA_CALL_PERSON", "EXTRA_PEOPLE", "MessagingStyle",
            "tickerText", "getCharSequence", "getString", "Log.",
            // 17b: an action is read by its intent and CallStyle's marker only - never its title.
            "title", "semanticAction", "remoteInputs",
        )) {
            assertFalse(banned, reader.contains(banned))
        }
        // The only extras it reads: the decline and answer actions and the call type (an int, qa-16-17 #8).
        assertEquals(
            setOf("EXTRA_DECLINE_INTENT", "EXTRA_ANSWER_INTENT", "EXTRA_CALL_TYPE"),
            Regex("Notification\\.(EXTRA_[A-Z_]+)").findAll(reader).map { it.groupValues[1] }.toSet(),
        )
        assertTrue(reader.contains("getInt(Notification.EXTRA_CALL_TYPE"))
        assertTrue(reader.contains("CallAction(it.actionIntent, it.extras?.getBoolean(KEY_CALL_STYLE_ACTION) == true)"))
    }

    @Test
    fun `a content intent is only ever kept from the call service notification, never from the ring`() {
        val reader = code(file("badges/VoipCallReader.kt"))
        assertEquals(1, Regex("contentIntent").findAll(reader).count())
        val inCallBranch = reader.substringAfter("VoipNoticeKind.IN_CALL ->").substringBefore("\n")
        assertTrue(inCallBranch, inCallBranch.contains("content = n.contentIntent"))
        assertTrue(reader.contains("VoipNoticeKind.RINGING, VoipNoticeKind.FSI_DENIED -> ring(sbn, n, kind, incoming)"))
        val ring = reader.substringAfter("private fun ring(").substringBefore("\n    }")
        assertTrue(ring, ring.contains("pickAnswerIntent("))
        assertFalse(ring, ring.contains("content"))
    }

    @Test
    fun `the lock sends the ring's full-screen intent, Answer the CallStyle answer (else the FSI) - never a content intent`() {
        val calls = code(file("lock/VoipCalls.kt"))
        val ringScreen = calls.substringAfter("fun showRingScreen(").substringBefore("\n    }")
        assertTrue(ringScreen, ringScreen.contains("?.fullScreen") && ringScreen.contains("sendRing(context, intent)"))
        assertFalse(ringScreen, ringScreen.contains("answer") || ringScreen.contains("content"))
        val answer = calls.substringAfter("fun answer(").substringBefore("\n    }")
        assertTrue(answer, answer.contains("notice.answer") && answer.contains("notice.fullScreen"))
        assertEquals(answer, 2, Regex("sendRing\\(context, ").findAll(answer).count())
        assertFalse(answer, answer.contains("content") || Regex("\\bsend\\(").containsMatchIn(answer))
        // qa-17b-code #4: a ring's call-starting intents pass the calls gate again at the send.
        val sendRing = calls.substringAfter("private fun sendRing(").substringBefore("\n    }")
        assertTrue(sendRing, sendRing.indexOf("eligible(app)") in 0 until sendRing.indexOf("send(context, intent)"))
        // 17b QA #5: one way out, behind the other-call guard.
        assertEquals(1, Regex("\\.send\\(context, 0,").findAll(calls).count())
        val send = calls.substringAfter("private fun send(").substringBefore("\n    }")
        assertTrue(send, send.indexOf("voipCallOrEmergency(context)") in 0 until send.indexOf(".send(context, 0,"))
        // No key, intent or notice in any log line.
        for (line in calls.lines().filter { it.contains("Log.") }) {
            assertFalse(line, line.contains("key") || line.contains("notice") || line.contains("intent ="))
        }
    }

    @Test
    fun `the lock never sets turn-screen-on - the wake activity's manifest does (qa-16-17 2)`() {
        for (name in listOf("lock/PinLockActivity.kt", "lock/PinLockRuntime.kt", "lock/VoipCalls.kt", "lock/VoipWakeActivity.kt")) {
            assertFalse(name, code(file(name)).contains("setTurnScreenOn"))
        }
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        val wake = manifest.substringAfter("android:name=\".lock.VoipWakeActivity\"").substringBefore("/>")
        for (attr in listOf("android:turnScreenOn=\"true\"", "android:exported=\"false\"", "android:taskAffinity=\"\${applicationId}.pinlock\"", "android:noHistory=\"true\"")) {
            assertTrue(attr, wake.contains(attr))
        }
        assertFalse("never direct-boot-aware", wake.contains("directBootAware"))
        val lock = manifest.substringAfter("android:name=\".lock.PinLockActivity\"").substringBefore("/>")
        assertFalse(lock.contains("turnScreenOn"))
    }

    @Test
    fun `the lock yields to managed calls only - never isInCall`() {
        val lockSources = file("lock").walk().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue(lockSources.size >= 10)
        for (source in lockSources) {
            assertFalse(source.name, Regex("\\.isInCall\\b").containsMatchIn(code(source)))
        }
        assertTrue(code(file("lock/PinLockRuntime.kt")).contains("isInManagedCall"))
    }
}
