package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Security review M10, the phone side: a catalog APK is checked against the server's pins before it installs. */
class CatalogApkCheckTest {
    private val pkg = "org.example.chat"
    private val pinned = "a".repeat(64)
    private val rotated = "b".repeat(64)
    private val stranger = "c".repeat(64)

    @Test
    fun `the right package and key install`() {
        assertEquals(CatalogApkVerdict.OK, catalogApkCheck(pkg, pinned, pkg, setOf(pinned)))
    }

    @Test
    fun `another package or another key is refused`() {
        assertEquals(CatalogApkVerdict.WRONG_PACKAGE, catalogApkCheck(pkg, pinned, "com.android.settings", setOf(pinned)))
        assertEquals(CatalogApkVerdict.WRONG_SIGNER, catalogApkCheck(pkg, pinned, pkg, setOf(stranger)))
        assertEquals("package before signer", CatalogApkVerdict.WRONG_PACKAGE, catalogApkCheck(pkg, pinned, "other", setOf(stranger)))
    }

    /** Android 13+ verifies a v3.1 rotation the server ignores: the pin is the older key, the APK's
     * current signer the newer one - the lineage (signingCertificateHistory) holds both. */
    @Test
    fun `a rotated key passes through its lineage`() {
        assertEquals(CatalogApkVerdict.OK, catalogApkCheck(pkg, pinned, pkg, setOf(pinned, rotated)))
        assertEquals("the new key alone isn't the pin", CatalogApkVerdict.WRONG_SIGNER, catalogApkCheck(pkg, pinned, pkg, setOf(rotated)))
    }

    /** With several signers the server pins the first; it must be one of them. */
    @Test
    fun `several signers pass when the pinned one is among them`() {
        assertEquals(CatalogApkVerdict.OK, catalogApkCheck(pkg, pinned, pkg, setOf(stranger, pinned)))
        assertEquals(CatalogApkVerdict.WRONG_SIGNER, catalogApkCheck(pkg, pinned, pkg, setOf(stranger, rotated)))
    }

    @Test
    fun `an unreadable APK fails closed`() {
        assertEquals(CatalogApkVerdict.UNREADABLE, catalogApkCheck(pkg, pinned, null, setOf(pinned)))
        assertEquals(CatalogApkVerdict.UNREADABLE, catalogApkCheck(pkg, pinned, "", setOf(pinned)))
        assertEquals("no signers read", CatalogApkVerdict.UNREADABLE, catalogApkCheck(pkg, pinned, pkg, null))
        assertEquals(CatalogApkVerdict.UNREADABLE, catalogApkCheck(pkg, pinned, pkg, emptySet()))
    }

    @Test
    fun `without both pins nothing installs`() {
        assertEquals(CatalogApkVerdict.NOT_PINNED, catalogApkCheck("", pinned, pkg, setOf(pinned)))
        assertEquals(CatalogApkVerdict.NOT_PINNED, catalogApkCheck(pkg, null, pkg, setOf(pinned)))
        assertEquals("not a SHA-256", CatalogApkVerdict.NOT_PINNED, catalogApkCheck(pkg, "abc", pkg, setOf("abc")))
        assertFalse(catalogPinned(pkg, null))
        assertFalse(catalogPinned(" ", pinned))
        assertTrue(catalogPinned(pkg, pinned))
    }

    @Test
    fun `the pin is compared as lower-case hex without colons`() {
        val spelled = pinned.uppercase().chunked(2).joinToString(":")
        assertEquals(pinned, normalizedSigner(spelled))
        assertEquals(CatalogApkVerdict.OK, catalogApkCheck(pkg, spelled, pkg, setOf(pinned)))
        assertTrue(catalogPinned(pkg, spelled))
    }

    @Test
    fun `the wire states are the server's`() {
        assertEquals(
            listOf("refused_unpinned", "refused_unreadable", "refused_package", "refused_signer"),
            CatalogApkVerdict.entries.drop(1).map { it.wire },
        )
        assertTrue(CatalogApkVerdict.entries.all { it.wire.length <= 32 })
    }

    @Test
    fun `refusals follow the list`() {
        val signer = CatalogRefusal(1, "v1@10", "refused_signer", 100)
        val stale = CatalogRefusal(2, "v1@11", "refused_package", 100)
        val wasUnpinned = CatalogRefusal(3, "v2@12", "refused_unpinned", 50)
        val nowPinned = CatalogRefusal(4, "v1@13", "refused_unpinned", 50)
        val listed = mapOf(1L to "v1@10", 2L to "v2@20", 3L to "v2@12", 4L to "v1@13", 5L to "v9@99")
        val unpinned = mapOf(3L to "v2@12", 5L to "v9@99")
        val after = reconcileRefusals(listOf(signer, stale, wasUnpinned, nowPinned), listed, unpinned, nowMs = 900)
        assertEquals(
            listOf(signer, wasUnpinned, CatalogRefusal(5, "v9@99", "refused_unpinned", 900)),
            after,
        )
        assertEquals("a new release clears it", emptyList<CatalogRefusal>(), reconcileRefusals(listOf(signer), mapOf(1L to "v2@1"), emptyMap(), 900))
        assertEquals(
            listOf(CatalogRefusal(1, "v2@1", "refused_unreadable", 7)),
            withRefusal(listOf(signer), CatalogRefusal(1, "v2@1", "refused_unreadable", 7)),
        )
    }

