package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.ParentPinStoreTest
import com.kidslauncher.mdm.server.SealedPrefs
import com.kidslauncher.mdm.server.SealedPrefsTest
import com.kidslauncher.mdm.server.dto.KidLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design r10 §3.2: the kid PIN's one sealed copy, the redacted cache, and a key that fails. */
class KidPinSealTest {
    private val hash = "aa".repeat(32)
    private val salt = "00112233445566778899aabbccddeeff"
    private val other = KidLockConfig("bb".repeat(32), salt, 4, pinRev = 7)
    private val real = KidLock(hash, salt, pinLength = 6, pinRev = 7)
    private val redacted = real.copy(pinHash = "", pinSalt = "")

    /** What `configure` decides for [config] - through [lockActivation], as the runtime does. */
    private fun activation(config: KidLockConfig?) = lockActivation(
        managed = true,
        hasKidPin = config != null,
        deviceSecure = false,
        keyguardDisabled = true,
        hashUsable = config?.usable == true,
        guardTripped = false,
    )

    private fun noRead(): KidLockConfig? = throw AssertionError("the store isn't read while memory is usable")

    @Test
    fun `no kid_lock is no PIN, a real hash is that PIN`() {
        assertNull(kidConfigFor(null, other) { other })
        assertEquals(false to LockInactive.NO_PIN, activation(kidConfigFor(null, other) { other }))
        val config = kidConfigFor(real, other, ::noRead)
        assertEquals(KidLockConfig(hash, salt, 6, 7), config)
        assertEquals(true to null, activation(config))
    }

    @Test
    fun `a redacted policy takes a usable memory, else the store, with the policy's length`() {
        assertEquals(other.copy(length = 6), kidConfigFor(redacted, other, ::noRead))
        assertEquals(other.copy(length = 6), kidConfigFor(redacted, null) { other })
        // QA R10-4: an unusable memory (the stand-in, a bad hash) falls through to the store.
        assertEquals(other.copy(length = 6), kidConfigFor(redacted, KidLockConfig("", "", 4)) { other })
        // Code QA Q-12: a stored copy of another rev is an older PIN - the stand-in; rev 0 (an older server) isn't checked.
        val older = other.copy(pinRev = 6)
        assertFalse(kidConfigFor(redacted, null) { older }!!.usable)
        assertEquals(true to LockInactive.BAD_HASH, activation(kidConfigFor(redacted, null) { older }))
        assertEquals(older.copy(length = 6), kidConfigFor(redacted.copy(pinRev = 0), null) { older })
        // Memory is the last applied: no rev check.
        assertEquals(older.copy(length = 6), kidConfigFor(redacted, older, ::noRead))
    }

    @Test
    fun `a redacted policy with nothing readable is the stand-in - the lock up, BAD_HASH, never off`() {
        val standIn = kidConfigFor(redacted, null) { null }!!
        assertFalse(standIn.usable)
        assertEquals(6, standIn.length)
        assertEquals(true to LockInactive.BAD_HASH, activation(standIn))
        assertEquals(true to LockInactive.BAD_HASH, activation(kidConfigFor(redacted, null) { KidLockConfig("zz", salt, 4) }))
    }

    @Test
    fun `only a real hash writes the PIN fields, and the stand-in takes no backoff step`() {
        assertEquals(PinWrite.Remove, pinWriteFor(null, null))
        assertTrue(pinWriteFor(real, kidConfigFor(real, null) { null }) is PinWrite.Write)
        assertEquals(PinWrite.Keep, pinWriteFor(redacted, kidConfigFor(redacted, other, ::noRead)))
        assertEquals(PinWrite.Keep, pinWriteFor(redacted, kidConfigFor(redacted, null) { null }))
        assertTrue(backoffStepDue(null, null))
        assertTrue(backoffStepDue(real, kidConfigFor(real, null) { null }))
        assertTrue(backoffStepDue(redacted, other))
        assertFalse(backoffStepDue(redacted, kidConfigFor(redacted, null) { null }))
    }

