package me.vibb.music.media

import me.vibb.music.logic.SealRecord
import me.vibb.music.logic.kekAlias
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Design 117 §2.2-§2.5, §4.3 (QA H1, M1, M4): the Keystore key's alias and id, never generated over a live one; the
 * canary; the clean-up of old aliases; `prepare`'s spans read again under the lock. The fakes are SealFakes.kt.
 */
class BookKeysTest {
    private val a = "sealed:e7:111"
    private val b = "sealed:e7:222"

    private fun SealWorld.readable(key: String) = runCatching { keys.keyFor(key) }.isSuccess

    @Test
    fun `getKey reading null makes a new alias with a new id - getKey throwing never generates`() {
        val w = SealWorld()
        w.keys.prepare(a)
        assertEquals(listOf(kekAlias(1)), w.keystore.generated)
        assertEquals("00000001", w.prefs.get(BookKeys.PREF_ID))
        assertEquals(1, SealRecord.decode(w.cache.records[a])!!.keyId)
        // the current key reads null (a wipe): the next new book gets a new key under a new alias
        w.keystore.keys.clear()
        w.keys.prepare(b)
        assertEquals(listOf(kekAlias(1), kekAlias(2)), w.keystore.generated)
        assertEquals(2, SealRecord.decode(w.cache.records[b])!!.keyId)
        // a hiccup: getKey throws - Transient, and nothing is generated
        val hiccup = SealWorld()
        hiccup.keys.prepare(a)
        hiccup.keystore.getKeyFails = { FakeKeystoreError(missing = false, transient = true) }
        try {
            hiccup.keys.prepare(b)
            fail("a hiccup is Transient")
        } catch (e: SealTransientException) {
            assertEquals(listOf(kekAlias(1)), hiccup.keystore.generated)
            assertNull(hiccup.cache.records[b])
        }
    }

    @Test
    fun `a new id is never one in use - a colliding alias, the stored id or an id a record names is skipped`() {
        val w = SealWorld(ids = listOf(5, 1, 5, 6, 7))
        w.keystore.keys[kekAlias(5)] = SecretKeySpec(ByteArray(32), "AES") // an alias that exists already
        w.keys.prepare(a) // 5 exists: retried; 1 is free
        assertEquals(listOf(kekAlias(1)), w.keystore.generated)
        // the stored id 1 reads null (mistakenly or not): never generated again - 5 exists, so 6
        w.keystore.keys.remove(kekAlias(1))
        w.keys.prepare(b)
        assertEquals(listOf(kekAlias(1), kekAlias(6)), w.keystore.generated)
    }


    @Test
    fun `the order is key, canary, id - a crash at each point leaves the books readable, and the clean-up takes the unused alias`() {
        for (crash in listOf("seal_canary_", BookKeys.PREF_ID)) {
            val w = SealWorld()
            w.keys.prepare(a)
            // the current key reads null for a moment (it looks like a wipe), and the new key's prefs write crashes
            val real = w.keystore.keys.remove(kekAlias(1))!!
            w.prefs.crashAt = crash
            try {
                w.keys.prepare(b)
                fail("$crash: the crash is a Transient failure")
            } catch (e: SealTransientException) {
                assertTrue("$crash: the new key was made first", kekAlias(2) in w.keystore.keys)
            }
            w.keystore.keys[kekAlias(1)] = real // it was there all along
            assertEquals("$crash: the id is still the old one", "00000001", w.prefs.get(BookKeys.PREF_ID))
            assertTrue("$crash: book a reads (a new process)", BookKeys(w.keystore, w.prefs, w.cache).let { k -> runCatching { k.keyFor(a) }.isSuccess })
            // a clean check: the unused alias 2 goes, with its canary; the current one, which a's record names, stays
            assertTrue(w.keys.check(listOf(a)).isEmpty())
            assertEquals("$crash", listOf(kekAlias(2)), w.keystore.deleted)
            assertNull(w.prefs.get(BookKeys.canaryPref(2)))
            assertNotNull(w.prefs.get(BookKeys.canaryPref(1)))
            // and the next new book seals under key 1
            w.keys.prepare(b)
            assertEquals(1, SealRecord.decode(w.cache.records[b])!!.keyId)
        }
    }

