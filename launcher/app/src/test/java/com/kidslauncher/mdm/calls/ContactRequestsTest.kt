package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.ContactRequestBody
import com.kidslauncher.mdm.server.dto.PolicyContactRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The kid's contact request (design 77 §14): the body and the answers of the shared `contact_request.json`
 * (byte-identical with `server/testdata/`, whose `tests::contact_requests::the_contract_answers` posts and produces
 * them), the form's check on the shared vectors, what the policy's rows do, and the rows the phone book shows.
 */
class ContactRequestsTest {
    private val contract = javaClass.classLoader!!.getResource("contact_request.json")!!.readText()
    private val json = Json.parseToJsonElement(contract).jsonObject

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/contact_request.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    @Test
    fun `the phone encodes exactly the contracts requests`() {
        for (key in listOf("request", "request_without_matrix")) {
            val row = json[key]!!.jsonObject
            val record = ContactRequestRecord(
                id = row["id"]!!.jsonPrimitive.content,
                name = row["name"]!!.jsonPrimitive.content,
                number = row["number"]!!.jsonPrimitive.content,
                matrixId = row["matrix_id"]?.jsonPrimitive?.content,
                askedAtMs = row["asked_at_ms"]!!.jsonPrimitive.long,
            )
            assertEquals(key, row, Json.parseToJsonElement(ServerJson.encodeToString(ContactRequestBody.serializer(), record.body())))
        }
    }

    @Test
    fun `each answer reads as its verdict`() {
        val answers = json["answers"]!!.jsonArray.map { it.toString() }
        assertEquals(
            listOf(
                ContactRequestVerdict.ACCEPTED,
                ContactRequestVerdict.CAPPED,
                ContactRequestVerdict.NOT_SENT,
                ContactRequestVerdict.KNOWN,
            ),
            answers.map { contactRequestAnswer(200, it) },
        )
        assertEquals(ContactRequestVerdict.RETRY, contactRequestAnswer(null, null))
        assertEquals(ContactRequestVerdict.RETRY, contactRequestAnswer(503, null))
        assertEquals(ContactRequestVerdict.RETRY, contactRequestAnswer(429, null))
        assertEquals(ContactRequestVerdict.NOT_SENT, contactRequestAnswer(404, null))
        assertEquals(ContactRequestVerdict.NOT_SENT, contactRequestAnswer(200, "<html>"))
    }

    private fun check(row: JsonObject, known: Set<String> = emptySet(), records: List<ContactRequestRecord> = emptyList()) =
        checkContactRequest(
            row["name"]!!.jsonPrimitive.content,
            row["number"]!!.jsonPrimitive.content,
            row["matrix_id"]?.jsonPrimitive?.content ?: "",
            "47",
            known,
            records,
        )

    @Test
    fun `the shared vectors check as the servers`() {
        for (row in json["valid"]!!.jsonArray.map { it.jsonObject }) {
            val ok = check(row) as? RequestCheck.Ok ?: error("refused: $row")
            assertEquals(row.toString(), row["cleaned"]!!.jsonPrimitive.content, ok.name)
            assertEquals(row.toString(), row["normalized"]!!.jsonPrimitive.content, ok.number)
            assertEquals(row.toString(), row["matrix_id"]?.jsonPrimitive?.content, ok.matrixId)
        }
        for (row in json["invalid"]!!.jsonArray.map { it.jsonObject }) {
            assertTrue(row.toString(), check(row) is RequestCheck.Refused)
        }
    }

    private val now = 1_793_034_120_000L

    private fun record(n: Int, phase: ContactRequestPhase = ContactRequestPhase.ASKED, number: String = "+479123456$n", askedAt: Long = now) =
        ContactRequestRecord("%032x".format(n), "Venn $n", number, null, askedAt, phase)

