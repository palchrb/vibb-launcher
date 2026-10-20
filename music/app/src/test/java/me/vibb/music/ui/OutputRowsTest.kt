package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.bluetooth.HeadsetAction
import me.vibb.music.bluetooth.HeadsetLine
import me.vibb.music.bluetooth.HeadsetRow
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.sonos.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Spill av på"'s rows (design 52 decision 3; 26a §5.1). */
class OutputRowsTest {
    private val buds = Output(Output.deviceKey(7), OutputKind.BLUETOOTH, "Buds")
    private val wired = Output(Output.deviceKey(9), OutputKind.WIRED, null)
    private val koble = HeadsetRow("AA:BB:CC:00:00:01", "Høyttaler", HeadsetLine.CONNECT, HeadsetAction.CONNECT)
    private val bytt = HeadsetRow("AA:BB:CC:00:00:02", "Propper", HeadsetLine.SWITCH, HeadsetAction.MAKE_ACTIVE)
    private val stua = Room("Stua", "RINCON_1", listOf("Stua"))

    @Test
    fun `the order - phone, routed outputs, paired-not-routed, rooms, Se etter hoyttalere`() {
        val rows = outputSheetRows(listOf(Output.PHONE, buds, wired), listOf(koble, bytt), listOf(stua))
        assertEquals(
            listOf(
                SheetItem.Out(Output.PHONE), SheetItem.Out(buds), SheetItem.Out(wired),
                SheetItem.Headset(koble), SheetItem.Headset(bytt),
                SheetItem.Speaker(stua), SheetItem.Rescan,
            ),
            rows,
        )
    }

    @Test
    fun `no grant or Bluetooth off - today's sheet`() {
        assertEquals(
            listOf(SheetItem.Out(Output.PHONE), SheetItem.Speaker(stua), SheetItem.Rescan),
            outputSheetRows(listOf(Output.PHONE), emptyList(), listOf(stua)),
        )
    }

    @Test
    fun `each line has its words, the failure line takes the name`() {
        val ids = HeadsetLine.entries.map(::headsetLineText)
        assertEquals(ids.size, ids.toSet().size)
        val sheet = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue(sheet.contains("if (h.line == HeadsetLine.NO_CONTACT) stringResource(line, name) else stringResource(line)"))
        // a headset tap keeps the sheet open (only the routed success closes it, in the view model); a pick closes it
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("onHeadset = { vm.headsetTapped(it, onRoom) },"))
        assertTrue(root.contains("onPick = { actions.output(it.key); vm.sheet = null },"))
        assertTrue(root.contains("PlayerHub.Message.HEADSET_NO_CONTACT -> R.string.headset_no_contact"))
    }

    /** Decision 3 (52-3, 52-5): the view model's wiring of the pure rules. */
    @Test
    fun `the view model - OUTPUT only from a routed attempt's plan, the closed sheet's hint, the proxy with the sheet`() {
        val vm = moduleFile("src/main/java/me/vibb/music/ui/MusicViewModel.kt").readText()
        val reduce = vm.substringAfter("private fun reduce(e: ConnectEvent) {").substringBefore("\n    }\n")
        assertTrue(reduce.contains("if (step.failed.isNotEmpty() && !sheetOpen) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.HEADSET_NO_CONTACT)"))
        assertTrue(reduce.contains("if (headsetTapPlan(attempt.onRoom, routedNow = true) == HeadsetMove.OUTPUT) {"))
        assertTrue(reduce.contains("routedIds[address]?.let { outputAsks.trySend(Output.deviceKey(it)) }"))
        assertTrue("the only OUTPUT the view model asks for", vm.split("outputAsks.trySend(").size == 2)
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("LaunchedEffect(Unit) { vm.outputRequests.collect { actions.output(it) } }"))
        // the tap: no output change at the tap, the onRoom of the moment carried in the attempt
        val tap = vm.substringAfter("fun headsetTapped(row: HeadsetRow, onRoom: Boolean) {").substringBefore("\n    }\n")
        assertTrue(tap.contains("reduce(ConnectEvent.Tapped(row.address, SystemClock.elapsedRealtime(), onRoom))"))
        assertTrue(tap.contains("delay(BluetoothAudio.CONNECT_WINDOW_MS)"))
        assertTrue("the renderer doesn't move at the tap", !tap.contains("output"))
        // the proxy opens with the sheet and closes after it unless a call still runs
        val sheet = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue(sheet.contains("DisposableEffect(Unit) {\n        onOpen()\n        onDispose(onClose)\n    }"))
        assertTrue(vm.contains("if (sheetOpen || calls > 0) return"))
        assertTrue(root.contains("onOpen = { actions.refreshRooms(); vm.outputSheetOpened() },"))
        assertTrue(root.contains("onClose = vm::outputSheetClosed,"))
    }
}
