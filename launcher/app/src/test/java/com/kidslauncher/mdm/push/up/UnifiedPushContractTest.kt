package com.kidslauncher.mdm.push.up

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Design 108: the shared contract `unified_push.json` (byte-identical with `server/testdata/`, whose
 * `tests::unified_push` produces its rows). R1-C holds only the copies' check; R1-L encodes the bodies, decodes every
 * answer and policy row and maps each refusal to its `failed_reason`. */
class UnifiedPushContractTest {
    private val contractText = javaClass.classLoader!!.getResource("unified_push.json")!!.readText()

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/unified_push.json", "../server/testdata/unified_push.json").map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }
}
