package com.kidslauncher.mdm.server

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * Design 76's throwaway chain for the JVM tests, made in memory at each run (okhttp-tls): a test root, an intermediate
 * and a leaf for localhost and 127.0.0.1 (EC P-256). Nothing private is ever written or committed; no phone and no
 * server trusts this root.
 */
object TestOnlyChain {
    val root: HeldCertificate = HeldCertificate.Builder()
        .certificateAuthority(1)
        .commonName("Test-only Root")
        .organizationalUnit("Vibb test only")
        .build()

    val intermediate: HeldCertificate = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("Test-only Intermediate")
        .organizationalUnit("Vibb test only")
        .signedBy(root)
        .build()

    val leaf: HeldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .signedBy(intermediate)
        .build()

    /** The TLS server's side: the leaf and the intermediate, as a real server sends them. */
    val server: HandshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(leaf, intermediate.certificate)
        .build()

    /** The client's side: the test root trusted as Android trusts a system CA. */
    val client: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(root.certificate)
        .build()
}
