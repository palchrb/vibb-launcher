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
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = false, policyEverApplied = false, witnessEnrolled = false))
        // Not Device Owner (a plain install, adb before set-device-owner): Settings is the way, as before.
        assertFalse(connectScreenDue(deviceOwner = false, enrolled = false, tokenStored = false, keyAdopted = false, policyEverApplied = false, witnessEnrolled = false))
        // Never on an enrolled phone, whichever of the enrolment's two writes is seen (an unreadable token counts).
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = true, keyAdopted = false, policyEverApplied = false, witnessEnrolled = false))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = false, keyAdopted = false, policyEverApplied = false, witnessEnrolled = false))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = true, keyAdopted = false, policyEverApplied = false, witnessEnrolled = false))
        // Never wider than the Settings gate: once a policy was applied, Settings needs the parent PIN - no screen.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = false, policyEverApplied = true, witnessEnrolled = false))
    }

    @Test
    fun `a managed phone whose preferences were lost gets the screen, with Settings still gated`() {
        // The witness says managed, the preferences say never enrolled: due even though a policy was applied.
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = false, policyEverApplied = true, witnessEnrolled = true))
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = false, policyEverApplied = false, witnessEnrolled = true))
        // Settings' gate reads the witness as "a policy was applied": the parent code, never open.
        assertEquals(
            com.kidslauncher.mdm.ui.settings.SettingsAccess.REFUSE_NO_PIN,
            com.kidslauncher.mdm.ui.settings.settingsAccess(policyEverApplied = true, pinConfigured = false, lockedOut = false),
        )
        // Still never on an enrolled phone or off Device Owner.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = false, keyAdopted = false, policyEverApplied = true, witnessEnrolled = true))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = true, keyAdopted = false, policyEverApplied = true, witnessEnrolled = true))
        assertFalse(connectScreenDue(deviceOwner = false, enrolled = false, tokenStored = false, keyAdopted = false, policyEverApplied = true, witnessEnrolled = true))
    }

    @Test
    fun `a phone with a hardware key that lost its preferences gets the screen`() {
        // §6bp row 6 (2026-11-25): the key book outlives the cut; the server address and the enrolment didn't. Due.
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = true, policyEverApplied = true, witnessEnrolled = true))
        // Enrolled before its first policy, then cut (§6bp row 13 with a key): due as well.
        assertTrue(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = true, policyEverApplied = false, witnessEnrolled = true))
        // A healthy enrolled phone with its key: never.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = false, keyAdopted = true, policyEverApplied = true, witnessEnrolled = true))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = true, keyAdopted = true, policyEverApplied = false, witnessEnrolled = true))
        // A stored token means the preferences are there (or an enrolment is mid-write): never.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = true, keyAdopted = true, policyEverApplied = true, witnessEnrolled = true))
        // Not Device Owner: never.
        assertFalse(connectScreenDue(deviceOwner = false, enrolled = false, tokenStored = false, keyAdopted = true, policyEverApplied = true, witnessEnrolled = true))
        // No witness (never enrolled by it): an adopted key holds the screen back, as before.
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = true, policyEverApplied = false, witnessEnrolled = false))
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = false, tokenStored = false, keyAdopted = true, policyEverApplied = true, witnessEnrolled = false))
        // The live inputs: the token and the key apart, the key from its own book.
        val due = code("setup/ConnectActivity.kt").substringAfter("fun due(context: Context)").substringBefore("} catch")
        assertTrue("tokenStored = DeviceCredentials.tokenStored()," in due)
        assertTrue("keyAdopted = KeyBook.adoptedStored(context)," in due)
        assertFalse("DeviceCredentials.stored(" in due)
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
            "connectUrlRefusal(serverUrlUsable(url, BuildConfig.DEBUG), connectUrlAllowed(url, EnrolmentWitness.serverUrl(this)))" in connect,
        )
        assertTrue("ConnectUrlRefusal.ADDRESS_RULE -> R.string.server_url_refused" in connect)
        assertTrue("ConnectUrlRefusal.OTHER_SERVER -> R.string.connect_other_server" in connect)
    }

    @Test
    fun `another host gets its own line, the address rule keeps its own`() {
        // Gate rc.4 finding 4: https://other.example.org broke no address rule - the line says it isn't this phone's server.
        assertEquals(ConnectUrlRefusal.OTHER_SERVER, connectUrlRefusal(usable = true, allowed = false))
        assertEquals(ConnectUrlRefusal.ADDRESS_RULE, connectUrlRefusal(usable = false, allowed = true))
        assertEquals("the address rule first", ConnectUrlRefusal.ADDRESS_RULE, connectUrlRefusal(usable = false, allowed = false))
        assertEquals(null, connectUrlRefusal(usable = true, allowed = true))
        val en = File(main, "res/values/strings.xml").readText()
        val nb = File(main, "res/values-nb/strings.xml").readText()
        assertTrue("<string name=\"connect_other_server\">That isn\\'t this phone\\'s server.</string>" in en)
        assertTrue("<string name=\"connect_other_server\">Det er ikke telefonens server.</string>" in nb)
    }

    @Test
    fun `a signed sync with the witnessed server restores the enrolment`() {
        val witnessed = "https://vibb.example.org"
        // Gate rc.4 finding 2: the cut phone's adopted key synced with its own server - enrolled again.
        assertTrue(enrolmentRestoredBySync(enrolled = false, keySigned = true, witnessEnrolled = true, syncedUrl = witnessed, witnessUrl = witnessed))
        assertTrue(enrolmentRestoredBySync(enrolled = false, keySigned = true, witnessEnrolled = true, syncedUrl = "https://VIBB.example.org/", witnessUrl = witnessed))
        // Already enrolled: nothing to restore.
        assertFalse(enrolmentRestoredBySync(enrolled = true, keySigned = true, witnessEnrolled = true, syncedUrl = witnessed, witnessUrl = witnessed))
        // The bearer alone signed nothing with the key: not this rule.
        assertFalse(enrolmentRestoredBySync(enrolled = false, keySigned = false, witnessEnrolled = true, syncedUrl = witnessed, witnessUrl = witnessed))
        // Never against another host, never without a witnessed address, never without the witness.
        assertFalse(enrolmentRestoredBySync(enrolled = false, keySigned = true, witnessEnrolled = true, syncedUrl = "https://other.example.org", witnessUrl = witnessed))
        assertFalse(enrolmentRestoredBySync(enrolled = false, keySigned = true, witnessEnrolled = true, syncedUrl = "https://vibb.example.org.evil.example", witnessUrl = witnessed))
        assertFalse(enrolmentRestoredBySync(enrolled = false, keySigned = true, witnessEnrolled = true, syncedUrl = witnessed, witnessUrl = null))
        assertFalse(enrolmentRestoredBySync(enrolled = false, keySigned = true, witnessEnrolled = false, syncedUrl = witnessed, witnessUrl = witnessed))
        // Restored, Koble til is no longer due (the key and the witness unchanged).
        assertFalse(connectScreenDue(deviceOwner = true, enrolled = true, tokenStored = false, keyAdopted = true, policyEverApplied = true, witnessEnrolled = true))
        // The sync: the witness read before the policy rewrites its address, the restore after the accepted policy,
        // only for a key-signed request, committed synchronously.
        val sync = code("server/MdmSyncWorker.kt")
        val perform = sync.substringAfter("suspend fun performMdmSync(")
        val before = perform.indexOf("val witnessUrlBefore = EnrolmentWitness.serverUrl(context)")
        val store = perform.indexOf("storeAcceptedPolicy(context, freshPolicy)")
        val restore = perform.indexOf("enrolmentRestoredBySync(mdm.enrolled(), keySigned, witnessEnrolledBefore, serverUrl, witnessUrlBefore)")
        assertTrue(before in 0 until perform.indexOf("fetchPolicy("))
        assertTrue(restore > store && store > 0)
        assertTrue("val keySigned = credential is DeviceCredential.Key" in perform)
        val write = sync.substringAfter("private fun restoreEnrolled()").substringBefore("\n}\n")
        assertTrue(".putBoolean(mdm.keys().enrolled(), true).commit()" in write)
        assertTrue("ConnectActivity.enrolmentRestored()" in write)
        for (key in listOf("DeviceKeys.", "KeyBook.", "DeviceToken.", "EnrolmentWitness.")) assertFalse(key, key in write)
        // An open screen reads the rule again.
        assertTrue("restored.collect { render() }" in code("setup/ConnectActivity.kt").substringAfter("override fun onStart()"))
    }

    @Test
    fun `Koble til never comes up over parent Settings`() {
        // Gate rc.4 finding 3: the drawer's Settings showed its gate's dialog, then Koble til over it.
        val start = code("setup/ConnectActivity.kt").substringAfter("fun startFromHome(activity: Activity)")
        assertTrue(start.indexOf("if (com.kidslauncher.mdm.ui.settings.SettingsFront.shown) return false") in 0 until start.indexOf("activity.startActivity("))
        val settings = code("ui/settings/SettingsActivity.kt")
        assertTrue("markFront(true)" in settings.substringAfter("override fun onStart()").substringBefore("private fun enforceGate()"))
        assertTrue("if (isFinishing) markFront(false)" in settings.substringAfter("override fun onPause()").substringBefore("override fun onStop()"))
        for (left in listOf("override fun onUserLeaveHint()", "override fun onStop()", "override fun onDestroy()")) {
            assertTrue(left, "markFront(false)" in settings.substringAfter(left).substringBefore("\n    }\n"))
        }
    }

    @Test
    fun `after a failed QR the screen says why, in a few words`() {
        // Design 142 M4: kept for a retry and nothing running - trying again; refused - a new code; nothing while busy.
        assertEquals(QrConnectLine.RETRYING, qrConnectLine(refused = false, pending = true, running = false, witnessEnrolled = false))
        assertEquals(null, qrConnectLine(refused = false, pending = true, running = true, witnessEnrolled = false))
        assertEquals(QrConnectLine.REFUSED, qrConnectLine(refused = true, pending = false, running = false, witnessEnrolled = false))
        assertEquals(null, qrConnectLine(refused = true, pending = false, running = true, witnessEnrolled = false))
        assertEquals("kept extras are a newer QR's", QrConnectLine.RETRYING, qrConnectLine(refused = true, pending = true, running = false, witnessEnrolled = false))
        // Design QA M-b: the refused flag is an old QR's word on a witnessed phone; kept extras still say so.
        assertEquals(null, qrConnectLine(refused = true, pending = false, running = false, witnessEnrolled = true))
        assertEquals(QrConnectLine.RETRYING, qrConnectLine(refused = false, pending = true, running = false, witnessEnrolled = true))
        assertEquals(null, qrConnectLine(refused = false, pending = false, running = false, witnessEnrolled = false))
        // The waiting enrolment's lines and a typed failure come first; the QR's line is read at every render.
        val render = code("setup/ConnectActivity.kt").substringAfter("private fun render()").substringBefore("private fun qrLine()")
        assertTrue("EnrolWait.lines(this) ?: lastError.value?.let { getString(it) } ?: qrLine()?.let { getString(it) }" in render)
        val line = code("setup/ConnectActivity.kt").substringAfter("private fun qrLine()").substringBefore("private fun connect()")
        assertTrue("QrExtras.keptState(this)" in line)
        assertTrue("EnrolmentWitness.enrolled(this)" in line)
        assertTrue("running = QrExtras.running.value > 0" in line)
        val en = File(main, "res/values/strings.xml").readText()
        val nb = File(main, "res/values-nb/strings.xml").readText()
        assertTrue("<string name=\"connect_qr_refused\">QR-koden virket ikke. Bruk en ny oppsettkode.</string>" in nb)
        assertTrue("<string name=\"connect_qr_retrying\">Ikke koblet til ennå. Prøver igjen.</string>" in nb)
        assertTrue("<string name=\"connect_qr_refused\">The QR code didn\\'t work. Use a new setup code.</string>" in en)
        assertTrue("<string name=\"connect_qr_retrying\">Not connected yet. Trying again.</string>" in en)
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
