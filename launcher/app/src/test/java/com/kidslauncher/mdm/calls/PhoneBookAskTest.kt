package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone book's exported entry (code QA M2 of the phone book's own task): what an outside intent may pass on to the
 * page ([entryRoute]) - the call log to the system dialer while calls are unmanaged, else a number from a `tel:` or
 * `voicemail:` link and our notification's contact with the call log, nothing else.
 */
class PhoneBookAskTest {
    private val dial = "android.intent.action.DIAL"
    private val view = "android.intent.action.VIEW"
    private val call = "android.intent.action.CALL"
    private val callButton = "android.intent.action.CALL_BUTTON"

    private data class Row(
        val name: String,
        val action: String?,
        val type: String?,
        val data: String?,
        val contact: String?,
        val unmanaged: Boolean,
        val route: EntryRoute,
    )

    private fun open(callLog: Boolean = false, number: String? = null, contact: String? = null) =
        EntryRoute.Open(PhoneBookAsk(callLog, number, contact))

    private val rows = listOf(
        Row("plain DIAL", dial, null, null, null, false, open()),
        Row("DIAL tel:", dial, null, "tel:+4790000001", null, false, open(number = "tel:+4790000001")),
        Row("VIEW tel: link", view, null, "tel:90000001", null, false, open(number = "tel:90000001")),
        Row("VIEW voicemail:", view, null, "voicemail:1234", null, false, open(number = "voicemail:1234")),
        Row("TEL: in capitals", dial, null, "TEL:112", null, false, open(number = "TEL:112")),
        // A CALL sent explicitly is a number like any other: the page asks "Ring X?" first.
        Row("CALL tel:", call, null, "tel:+4790000001", null, false, open(number = "tel:+4790000001")),
        Row("the call button", callButton, null, null, null, false, open()),
        // Another scheme's data is no number.
        Row("http data", view, null, "https://example.invalid/", null, false, open()),
        Row("content data", dial, null, "content://contacts/people/1", null, false, open()),
        Row("sms data", dial, null, "smsto:+4790000001", null, false, open()),
        Row("an over-long link", dial, null, "tel:" + "9".repeat(PHONE_BOOK_ASK_MAX_CHARS), null, false, open()),
        // The call log: shown, or passed on to the system dialer while calls are unmanaged; its data never a number.
        Row("our notification", view, CALL_LOG_TYPE, null, "+4790000001", false, open(callLog = true, contact = "+4790000001")),
        Row("the call log by URI", view, null, "content://call_log/calls", null, false, open(callLog = true)),
        Row("the call log, unmanaged", view, CALL_LOG_TYPE, null, "+4790000001", true, EntryRoute.PassOnCallLog),
        Row("the call log URI, unmanaged", view, null, "content://call_log/calls?limit=1", null, true, EntryRoute.PassOnCallLog),
        // Unmanaged, anything else still opens the phone book (it says calls are off).
        Row("DIAL tel:, unmanaged", dial, null, "tel:112", null, true, open(number = "tel:112")),
        // The contact is honoured only with the call log - the only way our notification asks for it.
        Row("a contact on a DIAL", dial, null, "tel:112", "+4790000001", false, open(number = "tel:112")),
        Row("a blank contact", view, CALL_LOG_TYPE, null, " ", false, open(callLog = true)),
        Row("an over-long contact", view, CALL_LOG_TYPE, null, "1".repeat(PHONE_BOOK_ASK_MAX_CHARS + 1), false, open(callLog = true)),
        Row("no action", null, null, "tel:112", null, false, open(number = "tel:112")),
    )

    @Test
    fun `the entry passes on only the call log, a number and our contact`() {
        for (row in rows) {
            assertEquals(row.name, row.route, entryRoute(row.action, row.type, row.data, row.contact, row.unmanaged))
        }
    }
}
