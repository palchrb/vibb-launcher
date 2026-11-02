package com.kidslauncher.mdm

import com.kidslauncher.mdm.badges.reportableChannel
import com.kidslauncher.mdm.lock.intentOfApp
import com.kidslauncher.mdm.lock.lockCrash
import com.kidslauncher.mdm.server.DNS_IN_FLIGHT_MAX
import com.kidslauncher.mdm.server.DedupTimes
import com.kidslauncher.mdm.server.HardeningRestriction
import com.kidslauncher.mdm.server.bluetoothSharingRestricted
import com.kidslauncher.mdm.server.dnsAdmit
import com.kidslauncher.mdm.server.DNS_SLOT_WAIT_MAX_MS
import com.kidslauncher.mdm.server.dnsQuestionsOk
import com.kidslauncher.mdm.server.dnsStillFresh
import com.kidslauncher.mdm.server.formerrResponse
import com.kidslauncher.mdm.server.dto.HardeningPolicy
import com.kidslauncher.mdm.server.hardeningPlan
import com.kidslauncher.mdm.server.readFully
import com.kidslauncher.mdm.server.sparedAlarmApps
import com.kidslauncher.mdm.server.withFilterVpnLock
import com.kidslauncher.mdm.server.writeAtomically
import com.kidslauncher.mdm.timerules.TimeRuleWake
import com.kidslauncher.mdm.timerules.timeRuleSystemAction
import com.kidslauncher.mdm.ui.kidsettings.ControlsSection
import com.kidslauncher.mdm.ui.kidsettings.bluetoothPageAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.net.SocketTimeoutException
import javax.xml.parsers.DocumentBuilderFactory

/** The launcher's security Lows (security review 2026-10-21, round L4): one property pinned per fix. */
class SecurityLowsTest {
    private fun src(path: String) =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    @Test
    fun `S-L1 - the lock keeps only intents the notification's own app created`() {
        assertTrue(intentOfApp("io.element.android.x", "io.element.android.x"))
        assertFalse(intentOfApp("com.android.settings", "io.element.android.x"))
        assertFalse(intentOfApp(null, "io.element.android.x"))
        // Every intent of a ring and the call's content intent go through the creator check.
        val reader = src("java/com/kidslauncher/mdm/badges/VoipCallReader.kt").readText()
        for (kept in listOf(
            "content = n.contentIntent.ofApp(sbn.packageName)", "fullScreen = n.fullScreenIntent.ofApp(pkg)",
            "EXTRA_DECLINE_INTENT, PendingIntent::class.java).ofApp(pkg)", "EXTRA_ANSWER_INTENT, PendingIntent::class.java).ofApp(pkg)",
            "CallAction(it.actionIntent.ofApp(pkg)",
        )) {
            assertTrue(kept, reader.contains(kept))
        }
        assertEquals(2, Regex("PendingIntent::class.java\\)").findAll(reader).count())
        val sessions = src("java/com/kidslauncher/mdm/music/MusicSessions.kt").readText()
        assertTrue(sessions.contains("intentOfApp(it.creatorPackage, controller.packageName)"))
    }

    @Test
    fun `S-L2 - only a crash with a frame of the lock counts toward the crash guard`() {
        fun thrown(className: String) = RuntimeException("x").apply {
            stackTrace = arrayOf(StackTraceElement(className, "run", "F.kt", 1), StackTraceElement("android.os.Looper", "loop", "L.java", 2))
        }
        fun stack(vararg classes: String) = RuntimeException("x").apply {
            stackTrace = classes.map { StackTraceElement(it, "run", "F.kt", 1) }.toTypedArray()
        }
        assertTrue(lockCrash(thrown("com.kidslauncher.mdm.lock.PinLockActivity")))
        assertTrue(lockCrash(thrown("com.kidslauncher.mdm.lock.PinLockActivity\$onCreate\$1")))
        assertTrue("Android's view code under the lock's onCreate", lockCrash(stack("android.view.View", "com.kidslauncher.mdm.lock.PinLockActivity")))
        assertFalse(lockCrash(thrown("com.kidslauncher.mdm.badges.GlanceReader")))
        // Code QA L1: the glance and the VoIP calls render other apps' notifications - their crashes never count, not even
        // wrapped, nor when the lock screen called into them.
        assertFalse(lockCrash(IllegalStateException("wrapped", thrown("com.kidslauncher.mdm.lock.LockGlance"))))
        assertFalse(lockCrash(thrown("com.kidslauncher.mdm.lock.VoipCalls")))
        assertFalse(lockCrash(stack("com.kidslauncher.mdm.lock.GlancePanel", "com.kidslauncher.mdm.lock.PinLockActivity")))
        assertTrue(lockCrash(RuntimeException("Unable to start activity", stack("com.kidslauncher.mdm.lock.RuleCardView"))))
    }

