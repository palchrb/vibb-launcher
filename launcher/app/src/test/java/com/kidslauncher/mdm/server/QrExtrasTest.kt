package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The setup QR's admin extras applied once (2026-11-20): on a GMS phone a role holder (CloudDPC) provisions and the
 * extras reach only ADMIN_POLICY_COMPLIANCE; both delivery paths and the sync's retry share [QrExtras].
 */
class QrExtrasTest {
    private val code = "setup-code-0123"
    private val key = "tskey-auth-FAKEFAKEFAKE"
    private val extras = ProvisioningExtras("https://vibb.example.org", key, code)

    @Test
    fun `no extras - nothing`() {
        assertEquals(QrExtrasAction.NONE, qrExtrasAction(null, null, enrolled = false, debug = false))
        assertEquals(QrExtrasAction.NONE, qrExtrasAction(null, qrCodeHash(code), enrolled = true, debug = false))
    }

    @Test
    fun `fresh extras on a phone not enrolled are applied`() {
        assertEquals(QrExtrasAction.APPLY, qrExtrasAction(extras, null, enrolled = false, debug = false))
        // Another code's marker doesn't stop a new one.
        assertEquals(QrExtrasAction.APPLY, qrExtrasAction(extras, qrCodeHash("older-code"), enrolled = false, debug = false))
        // http only to a Tailscale IP literal (S-M1).
        assertEquals(QrExtrasAction.APPLY, qrExtrasAction(extras.copy(serverUrl = "http://100.64.0.7:8080"), null, false, false))
    }

    @Test
    fun `a second delivery of the same code is a no-op`() {
        assertEquals(QrExtrasAction.SKIP_APPLIED, qrExtrasAction(extras, qrCodeHash(code), enrolled = true, debug = false))
        assertEquals(QrExtrasAction.SKIP_APPLIED, qrExtrasAction(extras, qrCodeHash(code), enrolled = false, debug = false))
        // The code is compared trimmed, as the bundle may carry it.
        assertEquals(QrExtrasAction.SKIP_APPLIED, qrExtrasAction(extras.copy(enrollmentCode = " $code\n"), qrCodeHash(code), false, false))
        // A refused code's marker stops it too, refused address or not.
        assertEquals(QrExtrasAction.SKIP_APPLIED, qrExtrasAction(extras.copy(serverUrl = "http://vibb.example.org"), qrCodeHash(code), false, false))
    }

    @Test
    fun `an enrolled phone never enrols again from the extras`() {
        assertEquals(QrExtrasAction.SKIP_ENROLLED, qrExtrasAction(extras, null, enrolled = true, debug = false))
        assertEquals(QrExtrasAction.SKIP_ENROLLED, qrExtrasAction(extras, qrCodeHash("older-code"), enrolled = true, debug = false))
    }

    @Test
    fun `an address S-M1 refuses is refused, the emulator's only in a debug build`() {
        for (url in listOf("http://vibb.example.org", "ftp://vibb.example.org", "https://user@vibb.example.org", "http://192.168.1.2")) {
            assertEquals(url, QrExtrasAction.REFUSE, qrExtrasAction(extras.copy(serverUrl = url), null, false, debug = false))
        }
        assertEquals(QrExtrasAction.REFUSE, qrExtrasAction(extras.copy(serverUrl = "http://10.0.2.2:8080"), null, false, debug = false))
        assertEquals(QrExtrasAction.APPLY, qrExtrasAction(extras.copy(serverUrl = "http://10.0.2.2:8080"), null, false, debug = true))
    }

    @Test
    fun `after an apply - done, refused for good, or kept for a retry`() {
        assertEquals(QrExtrasAfter.DONE, qrExtrasAfter(Result.success(Unit)))
        // Only the code's own fault is refused for good (QA M1): 429 (slots full, Retry-After) and 408 are a good code.
        for (status in listOf(400, 401, 403, 404, 410)) {
            assertEquals("$status", QrExtrasAfter.REFUSED, qrExtrasAfter(Result.failure(enrollFailureOf(status))))
        }
        for (status in listOf(408, 409, 413, 425, 429, 500, 503)) {
            assertEquals("$status", QrExtrasAfter.RETRY, qrExtrasAfter(Result.failure(enrollFailureOf(status))))
        }
        assertEquals(setOf(400, 401, 403, 404, 410), CODE_REFUSED_STATUSES)
        assertEquals(QrExtrasAfter.REFUSED, qrExtrasAfter(Result.failure(EnrollFailure(refused = true, "address"))))
        assertEquals(QrExtrasAfter.RETRY, qrExtrasAfter(Result.failure(enrollFailureOf(502))))
        assertEquals(QrExtrasAfter.RETRY, qrExtrasAfter(Result.failure(EnrollFailure(refused = false, "token not stored"))))
        assertEquals(QrExtrasAfter.RETRY, qrExtrasAfter(Result.failure(IOException("no route"))))
    }