    @Test
    fun `old aliases go only after a clean full check, never the current one or one a record names`() {
        val w = SealWorld()
        w.keys.prepare(a)
        w.keys.prepare(b)
        w.keystore.keys[kekAlias(9)] = SecretKeySpec(ByteArray(32), "AES") // an old alias nothing names
        // a transient failure on one record's unwrap (a new process, no memo): no clean-up
        w.keystore.unwrapFails = { aad -> if (String(aad).startsWith("canary:")) null else FakeKeystoreError(missing = false, transient = true) }
        assertTrue(BookKeys(w.keystore, w.prefs, w.cache).check(listOf(a, b)).isEmpty())
        assertTrue(w.keystore.deleted.isEmpty())
        // clean: alias 9 goes
        w.keystore.unwrapFails = { null }
        assertTrue(BookKeys(w.keystore, w.prefs, w.cache).check(listOf(a, b)).isEmpty())
        assertEquals(listOf(kekAlias(9)), w.keystore.deleted)
        // a new current key (the old one read null once): the old alias stays while a record names it
        val real = w.keystore.keys.remove(kekAlias(1))!!
        w.keys.prepare("sealed:e7:333")
        w.keystore.keys[kekAlias(1)] = real
        assertEquals("00000002", w.prefs.get(BookKeys.PREF_ID))
        assertTrue(w.keys.check(listOf(a, b, "sealed:e7:333")).isEmpty())
        assertEquals(listOf(kekAlias(9)), w.keystore.deleted)
        assertTrue(w.readable(a))
    }

    @Test
    fun `prepare - a readable record is kept with or without spans, a Lost record over held spans Transient (QA M4)`() {
        val w = SealWorld()
        w.keys.prepare(a)
        val first = w.cache.records.getValue(a)
        w.keys.prepare(a)
        assertArrayEquals("code QA M2: no spans yet, but the record stays (a stopped writer may still commit under it)", first, w.cache.records.getValue(a))
        val second = w.cache.records.getValue(a)
        w.cache.spans[a] = 5_000_000
        w.keys.prepare(a)
        assertArrayEquals("a resume keeps its record", second, w.cache.records.getValue(a))
        // the record's key is gone (getKey null: Lost) while the player holds the book: never a new record over its spans
        w.keystore.keys.clear()
        w.cache.held += a
        val fresh = BookKeys(w.keystore, w.prefs, w.cache, newId = { 2 })
        try {
            fresh.prepare(a)
            fail("held spans of a lost book: Transient")
        } catch (e: SealTransientException) {
            assertArrayEquals(second, w.cache.records.getValue(a))
            assertEquals(5_000_000L, w.cache.spans[a])
        }
        // let go: the spans go and the book starts over under a new record
        w.cache.held.clear()
        fresh.prepare(a)
        assertEquals(listOf(a), w.cache.removed)
        val third = SealRecord.decode(w.cache.records[a])!!
        assertEquals(2, third.keyId)
        assertNull(w.cache.spans[a])
    }

    @Test
    fun `a transient failure on a resume deletes nothing`() {
        val w = SealWorld()
        w.keys.prepare(a)
        w.cache.spans[a] = 5_000_000
        val record = w.cache.records.getValue(a)
        w.keystore.unwrapFails = { FakeKeystoreError(missing = false, transient = true) }
        try {
            BookKeys(w.keystore, w.prefs, w.cache).prepare(a)
            fail("Transient")
        } catch (e: SealTransientException) {
            assertTrue(w.cache.removed.isEmpty())
            assertArrayEquals(record, w.cache.records.getValue(a))
        }
    }

    @Test
    fun `the cipher's self-check failing seals and reads nothing`() {
        val w = SealWorld()
        val broken = BookKeys(w.keystore, w.prefs, w.cache, selfCheck = { false })
        try {
            broken.prepare(a)
            fail("no seal without the self-check")
        } catch (e: SealTransientException) {
            assertTrue(w.keystore.generated.isEmpty())
        }
        w.keys.prepare(a)
        try {
            broken.keyFor(a)
            fail("no read either")
        } catch (e: SealTransientException) {
            // Transient: nothing deleted
        }
        assertTrue(broken.check(listOf(a)).isEmpty())
    }

