package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 24b §1.6 (QA #1, the user's answer 2): an app installed, updated or re-signed during a
 * parent-PIN window is hidden at the relock unless it is allowlisted with its expected signer.
 */
class RelockBlockTest {

    private val trusted = setOf(PLAY, OWN)

    private fun pkg(name: String = CHAT, signers: Set<String> = setOf(REAL), updatedAt: Long = 1L, installer: String? = PLAY) =
        InstalledPackage(name, signers, updatedAt, installer)

    private fun decide(pkg: InstalledPackage, allowlisted: Boolean = true, atStart: PackageState? = PackageState(setOf(REAL), 1L), expected: Set<String>? = atStart?.signers) =
        relockBlockDecision(pkg, allowlisted, atStart, expected, trusted)

    @Test
    fun `blocked - an allowlisted app re-signed, and a new one from the package installer`() {
        // Uninstalled and sideloaded again under the same name, another key.
        assertEquals(RelockBlock.BLOCK, decide(pkg(signers = setOf(EVIL), updatedAt = 2L, installer = SIDELOAD)))
        // Even "installed by Play" can't hide another key.
        assertEquals(RelockBlock.BLOCK, decide(pkg(signers = setOf(EVIL), updatedAt = 2L)))
        // An allowlisted name that wasn't installed before: no signer known, so the installer decides.
        assertEquals(RelockBlock.BLOCK, decide(pkg(signers = setOf(EVIL), installer = SIDELOAD), atStart = null))
        assertEquals(RelockBlock.BLOCK, decide(pkg(signers = setOf(EVIL), installer = null), atStart = null))
    }

    @Test
    fun `not blocked - a new allowlisted app from Play or us, an update with the same signer`() {
        assertEquals(RelockBlock.NONE, decide(pkg(installer = PLAY), atStart = null))
        assertEquals(RelockBlock.NONE, decide(pkg(installer = OWN), atStart = null))
        assertEquals(RelockBlock.NONE, decide(pkg(updatedAt = 5L)))
        // Unchanged since the window opened.
        assertEquals(RelockBlock.NONE, decide(pkg(installer = SIDELOAD)))
        // A key rotation: the signing history keeps the old certificate.
        assertEquals(RelockBlock.NONE, decide(pkg(signers = setOf(REAL, "rotated"), updatedAt = 5L)))
    }

    @Test
    fun `an app that isn't allowlisted is hidden by apply anyway - reported only if new`() {
        assertEquals(RelockBlock.REPORT, decide(pkg(name = "com.example.game", installer = SIDELOAD), allowlisted = false, atStart = null))
        assertEquals(RelockBlock.NONE, decide(pkg(name = "com.example.game", signers = setOf(EVIL), updatedAt = 3L), allowlisted = false))
    }

    @Test
    fun `M10's expected signer wins over the snapshot's`() {
        // The snapshot saw the evil signer (it was already there), the locked one says otherwise.
        val check = signerCheck(
            installed = listOf(pkg(signers = setOf(EVIL), updatedAt = 2L)),
            snapshot = mapOf(CHAT to PackageState(setOf(EVIL), 1L)),
            allowlist = listOf(CHAT),
            blockedBefore = emptyMap(),
            trustedInstallers = trusted,
            lockedSigners = mapOf(CHAT to setOf(REAL)),
        )
        assertEquals(mapOf(CHAT to setOf(REAL)), check.blocked)
        // A new package from Play with the locked signer passes; another key doesn't.
        assertEquals(RelockBlock.NONE, decide(pkg(), atStart = null, expected = setOf(REAL)))
        assertEquals(RelockBlock.BLOCK, decide(pkg(signers = setOf(EVIL)), atStart = null, expected = setOf(REAL)))
    }

    @Test
    fun `the signer check over every package`() {
        val check = signerCheck(
            installed = listOf(
                pkg(signers = setOf(EVIL), updatedAt = 2L, installer = SIDELOAD),
                pkg(name = "org.example.maps", updatedAt = 7L),
                pkg(name = "com.example.game", installer = SIDELOAD),
                pkg(name = "org.example.new", installer = PLAY),
            ),
            snapshot = mapOf(CHAT to PackageState(setOf(REAL), 1L), "org.example.maps" to PackageState(setOf(REAL), 1L)),
            allowlist = listOf(CHAT, "org.example.maps", "org.example.new"),
            blockedBefore = emptyMap(),
            trustedInstallers = trusted,
        )
        assertEquals(mapOf(CHAT to setOf(REAL)), check.blocked)
        assertEquals(setOf(CHAT), check.newlyBlocked)
        assertEquals(setOf(CHAT, "com.example.game"), check.reported)
        // No snapshot (an adopted window): nothing new is judged.
        assertTrue(signerCheck(listOf(pkg(signers = setOf(EVIL))), null, listOf(CHAT), emptyMap(), trusted).blocked.isEmpty())
    }

    @Test
    fun `a block clears when the app is uninstalled or its signer is back`() {
        val before = mapOf(CHAT to setOf(REAL), "org.example.gone" to setOf(REAL), "org.example.side" to null)
        // The kid's copy still there; the real app back from Play in a later pause; one gone.
        val stays = signerCheck(
            installed = listOf(pkg(signers = setOf(EVIL), installer = SIDELOAD), pkg(name = "org.example.side", installer = SIDELOAD)),
            snapshot = mapOf(CHAT to PackageState(setOf(EVIL), 1L)),
            allowlist = listOf(CHAT, "org.example.side"),
            blockedBefore = before,
            trustedInstallers = trusted,
        )
        assertEquals(mapOf(CHAT to setOf(REAL), "org.example.side" to null), stays.blocked)
        // The block's own expected signer decides, not the snapshot (which holds the bad one).
        assertTrue(stays.newlyBlocked.isEmpty())

        val back = signerCheck(
            installed = listOf(pkg(updatedAt = 9L), pkg(name = "org.example.side", installer = PLAY, updatedAt = 9L)),
            snapshot = mapOf(CHAT to PackageState(setOf(EVIL), 1L)),
            allowlist = listOf(CHAT, "org.example.side"),
            blockedBefore = before,
            trustedInstallers = trusted,
        )
        assertTrue(back.blocked.isEmpty())
        assertTrue(blockStays(pkg(signers = setOf(EVIL)), setOf(REAL), trusted))
        assertFalse(blockStays(pkg(), setOf(REAL), trusted))
        assertFalse(blockStays(pkg(installer = OWN), null, trusted))
    }

    @Test
    fun `allow_blocked clears every block, and blocked apps count as not allowlisted`() {
        val store = WindowRelockStore(ParentPinStoreTest.FakePrefs())
        store.saveBlocked(mapOf(CHAT to setOf(REAL), "org.example.side" to null))
        assertEquals(mapOf(CHAT to setOf(REAL), "org.example.side" to null), store.blocked())
        assertEquals(2, store.clearBlocked())
        assertTrue(store.blocked().isEmpty())

        assertEquals(listOf("org.example.maps"), effectiveAllowlist(listOf(CHAT, "org.example.maps"), setOf(CHAT)))
        assertEquals(null, effectiveAllowlist(null, setOf(CHAT)))
        // A new install of a blocked package is suspended and hidden at once.
        val decision = PolicyToApply.Apply(com.kidslauncher.mdm.server.dto.PolicyResponse(allowlist = listOf(CHAT)))
        assertTrue(shouldSuspendNewPackage(CHAT, decision, overrideActive = false, ownPackage = OWN, systemDialer = null, relockBlocked = setOf(CHAT)))
        assertFalse(shouldSuspendNewPackage(CHAT, decision, overrideActive = false, ownPackage = OWN, systemDialer = null))
        // Nothing is blocked during a window.
        assertFalse(shouldSuspendNewPackage(CHAT, decision, overrideActive = true, ownPackage = OWN, systemDialer = null, relockBlocked = setOf(CHAT)))
    }

    private companion object {
        const val CHAT = "org.example.chat"
        const val PLAY = "com.android.vending"
        const val OWN = "me.vibb.launcher"
        const val SIDELOAD = "com.google.android.packageinstaller"
        const val REAL = "a1a1"
        const val EVIL = "e5e5"
    }
}
