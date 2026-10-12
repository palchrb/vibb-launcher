package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun decide(pkg: InstalledPackage, atStart: PackageState? = PackageState(setOf(REAL), 1L), expected: Set<String>? = atStart?.signers) =
        relockBlockDecision(pkg, atStart, expected, trusted)

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
    fun `an app that isn't allowlisted is blocked when sideloaded or re-signed, not when new from Play (QA Medium 2)`() {
        // A fake under a name the parent may tick later: blocked, so ticking the name won't show it.
        assertEquals(RelockBlock.BLOCK, decide(pkg(name = "com.whatsapp", installer = SIDELOAD), atStart = null))
        assertEquals(RelockBlock.BLOCK, decide(pkg(name = "com.example.game", signers = setOf(EVIL), updatedAt = 3L)))
        // From Play: apply() hides it until ticked.
        assertEquals(RelockBlock.NONE, decide(pkg(name = "com.example.game", installer = PLAY), atStart = null))
        // Not changed in the window: nothing.
        assertEquals(RelockBlock.NONE, decide(pkg(name = "com.example.game", installer = SIDELOAD)))
    }

    @Test
    fun `the catalog pin blocks another key, ticked or not, and allow_blocked cannot let it out`() {
        val pins = mapOf(CHAT to setOf(REAL))
        val check = signerCheck(
            installed = listOf(pkg(signers = setOf(EVIL), installer = PLAY)),
            snapshot = emptyMap(),
            allowlist = emptyList(),
            blockedBefore = emptyMap(),
            trustedInstallers = trusted,
            lockedSigners = pins,
        )
        assertEquals(mapOf(CHAT to setOf(REAL)), check.blocked)
        assertEquals(mapOf(CHAT to EVIL), check.signers)
        // "Allow these apps": the pinned app with another key stays, a sideloaded one goes.
        val blocked = mapOf(CHAT to setOf(REAL), "org.example.side" to null)
        assertEquals(mapOf(CHAT to setOf(REAL)), allowBlocked(blocked, mapOf(CHAT to setOf(EVIL), "org.example.side" to setOf("bbbb")), pins))
        assertEquals(emptyMap<String, Set<String>?>(), allowBlocked(blocked, mapOf(CHAT to setOf(REAL, "rotated"), "org.example.side" to setOf("bbbb")), pins))
    }

    @Test
    fun `without a snapshot, what came from elsewhere since the window opened is blocked (QA Low 2)`() {
        val check = signerCheck(
            installed = listOf(
                pkg(updatedAt = 900L, installer = SIDELOAD),
                pkg(name = "org.example.old", updatedAt = 100L, installer = SIDELOAD),
                pkg(name = "org.example.play", updatedAt = 900L, installer = PLAY),
            ),
            snapshot = null,
            allowlist = listOf(CHAT),
            blockedBefore = emptyMap(),
            trustedInstallers = trusted,
            windowStartMs = 500L,
        )
        assertEquals(mapOf<String, Set<String>?>(CHAT to null), check.blocked)
        // Without a start either: nothing to judge.
        assertTrue(signerCheck(listOf(pkg(signers = setOf(EVIL), installer = SIDELOAD)), null, listOf(CHAT), emptyMap(), trusted).blocked.isEmpty())
    }

    @Test
    fun `an install outside a window sets or clears a block (QA Low 3)`() {
        val now = 1_000_000L
        // The real app back from the catalog: its block goes.
        assertEquals(emptyMap<String, Set<String>?>(), blockAfterInstall(pkg(installer = OWN), mapOf(CHAT to null), emptyMap(), trusted, now))
        assertEquals(emptyMap<String, Set<String>?>(), blockAfterInstall(pkg(), mapOf(CHAT to setOf(REAL)), emptyMap(), trusted, now))
        // The fake still there: no change.
        assertNull(blockAfterInstall(pkg(signers = setOf(EVIL), installer = SIDELOAD), mapOf(CHAT to null), emptyMap(), trusted, now))
        // Just installed from elsewhere (a race with the relock): blocked.
        assertEquals(mapOf<String, Set<String>?>(CHAT to null), blockAfterInstall(pkg(updatedAt = now - 1_000, installer = SIDELOAD), emptyMap(), emptyMap(), trusted, now))
        // Another key than the catalog's pin, even from Play: blocked with the pin.
        assertEquals(mapOf(CHAT to setOf(REAL)), blockAfterInstall(pkg(signers = setOf(EVIL), updatedAt = now - 1_000), emptyMap(), mapOf(CHAT to setOf(REAL)), trusted, now))
        // An old app shown again (unhiding sends a package-added) or the one the parent allowed: left alone.
        assertNull(blockAfterInstall(pkg(updatedAt = 5L, installer = SIDELOAD), emptyMap(), emptyMap(), trusted, now))
        assertNull(blockAfterInstall(pkg(updatedAt = now - 1_000, installer = SIDELOAD), emptyMap(), emptyMap(), trusted, now, allowed = mapOf(CHAT to now - 1_000)))
        // From Play with no pin: fine.
        assertNull(blockAfterInstall(pkg(updatedAt = now - 1_000, installer = PLAY), emptyMap(), emptyMap(), trusted, now))
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
        assertEquals(mapOf(CHAT to setOf(REAL), "com.example.game" to null), check.blocked)
        assertEquals(setOf(CHAT, "com.example.game"), check.newlyBlocked)
        assertEquals(setOf(CHAT, "com.example.game"), check.reported)
        assertEquals(mapOf(CHAT to EVIL, "com.example.game" to REAL), check.signers)
        // Apps unmanaged (calls-only phone): nothing is hidden, so nothing is blocked or reported.
        val unmanaged = signerCheck(listOf(pkg(signers = setOf(EVIL), installer = SIDELOAD)), emptyMap(), null, emptyMap(), trusted)
        assertTrue(unmanaged.blocked.isEmpty() && unmanaged.reported.isEmpty())

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
    fun `allow_blocked clears the blocks without a pin, and blocked apps count as not allowlisted`() {
        val store = WindowRelockStore(ParentPinStoreTest.FakePrefs())
        store.saveBlocked(mapOf(CHAT to setOf(REAL), "org.example.side" to null))
        assertEquals(mapOf(CHAT to setOf(REAL), "org.example.side" to null), store.blocked())
        val after = allowBlocked(store.blocked(), mapOf(CHAT to setOf(EVIL), "org.example.side" to setOf("bbbb")), emptyMap())
        assertTrue(store.saveAllowed(after, mapOf(CHAT to 2L, "org.example.side" to 3L)))
        assertTrue(store.blocked().isEmpty())
        assertEquals(mapOf(CHAT to 2L, "org.example.side" to 3L), store.allowed())

        assertEquals(listOf("org.example.maps"), effectiveAllowlist(listOf(CHAT, "org.example.maps"), setOf(CHAT)))
        assertEquals(null, effectiveAllowlist(null, setOf(CHAT)))
        // A new install of a blocked package is suspended and hidden at once.
        val decision = PolicyToApply.Apply(com.kidslauncher.mdm.server.dto.PolicyResponse(allowlist = listOf(CHAT)))
        assertTrue(shouldSuspendNewPackage(CHAT, decision, overrideActive = false, ownPackage = OWN, systemDialer = null, relockBlocked = setOf(CHAT)))
        assertFalse(shouldSuspendNewPackage(CHAT, decision, overrideActive = false, ownPackage = OWN, systemDialer = null))
        // Nothing is blocked during a window.
        assertFalse(shouldSuspendNewPackage(CHAT, decision, overrideActive = true, ownPackage = OWN, systemDialer = null, relockBlocked = setOf(CHAT)))
    }

    @Test
    fun `an install-time block reaches the report with its key and why, and Allow these apps lets it out (fix 2026-10-12)`() {
        val store = WindowRelockStore(ParentPinStoreTest.FakePrefs())
        val now = 1_760_100_000_000L
        val music = "me.vibb.music.debug"
        val debugKey = "d3b0" + "0".repeat(56) + "6a6e"
        val fakeKey = "e5e5" + "0".repeat(56) + "e5e5"
        val pinKey = "a1a1" + "0".repeat(56) + "a1a1"
        // adb install outside a window: no Play, no pin - blocked, with its note.
        val installed = pkg(name = music, signers = setOf(debugKey), updatedAt = now - 2_000, installer = null)
        val after = blockAfterInstall(installed, store.blocked(), emptyMap(), trusted, now)!!
        assertEquals(BlockReason.OUTSIDE_PLAY, blockReason(installed, null, null))
        store.saveBlocked(after, mapOf(music to BlockNote(BlockReason.OUTSIDE_PLAY, BlockFound.INSTALL, now)))
        // An imposter of a catalog app, set by a relock: another key than the pin.
        val imposter = pkg(signers = setOf(fakeKey), updatedAt = now - 1_000, installer = SIDELOAD)
        val check = signerCheck(listOf(installed, imposter), emptyMap(), listOf(CHAT), store.blocked(), trusted, mapOf(CHAT to setOf(pinKey)))
        assertEquals(mapOf(CHAT to BlockReason.CATALOG_KEY), check.reasons)
        // The relock carries the install-time block over (it isn't this run's, so not in `reported`)...
        assertEquals(setOf(music, CHAT), check.blocked.keys)
        assertEquals(setOf(CHAT), check.reported)
        store.saveBlocked(check.blocked, check.reasons.mapValues { BlockNote(it.value, BlockFound.RELOCK, now) })
        // ...and the status report has both, each with its key and why - also a block from before the notes.
        store.saveBlocked(store.blocked() + ("org.example.old" to null))
        val signers = mapOf(music to setOf(debugKey), CHAT to setOf(fakeKey), "org.example.old" to setOf("short"))
        val report = currentBlocks(store.blocked(), store.blockNotes(), signers)
        assertEquals(
            listOf(
                com.kidslauncher.mdm.server.dto.RelockBlockReport(music, debugKey, BlockReason.OUTSIDE_PLAY, BlockFound.INSTALL, now),
                com.kidslauncher.mdm.server.dto.RelockBlockReport(CHAT, fakeKey, BlockReason.CATALOG_KEY, BlockFound.RELOCK, now),
                com.kidslauncher.mdm.server.dto.RelockBlockReport("org.example.old", null, BlockReason.UNKNOWN, BlockFound.RELOCK, null),
            ).sortedBy { it.packageName },
            report,
        )
        // A blocked package that is gone isn't reported.
        assertEquals(2, currentBlocks(store.blocked(), store.blockNotes(), signers - "org.example.old").size)
        assertEquals(3, currentBlocksTotal(store.blocked(), signers))
        assertEquals(2, currentBlocksTotal(store.blocked(), signers - "org.example.old"))
        // Past the cap the list stops, the total doesn't (QA of cmd-expiry, Low 2).
        val many = (0 until RELOCK_BLOCKS_MAX + 3).associate { "org.example.app$it" to null as Set<String>? }
        val manySigners = many.keys.associateWith { setOf(debugKey) }
        assertEquals(RELOCK_BLOCKS_MAX, currentBlocks(many, emptyMap(), manySigners).size)
        assertEquals(RELOCK_BLOCKS_MAX + 3, currentBlocksTotal(many, manySigners))

        // "Allow these apps": the debug build goes (with its note), the imposter stays.
        val left = allowBlocked(store.blocked(), signers, mapOf(CHAT to setOf(pinKey)))
        assertTrue(store.saveAllowed(left, mapOf(music to installed.updatedAt, "org.example.old" to 1L)))
        assertEquals(setOf(CHAT), store.blocked().keys)
        assertEquals(setOf(CHAT), store.blockNotes().keys)
        assertEquals(listOf(CHAT), currentBlocks(store.blocked(), store.blockNotes(), signers).map { it.packageName })
        // Unhiding it sends a package-added: the allowed install isn't blocked again.
        assertNull(blockAfterInstall(installed, store.blocked(), emptyMap(), trusted, now + 1_000, store.allowed()))
    }

    @Test
    fun `Allow these apps lets out only the listed blocks whose key is still the one shown (QA of cmd-expiry, Low 1)`() {
        val keyA = "a1" + "0".repeat(60) + "a1"
        val keyB = "b2" + "0".repeat(60) + "b2"
        val other = "c3" + "0".repeat(60) + "c3"
        val blocked = mapOf("org.example.a" to null, "org.example.b" to null, "org.example.late" to null, "org.example.old" to null)
        val installed = mapOf(
            "org.example.a" to setOf(keyA),
            // Replaced since the parent looked: another key now.
            "org.example.b" to setOf(other),
            // Blocked after the parent looked: not listed.
            "org.example.late" to setOf(keyB),
            // Its key unreadable then and now.
            "org.example.old" to setOf("short"),
        )
        val listed = mapOf("org.example.a" to keyA, "org.example.b" to keyB, "org.example.old" to null)
        assertEquals(
            setOf("org.example.b", "org.example.late"),
            allowBlocked(blocked, installed, emptyMap(), listed).keys,
        )
        // An empty list lets out nothing; a gone package leaves anyway.
        assertEquals(blocked.keys - "org.example.a", allowBlocked(blocked, installed - "org.example.a", emptyMap(), emptyMap()).keys)
        // A listed catalog imposter stays.
        assertEquals(setOf("org.example.a"), allowBlocked(mapOf("org.example.a" to null), installed, mapOf("org.example.a" to setOf(other)), listed).keys)
        // An older server: every block but the imposter goes.
        assertTrue(allowBlocked(blocked, installed, emptyMap(), null).isEmpty())
    }

    @Test
    fun `the reasons follow the pin, then the window start's key, then the installer`() {
        val evil = pkg(signers = setOf(EVIL))
        assertEquals(BlockReason.CATALOG_KEY, blockReason(evil, setOf(REAL), setOf(EVIL)))
        assertEquals(BlockReason.OTHER_KEY, blockReason(evil, null, setOf(REAL)))
        assertEquals(BlockReason.OUTSIDE_PLAY, blockReason(evil, null, null))
        assertEquals(BlockReason.OUTSIDE_PLAY, blockReason(evil, setOf(EVIL), null))
        // A relock's re-signed app: its window-start key.
        val check = signerCheck(listOf(pkg(signers = setOf(EVIL), updatedAt = 2L)), mapOf(CHAT to PackageState(setOf(REAL), 1L)), listOf(CHAT), emptyMap(), trusted)
        assertEquals(mapOf(CHAT to BlockReason.OTHER_KEY), check.reasons)
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
