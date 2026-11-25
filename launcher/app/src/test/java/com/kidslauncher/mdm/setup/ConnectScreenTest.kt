package com.kidslauncher.mdm.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** "Koble til" (2026-11-20): a Device Owner phone that isn't enrolled gets the server address and the setup code. */
class ConnectScreenTest {

    @Test
    fun `due only on an unenrolled Device Owner phone before the first policy`() {
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, policyEverApplied = false, witnessEnrolled = false))
        // Not Device Owner (a plain install, adb before set-device-owner): Settings is the way, as before.
        assertFalse(connectScreenDue(deviceOwner = false, enrolled = false, tokenStored = false, policyEverApplied = false, witnessEnrolled = false))
        // Never on an enrolled phone, whichever of the enrolment's two writes is seen (an unreadable token counts).
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = true, policyEverApplied = false, witnessEnrolled = false))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = false, policyEverApplied = false, witnessEnrolled = false))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = true, policyEverApplied = false, witnessEnrolled = false))
        // Never wider than the Settings gate: once a policy was applied, Settings needs the parent PIN - no screen.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, policyEverApplied = true, witnessEnrolled = false))
    }

    @Test
    fun `a managed phone whose preferences were lost gets the screen, with Settings still gated`() {
        // The witness says managed, the preferences say never enrolled: due even though a policy was applied.
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, policyEverApplied = true, witnessEnrolled = true))
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, policyEverApplied = false, witnessEnrolled = true))
        // Settings' gate reads the witness as "a policy was applied": the parent code, never open.
        assertEquals(
            com.kidslauncher.mdm.ui.settings.SettingsAccess.REFUSE_NO_PIN,
            com.kidslauncher.mdm.ui.settings.settingsAccess(policyEverApplied = true, pinConfigured = false, lockedOut = false),
        )
        // Still never on an enrolled phone or off Device Owner.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = false, policyEverApplied = true, witnessEnrolled = true))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = true, policyEverApplied = true, witnessEnrolled = true))
        assertFalse(connectScreenDue(deviceOwner = false, enrolled = false, tokenStored = false, policyEverApplied = true, witnessEnrolled = true))
    }

    @Test
    fun `the screen has the emergency button and asks the witness`() {
        val text = code("setup/ConnectActivity.kt")
        assertTrue("binding.connectEmergency.setOnClickListener { EmergencyCall.confirm(this) }" in text.substringAfter("override fun onCreate("))
        assertTrue("witnessEnrolled = EnrolmentWitness.enrolled(context)" in text.substringAfter("fun due(context: Context)"))
        val layout = File(main, "res/layout/activity_connect.xml").readText()
        assertTrue("android:id=\"@+id/connect_emergency\"" in layout)
        assertTrue("android:text=\"@string/calls_emergency\"" in layout.substringAfter("@+id/connect_emergency"))
        assertTrue("<string name=\"calls_emergency\">Nødsamtale</string>" in File(main, "res/values-nb/strings.xml").readText())
    }

    @Test
    fun `a witnessed phone goes back to its own server only`() {
        val witnessed = "https://vibb.example.org"
        assertTrue(connectUrlAllowed("https://vibb.example.org", witnessed))
        assertTrue(connectUrlAllowed(" https://VIBB.example.org./ ", witnessed))
        assertTrue("another path or port on the same host", connectUrlAllowed("https://vibb.example.org:8443/x", witnessed))
        assertFalse(connectUrlAllowed("https://other.example.org", witnessed))
        assertFalse(connectUrlAllowed("https://vibb.example.org.evil.example", witnessed))
        assertFalse(connectUrlAllowed("not a url", witnessed))
        assertFalse("a witnessed address that doesn't read takes nothing", connectUrlAllowed("https://vibb.example.org", "::"))
        assertTrue("no witnessed address: the address rule alone", connectUrlAllowed("https://other.example.org", null))
        val connect = code("setup/ConnectActivity.kt").substringAfter("private fun connect()").substringBefore("private fun hideIme()")
        assertTrue(
            "if (!serverUrlUsable(url, BuildConfig.DEBUG) || !connectUrlAllowed(url, EnrolmentWitness.serverUrl(this))) {\n" +
                "            lastError.value = R.string.server_url_refused" in connect,
        )
    }

    @Test
    fun `the button wants both fields and no enrolment running`() {
        assertTrue(connectButtonEnabled("https://vibb.example.org", "ABCD2345", busy = false))
        assertFalse(connectButtonEnabled("", "ABCD2345", busy = false))
        assertFalse(connectButtonEnabled("https://vibb.example.org", "  ", busy = false))
        assertFalse(connectButtonEnabled("https://vibb.example.org", "ABCD2345", busy = true))
    }

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun code(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText().replace(Regex("//[^\\n]*"), "")

    @Test
    fun `Home starts it past the gate and the lock, before the setup hold`() {
        val resume = code("ui/HomeActivity.kt").substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        val start = resume.indexOf("com.kidslauncher.mdm.setup.ConnectActivity.startFromHome(this)) return")
        assertTrue(start > 0)
        assertTrue(resume.indexOf("if (!gate())") < start)
        assertTrue(resume.lastIndexOf("if (redirectToLockScreenIfLocked()) return") < start)
        assertTrue(start < resume.indexOf("RestrictionsPause.holdActive()"))
    }

    @Test
    fun `the screen checks the address rule, enrols through the one apply and checks the rule again at every start`() {
        val text = code("setup/ConnectActivity.kt")
        val connect = text.substringAfter("private fun connect()").substringBefore("private fun hideIme()")
        val check = connect.indexOf("serverUrlUsable(url, BuildConfig.DEBUG)")
        assertTrue(check > 0)
        assertTrue(check < connect.indexOf("QrExtras.enrolTyped(app, url, code)"))
        assertTrue("CONNECT_SCOPE.launch" in connect)
        for (other in listOf("Enrollment.enroll(", "applyProvisioningExtras(", "runBlocking")) assertFalse(other, other in text)
        assertTrue("if (!busy && !due(this)) {\n            finish()" in text.substringAfter("override fun onStart()"))
        // QA M2, L6: the busy state is the process's (any instance, a QR apply too), one tap is one enrolment, and the
        // rule is read again whenever it changes.
        assertTrue("private val busy: Boolean get() = typing.value || QrExtras.running.value > 0" in text)
        assertTrue("if (!typing.compareAndSet(false, true)) return" in connect)
        for (flow in listOf("QrExtras.running.collect { render() }", "typing.collect { render() }", "lastError.collect { render() }")) {
            assertTrue(flow, flow in text.substringAfter("override fun onStart()"))
        }
        assertTrue("if (!busyNow && !isFinishing && !due(this)) {" in text.substringAfter("private fun render()"))
        assertTrue("private val typing = MutableStateFlow(false)" in text)
        assertTrue("connectScreenDue(" in text.substringAfter("fun due(context: Context)"))
        assertTrue("FLAG_SECURE" in text)
    }

    @Test
    fun `not exported, out of Recents, its words in nb and en`() {
        val ns = "http://schemas.android.com/apk/res/android"
        val activities = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(main, "AndroidManifest.xml")).getElementsByTagName("activity")
        val connect = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".setup.ConnectActivity" }
        assertEquals("false", connect.getAttributeNS(ns, "exported"))
        assertEquals("true", connect.getAttributeNS(ns, "excludeFromRecents"))
        assertEquals(0, connect.getElementsByTagName("intent-filter").length)
        val nb = File(main, "res/values-nb/strings.xml").readText()
        assertTrue("<string name=\"connect_title\">Koble til</string>" in nb)
        assertTrue("Avansert › Ny oppsettkode" in nb)
        assertTrue("<string name=\"re_enrol_field\">Oppsettkode</string>" in nb)
        assertTrue("<string name=\"settings_mdm_server_url\">Serveradresse</string>" in nb)
        val en = File(main, "res/values/strings.xml").readText()
        for (name in listOf("connect_title", "connect_busy", "connect_hint")) {
            assertTrue(name, "<string name=\"$name\">" in en && "<string name=\"$name\">" in nb)
        }
    }
}
