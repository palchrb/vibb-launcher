package com.kidslauncher.mdm.calls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared vectors (QA 02 criterion T1/T6): the same file the server's src/phone.rs tests read
 * (server/testdata/ in this monorepo). This copy lives in app/src/test/resources; the two must be
 * identical.
 */
class PhoneNumbersTest {

    private val text = javaClass.classLoader!!.getResource("phone_vectors.json")!!.readText()
    private val vectors = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `normalize matches the shared vectors`() {
        val cases = vectors["normalize"]!!.jsonArray
        assertTrue(cases.size > 30)
        for (case in cases.map { it.jsonObject }) {
            val input = case.str("input")!!
            val cc = case.str("cc")!!
            assertEquals("normalize(\"$input\", $cc)", case.str("output"), PhoneNumbers.normalize(input, cc)?.value)
        }
    }

    @Test
    fun `handles match the shared vectors`() {
        for (case in vectors["handles"]!!.jsonArray.map { it.jsonObject }) {
            val input = case.str("input")!!
            assertEquals("numberFromHandle(\"$input\")", case.str("number"), PhoneNumbers.numberFromHandle(input))
        }
    }

    @Test
    fun `vectors are identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/phone_vectors.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }

    @Test
    fun `short and E164 are told apart`() {
        assertEquals(Normalized.Short("112"), PhoneNumbers.normalize("112", "47"))
        assertEquals(Normalized.E164("+4791234567"), PhoneNumbers.normalize("91234567", "47"))
    }

    @Test
    fun `a bad default country code gives null`() {
        for (cc in listOf("", "0", "04", "4x", "1234", "+47")) {
            assertFalse(cc, PhoneNumbers.validCountryCode(cc))
            assertNull(cc, PhoneNumbers.normalize("91234567", cc))
        }
        assertTrue(PhoneNumbers.validCountryCode("47"))
    }

    @Test
    fun `sip handle of an allowed contact normalises to the stored form`() {
        val raw = PhoneNumbers.numberFromHandle("sip:+4791234567@ims.example.net;user=phone")
        assertEquals("+4791234567", PhoneNumbers.normalize(raw!!, "47")?.value)
        assertNull(PhoneNumbers.numberFromHandle("tel:%4"))
        assertNull(PhoneNumbers.numberFromHandle("tel:%zz12"))
    }

    @Test
    fun `dialable digits are ASCII digits only`() {
        assertEquals("112", PhoneNumbers.dialableDigits("1 1 2"))
        assertNull(PhoneNumbers.dialableDigits("112#"))
        assertNull(PhoneNumbers.dialableDigits("+112"))
        assertNull(PhoneNumbers.dialableDigits("١١٢"))
        assertNull(PhoneNumbers.dialableDigits(null))
    }
}
