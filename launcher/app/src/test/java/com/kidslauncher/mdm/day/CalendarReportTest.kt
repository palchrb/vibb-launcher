package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CalendarReport
import com.kidslauncher.mdm.server.dto.CalendarReportRow
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 69 §4.2: the status report's `calendar` against the shared contract `calendar_status_report.json`
 * (in `contracts/core/`, whose `calendar::a_launcher_report_lists_the_calendars` posts it), the
 * calendar key and the feed's host.
 */
class CalendarReportTest {
    private val contract = javaClass.classLoader!!.getResource("calendar_status_report.json")!!.readText()

    @Test
    fun `the report encodes exactly the contract`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            calendar = CalendarReport(
                readable = true,
                calendars = listOf(
                    CalendarReportRow("9f3a61c2d84be705", "at.bitfire.icsdroid", "Skolen", "skole.kommune.no", 200),
                    CalendarReportRow("c04e7b19a2f6d853", "bitfire.at.davdroid", "Familie", "nextcloud.example.no", 700),
                    CalendarReportRow("51d8e0aa3b7c9462", "LOCAL", "Min kalender", null, 700),
                ),
                rulesSkipped = listOf(9),
            ),
        )
        assertEquals(contract.trimEnd(), ServerJson.encodeToString(StatusReportRequest.serializer(), report))
        val none = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertFalse(none.containsKey("calendar"))
    }

    @Test
    fun `the key hashes type, account and NAME, and the source is a host only`() {
        assertEquals("68ad4f83fa2a80f3", calendarKey("at.bitfire.icsdroid", "ICSx5", "https://skole.kommune.no/feed.ics?token=abc"))
        assertEquals("96a296d224f285c6", calendarKey(null, null, null))
        assertEquals("skole.kommune.no", calendarSource("https://skole.kommune.no/feed.ics?token=abc"))
        assertEquals("spond.com", calendarSource("webcal://user:secret@Spond.com:8443/ical/x"))
        assertEquals("cloud.example.no", calendarSource("https://cloud.example.no/remote.php/dav/calendars/anna/family/"))
        for (odd in listOf(null, "", "Skolen", "file:///x", "mailto:a@b.no", "https://", "ftp://x.no/a", "http://æøå.no/")) {
            assertNull(odd, calendarSource(odd))
        }
    }

    @Test
    fun `every address-shaped token in a name is shortened, the rest kept`() {
        assertEquals("p\u2026@example.org", reportedCalendarName("per.hansen@example.org"))
        assertEquals("\u00e5\u2026@familie.no", reportedCalendarName(" \u00e5se@familie.no "))
        assertEquals("\uD83D\uDE00\u2026@x.no", reportedCalendarName("\uD83D\uDE00a@x.no"))
        assertEquals("p\u2026@example.org og m\u2026@familie.no", reportedCalendarName("per@example.org og mor@familie.no"))
        assertEquals("a\u2026@b", reportedCalendarName("a@b"))
        assertEquals("a\u2026@c", reportedCalendarName("a@b@c"))
        // The fullwidth and small at signs.
        assertEquals("p\u2026@example.org", reportedCalendarName("per\uFF20example.org"))
        assertEquals("p\u2026@example.org", reportedCalendarName("per\uFE6Bexample.org"))
        // An invisible character inside an address is cleaned out first, so the address is masked whole.
        assertEquals("p\u2026@example.org", reportedCalendarName("pe\u200Br@exam\u2060ple.org"))
        // A long name is masked before the cut.
        assertEquals("p\u2026@example.org", reportedCalendarName("p" + "e".repeat(150) + "@example.org"))
        for (kept in listOf("Skolen", "Min kalender", "Familie @ hjemme", "@example.org", "per@", "")) {
            assertEquals(kept, reportedCalendarName(kept))
        }
    }

    @Test
    fun `a calendar without a display name of its own reports none, never its NAME`() {
        assertEquals("", reportedCalendarName("https://skole.kommune.no/feed.ics?token=abc", ownName = false))
        assertEquals("Skolen", reportedCalendarName("Skolen", ownName = true))
        val store = File(listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }, "java/com/kidslauncher/mdm/calendar/CalendarStore.kt").readText()
        assertTrue("ownName = ownName != null," in store)
        assertTrue("reportedCalendarName(it.displayName, it.ownName)" in store)
    }
}