    @Test
    fun `the records carry the pins from the list`() {
        val wanted = WantedDownload(9, "v1@1", "Chat", false, "/api/devices/apps/9/download", null, pkg, pinned)
        val record = reconcileRecords(emptyList(), listOf(wanted), 5).single()
        assertEquals(pkg, record.packageName)
        assertEquals(pinned, record.signerSha256)
        val older = record.copy(packageName = "", signerSha256 = null, etag = "\"e\"")
        val refreshed = reconcileRecords(listOf(older), listOf(wanted), 6).single()
        assertEquals(pkg, refreshed.packageName)
        assertEquals(pinned, refreshed.signerSha256)
        assertEquals("the resume state stays", "\"e\"", refreshed.etag)
    }

    /** The wiring: checked before anything installs, the session held to the package, unpinned apps not queued. */
    @Test
    fun `the check runs before the install`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        fun source(name: String) = File(src, "main/java/com/kidslauncher/mdm/server/$name").readText()
        val downloads = source("AppDownloads.kt")
        val install = downloads.substringAfter("private suspend fun install(").substringBefore("\n    }\n")
        val verdict = install.indexOf("catalogVerdict(app, rec, file)")
        assertTrue(verdict >= 0)
        assertTrue(verdict < install.indexOf("deferInstall"))
        assertTrue(verdict < install.indexOf("AppInstaller.installSilently("))
        assertTrue("expectedPackage = rec.packageName" in install)
        assertTrue(
            "getPackageArchiveInfo with signing certificates",
            "PackageManager.GET_SIGNING_CERTIFICATES" in downloads.substringAfter("private fun catalogVerdict("),
        )
        assertTrue("params.setAppPackageName(it)" in source("AppInstaller.kt"))
        val sync = source("MdmSyncWorker.kt")
        assertTrue("if (!catalogPinned(update.packageName, update.signerSha256))" in sync)
        assertTrue("expectedPackage = context.packageName" in sync)
    }

    /** The user's emulator (2026-10-12): an app uninstalled by hand was never installed again -
     * the record alone said "installed". Now only a copy on the phone counts. */
    @Test
    fun `a release removed from the phone is installed again`() {
        assertFalse("uninstalled by hand", catalogReleaseStillInstalled(PhonePackage.Missing, 5))
        assertFalse("an older build's record", catalogReleaseStillInstalled(PhonePackage.Missing, null))
        assertTrue(catalogReleaseStillInstalled(PhonePackage.Installed(5), 5))
        assertTrue("updated past it", catalogReleaseStillInstalled(PhonePackage.Installed(6), 5))
        assertFalse("an older copy put back", catalogReleaseStillInstalled(PhonePackage.Installed(4), 5))
        assertTrue("no recorded version: installed is enough", catalogReleaseStillInstalled(PhonePackage.Installed(1), null))
        assertTrue("PackageManager couldn't tell: never a reinstall loop", catalogReleaseStillInstalled(PhonePackage.Unknown, 5))
    }

    /** The wiring: the sync forgets the record of a release that's gone and lets the usual checks
     * (pins, refusal, backoff) decide; the installed versionCode is recorded at install. */
    @Test
    fun `the sync reinstalls a release that is gone`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        fun source(name: String) = File(src, "main/java/com/kidslauncher/mdm/server/$name").readText()
        val sync = source("MdmSyncWorker.kt")
        val loop = sync.substringAfter("for (update in otherUpdates) {").substringBefore("wanted += update.wanted()")
        val installedCheck = loop.indexOf("if (catalogReleaseStillInstalled(phone, known?.lastInstalledVersionCode)) continue")
        val forget = loop.indexOf("TrackedAppUpdateState.forgetInstalled(context, key)")
        val pins = loop.indexOf("if (!catalogPinned(update.packageName, update.signerSha256))")
        assertTrue(installedCheck in 0 until forget && forget < pins)
        assertTrue("PackageManager.MATCH_UNINSTALLED_PACKAGES" in sync.substringAfter("private fun phonePackage("))
        assertTrue(
            "intent.getLongExtra(APP_INSTALL_VERSION_CODE_EXTRA, -1L)" in source("AppInstallReceiver.kt"),
        )
        assertTrue("versionCode = versionCode," in source("AppDownloads.kt"))
    }
}
