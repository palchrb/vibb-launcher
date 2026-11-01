package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.parsers.DocumentBuilderFactory

/** The quiet row plus a ping (PLAN "Lock-screen reminders: a third option"): `ping` read, an unknown value off, the
 * sound only with `ping`, the ringer on normal, no Do Not Disturb, no call and no time rule, once per reminder, and
 * the next instant to check. */
class ReminderPingTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private val day = LocalDate.of(2026, 10, 26)
    private val football = CalendarInfo(1, "c04e7b19a2f6d853", "at.bitfire.icsdroid", "Fotball")

    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 26, h, m, 0, 0, oslo).toInstant().toEpochMilli()

    private fun policy(master: String) = calendarPolicy(
        ServerJson.parseToJsonElement(
            """{"on_home": true, "lock_reminders": "$master",
               "calendars": [{"key": "c04e7b19a2f6d853", "shown": true, "remind": "before", "remind_minutes": 15}]}""",
        ),
    )

    private val training = InstanceRow(10, at(17, 30), at(18, 45), false, "Fotball: Trening", null, null, 1)
    private val cup = InstanceRow(11, at(17, 40), at(19), false, "Fotball: Cup", null, null, 1)

    private fun today(p: CalendarPolicy) = TodayCalendar(
        dayView(listOf(training, cup), mapOf(1L to football), p, day, oslo, 0, "me.vibb.launcher", "1b2c3d4e"),
        emptyMap(),
    )

    private val loud = PingQuiet(ringerNormal = true, interruptionsAll = true, inCall = false, ruleOn = false)

    @Test
    fun `ping is the row plus the sound, an unknown value is off, and the capability is claimed`() {
        with(policy("ping")) { assertTrue(lockReminders && lockPing) }
        with(policy("row")) { assertTrue(lockReminders && !lockPing) }
        // A value this launcher doesn't know is off, as before: a server sends a new form only to a launcher that
        // claims it (`row` for `ping` without calendar_ping_v1 - what an older launcher reads as the row).
        for (odd in listOf("loud", "PING", "")) with(policy(odd)) { assertFalse(odd, lockReminders || lockPing) }
        assertEquals("calendar_ping_v1", CALENDAR_PING_CAPABILITY)
        assertTrue(CALENDAR_PING_CAPABILITY in STATUS_CAPABILITIES)
    }

    @Test
    fun `the sound only with ping, on a phone that may make it, once per reminder`() {
        val ping = policy("ping")
        val up = reminderNow(today(ping), ping, at(17, 15))!!
        assertEquals(PingDecision(PingSound.PLAY, "10@${at(17, 30)}"), reminderPing(up, ping, loud, emptySet()))
        // The quiet row alone, or no reminder up: nothing.
        val row = policy("row")
        assertEquals(PingDecision(PingSound.NONE), reminderPing(reminderNow(today(row), row, at(17, 15)), row, loud, emptySet()))
        assertEquals(PingDecision(PingSound.NONE), reminderPing(null, ping, loud, emptySet()))
        // Silent or vibrate, Do Not Disturb, a call, a time rule (bedtime is one): the moment passes without a sound.
        for (quiet in listOf(
            loud.copy(ringerNormal = false),
            loud.copy(interruptionsAll = false),
            loud.copy(inCall = true),
            loud.copy(ruleOn = true),
        )) {
            assertEquals(quiet.toString(), PingDecision(PingSound.QUIET, pingKey(up)), reminderPing(up, ping, quiet, emptySet()))
        }
        // Once: a re-render, a screen-on or the next check with the same reminder up gives nothing, also when the
        // phone was quiet at its moment and isn't now.
        assertEquals(PingDecision(PingSound.NONE), reminderPing(up, ping, loud, setOf(pingKey(up))))
        // The event moved: a new reminder.
        assertEquals(PingSound.PLAY, reminderPing(up.copy(beginMs = at(17, 45)), ping, loud, setOf(pingKey(up))).sound)
    }

    @Test
    fun `the next check - a reminder's start, the up row's start, nothing without ping`() {
        val ping = policy("ping")
        assertEquals(at(17, 15), nextPingCheck(today(ping), ping, at(12)))
        // Training's row is up (17:15), Cup's comes up at 17:25.
        assertEquals(at(17, 25), nextPingCheck(today(ping), ping, at(17, 16)))
        // Both reminders started: the soonest shows until it starts, then Cup's surfaces.
        assertEquals(at(17, 30), nextPingCheck(today(ping), ping, at(17, 26)))
        assertEquals(at(17, 40), nextPingCheck(today(ping), ping, at(17, 31)))
        assertNull(nextPingCheck(today(ping), ping, at(17, 41)))
        val row = policy("row")
        assertNull(nextPingCheck(today(row), row, at(12)))
        // The keys are kept a day after their event's start.
        val keys = setOf("10@${at(17, 30)}", "9@${at(17, 30) - 90_000_000L}", "junk")
        assertEquals(setOf("10@${at(17, 30)}"), keptPings(keys, at(18)))
    }

    @Test
    fun `the alarm reaches only the non-exported receiver`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        val android = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val receivers = doc.getElementsByTagName("receiver").let { n -> (0 until n.length).map { n.item(it) as Element } }
        val ping = receivers.single { it.getAttributeNS(android, "name") == ".calendar.ReminderPingReceiver" }
        assertEquals("false", ping.getAttributeNS(android, "exported"))
        assertEquals(0, ping.getElementsByTagName("intent-filter").length)
    }
}
