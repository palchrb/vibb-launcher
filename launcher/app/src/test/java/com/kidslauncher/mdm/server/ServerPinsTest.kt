package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.StatusReportRequest
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.CertificatePinner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Design 76: the built-in set from the roots' certificates, the flag's rule and the ring (the shared contract
 * `server_tls_report.json`), OkHttp's pin failure, and where the rules are called from. */
class ServerPinsTest {
    private val contractText = javaClass.classLoader!!.getResource("server_tls_report.json")!!.readText()
    private val contract = Json.parseToJsonElement(contractText).jsonObject

    private fun certificate(name: String): X509Certificate =
        javaClass.classLoader!!.getResourceAsStream("server_pins/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    @Test
    fun `the built-in set is the roots' keys, computed from their certificates`() {
        val files = mapOf(
            "ISRG Root X1" to "isrg-root-x1.pem",
            "ISRG Root X2" to "isrg-root-x2.pem",
            "Root YR" to "isrg-root-yr.pem",
            "Root YE" to "isrg-root-ye.pem",
            "USERTrust RSA Certification Authority" to "usertrust-rsa.pem",
            "USERTrust ECC Certification Authority" to "usertrust-ecc.pem",
            "Sectigo Public Server Authentication Root R46" to "sectigo-r46.pem",
            "Sectigo Public Server Authentication Root E46" to "sectigo-e46.pem",
        )
        assertEquals(files.keys.toList(), BUILTIN_PINS.map { it.name })
        for (pin in BUILTIN_PINS) {
            val root = certificate(files.getValue(pin.name))
            assertEquals(pin.name, pin.pin, spkiPin(root))
            assertEquals(pin.name, pin.name, anchorName(root.subjectX500Principal.name))
        }
        // A cross-sign carries its root's key (D1, C-1): the chain passes whichever one Android's store ends at.
        assertEquals(spkiPin(certificate("isrg-root-yr.pem")), spkiPin(certificate("isrg-root-yr-by-x1.pem")))
        assertEquals(spkiPin(certificate("isrg-root-ye.pem")), spkiPin(certificate("isrg-root-ye-by-x2.pem")))
        assertEquals(8, BUILTIN_PINS.map { it.pin }.toSet().size)
        // Every pin builds into OkHttp's pinner (qa L-4: B's constants never fail closed by accident).
        assertTrue(serverPinner("vibb.example.org", BUILTIN_PINS.map { it.pin })!!.findMatchingPins("vibb.example.org").size == 8)
    }

    private fun connection(wire: String) = PinConnection.entries.single { it.wire == wire }

    @Test
    fun `every flag row of the contract`() {
        val rows = contract.getValue("flags").jsonArray
        assertEquals(72, rows.size)
        for (row in rows) {
            val o = row.jsonObject
            val serverTls = o["server_tls"]
            val got = nextFlag(o.getValue("from").jsonPrimitive.boolean, enforceOf(serverTls), connection(o.getValue("connection").jsonPrimitive.content))
            assertEquals(o.toString(), o.getValue("flag").jsonPrimitive.boolean, got)
        }
        // R-1: the server's `watching` keeps an enforcing phone on over a passing connection.
        val watching = contract.getValue("policy").jsonArray.single { it.jsonObject.getValue("state").jsonPrimitive.content == "watching" }
        assertTrue(nextFlag(true, enforceOf(watching.jsonObject["server_tls"]), PinConnection.PASSED))
        // Each policy row's value is one the phone reads: true, false, keep.
        val read = contract.getValue("policy").jsonArray.associate {
            it.jsonObject.getValue("state").jsonPrimitive.content to enforceOf(it.jsonObject["server_tls"])
        }
        assertEquals(mapOf("watching" to null, "on" to true, "off" to false), read)
    }

    @Test
    fun `the policy's server_tls arrives raw and never fails the policy`() {
        for (value in listOf("""{"enforce": true}""", """{}""", "\"on\"", "[1]", "null", """{"enforce": "true"}""")) {
            val body = """{"allowlist": [], "kiosk_desired": false, "lock_task_features": 0, "quick_controls_mask": 0,
                "vpn_filter_enabled": false, "dns_filter_version": "x", "dns_upstream_provider": "quad9",
                "packages_to_uninstall": [], "server_tls": $value}"""
            val fresh = decodeFresh(body)
            assertTrue(value, fresh is FreshDecode.Ok)
            assertEquals(value, if (value == """{"enforce": true}""") true else null, enforceOf((fresh as FreshDecode.Ok).policy.serverTls))
        }
    }

    private fun row(o: JsonObject) = OutsideRow(
        kind = o.getValue("kind").jsonPrimitive.content,
        host = o.getValue("host").jsonPrimitive.content,
        name = o.getValue("name").jsonPrimitive.content,
        pin = o.getValue("pin").jsonPrimitive.content,
        network = o.getValue("network").jsonPrimitive.content,
        atMs = o.getValue("at_ms").jsonPrimitive.long,
    )

    @Test
    fun `the report is encoded exactly as the contract's`() {
        val expected = contract.getValue("report").jsonObject.getValue("server_tls").jsonObject
        val seen = expected.getValue("seen").jsonObject
        val report = ServerTlsReport(
            enforcing = expected.getValue("enforcing").jsonPrimitive.boolean,
            seen = ServerTlsSeen(
                inSet = seen.getValue("in_set").jsonPrimitive.boolean,
                name = seen.getValue("name").jsonPrimitive.content,
                pin = seen.getValue("pin").jsonPrimitive.content,
                network = seen.getValue("network").jsonPrimitive.content,
                atMs = seen.getValue("at_ms").jsonPrimitive.long,
            ),
            outside = expected.getValue("outside").jsonArray.map { row(it.jsonObject) },
        )
        assertEquals(setOf(OutsideRow.SEEN, OutsideRow.REFUSED), report.outside.map { it.kind }.toSet())
        val encoded = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true, serverTls = report)),
        ).jsonObject
        assertEquals(expected, encoded["server_tls"])
        // Without a chain (the tailnet, plain http) `seen` is left out; `outside` is always sent, empty too.
        val bare = ServerJson.parseToJsonElement(ServerJson.encodeToString(ServerTlsReport.serializer(), ServerTlsReport(true, null, emptyList())))
        assertEquals(Json.parseToJsonElement("""{"enforcing": true, "outside": []}"""), bare)
        // The seen pin in the report is one of B's.
        assertTrue(inSet(listOf(seen.getValue("pin").jsonPrimitive.content)))
    }

    @Test
    fun `the ring drops its sent rows only over a passing or a tailnet connection`() {
        val sent = contract.getValue("report").jsonObject.getValue("server_tls").jsonObject.getValue("outside").jsonArray.map { row(it.jsonObject) }
        val later = sent.last().copy(atMs = sent.last().atMs + 1, network = "other")
        for (drop in contract.getValue("drops").jsonArray) {
            val o = drop.jsonObject
            val kept = dropOutside(sent + later, sent, connection(o.getValue("connection").jsonPrimitive.content))
            assertEquals(o.toString(), if (o.getValue("dropped").jsonPrimitive.boolean) listOf(later) else sent + later, kept)
        }
        assertEquals(PinConnection.entries.map { it.wire }.toSet(), contract.getValue("drops").jsonArray.map { it.jsonObject.getValue("connection").jsonPrimitive.content }.toSet())
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/server_tls_report.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }

    @Test
    fun `a sighting is added only when it differs from the last, at most eight kept`() {
        val a = OutsideRow(OutsideRow.SEEN, "vibb.example.org", "emSign Root CA - G1", "N2oacIKlk9zMINVh0Rnpq40w8RzDIdCjf6QfDfKE4Bw=", "wifi", 1)
        assertEquals(listOf(a), addOutside(addOutside(emptyList(), a), a.copy(atMs = 31_000)))
        val b = a.copy(network = "mobile", atMs = 2)
        assertEquals(listOf(a, b), addOutside(listOf(a), b))
        var ring = emptyList<OutsideRow>()
        for (i in 0 until 20) ring = addOutside(ring, a.copy(kind = if (i % 2 == 0) OutsideRow.SEEN else OutsideRow.REFUSED, atMs = i.toLong()))
        assertEquals(OUTSIDE_MAX, ring.size)
        assertEquals(19L, ring.last().atMs)
        assertEquals(ring, decodeRing(encodeRing(ring)))
        for (junk in listOf(null, "", "nonsense", "{}", """[{"kind": 1}]""")) assertEquals(junk.toString(), emptyList<OutsideRow>(), decodeRing(junk))
    }

    @Test
    fun `enforce is a JSON Boolean in an object, nothing else`() {
        fun e(json: String?): Boolean? = enforceOf(json?.let { Json.parseToJsonElement(it) })
        assertEquals(true, e("""{"enforce": true}"""))
        assertEquals(false, e("""{"enforce": false}"""))
        for (keep in listOf(null, "{}", "null", "true", "\"on\"", """{"enforce": "true"}""", """{"enforce": null}""", """{"enforce": 1}""", """[true]""", """{"on": true}""")) {
            assertNull(keep, e(keep))
        }
    }

    @Test
    fun `an anchor's name is its CN in either spacing, cut to 64`() {
        assertEquals("emSign Root CA - G1", anchorName("CN=emSign Root CA - G1,OU=emSign PKI,O=eMudhra Technologies Limited,C=IN"))
        assertEquals("emSign Root CA - G1", anchorName("C=IN, OU=emSign PKI, O=eMudhra Technologies Limited, CN=emSign Root CA - G1"))
        assertEquals("O=No Name", anchorName(" O=No Name "))
        assertEquals(64, anchorName("CN=" + "x".repeat(200)).length)
    }

    @Test
    fun `the anchor of OkHttp 4_12's pin failure is the validated chain's last`() {
        val root = TestOnlyChain.root.certificate
        val chain = listOf(TestOnlyChain.leaf.certificate, TestOnlyChain.intermediate.certificate, root)
        val pinner = CertificatePinner.Builder().add("localhost", *BUILTIN_PINS.map { "sha256/${it.pin}" }.toTypedArray()).build()
        try {
            pinner.check("localhost", chain)
            fail("a chain outside the set passed")
        } catch (e: SSLPeerUnverifiedException) {
            assertTrue(e.message!!.startsWith(PIN_FAILURE_PREFIX))
            assertEquals(Anchor("Test-only Root", spkiPin(root)), pinFailureAnchor(e))
            assertEquals(chainAnchor(chain), pinFailureAnchor(e))
        }
        // Code QA L-1: a leaf whose subject carries OkHttp's own phrases, or a newline and a pinned root's line,
        // still reports the real anchor - the block ends at the last "Pinned certificates for", lines are strict.
        val x1 = "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="
        for (cn in listOf("ISRG Root X1 Pinned certificates for x", "a\n  Pinned certificates for x:\n    sha256/$x1: CN=ISRG Root X1", "b\n    sha256/$x1: CN=ISRG Root X1")) {
            val leaf = okhttp3.tls.HeldCertificate.Builder().commonName(cn).signedBy(TestOnlyChain.intermediate).build()
            try {
                pinner.check("localhost", listOf(leaf.certificate, TestOnlyChain.intermediate.certificate, root))
                fail("a chain outside the set passed")
            } catch (e: SSLPeerUnverifiedException) {
                assertEquals(cn, Anchor("Test-only Root", spkiPin(root)), pinFailureAnchor(e))
            }
        }
        // A message of another shape: a pin failure still, but no anchor read from it.
        val odd = SSLPeerUnverifiedException("$PIN_FAILURE_PREFIX\n  Peer certificate chain:\n    sha256/short: CN=x\n  Pinned certificates for localhost:\n")
        assertTrue(isPinFailure(odd))
        assertNull(pinFailureAnchor(odd))
        // A hostname failure (OkHttp's other SSLPeerUnverifiedException) and anything else is not a pin failure.
        assertNull(pinFailureAnchor(SSLPeerUnverifiedException("Hostname localhost not verified:\n    certificate: sha256/x=")))
        assertNull(pinFailureAnchor(java.io.IOException(PIN_FAILURE_PREFIX)))
        assertNull(pinFailureAnchor(SSLPeerUnverifiedException("$PIN_FAILURE_PREFIX\n  Peer certificate chain:\n")))
        // Without a TLS handshake: no connection judged, nothing seen (a debug build's plain http, R-5).
        assertEquals(PinConnection.NONE, pinConnection(ServerPath.Direct("http://10.0.2.2:3100/", null), null))
        assertNull(seenOf(null, "wifi", 1))
    }

    private val main = File("src/main/java/com/kidslauncher/mdm")

    private fun callers(call: String): Map<String, Int> = main.walk().filter { it.isFile && it.extension == "kt" }
        .associate { it.name to Regex(Regex.escape(call)).findAll(it.readText()).count() }.filterValues { it > 0 }

    @Test
    fun `the flag comes from a fresh policy only, the pinner from the transport only`() {
        // R-5: one caller of nextFlag, the sync's fresh fetch - right after the accepted policy is cached, with the
        // response's own handshake and the client's path.
        assertEquals(mapOf("ServerPins.kt" to 1, "MdmSyncWorker.kt" to 1), callers("nextFlag("))
        val sync = File(main, "server/MdmSyncWorker.kt").readText()
        val fresh = sync.substringAfter("    if (freshPolicy != null) {\n        storeAcceptedPolicy(context, freshPolicy)\n")
        assertTrue(fresh.substringBefore("CallPolicyStore.refresh(context)").contains(
            "nextFlag(ServerPinStore.enforce(context), enforceOf(freshPolicy.serverTls), pinConnection(apiPath, policyHandshake))",
        ))
        assertTrue(sync.contains("fetchPolicy(api) { policyHandshake = it.handshake }"))
        // The cached policy's readers never set the flag.
        for (name in listOf("cachedPolicy", "currentPolicyDecision", "reevaluateLockReasonFromCache")) {
            val body = sync.substringAfter("fun $name(", "").substringBefore("\nfun ").substringBefore("\nprivate fun ")
            assertFalse(name, body.contains("setEnforce") || body.contains("nextFlag"))
        }
        // The recovery address: one caller, the same fresh block, by the same connection.
        assertEquals(mapOf("ServerPins.kt" to 1, "MdmSyncWorker.kt" to 1), callers("nextRecovery("))
        assertEquals(mapOf("MdmSyncWorker.kt" to 1), callers("ServerPinStore.setRecovery("))
        assertTrue(fresh.substringBefore("CallPolicyStore.refresh(context)").contains(
            "nextRecovery(ServerPinStore.recovery(context), freshPolicy.serverTls, pinConnection(apiPath, policyHandshake))",
        ))
        // The shutdown's prepared client is rebuilt when the flag moved.
        assertTrue(fresh.substringBefore("CallPolicyStore.refresh(context)").contains("if (ServerPinStore.enforce(context) != pinnedBefore) PowerEvents.prepare(context)"))
        // The flag is written by the sync and a setup QR (off) only.
        assertEquals(mapOf("MdmSyncWorker.kt" to 1, "Provisioning.kt" to 1), callers("ServerPinStore.setEnforce("))
        assertTrue(File(main, "server/Provisioning.kt").readText().contains("ServerPinStore.setEnforce(context, false)"))
        // OkHttp's pinner is set up in one place: the transport's direct path.
        assertEquals(mapOf("ServerTransport.kt" to 1), callers("certificatePinner("))
        // The report: the ring sent, dropped by the report's own connection.
        assertTrue(sync.contains("if (sent.isSuccessful) ServerPinStore.reported(context, tlsSent, pinConnection(apiPath, sent.raw().handshake))"))
        assertTrue(SERVER_PIN_CAPABILITY in STATUS_CAPABILITIES)
    }

    /** Code QA H-1: the recovery address is taken only over a passing or a tailnet connection, only as tailnet http. */
    @Test
    fun `every recovery row of the contract`() {
        val rows = contract.getValue("recovery").jsonArray
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val o = row.jsonObject
            val from = o.getValue("from").let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
            val stored = o.getValue("stored").let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
            assertEquals(o.toString(), stored, nextRecovery(from, o["server_tls"], connection(o.getValue("connection").jsonPrimitive.content)))
        }
        // The server's policy row with an address is one the phone takes.
        val withUrl = contract.getValue("policy").jsonArray.map { it.jsonObject }.single { "recovery_url" in it }
        assertEquals(withUrl.getValue("recovery_url").jsonPrimitive.content, recoveryOf(withUrl["server_tls"]))
    }

    /** The contract's `server_tls` values are those of a policy row, a malformed row or none: keep this test honest. */
    @Test
    fun `the contract's flag rows cover every value and connection from both starts`() {
        val rows = contract.getValue("flags").jsonArray.map { it.jsonObject }
        val values = rows.map { it["server_tls"]?.toString() }.toSet()
        assertTrue(null in values)
        for (policy in contract.getValue("policy").jsonArray) assertTrue((policy.jsonObject["server_tls"] as JsonElement).toString() in values)
        for (value in values) for (c in PinConnection.entries) for (from in listOf(false, true)) {
            assertEquals(1, rows.count { it["server_tls"]?.toString() == value && it.getValue("connection").jsonPrimitive.content == c.wire && it.getValue("from").jsonPrimitive.boolean == from })
        }
    }
}
