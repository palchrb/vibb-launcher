package com.kidslauncher.mdm.crash

import com.kidslauncher.mdm.server.dto.CrashReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashSignatureTest {
    private fun frame(cls: String, method: String, line: Int) = StackTraceElement(cls, method, "${cls.substringAfterLast('.')}.kt", line)

    private fun crash(message: String, line: Int = 10, cause: Throwable? = null) =
        IllegalStateException(message, cause).apply {
            stackTrace = arrayOf(
                frame("com.kidslauncher.mdm.calls.PhoneBookActivity", "call", line),
                frame("android.app.Activity", "performCreate", 8000),
            )
        }

    @Test
    fun `the trace has class names and frames but never the message`() {
        val sig = crashSignature(crash("calling +4791234567 for Mamma", cause = RuntimeException("https://secret.example/token=abc")))
        assertFalse(sig.trace.contains("4791234567"))
        assertFalse(sig.trace.contains("Mamma"))
        assertFalse(sig.trace.contains("secret"))
        assertTrue(sig.trace.startsWith("java.lang.IllegalStateException\n"))
        assertTrue(sig.trace.contains("    at com.kidslauncher.mdm.calls.PhoneBookActivity.call(PhoneBookActivity.kt:10)\n"))
        assertTrue(sig.trace.contains("Caused by: java.lang.RuntimeException\n"))
        assertTrue(Regex("[0-9a-f]{16}").matches(sig.hash))
    }

    @Test
    fun `the hash ignores messages and line numbers but not the code path`() {
        assertEquals(crashSignature(crash("a", line = 10)).hash, crashSignature(crash("b", line = 99)).hash)
        val other = IllegalArgumentException("a").apply { stackTrace = crash("a").stackTrace }
        assertNotEquals(crashSignature(crash("a")).hash, crashSignature(other).hash)
    }

    @Test
    fun `long traces and cause loops are cut`() {
        val deep = RuntimeException("x").apply {
            stackTrace = Array(100) { frame("com.example.Deep", "level$it", it) }
        }
        val sig = crashSignature(deep)
        assertEquals(MAX_FRAMES, Regex("    at ").findAll(sig.trace).count())
        assertTrue(sig.trace.contains("... 88 more"))
        assertTrue(sig.trace.length <= MAX_TRACE_CHARS)
        // A cause chain that loops back on itself ends.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertTrue(crashSignature(a).trace.split("Caused by").size <= MAX_CAUSES + 1)
    }

    @Test
    fun `the same crash counts up, at most five are kept`() {
        val sig = crashSignature(crash("a"))
        var stored = recordCrash(emptyList(), sig, 1_000, 7)
        stored = recordCrash(stored, sig, 2_000, 8)
        assertEquals(listOf(CrashReport(sig.hash, sig.trace, 2, 1_000, 2_000, 8)), stored)
        for (i in 0 until 6) {
            stored = recordCrash(stored, CrashSignature("%016x".format(i), "t$i"), 3_000L + i, 8)
        }
        assertEquals(MAX_STORED_CRASHES, stored.size)
        // Newest first; the one seen longest ago (the first crash) went.
        assertEquals("0000000000000005", stored.first().hash)
        assertFalse(stored.any { it.hash == sig.hash })
    }

    @Test
    fun `an upload clears exactly what was sent`() {
        val one = CrashReport("0000000000000001", "t", 1, 1, 1, 1)
        val two = CrashReport("0000000000000002", "t", 1, 2, 2, 1)
        val twoAgain = two.copy(count = 2, lastAtMs = 3)
        assertEquals(listOf(twoAgain), afterUpload(listOf(twoAgain, one), listOf(one, two)))
    }

    @Test
    fun `the phone has no crash screen and no share or email for crashes`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("ReportCrashActivity"))
        val crashCode = File(main, "java/com/kidslauncher/mdm/crash").walk().filter { it.isFile }.map { it.readText() }.joinToString()
        for (banned in listOf("Intent", "ACTION_SEND", "mailto", "ClipboardManager", "startActivity")) {
            assertFalse(banned, crashCode.contains(banned))
        }
    }
}
