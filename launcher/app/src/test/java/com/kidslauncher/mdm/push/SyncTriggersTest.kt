package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.ParentPinResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Launcher round 6 §1: the parent PIN asks for a sync at once, after the verdict. */
class SyncTriggersTest {
    @Test
    fun `a use, a wrong try and a lockout that starts now sync - nothing else does`() {
        for (running in listOf(false, true)) {
            assertTrue(SyncTriggers.pin(ParentPinResult.Ok, running))
            assertTrue(SyncTriggers.pin(ParentPinResult.Wrong(triesLeft = 3, nextLockoutMs = 900_000L), running))
            assertFalse(SyncTriggers.pin(ParentPinResult.TooShort, running))
            assertFalse(SyncTriggers.pin(ParentPinResult.NotConfigured, running))
            assertFalse(SyncTriggers.pin(ParentPinResult.Unavailable(storageFull = true), running))
            assertFalse(SyncTriggers.pin(ParentPinResult.Unavailable(storageFull = false, untilSync = true), running))
            assertFalse(SyncTriggers.pin(ParentPinResult.ForeignAccessOn, running))
        }
        assertTrue(SyncTriggers.pin(ParentPinResult.LockedOut(900_000L), lockoutAlreadyRunning = false))
        // A kid hammering OK during a lockout (or an unreadable state, which became one): no event, no sync.
        assertFalse(SyncTriggers.pin(ParentPinResult.LockedOut(900_000L), lockoutAlreadyRunning = true))
    }

    @Test
    fun `the glue reads the lockout first and requests after the lock, never inside the pure step`() {
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        val pin = File(root, "server/ParentPin.kt").readText()
        val check = pin.substringAfter("fun check(").substringBefore("\n    }\n")
        val status = check.indexOf("lockoutAlreadyRunning = parentPinStatus(")
        val verdict = check.indexOf("checkParentPin(")
        val request = check.indexOf("SyncRunner.request(app, \"pin_event\")")
        assertTrue(status in 0 until verdict)
        // After the synchronized block's end, so the request never runs under ParentPin's lock.
        assertTrue(request > check.lastIndexOf("\n        }\n"))
        assertTrue("if (SyncTriggers.pin(result, lockoutAlreadyRunning))" in check)
        // Exactly one request, in check: never in checkParentPin, whose count-first save is provisional.
        assertEquals(1, Regex("SyncRunner\\.request\\(").findAll(pin).count())
    }
}
