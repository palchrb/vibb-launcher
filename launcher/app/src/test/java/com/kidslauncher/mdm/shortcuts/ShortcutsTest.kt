package com.kidslauncher.mdm.shortcuts

import com.kidslauncher.mdm.assistant.AirplaneRowState
import com.kidslauncher.mdm.server.KidMobileRow
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.timerules.AskPart
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 99: Snarveier's model, the tap check, the window's choice, the pacing, the picker - and the shared contract
 * `shortcuts.json` (byte-identical with `server/testdata/`, whose `tests::shortcuts` reads the same rows). */
class ShortcutsTest {
    private val contractText = javaClass.classLoader!!.getResource("shortcuts.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun code(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()

    /** A new phone's default mask (1, 2, 4, 8, 32, 64, 128). */
    private val mask = 1L or 2 or 4 or 8 or 32 or 64 or 128

    /** Everything present, the assistant role held, nothing on. */
    private val all = ShortcutsPlatform(
        torchLit = false,
        sound = true to true,
        airplane = AirplaneRowState.OFF,
        wifiOn = true,
        bluetoothOn = false,
        mobile = KidMobileRow.ON,
        brightness = 128,
        ask = AskPart.Action,
        music = ShortcutMusic(session = true, playing = false, usable = true),
    )

    private fun keys(panel: ShortcutsPanel) = panel.items.map { it.key.wire }

    // ---- the contract ----

    @Test
    fun `the keys, bits, defaults and limits are the contract's`() {
        assertEquals(QuickControlFeature.SHORTCUTS, contract.getValue("bit").jsonPrimitive.long)
        assertEquals(SHORTCUTS_MAX.toLong(), contract.getValue("max_items").jsonPrimitive.long)
        val items = contract.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(ShortcutKey.entries.map { it.wire }, items.map { it.getValue("key").jsonPrimitive.content })
        for (row in items) {
            val key = ShortcutKey.of(row.getValue("key").jsonPrimitive.content)!!
            assertEquals(key.wire, row.getValue("bit").jsonPrimitive.longOrNull, key.bit)
        }
        assertEquals(contract.getValue("defaults").jsonArray.map { it.jsonPrimitive.content }, SHORTCUT_DEFAULTS.map { it.wire })
    }

    @Test
    fun `the report encodes exactly as the contract's`() {
        for (name in listOf("report", "report_hidden")) {
            val row = contract.getValue(name).jsonObject
            val report = ShortcutsReport(
                row.getValue("shown").jsonPrimitive.content.toBoolean(),
                row.getValue("hidden").jsonPrimitive.content.toBoolean(),
                row.getValue("items").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(name, row, ServerJson.encodeToJsonElement(ShortcutsReport.serializer(), report))
        }
        // The default panel of a new phone is the contract's report.
        val panel = shortcutsModel(true, mask, null, false, all)
        assertEquals(contract.getValue("report"), ServerJson.encodeToJsonElement(ShortcutsReport.serializer(), shortcutsReport(panel)))
        // The status report's key.
        val body = ServerJson.encodeToJsonElement(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "none", kioskEngaged = true, shortcuts = shortcutsReport(panel))).jsonObject
        assertEquals(contract.getValue("report"), body.getValue("shortcuts"))
    }

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/shortcuts.json", "../server/testdata/shortcuts.json").map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    // ---- the panel ----

    @Test
    fun `a new phone shows the default's first four offered`() {
        assertEquals(listOf("torch", "sound", "airplane", "wifi"), keys(shortcutsModel(true, mask, null, false, all)))
        // No flash unit: Bluetooth is fourth.
        assertEquals(listOf("sound", "airplane", "wifi", "bluetooth"), keys(shortcutsModel(true, mask, null, false, all.copy(torchLit = null))))
        // Before the assistant step (Q99-L9): the default follows what is offered.
        val noRole = all.copy(airplane = AirplaneRowState.HIDDEN)
        assertEquals(listOf("torch", "sound", "wifi", "bluetooth"), keys(shortcutsModel(true, mask, null, false, noRole)))
    }

    @Test
    fun `shown needs the owner, an accepted policy, bit 128, an item, and not hidden`() {
        assertTrue(shortcutsShown(shortcutsModel(true, mask, null, false, all)))
        assertFalse(shortcutsShown(shortcutsModel(false, mask, null, false, all)))
        assertFalse("no accepted policy or a corrupt cache", shortcutsShown(shortcutsModel(true, null, null, false, all)))
        assertFalse(shortcutsShown(shortcutsModel(true, mask and QuickControlFeature.SHORTCUTS.inv(), null, false, all)))
        val hidden = shortcutsModel(true, mask, null, true, all)
        assertFalse(hidden.shown)
        assertTrue(hidden.hidden)
        assertEquals("the report still names the items", 4, hidden.items.size)
        val nothing = shortcutsModel(true, 128, listOf("wifi"), false, all)
        assertFalse("no item offered", nothing.shown)
    }

    @Test
    fun `the picks are kept in order, the unoffered and the unknown skipped, cut to four`() {
        val picks = listOf("music", "laser", "brightness", "wifi", "music", "more_time", "torch")
        assertEquals(listOf("music", "brightness", "wifi", "more_time"), keys(shortcutsModel(true, mask, picks, false, all)))
        val noWifiBit = mask and QuickControlFeature.WIFI.inv()
        assertEquals(listOf("music", "brightness", "more_time", "torch"), keys(shortcutsModel(true, noWifiBit, picks, false, all)))
        // Nothing is added by itself once the kid has picked.
        assertEquals(listOf("wifi"), keys(shortcutsModel(true, mask, listOf("wifi"), false, all)))
        assertEquals(emptyList<String>(), keys(shortcutsModel(true, mask, emptyList(), false, all)))
    }

    private fun item(key: ShortcutKey, p: ShortcutsPlatform, m: Long = mask) = shortcutItem(key, m, p)

    @Test
    fun `each item's gate, dimmed and absent cases`() {
        // Torch: no flash unit, absent.
        assertNull(item(ShortcutKey.TORCH, all.copy(torchLit = null)))
        assertEquals(ShortcutItem(ShortcutKey.TORCH, on = true), item(ShortcutKey.TORCH, all.copy(torchLit = true)))
        // Lyd: bit 8, no vibrator, DND dims it.
        assertNull(item(ShortcutKey.SOUND, all, mask and 8L.inv()))
        assertNull(item(ShortcutKey.SOUND, all.copy(sound = null)))
        assertEquals(ShortcutItem(ShortcutKey.SOUND, on = false, usable = false), item(ShortcutKey.SOUND, all.copy(sound = false to false)))
        // Wi-Fi: dimmed in airplane mode.
        assertNull(item(ShortcutKey.WIFI, all, mask and 1L.inv()))
        assertFalse(item(ShortcutKey.WIFI, all.copy(airplaneOn = true))!!.usable)
        // Bluetooth: no adapter.
        assertNull(item(ShortcutKey.BLUETOOTH, all.copy(bluetoothOn = null)))
        // Mobildata: the parent decides, Android's data off, airplane mode - dimmed; bit 32 off - absent.
        assertNull(item(ShortcutKey.MOBILE_DATA, all, mask and 32L.inv()))
        assertNull(item(ShortcutKey.MOBILE_DATA, all.copy(mobile = KidMobileRow.HIDDEN)))
        assertEquals(ShortcutItem(ShortcutKey.MOBILE_DATA, on = true), item(ShortcutKey.MOBILE_DATA, all))
        assertEquals(ShortcutItem(ShortcutKey.MOBILE_DATA, on = false), item(ShortcutKey.MOBILE_DATA, all.copy(mobile = KidMobileRow.OFF)))
        assertFalse(item(ShortcutKey.MOBILE_DATA, all.copy(mobile = KidMobileRow.PARENT))!!.usable)
        assertFalse(item(ShortcutKey.MOBILE_DATA, all.copy(mobile = KidMobileRow.PHONE_OFF))!!.usable)
        assertEquals(ShortcutItem(ShortcutKey.MOBILE_DATA, on = false, usable = false), item(ShortcutKey.MOBILE_DATA, all.copy(airplaneOn = true)))
        // Lysstyrke: the step, never peach.
        assertEquals(ShortcutItem(ShortcutKey.BRIGHTNESS, level = BrightnessStep.LOW), item(ShortcutKey.BRIGHTNESS, all.copy(brightness = 10)))
        assertEquals(BrightnessStep.HIGH, item(ShortcutKey.BRIGHTNESS, all.copy(brightness = 255))!!.level)
        // Mer tid: no budget today - absent; asked - dimmed, "Spurt".
        assertNull(item(ShortcutKey.MORE_TIME, all.copy(ask = AskPart.None)))
        assertEquals(ShortcutItem(ShortcutKey.MORE_TIME, usable = false, asked = true), item(ShortcutKey.MORE_TIME, all.copy(ask = AskPart.Asked(1L))))
        // Musikk: Vibb music's own session; not installed - absent; no session or not usable - dimmed.
        assertNull(item(ShortcutKey.MUSIC, all.copy(music = null)))
        assertFalse(item(ShortcutKey.MUSIC, all.copy(music = ShortcutMusic(session = false, playing = false, usable = true)))!!.usable)
        assertFalse("a rule suspends it", item(ShortcutKey.MUSIC, all.copy(music = ShortcutMusic(true, false, usable = false)))!!.usable)
        assertEquals(ShortcutItem(ShortcutKey.MUSIC, on = true), item(ShortcutKey.MUSIC, all.copy(music = ShortcutMusic(true, true, true))))
    }

    @Test
    fun `flymodus follows the row - hidden absent, off-only on, the parent's switch dimmed, the route busy`() {
        assertNull(item(ShortcutKey.AIRPLANE, all.copy(airplane = AirplaneRowState.HIDDEN)))
        assertEquals(ShortcutItem(ShortcutKey.AIRPLANE, on = true), item(ShortcutKey.AIRPLANE, all.copy(airplane = AirplaneRowState.OFF_ONLY, airplaneOn = true)))
        for (state in listOf(AirplaneRowState.RESTRICTED, AirplaneRowState.EMERGENCY, AirplaneRowState.COOLDOWN)) {
            assertEquals(state.name, ShortcutItem(ShortcutKey.AIRPLANE, usable = false), item(ShortcutKey.AIRPLANE, all.copy(airplane = state)))
        }
        assertEquals(ShortcutItem(ShortcutKey.AIRPLANE, usable = false, busy = true), item(ShortcutKey.AIRPLANE, all.copy(airplane = AirplaneRowState.BUSY_ON)))
        assertTrue(item(ShortcutKey.AIRPLANE, all.copy(airplane = AirplaneRowState.FAILED))!!.usable)
        assertEquals(ShortcutItem(ShortcutKey.AIRPLANE, on = true), item(ShortcutKey.AIRPLANE, all.copy(airplane = AirplaneRowState.ON_WIFI, airplaneOn = true)))
    }

    @Test
    fun `an unchanged platform is an equal panel, so nothing is posted again`() {
        assertEquals(shortcutsModel(true, mask, null, false, all), shortcutsModel(true, mask, null, false, all.copy()))
        // Brightness inside one step doesn't change the panel.
        assertEquals(shortcutsModel(true, mask, listOf("brightness"), false, all.copy(brightness = 120)), shortcutsModel(true, mask, listOf("brightness"), false, all.copy(brightness = 130)))
    }

    @Test
    fun `brightness has three steps that go round`() {
        assertEquals(listOf(BrightnessStep.MEDIUM, BrightnessStep.HIGH, BrightnessStep.LOW), BrightnessStep.entries.map { it.next })
        assertEquals(BrightnessStep.LOW, BrightnessStep.of(BrightnessStep.LOW.value))
        assertEquals(BrightnessStep.MEDIUM, BrightnessStep.of(BrightnessStep.MEDIUM.value))
        assertEquals(BrightnessStep.HIGH, BrightnessStep.of(BrightnessStep.HIGH.value))
    }

    // ---- the tap check (Q99-M1) ----

    @Test
    fun `a tap is refused unless every input is known and the item is in the panel and usable`() {
        val panel = shortcutsModel(true, mask, listOf("wifi", "music", "more_time", "airplane"), false, all)
        fun tap(key: String?, p: ShortcutsPanel = panel, known: Boolean = true, locked: Boolean = false, keyguard: Boolean = false, music: Boolean? = null) =
            shortcutTapAllowed(key, p, known, locked, keyguard, music)
        assertEquals(ShortcutTap.ALLOW, tap("wifi"))
        assertEquals(ShortcutTap.UNKNOWN_KEY, tap("laser"))
        assertEquals(ShortcutTap.UNKNOWN_KEY, tap(null))
        assertEquals("a cold process", ShortcutTap.STATE_UNKNOWN, tap("wifi", known = false))
        assertEquals(ShortcutTap.LOCKED, tap("wifi", locked = true))
        assertEquals("a Swipe keyguard (Q99-L1)", ShortcutTap.KEYGUARD, tap("wifi", keyguard = true))
        assertEquals("not picked", ShortcutTap.NOT_IN_PANEL, tap("torch"))
        assertEquals("hidden", ShortcutTap.NOT_IN_PANEL, tap("wifi", p = panel.copy(shown = false)))
        val airplaneOn = shortcutsModel(true, mask, listOf("wifi"), false, all.copy(airplaneOn = true))
        assertEquals("Wi-Fi in airplane mode", ShortcutTap.DIMMED, tap("wifi", p = airplaneOn))
        val busy = shortcutsModel(true, mask, listOf("airplane"), false, all.copy(airplane = AirplaneRowState.BUSY_ON))
        assertEquals(ShortcutTap.BUSY, tap("airplane", p = busy))
        // Musikk: the music app must be usable now, read at the tap.
        assertEquals(ShortcutTap.ALLOW, tap("music", music = true))
        assertEquals(ShortcutTap.MUSIC_UNUSABLE, tap("music", music = false))
        assertEquals(ShortcutTap.MUSIC_UNUSABLE, tap("music", music = null))
        val suspended = shortcutsModel(true, mask, listOf("music"), false, all.copy(music = ShortcutMusic(true, false, usable = false)))
        assertEquals(ShortcutTap.DIMMED, tap("music", p = suspended, music = false))
        val asked = shortcutsModel(true, mask, listOf("more_time"), false, all.copy(ask = AskPart.Asked(5L)))
        assertEquals(ShortcutTap.DIMMED, tap("more_time", p = asked))
    }

    @Test
    fun `the receiver and the window go through the one check, and flymodus turns on only from the window`() {
        val runtime = code("shortcuts/ShortcutsRuntime.kt")
        assertTrue(runtime.contains("val verdict = shortcutTapAllowed(key, panel, lockKnown, PinLockRuntime.mode == LockMode.LOCKED, keyguard != false, musicUsable)"))
        assertTrue(runtime.contains("val lockKnown = PinLockRuntime.decided && runCatching { TimeRulesRuntime.snapshot(app, currentPolicyDecision().policy) }.isSuccess"))
        assertTrue(runtime.contains("window -> AirplaneRoute.turnOn(app)"))
        assertEquals(1, Regex("""AirplaneRoute\.turnOn\(""").findAll(runtime).count())
        val receiver = code("shortcuts/ShortcutsReceiver.kt")
        assertTrue(receiver.contains("val pending = goAsync()"))
        assertTrue(receiver.contains("ShortcutsRuntime.tap(app, key, window = false)"))
        val window = code("shortcuts/ShortcutAirplaneActivity.kt")
        assertTrue(window.substringAfter("override fun onWindowFocusChanged").contains("ShortcutsRuntime.tap(app, ShortcutKey.AIRPLANE.wire, window = true)"))
    }

    // ---- the notification ----

    @Test
    fun `the notification is colorized, immediate, secret, with no action, and its one activity is flymodus's window`() {
        val notification = code("shortcuts/ShortcutsNotification.kt")
        for (call in listOf(".setColorized(true)", "FOREGROUND_SERVICE_IMMEDIATE", ".setVisibility(Notification.VISIBILITY_SECRET)", ".setOngoing(true)")) {
            assertTrue(call, notification.contains(call))
        }
        // Design 100 D1: no "Be om mer tid" in the panel - no action at all; the Vibb mark as its small icon (M10, 2026-11-16).
        assertFalse(notification.contains("addAction") || notification.contains("Notification.Action"))
        assertTrue(notification.contains(".setSmallIcon(R.drawable.ic_stat_vibb)"))
        assertEquals(1, Regex("""PendingIntent\.getActivity\(""").findAll(notification).count())
        assertTrue(notification.contains("Intent(context, ShortcutAirplaneActivity::class.java)"))
        assertTrue(notification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse(notification.contains("FLAG_MUTABLE"))
        val shortcuts = File(main, "java/com/kidslauncher/mdm/shortcuts").listFiles()!!.joinToString("\n") { it.readText() }
        assertEquals(1, Regex("""getActivity\(""").findAll(shortcuts).count())
        assertTrue(code("shortcuts/ShortcutsRuntime.kt").contains("lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET"))
        val manifest = File(main, "AndroidManifest.xml").readText()
        for (name in listOf(".shortcuts.ShortcutsReceiver", ".shortcuts.ShortcutAirplaneActivity")) {
            val entry = manifest.substringAfter("android:name=\"$name\"").substringBefore(">")
            assertTrue(name, entry.contains("android:exported=\"false\""))
        }
    }

    @Test
    fun `no service of its own - the panel is the anchor's notification (design 100 §2)`() {
        assertFalse(File(main, "java/com/kidslauncher/mdm/shortcuts/ShortcutsService.kt").exists())
        val all = File(main, "java").walk().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(all.contains("ShortcutsService"))
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("ShortcutsService"))
        // Snarveier starts and stops nothing: the anchor's life is design 07's.
        val shortcuts = File(main, "java/com/kidslauncher/mdm/shortcuts").listFiles()!!.joinToString("\n") { it.readText() }
        for (call in listOf("startForegroundService", "stopForeground", "startForeground(", ".notify(")) {
            assertFalse(call, shortcuts.contains(call))
        }
        // Mer tid's state is set from the poster's time part before the model is built (Q100-L6).
        val panelFor = code("shortcuts/ShortcutsRuntime.kt").substringAfter("fun panelFor(").substringBefore("\n    }")
        assertTrue(panelFor, panelFor.indexOf("ask = part") in 0 until panelFor.indexOf("computePanel("))
        // "Vis i varsellisten igjen" runs on the poster's thread, deleting only while the plain notice is up (Q100-L8).
        val showAgain = code("shortcuts/ShortcutsRuntime.kt").substringAfter("fun showAgain(").substringBefore("\n    }")
        assertTrue(showAgain, showAgain.contains("AnchorNotification.onPosterThread {"))
        assertTrue(showAgain, showAgain.indexOf("if (!AnchorNotification.panelUp())") in 0 until showAgain.indexOf("deleteNotificationChannel("))
    }

    @Test
    fun `only a usable flymodus that is off opens the window - dimmed or busy it is a broadcast (C99-M1)`() {
        fun airplane(state: AirplaneRowState, on: Boolean = false) =
            shortcutsModel(true, mask, listOf("airplane"), false, all.copy(airplane = state, airplaneOn = on)).items.single()
        assertTrue(shortcutOpensWindow(airplane(AirplaneRowState.OFF)))
        // The parent's "Blokker flymodus", the emergency refusal, the cool-down: dimmed, so the shade stays open (D10).
        for (state in AirplaneRowState.entries.filter { it != AirplaneRowState.HIDDEN && !it.clickable }) {
            val item = airplane(state)
            assertFalse(state.name, shortcutOpensWindow(item))
            assertTrue(state.name, item.busy || !item.usable)
        }
        // On: Off is a broadcast.
        assertFalse(shortcutOpensWindow(airplane(AirplaneRowState.OFF_ONLY, on = true)))
        assertFalse(shortcutOpensWindow(ShortcutItem(ShortcutKey.AIRPLANE, usable = false)))
        assertFalse(shortcutOpensWindow(ShortcutItem(ShortcutKey.AIRPLANE, busy = true)))
        assertFalse(shortcutOpensWindow(ShortcutItem(ShortcutKey.WIFI)))
        // The notification asks exactly this.
        assertTrue(code("shortcuts/ShortcutsNotification.kt").contains("if (shortcutOpensWindow(item)) {"))
    }

    @Test
    fun `at most one post a second`() {
        assertEquals(0L, shortcutPostDelay(1_000, null))
        assertEquals(0L, shortcutPostDelay(5_000, 4_000))
        assertEquals(700L, shortcutPostDelay(5_300, 5_000))
        assertEquals("a clock that went back", 0L, shortcutPostDelay(100, 5_000))
    }

    // ---- the picker ----

    @Test
    fun `the picker - four at most, moves at the ends, null until the first edit`() {
        val keys = SHORTCUT_DEFAULTS
        val four = keys.take(4)
        assertNull("a fifth is refused", togglePick(four, ShortcutKey.MUSIC))
        assertEquals(four - ShortcutKey.SOUND, togglePick(four, ShortcutKey.SOUND))
        assertEquals(listOf(ShortcutKey.WIFI, ShortcutKey.MUSIC), togglePick(listOf(ShortcutKey.WIFI), ShortcutKey.MUSIC))
        assertEquals(listOf(ShortcutKey.SOUND, ShortcutKey.TORCH, ShortcutKey.AIRPLANE, ShortcutKey.WIFI), movePick(four, ShortcutKey.SOUND, up = true))
        assertEquals("the top can't move up", four, movePick(four, ShortcutKey.TORCH, up = true))
        assertEquals("the bottom can't move down", four, movePick(four, ShortcutKey.WIFI, up = false))
        assertEquals(four, movePick(four, ShortcutKey.MUSIC, up = false))
        // A new phone starts from the default's first four allowed; a stored list as it is.
        val candidates = shortcutCandidates(mask, all)
        assertEquals(four, pickerStart(null, candidates))
        assertEquals(listOf(ShortcutKey.MUSIC), pickerStart(listOf("music", "laser"), candidates))
        // Unknown keys from a later build, and keys not allowed now, are kept after the chosen ones.
        assertEquals(listOf("wifi", "laser", "brightness"), picksToStore(listOf(ShortcutKey.WIFI), listOf("laser", "brightness", "wifi"), candidates - ShortcutKey.BRIGHTNESS))
        assertEquals(listOf("music"), picksToStore(listOf(ShortcutKey.MUSIC), null, candidates))
    }

    @Test
    fun `the picker lists what the parent allows - mer tid always, the phone's absences never`() {
        assertEquals(SHORTCUT_DEFAULTS, shortcutCandidates(mask, all))
        assertEquals(emptyList<ShortcutKey>(), shortcutCandidates(mask and QuickControlFeature.SHORTCUTS.inv(), all))
        assertEquals(emptyList<ShortcutKey>(), shortcutCandidates(null, all))
        val bare = shortcutCandidates(128, ShortcutsPlatform(torchLit = null, music = null))
        assertEquals(listOf(ShortcutKey.MORE_TIME), bare)
        assertTrue(ShortcutKey.MORE_TIME in shortcutCandidates(mask, all.copy(ask = AskPart.None)))
    }

    @Test
    fun `the contract's clean rows name only keys this launcher knows`() {
        for (row in contract.getValue("clean").jsonArray.map { it.jsonObject }) {
            val out = row.getValue("out")
            if (out is JsonNull) continue
            for (key in out.jsonObject.getValue("items").jsonArray) assertTrue(ShortcutKey.of(key.jsonPrimitive.content) != null)
        }
    }
}