    @Test
    fun `Settings' scanner sends a new code, never the one that enrolled this phone`() {
        assertTrue(scannedCodeSends(enrolled = false, appliedHash = null, code = code))
        assertTrue(scannedCodeSends(enrolled = false, appliedHash = qrCodeHash(code), code = code))
        // A deliberate re-scan of a new code re-enrols, as before.
        assertTrue(scannedCodeSends(enrolled = true, appliedHash = qrCodeHash(code), code = "a-new-code"))
        assertTrue(scannedCodeSends(enrolled = true, appliedHash = null, code = code))
        assertFalse(scannedCodeSends(enrolled = true, appliedHash = qrCodeHash(code), code = " $code "))
    }

    @Test
    fun `the marker is the code's SHA-256, never the code`() {
        val hash = qrCodeHash(code)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertFalse(code in hash)
        assertEquals(hash, qrCodeHash("  $code "))
        assertNotEquals(hash, qrCodeHash("other"))
    }

    @Test
    fun `the store keeps the extras sealed until marked, across a restart`() {
        val prefs = ParentPinStoreTest.FakePrefs()
        val box = SealedPrefsTest.FakeBox()
        val store = QrExtrasStore(prefs, SealedPrefs(box) { _, _ -> })
        assertFalse(store.hasPending())
        assertNull(store.pending())
        assertNull(store.appliedHash())

        assertTrue(store.setPending(extras))
        assertTrue(store.hasPending())
        val raw = prefs.map.values.joinToString()
        assertFalse("the code is never stored in the clear", code in raw)
        assertFalse("nor the tailnet key", key in raw)
        // Another process: the same file and key, no memo.
        val restarted = QrExtrasStore(prefs, SealedPrefs(box) { _, _ -> })
        assertEquals(extras, restarted.pending())

        assertTrue(restarted.markApplied(qrCodeHash(code), refused = false))
        assertFalse(restarted.hasPending())
        assertNull(restarted.pending())
        assertEquals(qrCodeHash(code), restarted.appliedHash())
        assertFalse(restarted.appliedRefused())

        store.setPending(extras)
        store.markApplied(qrCodeHash(code), refused = true)
        assertTrue(store.appliedRefused())
        assertFalse(store.hasPending())

        store.setPending(extras)
        store.dropPending()
        assertFalse(store.hasPending())
        assertEquals("the marker stays", qrCodeHash(code), store.appliedHash())
    }

    @Test
    fun `kept extras that don't open read as unreadable, a broken key told apart`() {
        val prefs = ParentPinStoreTest.FakePrefs()
        val box = SealedPrefsTest.FakeBox()
        QrExtrasStore(prefs, SealedPrefs(box) { _, _ -> }).setPending(extras)
        box.openFailures = 1
        val other = QrExtrasStore(prefs, SealedPrefs(box) { _, _ -> })
        assertTrue(other.hasPending())
        assertEquals(KeptRead.Unreadable, other.pendingRead())
        assertFalse("a transient error, the key fine", other.keyBroken())
        assertEquals(KeptRead.Value(extras), other.pendingRead())
        box.broken = true
        assertTrue(other.keyBroken())
        // Opened but not ours: corrupt, dropped.
        val sealed = SealedPrefs(box) { _, _ -> }
        sealed.edit(prefs) { it.putSealed("qr_extras/pending", "pending", "not json") }
        assertEquals(KeptRead.Corrupt, QrExtrasStore(prefs, sealed).pendingRead())
    }