    private val prefs = ParentPinStoreTest.FakePrefs()
    private val box = SealedPrefsTest.FakeBox()
    private val sealed = SealedPrefs(box) { _, _ -> }
    private val written = KidLockConfig(hash, salt, 6, 7)

    private fun save(write: PinWrite, active: Boolean = true) =
        PinLockStore.saveConfigTo(prefs, sealed, write, active, null, requested = null)

    @Test
    fun `a real hash is stored sealed and read back`() {
        assertTrue(save(PinWrite.Write(written)))
        assertTrue((prefs.map["pin_hash"] as String).startsWith(SealedPrefs.PREFIX))
        assertFalse(prefs.map.values.any { hash in it.toString() || salt in it.toString() })
        assertEquals(written, PinLockStore.configFrom(prefs, SealedPrefs(box) { _, _ -> }, migrate = false))
    }

    @Test
    fun `a redacted or stand-in apply and the crash guard write active only`() {
        save(PinWrite.Write(written))
        val pin = prefs.map.filterKeys { it.startsWith("pin_") }
        assertTrue(save(PinWrite.Keep, active = false))
        assertEquals(false, prefs.map["active"])
        assertEquals(pin, prefs.map.filterKeys { it.startsWith("pin_") })
        assertTrue(save(PinWrite.Remove))
        assertTrue(prefs.map.keys.none { it.startsWith("pin_") })
    }

    @Test
    fun `a Keep commits without the sealed lock (Q-7), and the PIN's toString shows no hash (N-c)`() {
        save(PinWrite.Write(written))
        val holder = Thread { synchronized(sealed.LOCK) { Thread.sleep(2_000) } }.apply { start() }
        while (holder.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
        val started = System.nanoTime()
        assertTrue(save(PinWrite.Keep, active = false))
        assertTrue("Keep waited for the lock", System.nanoTime() - started < 1_000_000_000L)
        holder.join()
        assertEquals(false, prefs.map["active"])
        assertFalse(hash in written.toString() || salt in written.toString())
        assertFalse(hash in real.toString() || salt in real.toString())
    }

    @Test
    fun `a failed seal removes the PIN fields and still commits active (QA R10-3)`() {
        save(PinWrite.Write(other))
        box.sealFailures = 1
        assertTrue(save(PinWrite.Write(written)))
        assertEquals(true, prefs.map["active"])
        assertTrue(prefs.map.keys.none { it.startsWith("pin_") })
    }

    @Test
    fun `an unreadable store is no PIN read, and the next good read heals without a sync (QA R10-4)`() {
        save(PinWrite.Write(written))
        val reader = SealedPrefs(box) { _, _ -> }
        box.openFailures = 1
        assertNull(PinLockStore.configFrom(prefs, reader, migrate = false))
        val memory = kidConfigFor(redacted, null) { null }
        assertEquals(true to LockInactive.BAD_HASH, activation(memory))
        val healed = kidConfigFor(redacted, memory) { PinLockStore.configFrom(prefs, reader, migrate = false) }
        assertEquals(written, healed)
        assertEquals(true to null, activation(healed))
    }

    @Test
    fun `a legacy plaintext PIN is read as it is and sealed by a migrating read`() {
        prefs.map["pin_hash"] = hash
        prefs.map["pin_salt"] = salt
        prefs.map["pin_length"] = 6
        prefs.map["pin_rev"] = 7L
        assertEquals(written, PinLockStore.configFrom(prefs, sealed, migrate = false))
        assertEquals(hash, prefs.map["pin_hash"])
        assertEquals(written, PinLockStore.configFrom(prefs, sealed, migrate = true))
        assertTrue((prefs.map["pin_hash"] as String).startsWith(SealedPrefs.PREFIX))
        assertTrue((prefs.map["pin_salt"] as String).startsWith(SealedPrefs.PREFIX))
    }
}
