package me.vibb.music.sonos

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `another uid, no udn, another root, a self-signed or expired leaf, a missing intermediate - each refused`() {
        assertEquals("udn mismatch", refusal("leaf_wrong_udn"))
        assertEquals("udn mismatch", refusal("leaf_good", uid = "RINCON_00000000000001401"))
        assertEquals("udn mismatch", refusal("leaf_good", uid = ""))
        assertEquals("no udn", refusal("leaf_no_san"))
        assertEquals("no udn", refusal("leaf_dns_only"))
        assertEquals("chain", refusal("leaf_other_root"))
        assertEquals("chain", refusal("leaf_other_root", "other_root"))
        assertEquals("chain", refusal("leaf_self_signed"))
        assertEquals("chain", refusal("leaf_expired"))
        assertEquals("the intermediate not sent: never fetched", "chain", refusal("leaf_via_intermediate"))
        assertEquals("no chain", speakerCertRefusal(emptyArray(), ecdhe, uid, testRoots))
        assertEquals("no chain", speakerCertRefusal(null, ecdhe, uid, testRoots))
        assertEquals("the anchor itself as the leaf: trusted, but it names no player", "no udn", refusal("test_root"))
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
        }
        assertEquals(listOf("sonos socket: certificate refused (chain)"), logs)
        try {
            trust.checkClientTrusted(arrayOf(cert("leaf_good")), ecdhe)
            fail("no client certificates")
        } catch (_: java.security.cert.CertificateException) {
        }
    }

    @Test
    fun `a refusal logs its class, a debug build the leaf's CNs and the verdict once - never the SAN`() {
        val logs = mutableListOf<String>()
        val debug = mutableListOf<String>()
        val testRoot = cert("test_root")
        // a uid of this test's own: the debug line is once per uid and process
        val udn = speakerSocketClient(OkHttpClient(), "10.0.0.5", "RINCON_00000000000001499", { logs += it }, { debug += it }, testRoot)
        try {
            udn.x509TrustManager!!.checkServerTrusted(arrayOf(cert("leaf_good")), ecdhe)
            fail("another uid")
        } catch (_: SpeakerCertificateException) {
        }
        assertEquals(listOf("sonos socket: certificate refused (udn mismatch)"), logs)
        assertEquals(listOf("sonos socket: certificate CN \"TEST00000000\", issuer CN \"Vibb Test Root\" - refused (udn mismatch)"), debug)

        val good = speakerSocketClient(OkHttpClient(), "10.0.0.5", uid, { logs += it }, { debug += it }, testRoot)
        good.x509TrustManager!!.checkServerTrusted(arrayOf(cert("leaf_good")), ecdhe)
        good.x509TrustManager!!.checkServerTrusted(arrayOf(cert("leaf_good")), ecdhe)
        assertEquals("an accepted certificate logs nothing in a release build", 1, logs.size)
        assertEquals(
            "once per uid",
            listOf("sonos socket: certificate CN \"TEST00000000\", issuer CN \"Vibb Test Root\" - verified (the pinned root, the udn)"),
            debug.drop(1),
        )
        for (line in logs + debug) {
            for (secret in listOf("urn:sonos", "RINCON", "Sonos_TEST", "sonos-0000")) assertTrue(line, secret !in line)
        }
    }

    @Test
    fun `commonName reads an RFC 2253 name with its escapes`() {
        assertEquals("Sonos\\, Inc", commonName(X500Principal("CN=Sonos\\, Inc, O=Vibb Test")))
        assertEquals("TEST00000000", commonName(X500Principal("O=Vibb Test, OU=Test Devices, CN=TEST00000000")))
        assertNull(commonName(X500Principal("O=Vibb Test")))
    }
}
