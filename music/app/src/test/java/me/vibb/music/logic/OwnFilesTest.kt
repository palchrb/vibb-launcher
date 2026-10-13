package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Own-file copies after an id reuse (design 29 §6 step 4): the recorded SHA-256 against the library's. */
class OwnFilesTest {
    /** Fake 64-hex hashes (not the SHA-256 of anything). */
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun `a matching copy is kept`() {
        assertEquals(OwnCopyVerdict.Keep, ownCopyVerdict(a, a))
    }

    @Test
    fun `a copy whose recorded hash differs is fetched again`() {
        assertEquals(OwnCopyVerdict.Refetch, ownCopyVerdict(a, b))
    }

    @Test
    fun `a copy without a record is hashed once, then judged`() {
        assertEquals(OwnCopyVerdict.Hash, ownCopyVerdict(a, null))
        assertEquals("an unreadable record counts as none", OwnCopyVerdict.Hash, ownCopyVerdict(a, "not a hash"))
        assertEquals(OwnCopyVerdict.Hash, ownCopyVerdict(a, a.uppercase()))
        // keepOwnCopy hashes it, writes the record, and the record decides from then on.
        var record: String? = null
        var hashed = 0
        fun keep(lib: String, bytes: String) = keepOwnCopy(lib, record, { hashed++; bytes }, { record = it })
        assertTrue(keep(a, a))
        assertEquals(a, record)
        assertTrue(keep(a, a))
        assertEquals("hashed once", 1, hashed)
        assertFalse("the id now names another file", keep(b, a))
        assertEquals(1, hashed)
        record = null
        assertFalse("a copy of another file", keep(b, a))
        assertEquals(a, record)
        assertFalse("unreadable: fetched again", keepOwnCopy(a, null, { null }, { error("nothing to record") }))
    }
}
