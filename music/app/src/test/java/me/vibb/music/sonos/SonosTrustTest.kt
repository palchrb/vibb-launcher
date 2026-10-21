package me.vibb.music.sonos

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * The event socket's certificate check (design 53 §2.2 "Trust", round 53-c): the chain against the pinned Sonos root
 * only, the leaf's `urn:sonos:udn:` against the uid we dial. The test chains are `sonos/certs/` (a throwaway "Vibb Test
 * Root", made by its `make-certs.sh`; no keys kept - README.md there); the pinned root itself is checked by its
 * fingerprint, the one the user's speaker's chain ended in.
 */
class SonosTrustTest {
    private val uid = "RINCON_00000000000001400"
    private val ecdhe = "ECDHE_ECDSA"

    private fun cert(name: String): X509Certificate =
        pemCertificate(javaClass.classLoader!!.getResource("sonos/certs/$name.pem")!!.readText())

    private val testRoots = rootOnlyTrust(cert("test_root"))

    private fun refusal(vararg chain: String, uid: String = this.uid) =
        speakerCertRefusal(chain.map(::cert).toTypedArray(), ecdhe, uid, testRoots)

    @Test
    fun `the pinned root is the Sonos Device Authentication Root CA the user's speaker chained to`() {
        val root = sonosRoot()
        val sha = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString(":") { "%02X".format(it) }
        assertEquals(SONOS_ROOT_SHA256, sha)
        assertEquals("Sonos Device Authentication Root CA", commonName(root.subjectX500Principal))
        assertEquals("self-signed", root.subjectX500Principal, root.issuerX500Principal)
        root.verify(root.publicKey)
        assertTrue("a CA", root.basicConstraints >= 0)
        assertTrue("valid until 2055", root.notAfter.time > 2_700_000_000_000L)
    }

    @Test
    fun `a chain to the root whose leaf names our uid is accepted - with an intermediate the speaker sends, too`() {
        assertNull(refusal("leaf_good"))
        assertNull("the root sent along", refusal("leaf_good", "test_root"))
        assertNull(refusal("leaf_via_intermediate", "test_intermediate"))
        assertNull(refusal("leaf_via_intermediate", "test_intermediate", "test_root"))
        assertNull("the udn's case", refusal("leaf_good", uid = uid.lowercase()))
        assertEquals(listOf(uid), certificateUdns(cert("leaf_good")))
        // qa-53c-code 4: the CA vouches for every name it lists - ours among others is enough
        assertEquals(listOf("RINCON_00000000000001401", uid), certificateUdns(cert("leaf_two_udns")))
        assertNull("our udn second", refusal("leaf_two_udns"))
        assertNull("the other one first", refusal("leaf_two_udns", uid = "RINCON_00000000000001401"))
    }

    /**
     * qa-53c-code 3: the udn's spelling against the uid the app commands. Measured on the user's speaker (2026-10-21,
     * here with a fake MAC): its SAN held `URI:urn:sonos:udn:RINCON_<12 hex>01400` and its device description
     * `<UDN>uuid:RINCON_<12 hex>01400</UDN>`; the app's uid is the latter without `uuid:` (`Soap.parseUdn`) - the
     * SAN's value after the prefix, character for character.
     */
    @Test
    fun `the SAN's udn is spelt as the uid the app reads from the device description`() {
        val description = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><UDN>uuid:$uid</UDN></device></root>"
        val ours = Soap.parseUdn(description)
        assertEquals(uid, ours)
        assertEquals(listOf(ours), certificateUdns(cert("leaf_good")))
        assertNull(refusal("leaf_good", uid = ours!!))
    }

    @Test
    fun `another uid, no udn, another root, a self-signed leaf, a missing intermediate - each refused`() {
        assertEquals("udn mismatch", refusal("leaf_wrong_udn"))
        assertEquals("udn mismatch", refusal("leaf_good", uid = "RINCON_00000000000001401"))
        assertEquals("udn mismatch", refusal("leaf_good", uid = ""))
        assertEquals("no udn", refusal("leaf_no_san"))
        assertEquals("no udn", refusal("leaf_dns_only"))
        assertEquals("chain", refusal("leaf_other_root"))
        assertEquals("chain", refusal("leaf_other_root", "other_root"))
        assertEquals("chain", refusal("leaf_self_signed"))
        assertEquals("the intermediate not sent: never fetched", "chain", refusal("leaf_via_intermediate"))
        assertEquals("no chain", speakerCertRefusal(emptyArray(), ecdhe, uid, testRoots))
        assertEquals("no chain", speakerCertRefusal(null, ecdhe, uid, testRoots))
        assertEquals("the anchor itself as the leaf: trusted, but it names no player", "no udn", refusal("test_root"))
    }

    /** qa-53c-code 5: a speaker's own valid leaf (CA:FALSE) can't mint a certificate naming our udn. */
    @Test
    fun `a leaf used as an issuer is refused - with or without the root sent along`() {
        assertEquals("chain", refusal("leaf_under_leaf", "leaf_good"))
        assertEquals("chain", refusal("leaf_under_leaf", "leaf_good", "test_root"))
        assertEquals("chain", refusal("leaf_under_leaf"))
    }

