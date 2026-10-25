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
    fun `a main-thread read never migrates, and the start's background pass runs`() {
        assertTrue(src("server/SealedPrefs.kt").contains("fun migrateHere(): Boolean = Looper.myLooper() != Looper.getMainLooper()"))
        val initRest = src("Application.kt").substringAfter("private fun initRest()")
        assertTrue(initRest.contains("CoroutineScope(Dispatchers.IO).launch { com.kidslauncher.mdm.server.Secrets.migrateAll(app) }"))
    }

    @Test
    fun `the kid PIN's glue reads the store, keeps the fields on the crash guard and re-seals a failed write`() {
        val runtime = src("lock/PinLockRuntime.kt")
        val configure = body(runtime, "configure")
        assertTrue(configure.contains("kidConfigFor(kidLock, config) { runCatching { PinLockStore.config(app) }.getOrNull() }"))
        assertTrue(configure.contains("(write is PinWrite.Write && !PinLockStore.pinStored(app))"))
        assertTrue(configure.contains("PinLockStore.saveConfig(app, write, active, why, storedRequest)"))
        assertTrue(body(runtime, "checkPin").contains("val cfg = config?.takeIf { it.usable } ?: runCatching { PinLockStore.config(app) }.getOrNull()"))
        val guard = body(runtime, "guardTripped")
        assertTrue(guard.contains("PinLockStore.saveConfig(app, PinWrite.Keep, active = false"))
        assertFalse(guard.contains("PinWrite.Remove"))
        assertTrue(body(runtime, "pinSetStored").contains("PinLockStore.pinStored(app)"))
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
        assertTrue(dialog.contains("if (enrolFailureRefused(result.exceptionOrNull())) R.string.re_enrol_failed else R.string.re_enrol_unreachable"))
        val bind = body(dialog, "bind")
        assertTrue(bind.indexOf("button?.visibility = View.GONE") in 0 until bind.indexOf("recheck()"))
        assertTrue(bind.contains("button?.visibility = if (due) View.VISIBLE else View.GONE"))
    }

    @Test
    fun `no Keystore read on the main thread for the stream or Settings' Sync now`() {
        val listener = src("server/CommandListenerService.kt")
        val connect = body(listener, "connect")
        assertTrue(connect.contains("TOKEN_READER.execute {"))
        assertTrue(connect.contains("handler.post { if (gen == connectGen && !stopped) openStream(deviceToken) }"))
        assertFalse(body(listener, "openStream").contains("DeviceToken"))
        val syncNow = body(src("ui/settings/launcher/SettingsFragmentLauncher.kt"), "syncNowWithServer")
        assertTrue(syncNow.contains("!com.kidslauncher.mdm.server.DeviceToken.present()"))
        assertFalse(syncNow.contains("DeviceToken.get()"))
    }
}
