package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.ui.PARENT_PIN_MAX_DIGITS
import com.kidslauncher.mdm.ui.appendParentDigit
import com.kidslauncher.mdm.ui.parentPinDots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 24b §6: the relock's wiring, as a source scan (like ParentPinCallersTest) - the starters
 * record the window before lifting anything, the check never blocks its caller, and every
 * parent-PIN dialog types on our own keypad.
 */
class WindowRelockCallersTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val root = File(main, "java/com/kidslauncher/mdm")

    /** Every Kotlin source, code only (comments may name what the code must never do). */
    private val sources: Map<String, String> = root.walk().filter { it.isFile && it.name.endsWith(".kt") }.associate { file ->
        file.relativeTo(root).path to file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    private fun body(path: String, signature: String): String {
        val text = sources.getValue(path)
        val start = text.indexOf(signature)
        assertTrue("$path: $signature", start >= 0)
        var depth = 0
        var i = text.indexOf('{', start)
        val open = i
        while (i < text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) break
            i++
        }
        return text.substring(open, i + 1)
    }

    private fun assertBefore(text: String, first: String, then: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertTrue("'$first' missing", a >= 0)
        assertTrue("'$then' missing", b >= 0)
        assertTrue("'$first' must come before '$then'", a < b)
    }

    @Test
    fun `both starters record the window before they lift anything`() {
        val activate = body("server/OfflineOverride.kt", "fun activate(")
        for (lift in listOf("offlineOverrideActive(true)", "offlineOverrideExpiresAt(", "AppEnforcer.apply(")) {
            assertBefore(activate, "WindowRelock.opened(context, WindowKind.OVERRIDE", lift)
        }
        val start = body("server/RestrictionsPause.kt", "fun start(")
        for (lift in listOf("restrictionsPaused(true)", "restrictionsPausedUntil(", "reapply(")) {
            assertBefore(start, "WindowRelock.opened(context, WindowKind.PAUSE", lift)
        }
        // The starters stay the gate's (design 24): nobody else opens a window.
        assertEquals(setOf("OfflineOverride.kt", "RestrictionsPause.kt", "WindowRelock.kt"), sources.filterValues { "WindowRelock.opened(" in it || "fun opened(" in it }.keys.map { File(it).name }.toSet())
    }

    @Test
    fun `the check is never awaited - it hands off to the worker`() {
        val text = sources.getValue("server/WindowRelock.kt")
        assertTrue(Regex("(?<!suspend )fun check\\(").containsMatchIn(text))
        assertTrue("trySend" in body("server/WindowRelock.kt", "fun check("))
        for ((path, source) in sources.filterValues { "WindowRelock.check(" in it }) {
            assertTrue(path, "runBlocking" !in source)
        }
        // The sync waits for its own relock - it runs on the sync's coroutine, never the main thread.
        assertTrue(Regex("suspend fun relockInSync\\(").containsMatchIn(text))
    }

    @Test
    fun `only the relock removes a credential, certificates or proxies`() {
        for (token in listOf("resetPasswordWithToken(", "uninstallAllUserCaCerts(", "updateNetwork(")) {
            assertEquals(token, setOf("WindowRelock.kt"), sources.filterValues { token in it }.keys.map { File(it).name }.toSet())
        }
    }

    @Test
    fun `the parent-PIN dialogs have no EditText - our own keypad, out of screenshots`() {
        for (file in listOf("ui/ParentPinDialog.kt", "lock/PinLockActivity.kt", "ui/LockActivity.kt", "ui/settings/SettingsActivity.kt")) {
            assertTrue(file, "EditText" !in sources.getValue(file))
        }
        for (layout in listOf("dialog_offline_override_pin.xml", "dialog_pin_parent_code.xml", "include_parent_pin_keypad.xml")) {
            val xml = File(main, "res/layout/$layout").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertTrue(layout, "EditText" !in xml)
        }
        assertTrue("include_parent_pin_keypad" in File(main, "res/layout/dialog_offline_override_pin.xml").readText())
        assertTrue("include_parent_pin_keypad" in File(main, "res/layout/dialog_pin_parent_code.xml").readText())
        for (file in listOf("ui/ParentPinDialog.kt", "lock/PinLockActivity.kt")) {
            assertTrue(file, "ParentPinKeypad.secure(dialog)" in sources.getValue(file))
            assertTrue(file, "ParentPinKeypad.bind(" in sources.getValue(file))
        }
    }

    @Test
    fun `the keypad takes up to 32 digits and nothing else`() {
        var pin = ""
        repeat(40) { pin = appendParentDigit(pin, '7') }
        assertEquals(PARENT_PIN_MAX_DIGITS, pin.length)
        assertEquals("12", appendParentDigit(appendParentDigit(appendParentDigit("1", 'a'), '2'), '-'))
        assertEquals("", parentPinDots(0))
        assertEquals("●●●●●●", parentPinDots(6))
        assertEquals("●●●●●●●●●●●● +3", parentPinDots(15))
    }
}
