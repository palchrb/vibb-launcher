package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 69 §4.1: `PolicyResponse.calendar` read field by field - the defaults, unknown values, the rules skipped. */
class CalendarPolicyTest {
    private fun read(json: String) = calendarPolicy(ServerJson.parseToJsonElement(json))

    @Test
    fun `missing is an older server, anything but an object is unknown`() {
        assertEquals(CalendarPolicy.OLDER_SERVER, calendarPolicy(null))
        assertEquals(CalendarPolicy.OLDER_SERVER, calendarPolicy(JsonNull))
        for (odd in listOf("[]", "5", "\"x\"", "true")) assertEquals(odd, CalendarPolicy.UNKNOWN, read(odd))
        with(CalendarPolicy.OLDER_SERVER) {
            assertTrue(usable && onHome)
            assertFalse(kidAdds || glanceNext || lockReminders)
        }
        assertFalse(CalendarPolicy.UNKNOWN.usable || CalendarPolicy.UNKNOWN.onHome)
    }

    @Test
    fun `the server's fresh phone and a full policy`() {
        val fresh = read("""{"on_home": true, "kid_adds": true, "glance_next": false, "lock_reminders": "off", "calendars": [], "rules": []}""")
        assertEquals(CalendarPolicy(usable = true, onHome = true, kidAdds = true), fresh)
        val full = read(
            """{"on_home": false, "kid_adds": false, "glance_next": true, "lock_reminders": "row",
               "calendars": [
                 {"key": "9f3a61c2d84be705", "shown": false, "remind": "off", "remind_minutes": null, "writable_for_kid": false},
                 {"key": "c04e7b19a2f6d853", "shown": true, "remind": "before", "remind_minutes": 15, "writable_for_kid": true},
                 {"key": "51d8e0aa3b7c9462", "shown": true, "remind": "own", "remind_minutes": null, "writable_for_kid": false},
                 {"key": "aaaaaaaaaaaaaaaa", "remind": "before", "remind_minutes": 7},
                 {"key": "bbbbbbbbbbbbbbbb", "remind": "later", "shown": "no"},
                 {"shown": false}, 5
               ],
               "rules": [
                 {"id": 7, "calendar": "9f3a61c2d84be705", "field": "title", "match": "starts", "pattern": "Gruppe 2-", "action": "prefix", "text": "Skole:"},
                 {"id": 8, "calendar": null, "field": "title", "match": "regex", "pattern": "x", "action": "hide", "text": ""},
                 {"id": 9, "calendar": null, "field": "description", "match": "contains", "pattern": "6. trinn", "action": "hide", "text": ""},
                 {"id": 10, "field": "description", "match": "contains", "pattern": "x", "action": "replace", "text": "y"},
                 {"id": 11, "field": "title", "match": "starts", "pattern": "  ", "action": "hide", "text": ""},
                 {"id": 12, "field": "title", "match": "starts", "pattern": "Tur", "action": "replace", "text": ""},
                 {"id": "13", "field": "title", "match": "starts", "pattern": "x", "action": "hide"},
                 {"id": 14, "calendar": 5, "field": "title", "match": "starts", "pattern": "x", "action": "hide"}
               ]}""",
        )
        assertFalse(full.onHome)
        assertTrue(full.glanceNext && full.lockReminders)
        assertFalse(full.setting("9f3a61c2d84be705").shown)
        assertEquals(Remind.Before(15), full.setting("c04e7b19a2f6d853").remind)
        assertEquals(Remind.Own, full.setting("51d8e0aa3b7c9462").remind)
        assertEquals(Remind.Off, full.setting("aaaaaaaaaaaaaaaa").remind)
        assertEquals(CalendarSetting(), full.setting("bbbbbbbbbbbbbbbb"))
        assertEquals(CalendarSetting(), full.setting("not saved"))
        assertEquals("c04e7b19a2f6d853", full.kidCalendarKey)
        assertEquals(listOf(7L, 9L, 14L), full.rules.map { it.id })
        assertEquals(listOf(8L, 10L, 11L, 12L), full.rulesSkipped)
        // A calendar that isn't a key applies to nothing.
        assertEquals("-", full.rules.last().calendar)
        assertNull(read("""{"lock_reminders": "loud"}""").kidCalendarKey)
        assertFalse(read("""{"lock_reminders": "loud", "glance_next": "true"}""").let { it.lockReminders || it.glanceNext })
    }
}
