package me.vibb.music.media

import me.vibb.music.logic.SealVerdict
import me.vibb.music.logic.UNCLASSIFIED_LOST_MS
import me.vibb.music.logic.kekAlias
import me.vibb.music.logic.keyBroken
import me.vibb.music.logic.sealFailure
import me.vibb.music.logic.SealRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * Design 117 §7 (QA H1(b), M1): what a failed unwrap means - Lost only on definite signals, a bad tag only with the
 * same key's canary good, an unnamed fault only at 3 over 24 h and never while the canary fails; a hiccup never Lost.
 */
class SealFailureTest {
    private fun unrecoverable(cause: Throwable) = UnrecoverableKeyException("wrapped").apply { initCause(cause) }

    @Test
    fun `an UnrecoverableKeyException is Transient with a transient cause, Lost with key-does-not-exist that isn't transient`() {
        assertEquals(SealVerdict.TRANSIENT, sealFailure(unrecoverable(FakeKeystoreError(missing = false, transient = true)), true, ::fakeSignal))
        assertEquals(SealVerdict.TRANSIENT, sealFailure(unrecoverable(FakeKeystoreError(missing = true, transient = true)), true, ::fakeSignal))
        assertEquals(SealVerdict.LOST, sealFailure(unrecoverable(FakeKeystoreError(missing = true, transient = false)), false, ::fakeSignal))
        assertEquals("another Keystore code, not transient: no definite cause for the record alone (BookKeys asks the key's canary, code QA M1)", SealVerdict.TRANSIENT, sealFailure(unrecoverable(FakeKeystoreError(missing = false, transient = false)), true, ::fakeSignal))
        assertEquals("no cause at all", SealVerdict.TRANSIENT, sealFailure(UnrecoverableKeyException("x"), true, ::fakeSignal))
        assertEquals(SealVerdict.TRANSIENT, sealFailure(ProviderException("binder"), true, ::fakeSignal))
        assertEquals("permanently invalidated is Lost", SealVerdict.LOST, sealFailure(FakeInvalidated(), false, ::fakeSignal))
    }

    @Test
    fun `a bad tag is Lost with a good canary and Transient with a bad one, and an unnamed fault is Unclassified only with a good canary`() {
        assertEquals(SealVerdict.LOST, sealFailure(AEADBadTagException("tag"), true, ::fakeSignal))
        assertEquals(SealVerdict.TRANSIENT, sealFailure(AEADBadTagException("tag"), false, ::fakeSignal))
        assertEquals(SealVerdict.UNCLASSIFIED, sealFailure(IllegalStateException("odd"), true, ::fakeSignal))
        assertEquals(SealVerdict.TRANSIENT, sealFailure(IllegalStateException("odd"), false, ::fakeSignal))
    }

    @Test
    fun `the check - a key that reads null is Lost, a hiccup never, and a bad tag only while the canary unwraps`() {
        val w = SealWorld()
        w.keys.prepare("sealed:e7:111")
        w.cache.spans["sealed:e7:111"] = 1
        fun fresh() = BookKeys(w.keystore, w.prefs, w.cache)
        w.keystore.unwrapFails = { FakeKeystoreError(missing = false, transient = true) }
        assertTrue("a hiccup: nothing Lost", fresh().check(listOf("sealed:e7:111")).isEmpty())
        // a bad tag on the record while the canary works: this record is bad
        w.keystore.unwrapFails = { aad -> if (String(aad).startsWith("canary:")) null else AEADBadTagException("tag") }
        assertEquals(setOf("sealed:e7:111"), fresh().check(listOf("sealed:e7:111")).keys)
        // the same, with the Keystore itself down (the canary fails too): nothing Lost
        w.keystore.unwrapFails = { AEADBadTagException("tag") }
        assertTrue(fresh().check(listOf("sealed:e7:111")).isEmpty())
        w.keystore.unwrapFails = { null }
        w.keystore.keys.clear()
        assertEquals("its key reads null", setOf("sealed:e7:111"), fresh().check(listOf("sealed:e7:111")).keys)
        assertEquals("a record that can't be read is Lost", setOf("sealed:e7:222"), fresh().also { w.cache.records["sealed:e7:222"] = ByteArray(78) }.check(listOf("sealed:e7:222")).keys)
    }

