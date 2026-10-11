package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.lock.KidLockConfig
import com.kidslauncher.mdm.lock.LockClocks
import com.kidslauncher.mdm.server.dto.PolicyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.ProviderException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Design 24b §2 (L6): the parent PIN's vault - a Keystore HMAC of the PBKDF2 hash instead of the
 * hash - with a JVM HMAC key standing in for the Keystore's.
 */
class PinVaultTest {

    /** The Keystore key: [secret] `null` = missing; [failures] throws that many times first. */
    private class FakeKey : VaultKey {
        var secret: ByteArray? = null
        var failures = 0
        var recreated = 0

        override fun mac(create: Boolean): Mac {
            if (failures > 0) {
                failures--
                throw ProviderException("keystore busy")
            }
            val secret = secret ?: if (create) Random.nextBytes(32).also { secret = it } else throw IllegalStateException("no key")
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        }

        override fun recreate() {
            recreated++
            secret = Random.nextBytes(32)
        }
    }

    private class FakeLegacy(var hash: String? = null, var salt: String? = null, var clearFails: Boolean = false) : LegacyPinFields {
        override fun hash() = hash
        override fun salt() = salt
        override fun clear(): Boolean {
            if (clearFails) return false
            hash = null
            salt = null
            return true
        }
    }

    private val salt = "00112233445566778899aabbccddeeff"
    private val otherSalt = "ffeeddccbbaa99887766554433221100"
    private fun hashOf(pin: String, saltHex: String = salt) =
        PinHash.derive(pin, PinHash.parseHex(saltHex)!!).joinToString("") { "%02x".format(it) }
    private val pin = "246810"
    private val hash = hashOf(pin)

    private val prefs = ParentPinStoreTest.FakePrefs()
    private val key = FakeKey()
    private val legacy = FakeLegacy()
    private val logged = mutableListOf<String>()
    private val vault = PinVaultCore(prefs, key, legacy) { message, _ -> logged += message }
    private val lockoutPrefs = ParentPinStoreTest.FakePrefs()
    private val store = ParentPinStore(lockoutPrefs, ParentPinStoreTest.FakeLegacy()) { _, _ -> }

    private fun policy(hash: String?, salt: String? = this.salt) = PolicyResponse(allowlist = emptyList(), overridePinHash = hash, overridePinSalt = salt)

    private val now = LockClocks(1_000_000_000L, 1_000_000_000L, 3)

    private var ids = 0

    /** Five wrong tries under [fingerprint]: a 15-minute lockout. */
    private fun lockOut(fingerprint: String) {
        repeat(PARENT_FREE_TRIES) {
            checkParentPin(store, ParentPinEntry.SETTINGS, "999999", fingerprint, { PinVerdict.NO_MATCH }, now, { "id${++ids}" }, { false }, { null }, {})
        }
    }

    @Test
    fun `a PIN is wrapped, verified, and the cached policy keeps no hash - but the salt`() {
        val accepted = vault.accept(policy(hash))
        assertEquals("", accepted.policy.overridePinHash)
        assertEquals(salt, accepted.policy.overridePinSalt)
        assertEquals(emptyList<String>(), accepted.policy.allowlist)
        assertTrue(vault.configured())
        assertEquals(PinVerdict.MATCH, vault.verify(pin))
        assertEquals(PinVerdict.NO_MATCH, vault.verify("246811"))
        // Nothing stored is the hash, or a plain digest of it.
        assertFalse(prefs.map.values.any { it.toString().contains(hash) })
        assertFalse(prefs.map.values.any { it.toString().contains(parentPinFingerprint(hash, salt)) })
        // Its fingerprint is the tag's, 16 hex characters, not design 24's.
        assertEquals(16, vault.fingerprint()!!.length)
        assertNotEquals(parentPinFingerprint(hash, salt), vault.fingerprint())
        // A cached (redacted) policy changes nothing: the parent PIN stays configured.
        val again = vault.accept(accepted.policy)
        assertEquals(accepted.policy, again.policy)
        assertTrue(vault.configured())
        assertEquals(PinVerdict.MATCH, vault.verify(pin))
    }

    @Test
    fun `no PIN clears the tag, a new PIN changes the fingerprint`() {
        vault.accept(policy(hash))
        val first = vault.fingerprint()
        val newPin = vault.accept(policy(hashOf("135790", otherSalt), otherSalt))
        assertNotEquals(first, vault.fingerprint())
        // Another salt: another PIN save - no lockout carries over to it.
        assertEquals(setOf(parentPinFingerprint(hashOf("135790", otherSalt), otherSalt)), newPin.change?.from)
        assertEquals(PinVerdict.MATCH, vault.verify("135790"))
        val none = vault.accept(policy(null, null))
        assertNull(none.policy.overridePinHash)
        assertFalse(vault.configured())
        assertNull(vault.fingerprint())
        // An unusable hash is no PIN either, and never cached.
        assertEquals("", vault.accept(policy("abc")).policy.overridePinHash)
        assertFalse(vault.configured())
    }