    @Test
    fun `the phones own checks - known, asked already, five open, the platforms emergency answer`() {
        val row = Json.parseToJsonElement("""{"name": "Ola", "number": "91234567"}""").jsonObject
        assertEquals(RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_KNOWN), check(row, known = setOf("+4791234567")))
        assertEquals(
            RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_KNOWN),
            check(row, records = listOf(record(1, number = "+4791234567"))),
        )
        // A declined request for it is no hindrance to asking again.
        assertTrue(check(row, records = listOf(record(1, ContactRequestPhase.DECLINED, "+4791234567"))) is RequestCheck.Ok)
        assertEquals(
            RequestCheck.Refused(RequestField.NAME, RequestRefusal.TOO_MANY),
            check(row, records = (1..5).map { record(it) }),
        )
        assertEquals(
            RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_EMERGENCY),
            checkContactRequest("Ola", "911", "", "47", emptySet(), emptyList()),
        )
        assertEquals(
            RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_EMERGENCY),
            checkContactRequest("Ola", "1412", "", "47", emptySet(), emptyList()) { it == "1412" },
        )
        assertEquals(
            RequestCheck.Refused(RequestField.MATRIX, RequestRefusal.MATRIX_INVALID),
            checkContactRequest("Ola", "91234567", "ola", "47", emptySet(), emptyList()),
        )
    }

    @Test
    fun `the policys rows and contacts answer the requests`() {
        val rows = ServerJson.decodeFromString(
            ListSerializer(PolicyContactRequest.serializer()),
            json["policy"]!!.jsonObject["contact_requests"].toString(),
        )
        assertEquals(listOf("open", "declined", "added"), rows.map { it.state })
        val open = record(0).copy(id = rows[0].id)
        val declined = record(1).copy(id = rows[1].id)
        val added = record(2).copy(id = rows[2].id)
        val arrived = record(3)
        val old = record(4, askedAt = now - CONTACT_REQUEST_KEEP_MS - 1)
        val pending = record(5, ContactRequestPhase.PENDING)
        val after = reconcileContactRequests(listOf(open, declined, added, arrived, old, pending), rows, setOf(arrived.number), now)
        assertEquals(
            listOf(open, declined.copy(phase = ContactRequestPhase.DECLINED), pending),
            after,
        )
        // Calls not managed: the contacts say nothing.
        assertEquals(listOf(arrived), reconcileContactRequests(listOf(arrived), emptyList(), null, now))
        // An answer already shown stays as it is.
        val shown = declined.copy(phase = ContactRequestPhase.DECLINED, shown = true)
        assertEquals(listOf(shown), reconcileContactRequests(listOf(shown), rows, null, now))
    }

    @Test
    fun `an answer shows once, the waiting ones stay`() {
        val waiting = record(1)
        val declined = record(2, ContactRequestPhase.DECLINED)
        val capped = record(3, ContactRequestPhase.CAPPED, askedAt = now + 1)
        val records = listOf(waiting, declined, capped)
        assertEquals(
            listOf(RequestLine.CAPPED, RequestLine.WAITING, RequestLine.NOT_ADDED),
            contactRequestRows(records).map { it.line },
        )
        val shown = markContactRequestsShown(records)
        assertEquals(listOf(waiting), dropShownContactRequests(shown))
        // Not shown yet: nothing goes.
        assertEquals(records, dropShownContactRequests(records))
    }

    @Test
    fun `the upload's answers move the request`() {
        val pending = record(1, ContactRequestPhase.PENDING)
        assertEquals(ContactRequestPhase.ASKED, afterContactRequestAnswer(pending, ContactRequestVerdict.ACCEPTED).phase)
        assertEquals(ContactRequestPhase.CAPPED, afterContactRequestAnswer(pending, ContactRequestVerdict.CAPPED).phase)
        assertEquals(ContactRequestPhase.KNOWN, afterContactRequestAnswer(pending, ContactRequestVerdict.KNOWN).phase)
        assertEquals(ContactRequestPhase.NOT_SENT, afterContactRequestAnswer(pending, ContactRequestVerdict.NOT_SENT).phase)
        assertEquals(pending, afterContactRequestAnswer(pending, ContactRequestVerdict.RETRY))
        assertTrue(newContactRequestId().matches(Regex("[0-9a-f]{32}")))
    }
}
