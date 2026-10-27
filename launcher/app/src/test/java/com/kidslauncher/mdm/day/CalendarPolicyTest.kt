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
                 {"id": 14, "calendar": 5, "field": "title", "match": "starts", "pattern": "x", "action": "hide"},
                 {"id": 15, "field": "title", "match": "starts", "pattern": "x", "action": "rename", "text": "y"},
                 {"id": 16, "field": "location", "match": "contains", "pattern": "x", "action": "hide", "text": ""}
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
        assertEquals(listOf(8L, 10L, 11L, 12L, 15L, 16L), full.rulesSkipped)
        // A calendar that isn't a key applies to nothing.
        assertEquals("-", full.rules.last().calendar)
        assertNull(read("""{"lock_reminders": "loud"}""").kidCalendarKey)
        assertFalse(read("""{"lock_reminders": "loud", "glance_next": "true"}""").let { it.lockReminders || it.glanceNext })
    }

    /** A colour per calendar (2026-10-27): the eight names read, anything else - absent, null, a newer server's name,
     * not a string - is no colour (never a wrong dot); peach is never a policy value. */
    @Test
    fun `a calendar's colour - known, unknown, absent`() {
        val p = read(
            """{"calendars": [
                 {"key": "9f3a61c2d84be705", "color": "sky"},
                 {"key": "c04e7b19a2f6d853", "color": "slate"},
                 {"key": "aaaaaaaaaaaaaaaa", "color": "neon"},
                 {"key": "bbbbbbbbbbbbbbbb", "color": null},
                 {"key": "cccccccccccccccc", "color": 5},
                 {"key": "dddddddddddddddd"}
               ]}""",
        )
        assertEquals(CalendarColor.SKY, p.setting("9f3a61c2d84be705").color)
        assertEquals(CalendarColor.SLATE, p.setting("c04e7b19a2f6d853").color)
        for (key in listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "cccccccccccccccc", "dddddddddddddddd", "not saved")) {
            assertNull(key, p.setting(key).color)
        }
        assertEquals(listOf("sky", "forest", "sun", "berry", "sea", "lavender", "sand", "slate"), CalendarColor.entries.mapNotNull { it.wire })
        assertNull(CalendarColor.of("peach"))
        assertNull(CalendarColor.of(null))
        // The kid's own calendar's peach is the one accent (colors_kid.xml's kid_accent).
        val colors = java.io.File("src/main/res/values/colors_kid.xml").readText()
        val accent = Regex("""name="kid_accent">(#[0-9A-Fa-f]{6})<""").find(colors)!!.groupValues[1]
        assertEquals(accent.uppercase(), "#%06X".format(CalendarColor.OWN.argb and 0xFFFFFF))
    }
}