    @Test
    fun `the migration wraps the plain hash and the running lockout carries over`() {
        legacy.hash = hash
        legacy.salt = salt
        val oldFingerprint = parentPinFingerprint(hash, salt)
        lockOut(oldFingerprint)
        var redactions = 0
        val migrate = {
            vault.migrate(
                rewriteFingerprint = { store.rewriteFingerprint(it.from, it.to) },
                redactCache = { redactions++ },
            )
        }
        migrate()
        assertEquals(PinVerdict.MATCH, vault.verify(pin))
        assertNull(legacy.hash)
        assertNull(legacy.salt)
        assertEquals(1, redactions)
        // The lockout runs on under the new fingerprint (QA #6): a new PIN would have reset it.
        val status = parentPinStatus(store, vault.fingerprint()!!, now)
        assertEquals(PARENT_LOCKOUT_BASE_MS, status.lockoutMs)
        // A re-run is a no-op.
        val tagBefore = prefs.map.toMap()
        migrate()
        assertEquals(1, redactions)
        assertEquals(tagBefore, prefs.map)
    }

    @Test
    fun `the migration leaves a state about another PIN alone, and a crash before the cleanup re-runs it`() {
        legacy.hash = hash
        legacy.salt = salt
        lockOut("0123456789abcdef")
        legacy.clearFails = true
        vault.migrate(rewriteFingerprint = { store.rewriteFingerprint(it.from, it.to) }, redactCache = {})
        // Not cleared: it runs again next time, without a second key or tag.
        val tag = vault.fingerprint()
        legacy.clearFails = false
        vault.migrate(rewriteFingerprint = { store.rewriteFingerprint(it.from, it.to) }, redactCache = {})
        assertEquals(tag, vault.fingerprint())
        assertNull(legacy.hash)
        // The state was about another PIN: the vault's fingerprint gives a fresh one.
        assertEquals(0L, parentPinStatus(store, vault.fingerprint()!!, now).lockoutMs)
    }

    @Test
    fun `a key that fails gives unavailable until the next sync re-wraps - no try is counted`() {
        vault.accept(policy(hash))
        val fingerprint = vault.fingerprint()!!
        lockOut(fingerprint)
        val lockout = parentPinStatus(store, fingerprint, now).lockoutMs
        // The key is gone (or broken): one retry, then unavailable.
        key.secret = null
        assertEquals(PinVerdict.UNAVAILABLE, vault.verify(pin))
        assertTrue(vault.rewrapNeeded())
        assertTrue(vault.configured())
        assertEquals(PinVerdict.UNAVAILABLE, vault.verify(pin))
        // The gate undoes the attempt: no wrong try, no event.
        val later = LockClocks(now.wallMs + PARENT_LOCKOUT_BASE_MS + 1, now.elapsedMs + PARENT_LOCKOUT_BASE_MS + 1, 3)
        val queued = store.queue().size
        val result = checkParentPin(store, ParentPinEntry.SETTINGS, pin, fingerprint, vault::verify, later, { "x" }, { false }, { null }, {})
        assertEquals(ParentPinResult.Unavailable(storageFull = false, untilSync = true), result)
        assertEquals(queued, store.queue().size)
        assertEquals(0, PARENT_FREE_TRIES - parentPinStatus(store, fingerprint, later).triesLeft)

        // The next accepted policy (same PIN, same salt): a new key, a new tag, the state carries over.
        val accepted = vault.accept(policy(hash))
        assertEquals(1, key.recreated)
        assertFalse(vault.rewrapNeeded())
        assertEquals(PinVerdict.MATCH, vault.verify(pin))
        assertNotEquals(fingerprint, vault.fingerprint())
        assertTrue(fingerprint in accepted.change!!.from)
        assertTrue(store.rewriteFingerprint(accepted.change!!.from, accepted.change!!.to))
        assertEquals(lockout, parentPinStatus(store, vault.fingerprint()!!, now).lockoutMs)
    }

    @Test
    fun `a transient keystore error is retried once`() {
        vault.accept(policy(hash))
        key.failures = 1
        assertEquals(PinVerdict.MATCH, vault.verify(pin))
        assertFalse(vault.rewrapNeeded())
        key.failures = 2
        assertEquals(PinVerdict.UNAVAILABLE, vault.verify(pin))
    }

    @Test
    fun `the kid's PIN never depends on the vault`() {
        vault.accept(policy(hash))
        key.secret = null
        assertEquals(PinVerdict.UNAVAILABLE, vault.verify(pin))
        // The kid's lock checks its own PBKDF2 hash, no key involved.
        val kidSalt = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
        val kid = KidLockConfig(hashOf("4711", kidSalt), kidSalt, 4)
        assertTrue(kid.usable)
        assertTrue(PinHash.verify("4711", kid.hashHex, kid.saltHex))
    }

    @Test
    fun `the hash is read only by the vault and the DTOs`() {
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        val readers = root.walk().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { file ->
                file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")
                    .contains("overridePinHash")
            }
            .map { it.relativeTo(root).path }
            .toSet()
        assertEquals(setOf("server/PinVault.kt", "server/dto/PolicyResponse.kt"), readers)
    }
}
