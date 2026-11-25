package com.kidslauncher.mdm.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design r10's Android glue the JVM can't run, pinned by source (code QA Q-1, Q-6, Q-8, Q-11). */
class SealedGlueTest {
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()
    private fun body(text: String, fn: String) = text.substringAfter("fun $fn(").substringBefore("\n    fun ").substringBefore("\n    /**")

    @Test
    fun `no kid PIN material on the phone - the glue keeps none (design 132)`() {
        val runtime = src("lock/PinLockRuntime.kt")
        val configure = body(runtime, "configure")
        assertTrue(configure.contains("val kidPin = policy?.kidLock != null"))
        assertTrue(configure.contains("PinLockStore.saveConfig(app, active, why)"))
        assertTrue(body(runtime, "guardTripped").contains("PinLockStore.saveConfig(app, active = false, inactive = LockInactive.CRASH_GUARD)"))
        assertFalse(src("lock/PinLockStore.kt").contains("SealedPrefs"))
    }

    @Test
    fun `the enrolment brings the tailnet up first, and the dialog keeps a consumed code's result`() {
        val enroll = body(src("server/Enrollment.kt"), "enroll")
        val tsnet = enroll.indexOf("TsnetClient.connectFromPreferences(context)")
        assertTrue(tsnet in 0 until enroll.indexOf("createMdmApi(serverUrl)"))
        assertTrue(enrollFailureOf(401).refused && enrollFailureOf(404).refused)
        assertFalse(enrollFailureOf(500).refused || enrollFailureOf(503).refused)
        assertTrue(enrolFailureRefused(enrollFailureOf(400)))
        assertFalse(enrolFailureRefused(java.io.IOException("no route")))
        assertFalse(enrolFailureRefused(null))
        val dialog = src("ui/ReEnrolDialog.kt")
        assertTrue(dialog.contains("ENROL_SCOPE.launch {"))
        assertFalse(body(dialog, "open").contains("scope.launch"))
        assertTrue(dialog.contains("if (!dialog.isShowing) return@withContext"))
        // Q-1, and design 129b D12: the line from `enrolFailureLine` - waiting shows the parent's line and the number.
        assertTrue(dialog.contains("val line = enrolFailureLine(result.exceptionOrNull())"))
        org.junit.Assert.assertEquals(com.kidslauncher.mdm.R.string.re_enrol_failed, enrolFailureLine(enrollFailureOf(401)))
        org.junit.Assert.assertEquals(com.kidslauncher.mdm.R.string.re_enrol_unreachable, enrolFailureLine(java.io.IOException("no route")))
        val bind = body(dialog, "bind")
        assertTrue(bind.indexOf("button?.visibility = View.GONE") in 0 until bind.indexOf("recheck()"))
        assertTrue(bind.contains("button?.visibility = if (due) View.VISIBLE else View.GONE"))
    }

    @Test
    fun `no Keystore read on the main thread for the stream or Settings' Sync now`() {
        val listener = src("server/CommandListenerService.kt")
        val connect = body(listener, "connect")
        assertTrue(connect.contains("TOKEN_READER.execute {"))
        assertTrue(connect.contains("handler.post { if (gen == connectGen && !stopped) openStream(credential) }"))
        assertTrue(connect.contains("DeviceCredentials.current(applicationContext)"))
        assertFalse(body(listener, "openStream").contains("DeviceCredentials"))
        val syncNow = body(src("ui/settings/launcher/SettingsFragmentLauncher.kt"), "syncNowWithServer")
        assertTrue(syncNow.contains("!com.kidslauncher.mdm.server.DeviceCredentials.stored(context)"))
        assertFalse(syncNow.contains("DeviceCredentials.current("))
    }
}
