package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 17 source guards: the VoIP reader reads no text (privacy, like ElementDmReader), the
 * lock never sends the ring's content intent (Element's answer intent - QA #3), and the lock's
 * "system call" yield never uses `isInCall` (self-managed calls held the lock open - QA #7).
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
        )) {
            assertFalse(banned, reader.contains(banned))
        }
        // The only extra it reads is the decline action.
        assertEquals(listOf("EXTRA_DECLINE_INTENT"), Regex("Notification\\.(EXTRA_[A-Z_]+)").findAll(reader).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `a content intent is only ever kept from the call service notification, never from the ring`() {
        val reader = code(file("badges/VoipCallReader.kt"))
        assertEquals(1, Regex("contentIntent").findAll(reader).count())
        val inCallBranch = reader.substringAfter("VoipNoticeKind.IN_CALL ->").substringBefore("\n")
        assertTrue(inCallBranch, inCallBranch.contains("content = n.contentIntent"))
        val ringBranch = reader.substringAfter("VoipNoticeKind.RINGING ->").substringBefore("VoipNoticeKind.IN_CALL ->")
        assertFalse(ringBranch.contains("content"))
    }

    @Test
    fun `Answer sends the ring's full-screen intent only`() {
        val calls = code(file("lock/VoipCalls.kt"))
        val answer = calls.substringAfter("fun answer(").substringBefore("\n    }")
        assertTrue(answer, answer.contains(".fullScreen"))
        assertFalse(answer, answer.contains("content"))
        // No key, intent or notice in any log line.
        for (line in calls.lines().filter { it.contains("Log.") }) {
            assertFalse(line, line.contains("key") || line.contains("notice") || line.contains("intent ="))
        }
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
