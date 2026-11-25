package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** `time_state.storage_full`: a failed ledger write or a failed policy-cache write sets it, each cleared by its own
 * next success. */
class StorageWritesTest {
    @Before
    fun clear() = StorageWrites.reset()

    @Test
    fun `either failed write is storage full, each cleared by its own success`() {
        assertFalse(StorageWrites.full())
        assertTrue(StorageWrites.record(StorageWrites.Kind.POLICY_CACHE, ok = false))
        assertTrue(StorageWrites.full())
        // A second failure isn't news (one log line).
        assertFalse(StorageWrites.record(StorageWrites.Kind.POLICY_CACHE, ok = false))
        StorageWrites.record(StorageWrites.Kind.LEDGER, ok = false)
        // The ledger saved again: the policy cache still failing keeps it.
        StorageWrites.record(StorageWrites.Kind.LEDGER, ok = true)
        assertTrue(StorageWrites.full())
        StorageWrites.record(StorageWrites.Kind.POLICY_CACHE, ok = true)
        assertFalse(StorageWrites.full())
    }

    @Test
    fun `the policy cache and the ledger both record their commit`() {
        val main = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val sources = File(main, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val worker = sources.single { it.name == "MdmSyncWorker.kt" }.readText()
        val store = worker.substringAfter("private fun storeAcceptedPolicy(").substringBefore("\n}")
        assertTrue(store, store.contains("StorageWrites.record(StorageWrites.Kind.POLICY_CACHE, ok)"))
        val runtime = sources.single { it.name == "TimeRulesRuntime.kt" }.readText()
        val save = runtime.substringAfter("private fun saveLedger(").substringBefore("\n    }")
        assertTrue(save, save.contains("StorageWrites.Kind.LEDGER, ok)"))
        assertEquals(1, Regex("""StorageWrites\.Kind\.POLICY_CACHE""").findAll(worker).count())
    }
}
