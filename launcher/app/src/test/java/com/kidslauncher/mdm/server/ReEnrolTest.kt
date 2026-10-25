package com.kidslauncher.mdm.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design r10 §5 (QA R10-5): "Ny oppsettkode" only in the deadlock - both halves, and a server to enrol with. */
class ReEnrolTest {
    private val url = "https://phone.example.ts.net"
    private val untilSync = ParentPinStatus.NOT_CONFIGURED.copy(configured = true, untilSync = true)
    private val checkable = ParentPinStatus.NOT_CONFIGURED.copy(configured = true)

    @Test
    fun `the button shows only with the token unreadable and the parent PIN unusable`() {
        assertTrue(showReEnrol(Sealed.Unreadable, untilSync, url))
        assertTrue(showReEnrol(Sealed.Unreadable, ParentPinStatus.NOT_CONFIGURED, url))
        // One half alone is no deadlock: the parent code opens the lock and Settings' enrolment works.
        assertFalse(showReEnrol(Sealed.Unreadable, checkable, url))
        assertFalse(showReEnrol(Sealed.Unreadable, checkable.copy(lockoutMs = 60_000L), url))
        assertFalse(showReEnrol(Sealed.Unreadable, checkable.copy(foreignAccess = true), url))
        assertFalse(showReEnrol(Sealed.Value("ab"), untilSync, url))
        assertFalse(showReEnrol(Sealed.Absent, untilSync, url))
        assertFalse(showReEnrol(Sealed.Unreadable, untilSync, null))
        assertFalse(showReEnrol(Sealed.Unreadable, untilSync, " "))
    }

    @Test
    fun `both parent-code dialogs carry the button and look again after every result`() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        for (path in listOf("ui/ParentPinDialog.kt", "lock/PinLockActivity.kt")) {
            val text = File(main, "java/com/kidslauncher/mdm/$path").readText()
            assertTrue(path, ".setNeutralButton(R.string.re_enrol_button, null)" in text)
            assertTrue(path, "ReEnrolDialog.bind(dialog, " in text)
            val afterResult = text.substringAfter("okAfter(result))\n").substringBefore("}")
            assertTrue(path, "reEnrol()" in afterResult)
        }
        // It never unlocks: the code dialog enrols and syncs, nothing else.
        val dialog = File(main, "java/com/kidslauncher/mdm/ui/ReEnrolDialog.kt").readText()
        for (never in listOf("unlock(", "ParentPin.check(", "checkParentCode(", "LockEvent.")) assertFalse(never, never in dialog)
        assertTrue("SyncRunner.request(app, \"re_enrolled\")" in dialog)
    }
}
