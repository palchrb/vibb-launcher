package com.kidslauncher.mdm.notify

import com.kidslauncher.mdm.R
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

    /** Design 114's `vibb_notifications` step: the table's feature and orange, and 107's `pages` rows' `orange` through
     * the phone's own [channelsOrange] (the server's About rule). */
    @Test
    fun `the orange rule is the server's, row by row`() {
        val rows = contract.getValue("channels").jsonArray.map { it.jsonObject }
        for ((row, channel) in rows.zip(ReportedChannel.entries)) {
            assertEquals(channel.key, row["feature"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content, channel.feature)
            assertEquals(channel.key, row.getValue("orange").jsonPrimitive.boolean, channel.orange)
        }
        for (page in contract.getValue("pages").jsonArray.map { it.jsonObject }) {
            val report = page.getValue("report").jsonObject.let {
                NotificationChannelsReport(it.getValue("all_off").jsonPrimitive.boolean, it.getValue("blocked").jsonArray.map { b -> b.jsonPrimitive.content })
            }
            assertEquals(
                page.getValue("case").jsonPrimitive.content,
                page.getValue("orange").jsonPrimitive.boolean,
                channelsOrange(
                    report,
                    smsManaged = page.getValue("sms_mode").jsonPrimitive.content == "managed",
                    callsManaged = page.getValue("calls_mode").jsonPrimitive.content == "managed",
                ),
            )
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
        // Snarveier's panel is refreshed from here (code QA L1): its own receiver isn't exported and no longer lists
        // the channel action.
        assertTrue("ShortcutsRuntime.requestRefresh()" in watch)
        assertTrue(watch.indexOf("ShortcutsRuntime.requestRefresh()") < watch.indexOf("SyncRunner.request("))
        // Exported: the long-press sheet's broadcast comes with SystemUI's uid and is dropped for a not-exported
        // receiver (gate §6ax); the actions are protected broadcasts.
        assertTrue("ContextCompat.RECEIVER_EXPORTED," in watch)
        val code = watch.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
        assertTrue(code.none { "Extra" in it || "extras" in it })
        val app = File(main, "java/com/kidslauncher/mdm/Application.kt").readText()
        assertTrue("com.kidslauncher.mdm.notify.NotificationChannelsWatch.init(this)" in app)
        val shortcuts = File(main, "java/com/kidslauncher/mdm/shortcuts/ShortcutsRuntime.kt").readText()
        assertTrue(shortcuts.substringAfter("fun showAgain").substringBefore("fun report").contains("NOTIFICATION_CHANNELS_SYNC_REASON"))
        val shortcutsFilter = shortcuts.substringAfter("val filter = IntentFilter().apply {").substringBefore("ContextCompat.registerReceiver")
        assertFalse(shortcutsFilter.lines().any { "addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)" in it })
    }
}
