package com.kidslauncher.mdm

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 140 §2.2: the build writes contracts/music/min_server.json's entry with the contract's format. The build
 * itself checks the APK (checkReleaseHasMinServer, a dependency of assembleRelease); this pins the script to the
 * contract and the contract's rows to its format - the rows the server's reader is tested against.
 */
class MinServerContractTest {
    private val contract by lazy {
        ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("min_server.json")!!.readText()).jsonObject
    }

    private val buildScript by lazy {
        listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
    }

    private fun string(key: String) = (contract[key] as JsonPrimitive).content

    @Test
    fun `the build writes the contract's entry in its format`() {
        assertEquals("assets/vibb/min_server", string("entry"))
        assertTrue("the writer", "resolve(\"vibb/min_server\")" in buildScript)
        assertTrue("the release check", "getEntry(\"${string("entry")}\")" in buildScript)
        assertTrue("the format", "Regex(\"\"\"${string("value_format")}\"\"\")" in buildScript)
        assertTrue("one newline", "writeText(\"\$v\\n\")" in buildScript)
        assertTrue("checked for every variant", "\"check\${cap}HasMinServer\"" in buildScript)
        assertTrue("the generated assets", "addGeneratedSourceDirectory(writeMinServer" in buildScript)
    }

    @Test
    fun `the rows read by the format`() {
        val format = Regex(string("value_format"))
        val max = (contract["max_bytes"] as JsonPrimitive).int
        val rows = contract["files"] as JsonArray
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val text = (row as JsonObject)["text"]!!.jsonPrimitive.content
            val value = text.removeSuffix("\n")
            val reads = text.toByteArray().size <= max && format.matches(value)
            val want = row["version"]
            if (want is JsonNull) {
                assertTrue("'$text' is unreadable", !reads)
            } else {
                assertTrue("'$text' reads", reads)
                assertEquals(text, (want as JsonArray).map { it.jsonPrimitive.int }, value.split('.').map { it.toInt() })
            }
        }
    }

    @Test
    fun `the first tags are each app's`() {
        assertTrue(Regex("""^launcher-v\d+\.\d+\.\d+(-rc\.\d)?$""").matches(string("launcher_first")))
        assertTrue(Regex("""^music-v\d+\.\d+\.\d+(-rc\.\d)?$""").matches(string("music_first")))
    }
}
