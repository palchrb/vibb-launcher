package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.sms.store.SqliteMessageStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Design 81 L7: no JVM test opens SQLite (no Robolectric), so the store's version steps are pinned here - append only.
 * A phone that ran version n has run exactly these statements: editing an earlier step would leave it on a different
 * schema than a fresh install. A new version adds its list to `STEPS` and its hash below, and bumps `DB_VERSION`.
 */
class SqliteStoreVersionTest {
    private fun sha(statements: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(statements.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `version 1 and every step after it are as pinned, and the version counts them`() {
        assertEquals(SCHEMA, sha(SqliteMessageStore.SCHEMA))
        assertEquals(STEPS, SqliteMessageStore.STEPS.map(::sha))
        assertEquals(SqliteMessageStore.STEPS.size + 1, SqliteMessageStore.DB_VERSION)
    }

    private companion object {
        const val SCHEMA = "46b8a3282e0e64290318878ca86ebe9806ca5d5760c29d2eb0ad235008137926"

        /** Versions 2 (design 77 step 3b), 3 (its code QA's `deferred`), 4 (design 80's learned own number), 5 (design
         * 81's reactions). */
        val STEPS = listOf(
            "520245a8544dda274dd9d481da91b9ba226524a0cc3238be01f2d9d0b4857ac2",
            "16b48bfa18adda2a7a7fe6665f48cdbb9253db0b7ee294dedf59e809af23da38",
            "92262a096053eaf2937b8b12225f60a52f341a708f474a68a2a74d988c87d5eb",
            "1acf83f1e178a1d7e0e9de37a9ad6ce9ceb906e1dc258f4eed18566d76233f27",
        )
    }
}
