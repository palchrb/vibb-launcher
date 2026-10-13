package me.vibb.music.logic

import me.vibb.music.model.OwnFile
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

    private fun own(id: Long, sha: String) = OwnFile(id, 3, "Track $id", null, null, null, 1000, sha, null, id.toInt(), false)

    /** qa-29-code L2 (E), L3: the report names every copy but one known to be another file. */
    @Test
    fun `the report holds a kept or unjudged copy, never one of another file`() {
        val files = listOf(own(1, a), own(2, a), own(3, a), own(4, a))
        val records = mapOf(1L to a, 2L to b) // 3: no record yet; 4: no copy
        assertEquals(listOf(1L, 3L), heldOwnFiles(files, setOf(1L, 2L, 3L)) { records[it] }.map { it.id })
    }

    /** qa-29-code L2 (F): a held copy of another file is deleted and fetched again; a kept one is left alone. */
    @Test
    fun `pullFiles fetches what isn't held and refetches a copy of another file`() {
        val files = listOf(own(1, a), own(2, b), own(3, a))
        val judged = mutableListOf<Long>()
        val plan = ownFetchPlan(files, setOf(1L, 2L)) { judged += it.id; it.id == 1L }
        assertEquals(listOf(2L), plan.refetch)
        assertEquals(listOf(3L, 2L), plan.fetch.map { it.id })
        assertEquals("only held copies are judged", listOf(1L, 2L), judged)
    }
}
