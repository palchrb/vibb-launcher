package com.kidslauncher.mdm.notify

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.SystemPageAccess
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 107: the channels' table, the report's reading, Settings' row - and the shared contract
 * `notification_channels.json` (byte-identical with `server/testdata/`, whose `notification_channels::tests` and
 * `tests::notification_channels` read the same rows). */
class NotificationChannelsTest {
    private val contractText = javaClass.classLoader!!.getResource("notification_channels.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    /** The strings of `res/<dir>/strings.xml` by name (plain text, no markup in these). */
    private fun strings(dir: String): Map<String, String> =
        Regex("""<string name="([a-z0-9_]+)"[^>]*>([^<]*)</string>""")
            .findAll(File(main, "res/$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }

    private fun resName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    // ---- the contract ----

    @Test
    fun `the table is the contract's, named as the phone names them`() {
        assertEquals("notification_channels_v1", contract.getValue("capability").jsonPrimitive.content)
        assertEquals(NOTIFICATION_CHANNELS_CAPABILITY, contract.getValue("capability").jsonPrimitive.content)
        assertTrue(NOTIFICATION_CHANNELS_CAPABILITY in STATUS_CAPABILITIES)
        val rows = contract.getValue("channels").jsonArray.map { it.jsonObject }
        assertEquals(rows.map { it.getValue("key").jsonPrimitive.content }, ReportedChannel.entries.map { it.key })
        assertEquals(rows.map { it.getValue("id").jsonPrimitive.content }, ReportedChannel.entries.map { it.id })
        val en = strings("values")
        val nb = strings("values-nb")
        for ((row, channel) in rows.zip(ReportedChannel.entries)) {
            val name = resName(channel.nameRes)
            assertEquals(channel.key, row.getValue("en").jsonPrimitive.content, en[name])
            assertEquals(channel.key, row.getValue("nb").jsonPrimitive.content, nb[name])
        }
    }

    @Test
    fun `every read row through channelsReport`() {
        for (row in contract.getValue("read").jsonArray.map { it.jsonObject }) {
            val case = row.getValue("case").jsonPrimitive.content
            val channels = row.getValue("channels").jsonArray.map { it.jsonObject }.map {
                ChannelReading(
                    it.getValue("id").jsonPrimitive.content,
                    it.getValue("importance").jsonPrimitive.int,
                    it["group"]?.jsonPrimitive?.content,
                )
            }
            val report = channelsReport(
                channels,
                row.getValue("blocked_groups").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                row.getValue("app_enabled").jsonPrimitive.boolean,
                row.getValue("shortcuts_id").jsonPrimitive.content,
            )
            assertEquals(case, row.getValue("report"), ServerJson.encodeToJsonElement(NotificationChannelsReport.serializer(), report))
        }
    }

    @Test
    fun `the report rows encode exactly`() {
        for (name in listOf("report", "report_none", "report_all_off")) {
            val row = contract.getValue(name).jsonObject
            val report = NotificationChannelsReport(
                row.getValue("all_off").jsonPrimitive.boolean,
                row.getValue("blocked").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(name, row, ServerJson.encodeToJsonElement(NotificationChannelsReport.serializer(), report))
        }
    }

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/notification_channels.json", "../server/testdata/notification_channels.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    // ---- Settings' row (§6) ----

    @Test
    fun `the row says what is off and what a tap does, never Snarveier`() {
        val report = NotificationChannelsReport(false, listOf("messages", "command_listener", "shortcuts"))
        val actions = mapOf(
            SystemPageAccess.CAN_OPEN to R.string.settings_notifications_open,
            SystemPageAccess.PAUSE_FIRST to R.string.settings_notifications_pause_first,
            SystemPageAccess.WAIT to R.string.settings_notifications_wait,
            SystemPageAccess.NO_PAGE to R.string.settings_notifications_no_page,
        )
        for ((access, action) in actions) {
            val row = notificationsRowText(report, access)!!
            assertEquals(listOf(ReportedChannel.MESSAGES, ReportedChannel.COMMAND_LISTENER), row.off)
            assertFalse(row.allOff)
            assertEquals(access.name, action, row.action)
        }
        assertNull(notificationsRowText(NotificationChannelsReport(false, listOf("shortcuts")), SystemPageAccess.CAN_OPEN))
        assertNull(notificationsRowText(NotificationChannelsReport(false, emptyList()), SystemPageAccess.CAN_OPEN))
        assertNull("nothing read: no row", notificationsRowText(null, SystemPageAccess.CAN_OPEN))
        val allOff = notificationsRowText(NotificationChannelsReport(true, listOf("shortcuts")), SystemPageAccess.PAUSE_FIRST)!!
        assertTrue(allOff.allOff)
        assertEquals(emptyList<ReportedChannel>(), allOff.off)
        // Its words in both languages, the phone's quotes.
        val en = strings("values")
        val nb = strings("values-nb")
        assertEquals("Vibb One notifications", en["settings_notifications"])
        assertEquals("Varsler fra Vibb One", nb["settings_notifications"])
        assertEquals("Sett alle begrensninger på pause først", nb["settings_notifications_pause_first"])
        assertEquals("«%1\$s»", nb["settings_notifications_quoted"])
    }

    // ---- source pins ----

    private fun mainSources(): List<File> = File(main, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** Every channel created under `src/main` is in the table (a new channel without a key fails here). */
    @Test
    fun `every channel the launcher creates has a key`() {
        val sources = mainSources().associateWith { it.readText() }
        val constants = sources.values.flatMap { text ->
            Regex("""(?:const )?val ([A-Z_]+) = "([^"]+)"""").findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()
        }.toMap()
        val ids = ReportedChannel.entries.map { it.id }.toSet()
        var created = 0
        for ((file, text) in sources) {
            for (match in Regex("""\bNotificationChannel\(\s*([A-Za-z_][A-Za-z0-9_]*)""").findAll(text)) {
                val arg = match.groupValues[1]
                created++
                if (file.name == "ShortcutsRuntime.kt" && arg == "id") continue // Snarveier's channel in use
                val id = constants[arg] ?: error("${file.name}: NotificationChannel($arg) - not a known constant")
                assertTrue("${file.name}: $id has no key in the table", id in ids)
            }
        }
        assertTrue("$created channels found", created >= 13)
    }

    /** §3: the three block-state actions ask for a sync with the reason; the receiver reads no extra (L5); the watch is
     * started at process start, and Snarveier's "show again" asks for a sync too. */
    @Test
    fun `the broadcasts ask for a sync and read no extra`() {
        assertEquals("notification_channels", NOTIFICATION_CHANNELS_SYNC_REASON)
        val text = File(main, "java/com/kidslauncher/mdm/notify/NotificationChannels.kt").readText()
        val watch = text.substringAfter("object NotificationChannelsWatch")
        for (action in listOf(
            "ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED",
            "ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED",
            "ACTION_APP_BLOCK_STATE_CHANGED",
        )) {
            assertTrue(action, "addAction(NotificationManager.$action)" in watch)
        }
        assertTrue("SyncRunner.request(context.applicationContext, NOTIFICATION_CHANNELS_SYNC_REASON)" in watch)
        assertTrue("RECEIVER_NOT_EXPORTED" in watch)
        val code = watch.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
        assertTrue(code.none { "Extra" in it || "extras" in it })
        val app = File(main, "java/com/kidslauncher/mdm/Application.kt").readText()
        assertTrue("com.kidslauncher.mdm.notify.NotificationChannelsWatch.init(this)" in app)
        val shortcuts = File(main, "java/com/kidslauncher/mdm/shortcuts/ShortcutsRuntime.kt").readText()
        assertTrue(shortcuts.substringAfter("fun showAgain").substringBefore("fun report").contains("NOTIFICATION_CHANNELS_SYNC_REASON"))
    }
}