    @Test
    fun `code QA M2 - a record goes only with its resource - an unreadable or absent one gets a new one, one mint per fresh download`() {
        val w = SealWorld()
        w.keys.prepare(a)
        val wraps = w.keystore.wraps
        w.keys.prepare(a) // the download's second prepare (Downloads.fetch after prepareSeal)
        assertEquals("no second mint", wraps, w.keystore.wraps)
        val kept = w.cache.records.getValue(a)
        w.cache.records[a] = ByteArray(78) // unreadable
        w.keys.prepare(a)
        assertFalse(kept.contentEquals(w.cache.records.getValue(a)))
        val again = w.cache.records.getValue(a)
        w.cache.removeCopy(a) // a start-over (the 416): the resource goes, record and all
        w.keys.prepare(a)
        assertFalse(again.contentEquals(w.cache.records.getValue(a)))
        // a new record starts its book's Unclassified count afresh (code QA L2)
        w.prefs.put(BookKeys.failPref(b), "1,2")
        w.keys.prepare(b)
        assertNull(w.prefs.get(BookKeys.failPref(b)))
    }

    @Test
    fun `code QA L1 - a clean check drops the canaries of ids with no alias, no record and not current`() {
        val w = SealWorld()
        w.keys.prepare(a)
        w.prefs.put(BookKeys.canaryPref(9), "00".repeat(60)) // a canary left behind by a deleted key
        assertTrue(w.keys.check(listOf(a)).isEmpty())
        assertNull(w.prefs.get(BookKeys.canaryPref(9)))
        assertNotNull("the current key's stays", w.prefs.get(BookKeys.canaryPref(1)))
    }

    @Test
    fun `code QA L5 - a prefs write that isn't committed is Transient, and the next try makes a working key`() {
        val w = SealWorld()
        w.prefs.uncommittedAt = BookKeys.PREF_ID
        try {
            w.keys.prepare(a)
            fail("an uncommitted id is Transient")
        } catch (e: SealTransientException) {
            assertNull(w.prefs.get(BookKeys.PREF_ID))
        }
        w.keys.prepare(a)
        assertEquals("00000002", w.prefs.get(BookKeys.PREF_ID))
        assertEquals(2, SealRecord.decode(w.cache.records[a])!!.keyId)
        assertTrue(w.keys.check(listOf(a)).isEmpty())
        assertEquals("the unused alias 1 goes at the clean check", listOf(kekAlias(1)), w.keystore.deleted)
    }

    @Test
    fun `code QA L3 - the check unwraps each record once per process and never evicts the playing book's key`() {
        val w = SealWorld()
        val books = (1..8).map { "sealed:e7:$it" }
        books.forEach { w.keys.prepare(it) }
        val fresh = BookKeys(w.keystore, w.prefs, w.cache)
        val playing = fresh.keyFor(books[0])
        fresh.check(books)
        val unwraps = w.keystore.unwraps
        fresh.check(books)
        assertEquals("the second check unwraps nothing", unwraps, w.keystore.unwraps)
        assertTrue("the playing book's key stays in the memo", fresh.keyFor(books[0]) === playing)
        assertEquals(unwraps, w.keystore.unwraps)
    }

    @Test
    fun `code QA L4 - a book is dropped only while its record is the one judged Lost`() {
        val w = SealWorld()
        w.keys.prepare(a)
        w.cache.spans[a] = 1
        w.keystore.keys.clear()
        val lost = BookKeys(w.keystore, w.prefs, w.cache, newId = { 7 }).check(listOf(a))
        assertEquals(setOf(a), lost.keys)
        assertTrue(w.keys.stillLost(a, lost[a]))
        // meanwhile the download job started the book over: a new record - the sync must not drop it
        w.cache.removeCopy(a)
        BookKeys(w.keystore, w.prefs, w.cache, newId = { 7 }).prepare(a)
        assertFalse(w.keys.stillLost(a, lost[a]))
    }
}
