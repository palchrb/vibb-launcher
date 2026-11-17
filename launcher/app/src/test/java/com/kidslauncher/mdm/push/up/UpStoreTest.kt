package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.server.ParentPinStoreTest
import com.kidslauncher.mdm.server.SealedPrefs
import com.kidslauncher.mdm.server.SealedPrefsTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 108 §5.3: the registrations sealed in one `commit()`, an older blob decoded, the side keys plain. */
class UpStoreTest {
    private val prefs = ParentPinStoreTest.FakePrefs()
    private val box = SealedPrefsTest.FakeBox()
    private val store = UpFixtures.store(prefs, box)

    @Test
    fun `the rows round-trip sealed, in one commit, with no capability on disk`() {
        assertEquals(UpRead.Rows(emptyList()), store.read())
        val commits = prefs.commits
        assertTrue(store.write(UpFixtures.rows))
        assertEquals(commits + 1, prefs.commits)
        assertEquals(UpRead.Rows(UpFixtures.rows), store.read())
        val raw = prefs.map[UpStore.REGS] as String
        assertTrue(raw.startsWith(SealedPrefs.PREFIX))
        for (secret in listOf("tok-element-1", "FakeSecretForTheContract002", "@emma:example.org")) assertFalse(secret, secret in raw)
        // A new process (no memo) opens it too.
        assertEquals(UpRead.Rows(UpFixtures.rows), UpStore(prefs, SealedPrefs(box) { _, _ -> }).read())
    }

    @Test
    fun `an older blob decodes, unknown keys of a newer build are ignored, a broken one is unreadable and kept`() {
        val sealed = SealedPrefs(box) { _, _ -> }
        val old = """{"rows":[{"reg":"c0ffee00000000000000000000000001","pkg":"org.unifiedpush.example","token":"tok-example-1",""" +
            """"secret":"FakeSecretForTheContract001","endpoint":"https://phones.example.com/up/FakeSecretForTheContract001",""" +
            """"state":"confirmed","confirmed_at_ms":2000,"a_later_key":true}],"v":1,"also_later":[]}"""
        sealed.edit(prefs) { it.putSealed(UpStore.AAD, UpStore.REGS, old) }
        val rows = (store.read() as UpRead.Rows).rows
        assertEquals(listOf("c0ffee00000000000000000000000001"), rows.map { it.reg })
        assertTrue(rows.single().confirmed)
        assertEquals(null, rows.single().lastAppAckMs)
        sealed.edit(prefs) { it.putSealed(UpStore.AAD, UpStore.REGS, "not json") }
        assertEquals(UpRead.Unreadable, store.read())
        assertTrue((prefs.map[UpStore.REGS] as String).startsWith(SealedPrefs.PREFIX))
    }

    @Test
    fun `the deletes, the churn and the notes`() {
        assertTrue(store.setDeletes(listOf("a", "b", "a")))
        assertEquals(listOf("a", "b"), store.deletes())
        store.setChurn(mapOf("io.element.android.x" to listOf(1L, 2L)))
        assertEquals(mapOf("io.element.android.x" to listOf(1L, 2L)), store.churn())
        assertTrue(store.note(UpNote("com.example.old", NOTE_OLD_CONNECTOR, 1)))
        assertFalse(store.note(UpNote("com.example.old", NOTE_OLD_CONNECTOR, 2)))
        repeat(UpStore.NOTES_MAX + 2) { store.note(UpNote("com.example.n$it", NOTE_NO_RAISE, it.toLong())) }
        assertEquals(UpStore.NOTES_MAX, store.notes().size)
    }
}