    @Test
    fun `an unnamed fault becomes Lost at 3 over 24 h with a good canary, never while the canary fails, and a success resets it`() {
        val w = SealWorld()
        val key = "sealed:e7:111"
        w.keys.prepare(key)
        w.cache.spans[key] = 1
        val fail = BookKeys.failPref(key)
        fun fresh() = BookKeys(w.keystore, w.prefs, w.cache, nowMs = { w.now })
        val start = w.now
        w.keystore.unwrapFails = { aad -> if (String(aad).startsWith("canary:")) null else IllegalStateException("odd") }
        for (hours in listOf(0L, 1L, 2L)) {
            w.now = start + hours * 3600_000L
            assertTrue("3 within 2 h: not yet", fresh().check(listOf(key)).isEmpty())
        }
        assertEquals("$start,3", w.prefs.get(fail))
        // the canary fails: never counted, never Lost
        w.keystore.unwrapFails = { IllegalStateException("odd") }
        w.now = start + UNCLASSIFIED_LOST_MS + 1
        assertTrue(fresh().check(listOf(key)).isEmpty())
        assertEquals("$start,3", w.prefs.get(fail))
        // with a good canary again, past 24 h: Lost
        w.keystore.unwrapFails = { aad -> if (String(aad).startsWith("canary:")) null else IllegalStateException("odd") }
        assertEquals(setOf(key), fresh().check(listOf(key)).keys)
        // a success resets the count
        w.prefs.put(fail, "$start,2")
        w.keystore.unwrapFails = { null }
        assertTrue(fresh().check(listOf(key)).isEmpty())
        assertNull(w.prefs.get(fail))
    }

    @Test
    fun `keyBroken - a definite Keystore error that isn't key-does-not-exist, never with a transient one`() {
        assertTrue(keyBroken(FakeKeystoreError(missing = false, transient = false), ::fakeSignal))
        assertTrue(keyBroken(UnrecoverableKeyException("w").apply { initCause(FakeKeystoreError(missing = false, transient = false)) }, ::fakeSignal))
        assertFalse(keyBroken(FakeKeystoreError(missing = false, transient = true), ::fakeSignal))
        assertFalse(keyBroken(FakeKeystoreError(missing = true, transient = false), ::fakeSignal))
        assertFalse(keyBroken(AEADBadTagException("tag"), ::fakeSignal))
    }

    @Test
    fun `code QA M1 - a key present but broken - a fresh key stands in, its books count 3 over 24 h, then download again`() {
        val w = SealWorld()
        val key = "sealed:e7:111"
        w.keys.prepare(key)
        w.cache.spans[key] = 1
        w.keystore.broken += kekAlias(1) // an OTA corrupted it: getKey reads it, every use fails for good
        fun fresh() = BookKeys(w.keystore, w.prefs, w.cache, nowMs = { w.now }, newId = { 2 })
        val start = w.now
        assertTrue("never Lost at once", fresh().check(listOf(key)).isEmpty())
        assertEquals("a fresh key, probed, is current", "00000002", w.prefs.get(BookKeys.PREF_ID))
        assertEquals("$start,1", w.prefs.get(BookKeys.failPref(key)))
        w.now = start + 3600_000L
        fresh().check(listOf(key))
        w.now = start + 7200_000L
        assertTrue(fresh().check(listOf(key)).isEmpty())
        w.now = start + UNCLASSIFIED_LOST_MS + 1
        assertEquals("3 over 24 h: the book downloads again", setOf(key), fresh().check(listOf(key)).keys)
        assertTrue("the broken alias is never generated over", w.keystore.generated.count { it == kekAlias(1) } == 1)
        // with the Keystore down as well (no fresh key works): never counted
        val down = SealWorld()
        down.keys.prepare(key)
        down.cache.spans[key] = 1
        down.keystore.broken += kekAlias(1)
        down.keystore.generateFails = { FakeKeystoreError(missing = false, transient = true) }
        assertTrue(BookKeys(down.keystore, down.prefs, down.cache).check(listOf(key)).isEmpty())
        assertNull(down.prefs.get(BookKeys.failPref(key)))
    }

    @Test
    fun `code QA M1 - a new book is never sealed under a broken current key`() {
        val w = SealWorld()
        w.keys.prepare("sealed:e7:111")
        w.keystore.broken += kekAlias(1)
        BookKeys(w.keystore, w.prefs, w.cache, newId = { 2 }).prepare("sealed:e7:222") // a new process
        assertEquals(2, SealRecord.decode(w.cache.records["sealed:e7:222"])!!.keyId)
        assertEquals("00000002", w.prefs.get(BookKeys.PREF_ID))
    }
}