    @Test
    fun `an unreadable kept value is dropped only when the key is broken`() {
        val retry = code("server/QrExtras.kt").substringAfter("suspend fun retryPending(").substringBefore("\n    /**")
        assertTrue(retry.contains("KeptRead.Unreadable -> if (store.keyBroken()) {"))
        val unreadable = retry.substringAfter("KeptRead.Unreadable ->")
        assertTrue(unreadable.indexOf("store.dropPending()") < unreadable.indexOf("} else {"))
        assertFalse(unreadable.substringAfter("} else {").contains("dropPending"))
    }

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()
    private fun code(path: String) = src(path).replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the compliance activity hands the bundle to the shared apply and finishes OK at once`() {
        val body = code("server/ProvisioningActivities.kt").substringAfter("class PolicyComplianceActivity").substringAfter("onCreate(")
        assertTrue(body, "getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)" in body)
        val deliver = body.indexOf("QrExtras.deliver(this, bundle, \"Admin policy compliance\")")
        assertTrue(deliver > 0)
        assertTrue(deliver < body.indexOf("setResult(RESULT_OK)"))
        assertTrue(body.indexOf("setResult(RESULT_OK)") < body.indexOf("finish()"))
        // Nothing blocks the main thread there: no enrolment, no runBlocking.
        for (blocking in listOf("applyProvisioningExtras(", "runBlocking", "Enrollment.enroll(")) assertFalse(blocking, blocking in body)
    }

    @Test
    fun `the receiver and the sync share the one apply`() {
        val receiver = code("server/MdmDeviceAdminReceiver.kt").substringAfter("fun onProfileProvisioningComplete(")
        assertTrue("QrExtras.deliver(context, bundle, \"Provisioning complete\")" in receiver)
        assertTrue("goAsync()" in receiver)
        // QA L4: held only briefly - the extras are kept for the retry.
        assertTrue("QrExtras.finishWithin(job) { pendingResult.finish() }" in receiver)
        assertTrue("invokeOnCompletion" !in receiver)
        assertFalse("applyProvisioningExtras(" in receiver)
        val runner = code("push/SyncRunner.kt").substringAfter("private fun launchRun(")
        val retry = runner.indexOf("QrExtras.retryPending(context)")
        assertTrue(retry > 0)
        assertTrue("before the sync", retry < runner.indexOf("performMdmSync(context)"))
        // QA M3: no timeout around it - a cancelled enrolment could lose a code the server used.
        val retryLine = runner.substring(runner.lastIndexOf('\n', retry), retry)
        assertFalse("withTimeoutOrNull" in retryLine)
        assertTrue("withContext(Dispatchers.IO + NonCancellable)" in code("server/Enrollment.kt").substringAfter("suspend fun enroll("))

        val qr = code("server/QrExtras.kt")
        // The QR's apply, "Koble til"'s typed code and Settings' scanner, each under the one mutex, never cancelled.
        // Design 129b D11: the waiting enrolment's tries are Enrollment.enroll alone (code QA L5), under the same mutex.
        assertEquals(3, Regex("""applyProvisioningExtras\(""").findAll(qr).count())
        assertTrue("last = QrExtras.underLock { tryOnce() }" in code("server/EnrolWait.kt"))
        assertTrue("suspend fun <T> underLock(block: suspend () -> T): T = locked(block)" in qr)
        assertTrue("= counted { mutex.withLock { withContext(NonCancellable) { block() } } }" in qr)
        val typed = qr.substringAfter("suspend fun enrolTyped(").substringBefore("suspend fun applyScanned(")
        assertTrue(typed.contains("): Result<Unit> = locked {"))
        // QA M2: an enrolled phone sends nothing.
        assertTrue(typed.indexOf("if (enrolledNow()) {") in 0 until typed.indexOf("applyProvisioningExtras("))
        val scanned = qr.substringAfter("suspend fun applyScanned(").substringBefore("private suspend fun apply(")
        assertTrue(scanned.contains("): Result<Unit> = locked {"))
        assertTrue(scanned.indexOf("scannedCodeSends(") in 0 until scanned.indexOf("applyProvisioningExtras("))
        val apply = qr.substringAfter("private suspend fun apply(").substringBefore("\n/** What one delivery")
        assertTrue("one at a time", apply.trimStart().startsWith("context: Context, extras: ProvisioningExtras, source: String) = locked {"))
        // QA L1: deliver seals the extras before it launches the apply (and so before the activity's result).
        val deliver = qr.substringAfter("fun deliver(").substringBefore("fun finishWithin(")
        assertTrue(deliver.indexOf("store.setPending(extras)") in 0 until deliver.indexOf("scope.launch"))
        assertTrue(apply.indexOf("store.setPending(extras)") < apply.indexOf("applyProvisioningExtras("))
        // QA L3: every apply in the app is this one (Settings' scanner through applyScanned).
        assertTrue("QrExtras.applyScanned(appContext, extras)" in code("ui/settings/launcher/SettingsFragmentLauncher.kt"))
        val callers = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "applyProvisioningExtras(" in it.readText().replace(Regex("//[^\\n]*"), "") }
            .map { it.name }.toSet()
        assertEquals(setOf("Provisioning.kt", "QrExtras.kt"), callers)
    }

    @Test
    fun `no log line names the code or the key, and the AAD is the store's alone`() {
        val qr = src("server/QrExtras.kt")
        val logs = Regex("""Log\.[iwe]\((?:[^()]|\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))*\)""").findAll(qr).map { it.value }.toList()
        assertTrue(logs.size >= 8)
        assertTrue(logs.any { "tried again at the next sync" in it })
        for (line in logs) {
            for (secret in listOf("enrollmentCode", "tailscaleAuthKey", "\$extras", "{extras}", "bundle}", "\$code", "{code}", "\$key", "{key}")) {
                assertFalse("$line names $secret", secret in line)
            }
        }
        val owners = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "\"qr_extras/pending\"" in it.readText() }.map { it.name }.toSet()
        assertEquals(setOf("QrExtras.kt"), owners)
    }
}