    @Test
    fun `S-L3 - an allowlisted app's alarm isn't spared at bedtime, every system clock is`() {
        val system = setOf("com.android.deskclock", "com.oem.clock")
        // No input names the next alarm's creator: an allowlisted app's setAlarmClock can't add it.
        assertEquals(system, sparedAlarmApps(system, clockAppVerified = false))
        // Bug 2026-11-02: a verified Fossify Clock always - also with a system clock's alarm next in line.
        assertEquals(system + "org.fossify.clock", sparedAlarmApps(system, clockAppVerified = true))
        assertEquals(setOf("org.fossify.clock"), sparedAlarmApps(emptySet(), clockAppVerified = true))
        assertEquals(emptySet<String>(), sparedAlarmApps(emptySet(), clockAppVerified = false))
    }

    @Test
    fun `S-L5 - no Bluetooth sharing while managed, lifted by the override, and the page leaves without its row`() {
        assertTrue(bluetoothSharingRestricted(managed = true, overrideActive = false))
        assertFalse(bluetoothSharingRestricted(managed = true, overrideActive = true))
        assertFalse(bluetoothSharingRestricted(managed = false, overrideActive = false))
        val rows = ControlsSection.Rows(wifi = true, bluetooth = true, brightness = false, sound = false, touch = false)
        assertTrue(bluetoothPageAllowed(rows))
        assertFalse(bluetoothPageAllowed(rows.copy(bluetooth = false)))
        assertFalse(bluetoothPageAllowed(ControlsSection.NoneEnabled))
        assertFalse(bluetoothPageAllowed(ControlsSection.Unreadable))
    }

    @Test
    fun `S-L7 - the boundary alarm's receiver isn't exported and the exported one ignores the boundary action`() {
        assertNull(timeRuleSystemAction("com.kidslauncher.mdm.action.TIME_RULE_BOUNDARY"))
        assertNull(timeRuleSystemAction(null))
        assertEquals(TimeRuleWake.RECHECK, timeRuleSystemAction("android.intent.action.TIME_SET"))
        assertEquals(TimeRuleWake.BOOT, timeRuleSystemAction("android.intent.action.BOOT_COMPLETED"))
        val android = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(src("AndroidManifest.xml"))
        val receivers = doc.getElementsByTagName("receiver").let { n -> (0 until n.length).map { n.item(it) as Element } }
        val alarm = receivers.single { it.getAttributeNS(android, "name") == ".timerules.TimeRuleAlarmReceiver" }
        assertEquals("false", alarm.getAttributeNS(android, "exported"))
        assertEquals(0, alarm.getElementsByTagName("intent-filter").length)
        val alarmSource = src("java/com/kidslauncher/mdm/timerules/TimeRuleAlarm.kt").readText()
        assertTrue(alarmSource.contains("Intent(context, TimeRuleAlarmReceiver::class.java).setAction(ACTION_BOUNDARY)"))
    }

