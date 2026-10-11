package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 24 §2/§7: every parent-PIN check goes through [ParentPin], and only it starts the windows
 * a correct PIN opens - a source scan, like KidScreensEscapeTest.
 */
class ParentPinCallersTest {
    private val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm"))
        .first { it.exists() }

    /** Every Kotlin source, code only (comments may name what the code must never do). */
    private val sources: Map<String, String> = root.walk().filter { it.isFile && it.name.endsWith(".kt") }.associate { file ->
        file.relativeTo(root).path to file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    /** The files that call [token] - not ones that only declare it (`fun name(`). */
    private fun callers(token: String): Set<String> = sources.filter { (_, text) ->
        Regex("(?<!fun )${Regex.escape(token)}").containsMatchIn(text)
    }.keys.map { File(it).name }.toSet()

    @Test
    fun `only the vault and the kid lock hash a PIN`() {
        assertTrue(sources.size > 100)
        // Design 24b: the parent PIN through the vault (behind ParentPin's gate), the kid's lock on its own.
        assertEquals(setOf("PinLockRuntime.kt"), callers("PinHash.verify("))
        assertEquals(setOf("PinVault.kt"), callers("PinHash.derive("))
        assertEquals(setOf("ParentPin.kt"), callers("PinVault.of("))
    }

    @Test
    fun `only the gate starts an override, a pause or install mode`() {
        assertEquals(setOf("ParentPin.kt"), callers("OfflineOverride.activate("))
        // And the parent app's `start_pause` (24b fix round, QA High 1): no PIN on the phone.
        assertEquals(setOf("ParentPin.kt", "MdmSyncWorker.kt"), callers("RestrictionsPause.start("))
        assertTrue("\"start_pause\" -> {" in sources.getValue("server/MdmSyncWorker.kt"))
        assertEquals(setOf("ParentPin.kt"), callers("startInstallMode("))
    }

    @Test
    fun `the old counter is read only by the store's migration`() {
        for (token in listOf("offlineOverrideFailedAttempts", "offlineOverrideLockedUntil")) {
            assertEquals(token, setOf("ParentPinStore.kt"), sources.filterValues { token in it }.keys.map { File(it).name }.toSet())
        }
        for (token in listOf("offline_override_failed_attempts", "offline_override_locked_until")) {
            assertEquals(token, emptySet<String>(), sources.filterValues { token in it }.keys)
        }
    }

    @Test
    fun `the old check and its lockout are gone`() {
        for (token in listOf("verifyPin(", "isLockedOut(")) {
            assertEquals(token, emptySet<String>(), callers(token))
        }
    }

    @Test
    fun `no PIN result is a toast`() {
        // Toasts don't show while a budget sets DISALLOW_CREATE_WINDOWS (QA #5): the four dialogs
        // and the PIN lock show every result in their error line.
        val pinTexts = listOf(
            "lock_unlock_code_wrong", "lock_unlock_code_locked_out", "lock_unlock_code_not_configured",
            "settings_gate_no_pin", "toast_mdm_pause_needs_pin", "toast_play_install_mode_needs_pin",
            "pin_lock_parent_wrong", "pin_lock_parent_locked_out", "parent_pin_",
        )
        for ((path, text) in sources) {
            for (toast in Regex("Toast\\.makeText\\(([^)]*)\\)").findAll(text)) {
                for (pinText in pinTexts) {
                    assertTrue("$path: ${toast.value}", pinText !in toast.value)
                }
            }
        }
        for (file in listOf("ui/ParentPinDialog.kt", "ui/LockActivity.kt", "lock/PinLockActivity.kt")) {
            assertTrue(file, "Toast" !in sources.getValue(file))
        }
    }

    @Test
    fun `the parent PIN is refused while an app could read it - before the keypad and before the verify (24b fix round)`() {
        val pin = sources.getValue("server/ParentPin.kt")
        for (fn in listOf("fun check(", "fun status(")) {
            val body = pin.substringAfter(fn).substringBefore("\n    }\n")
            val refusal = body.indexOf("ForeignAccess.blocksParentPin(")
            assertTrue(fn, refusal >= 0)
            // Before anything is counted or verified.
            assertTrue(fn, "checkParentPin(" !in body || refusal < body.indexOf("checkParentPin("))
        }
        // Every entry opens through the one pre-check and shows its line: the four dialogs and the PIN lock.
        assertTrue("status.foreignAccess ->" in sources.getValue("ui/ParentPinDialog.kt"))
        assertTrue("ParentPinResult.ForeignAccessOn ->" in sources.getValue("ui/ParentPinDialog.kt"))
        assertTrue("ParentPinDialog.opening(" in sources.getValue("lock/PinLockActivity.kt"))
        for (file in listOf("ui/LockActivity.kt", "ui/settings/SettingsActivity.kt")) {
            assertTrue(file, "ParentPinDialog.show(" in sources.getValue(file))
        }
    }

    @Test
    fun `the event upload never wakes the sync runner`() {
        assertTrue("SyncRunner" !in sources.getValue("server/ParentPinEvents.kt"))
    }
}
