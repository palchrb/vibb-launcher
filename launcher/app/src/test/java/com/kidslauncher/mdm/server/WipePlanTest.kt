package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Security review M8: the remote wipe uses the call that works for a device owner on API 34+. */
class WipePlanTest {
    @Test
    fun `API 34 and later use wipeDevice`() {
        assertEquals(WipeCall.WIPE_DEVICE, wipeCall(34))
        assertEquals(WipeCall.WIPE_DEVICE, wipeCall(36))
        assertEquals(WipeCall.WIPE_DEVICE, wipeCall(37))
        assertEquals("before 34 wipeDevice doesn't exist", WipeCall.WIPE_DATA, wipeCall(33))
    }

    @Test
    fun `factory reset protection stays`() {
        // DevicePolicyManager.WIPE_EXTERNAL_STORAGE = 1, WIPE_RESET_PROTECTION_DATA = 2, WIPE_EUICC = 4.
        assertEquals(0, WIPE_FLAGS and 2)
        assertEquals(0, WIPE_FLAGS)
    }

    @Test
    fun `a refusal is a failure the parent sees, a dying system is not`() {
        assertEquals(WipeOutcome.STARTED, wipeOutcome(null))
        assertEquals(WipeOutcome.FAILED, wipeOutcome("SecurityException"))
        assertEquals(WipeOutcome.FAILED, wipeOutcome("IllegalStateException"))
        assertEquals(WipeOutcome.FAILED, wipeOutcome("UnsupportedOperationException"))
        assertEquals("API 33+", WipeOutcome.GOING_DOWN, wipeOutcome("DeadSystemRuntimeException"))
        assertEquals(WipeOutcome.GOING_DOWN, wipeOutcome("DeadSystemException"))
        assertEquals(WipeOutcome.GOING_DOWN, wipeOutcome("RuntimeException", "DeadSystemException"))
        assertEquals(WipeOutcome.GOING_DOWN, wipeOutcome("DeadObjectException"))
        assertEquals(WipeOutcome.FAILED, wipeOutcome("RuntimeException", "SecurityException"))

        val failed = wipeResultMessage(WipeOutcome.FAILED, "SecurityException: User 0 is a system user and cannot be removed")
        assertTrue(failed.startsWith("wipe failed, the phone keeps its data: SecurityException"))
        assertTrue(wipeResultMessage(WipeOutcome.FAILED, "x".repeat(500)).length <= 200)
        assertFalse(wipeResultMessage(WipeOutcome.STARTED).contains("failed"))
    }

    /** The dispatch reports what the wipe call did; LocateCommands no longer calls `wipeData(0)`. */
    @Test
    fun `the wipe goes through wipeCall and reports a refusal`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val locate = File(src, "main/java/com/kidslauncher/mdm/server/LocateCommands.kt").readText()
        assertTrue("WipeCall.WIPE_DEVICE -> dpm.wipeDevice(WIPE_FLAGS)" in locate)
        assertFalse("wipeData(0)" in locate)
        val sync = File(src, "main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        val wipe = sync.substringAfter("\"wipe\" -> {").substringBefore("\n        }\n")
        assertTrue("reportCommandResult(context, api, pending.id, success = outcome != WipeOutcome.FAILED" in wipe)
        val admin = File(src, "main/res/xml/device_admin.xml").readText()
        assertTrue("the admin declares the wipe policy", "<wipe-data />" in admin)
    }
}