    @Test
    fun `S-L9 - a trickling DoT answer ends at the deadline, the intake is bounded, the list written atomically`() {
        // A server that sends one byte per read: the old per-read timeout never fired.
        val trickle = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = 1; return 1 }
        }
        var left = 5
        val timeouts = mutableListOf<Int>()
        val thrown = runCatching { readFully(trickle, ByteArray(100), { left-- }, { timeouts += it }) }.exceptionOrNull()
        assertTrue("$thrown", thrown is SocketTimeoutException)
        assertEquals(listOf(5, 4, 3, 2, 1), timeouts)

        assertTrue(dnsAdmit(DNS_IN_FLIGHT_MAX - 1))
        assertFalse(dnsAdmit(DNS_IN_FLIGHT_MAX))
        assertTrue(dnsStillFresh(readAtMs = 1_000L, nowMs = 1_000L + DNS_SLOT_WAIT_MAX_MS))
        assertFalse("a query that waited past the limit leaves its slot", dnsStillFresh(1_000L, 1_001L + DNS_SLOT_WAIT_MAX_MS))
        val twoQuestions = org.xbill.DNS.Message.newQuery(
            org.xbill.DNS.Record.newRecord(org.xbill.DNS.Name.fromString("ads.example."), org.xbill.DNS.Type.A, org.xbill.DNS.DClass.IN),
        ).apply {
            addRecord(org.xbill.DNS.Record.newRecord(org.xbill.DNS.Name.fromString("ok.example."), org.xbill.DNS.Type.A, org.xbill.DNS.DClass.IN), org.xbill.DNS.Section.QUESTION)
        }
        val answer = org.xbill.DNS.Message(formerrResponse(twoQuestions))
        assertEquals(org.xbill.DNS.Rcode.FORMERR, answer.rcode)
        assertEquals(twoQuestions.header.id, answer.header.id)
        assertEquals(0, answer.header.getCount(org.xbill.DNS.Section.QUESTION))
        assertTrue(dnsQuestionsOk(1))
        assertFalse(dnsQuestionsOk(2))
        assertFalse(dnsQuestionsOk(0))

        val dedup = DedupTimes(max = 3)
        for (i in 0 until 10) assertFalse(dedup.seenWithin("d$i.example", 1_000L, 600_000L))
        assertEquals(3, dedup.size)
        assertTrue(dedup.seenWithin("d9.example", 2_000L, 600_000L))

        val dir = kotlin.io.path.createTempDirectory("sl9").toFile()
        val list = File(dir, "list.tsv").apply { writeText("old\tads\n") }
        runCatching { writeAtomically(list) { it.write("half"); throw java.io.IOException("killed") } }
        assertEquals("old\tads\n", list.readText())
        assertFalse(File(dir, "list.tsv.tmp").exists())
        writeAtomically(list) { it.write("new\tads\n") }
        assertEquals("new\tads\n", list.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `S-L11 - DISALLOW_CONFIG_VPN with the Web filter alone, unless the parent switched it off`() {
        val unmanaged = hardeningPlan(null, managed = false)
        assertFalse(unmanaged.isSet(HardeningRestriction.CONFIG_VPN))
        assertTrue(withFilterVpnLock(unmanaged, null, filterOn = true).isSet(HardeningRestriction.CONFIG_VPN))
        assertFalse(withFilterVpnLock(unmanaged, null, filterOn = false).isSet(HardeningRestriction.CONFIG_VPN))
        val off = HardeningPolicy(disallowConfigVpn = false)
        assertFalse(withFilterVpnLock(hardeningPlan(off, managed = false), off, filterOn = true).isSet(HardeningRestriction.CONFIG_VPN))
        // Only CONFIG_VPN changes.
        assertEquals(
            unmanaged.restrictions - HardeningRestriction.CONFIG_VPN,
            withFilterVpnLock(unmanaged, null, filterOn = true).restrictions - HardeningRestriction.CONFIG_VPN,
        )
    }

    @Test
    fun `P-L1 - no shortcut id or contact-like channel id is logged or reported`() {
        val key = ByteArray(32) { it.toByte() }
        assertEquals("messages", reportableChannel("messages : +4791234567", key))
        assertEquals("com.google.android.gms.availability", reportableChannel("com.google.android.gms.availability", key))
        assertNull(reportableChannel(null, key))
        val hashed = reportableChannel("contact_4791234567", key)!!
        assertTrue(hashed, Regex("#[0-9a-f]{8}").matches(hashed))
        assertEquals("stable per channel", hashed, reportableChannel("contact_4791234567", key))
        assertTrue(Regex("#[0-9a-f]{8}").matches(reportableChannel("dm_ola@example.org", key)!!))
        // Code QA M1: never a plain SHA-256 of the id (brute-forced over every phone number in seconds) - keyed, so
        // another install's key gives another tag, and without a key nothing of the id is shown.
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest("contact_4791234567".toByteArray())
        assertNotEquals("#" + sha.take(4).joinToString("") { "%02x".format(it) }, hashed)
        assertNotEquals(hashed, reportableChannel("contact_4791234567", ByteArray(32) { 7 }))
        assertEquals("#contact", reportableChannel("contact_4791234567", null))
    }

    @Test
    fun `P-L4 - no Wi-Fi network name is logged`() {
        val quick = src("java/com/kidslauncher/mdm/server/QuickControls.kt").readLines()
        val logged = quick.filter { "Log." in it && (Regex("\\\$\\{?ssid|SSID").containsMatchIn(it)) }
        assertEquals(emptyList<String>(), logged)
        assertFalse(quick.joinToString("\n").contains("ssids="))
    }
}