    /** qa-53c-code 2: outside its dates is the phone's clock as likely as the certificate - a class of its own. */
    @Test
    fun `a leaf outside its dates is refused as dates, not chain`() {
        assertEquals(CERT_DATES, refusal("leaf_expired"))
        assertEquals(CERT_DATES, refusal("leaf_not_yet_valid"))
    }

    @Test
    fun `the socket's client trusts the Sonos root alone - the system's CAs and the test root are not its anchors`() {
        val logs = mutableListOf<String>()
        val client = speakerSocketClient(OkHttpClient(), "10.0.0.5", uid, log = { logs += it })
        val trust = client.x509TrustManager!!
        assertEquals(listOf(sonosRoot()), trust.acceptedIssuers.toList())
        try {
            trust.checkServerTrusted(arrayOf(cert("leaf_good")), ecdhe)
            fail("a chain to another root")
        } catch (e: SpeakerCertificateException) {
            assertEquals("speaker certificate refused: chain", e.message)
            assertEquals("chain", e.reason)
        }
        assertEquals(listOf("sonos socket: certificate refused (chain)"), logs)
        try {
            trust.checkClientTrusted(arrayOf(cert("leaf_good")), ecdhe)
            fail("no client certificates")
        } catch (_: java.security.cert.CertificateException) {
        }
    }

    /** qa-53c-code 11 (M8): the host name verifier accepts exactly the dialled ip - no other name, no uid. */
    @Test
    fun `the socket's client verifies the host name as exactly its ip`() {
        val verifier = speakerSocketClient(OkHttpClient(), "10.0.0.5", uid).hostnameVerifier
        assertTrue(verifier.verify("10.0.0.5", null))
        for (name in listOf("10.0.0.6", uid, "sonos-000000000000.local", "", "10.0.0.50")) assertFalse(name, verifier.verify(name, null))
        val v6 = speakerSocketClient(OkHttpClient(), "fd00::5", uid).hostnameVerifier
        assertTrue("OkHttp's canonical form", v6.verify("fd00::5", null))
        assertFalse(speakerSocketClient(OkHttpClient(), "not an ip", uid).hostnameVerifier.verify("not an ip", null))
    }

    @Test
    fun `a refusal logs its class, a debug build the leaf's CNs and the verdict once per uid and verdict - never the SAN`() {
        val logs = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val client = speakerSocketClient(OkHttpClient(), "10.0.0.5", uid, { logs += it }, { debug += it }, testRoots)
        val trust = client.x509TrustManager!!
        repeat(2) {
            try {
                trust.checkServerTrusted(arrayOf(cert("leaf_wrong_udn")), ecdhe)
                fail("another uid's certificate (a stale ip)")
            } catch (_: SpeakerCertificateException) {
            }
        }
        assertEquals(List(2) { "sonos socket: certificate refused (udn mismatch)" }, logs)
        // qa-53c-code 7: a first refusal never hides the later verified line check 10 looks for
        trust.checkServerTrusted(arrayOf(cert("leaf_good")), ecdhe)
        trust.checkServerTrusted(arrayOf(cert("leaf_good")), ecdhe)
        assertEquals("an accepted certificate logs nothing in a release build", 2, logs.size)
        assertEquals(
            listOf(
                "sonos socket: certificate CN \"TEST00000000\", issuer CN \"Vibb Test Root\", valid to 2120 - refused (udn mismatch)",
                "sonos socket: certificate CN \"TEST00000000\", issuer CN \"Vibb Test Root\", valid to 2120 - verified (the pinned root, the udn)",
            ),
            debug,
        )
        for (line in logs + debug) {
            for (secret in listOf("urn:sonos", "RINCON", "Sonos_TEST", "sonos-0000")) assertTrue(line, secret !in line)
        }
    }

    /** qa-53c-code 8: the first CN relative name only - not one inside another attribute's value - printable, capped. */
    @Test
    fun `commonName reads an RFC 2253 name with its escapes, printable and capped`() {
        assertEquals("Sonos\\, Inc", commonName(X500Principal("CN=Sonos\\, Inc, O=Vibb Test")))
        assertEquals("TEST00000000", commonName(X500Principal("O=Vibb Test, OU=Test Devices, CN=TEST00000000")))
        assertNull(commonName(X500Principal("O=Vibb Test")))
        assertEquals("Real", commonName(X500Principal("O=Evil\\,CN=Fake, CN=Real")))
        assertNull("a CN inside a value is no CN", commonName(X500Principal("O=Evil\\,CN=Fake")))
        assertEquals("a", commonName(X500Principal("CN=a+O=b")))
        val wild = commonName(X500Principal("CN=a\u0007b\u001b[31m" + "x".repeat(100)))!!
        assertTrue(wild, wild.none { it.isISOControl() })
        assertEquals(64, wild.length)
    }
}
