package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.bluetooth.HeadsetAction
import me.vibb.music.bluetooth.HeadsetLine
import me.vibb.music.bluetooth.HeadsetRow
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.sonos.Room
import me.vibb.music.sonos.RoomsView
import me.vibb.music.sonos.allowedRooms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Spill av på"'s rows (design 52 decision 3; 26a §5.1). */
class OutputRowsTest {
    private val buds = Output(Output.deviceKey(7), OutputKind.BLUETOOTH, "Buds")
    private val wired = Output(Output.deviceKey(9), OutputKind.WIRED, null)
    private val koble = HeadsetRow("AA:BB:CC:00:00:01", "Høyttaler", HeadsetLine.CONNECT, HeadsetAction.CONNECT)
    private val bytt = HeadsetRow("AA:BB:CC:00:00:02", "Propper", HeadsetLine.SWITCH, HeadsetAction.MAKE_ACTIVE)
    private val stua = Room("Stua", "RINCON_1", listOf("RINCON_1" to "Stua"))

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
        assertTrue(root.contains("onHeadset = { vm.headsetTapped(it, hub.output.key.takeIf { onRoom }) },"))
        assertTrue(root.contains("onPick = { actions.output(it.key); vm.sheet = null },"))
        assertTrue(root.contains("PlayerHub.Message.HEADSET_NO_CONTACT -> R.string.headset_no_contact"))
    }

    // -- design 53 §2.7, §2.12: grouping in "Spill av på" (the approved sketch; no hint lines, Q3) --

    private val s = "RINCON_STUA01400"
    private val k = "RINCON_KJOKKEN01400"
    private val b = "RINCON_BARN01400"
    private val alone = Room("Stua", s, listOf(s to "Stua"))
    private val kjokken = Room("Kjøkken", k, listOf(k to "Kjøkken"))
    private val barn = Room("Barnerommet", b, listOf(b to "Barnerommet"))
    private val ourGroup = Room("Stua + Kjøkken", s, listOf(s to "Stua", k to "Kjøkken"), listOf(s, k))
    private fun view(vararg rooms: Room, stale: Boolean = false, busy: Set<String> = emptySet()) = RoomsView(rooms.toList(), stale, scanning = false, busy = busy)

    @Test
    fun `d53 no + or − while the phone renders, the session isn't live, the rows are stale or our room is grouped away`() {
        val v = view(alone, kjokken, barn)
        val today = listOf(SheetItem.Speaker(alone), SheetItem.Speaker(kjokken), SheetItem.Speaker(barn))
        assertEquals("the phone renders", today, groupSheet(v, ours = null, live = true, playing = false))
        assertEquals(
            "a stopped or not-started session: today's rows, ours selected",
            listOf(SheetItem.Speaker(alone, selected = true), SheetItem.Speaker(kjokken), SheetItem.Speaker(barn)),
            groupSheet(v, ours = s, live = false, playing = false),
        )
        assertEquals(
            "stale: today's stale rows",
            listOf(SheetItem.Speaker(alone, RoomLine.STALE, selected = true), SheetItem.Speaker(kjokken, RoomLine.STALE), SheetItem.Speaker(barn, RoomLine.STALE)),
            groupSheet(view(alone, kjokken, barn, stale = true), ours = s, live = true, playing = true),
        )
        // grouped away: our Stua sits in Barnerommet's row - nothing selected, that row says so, no + anywhere
        val away = Room("Barnerommet + Stua", b, listOf(b to "Barnerommet", s to "Stua"), listOf(b, s))
        assertEquals(
            listOf(SheetItem.Speaker(away, RoomLine.GROUPED_AWAY), SheetItem.Speaker(kjokken)),
            groupSheet(view(away, kjokken), ours = s, live = true, playing = true),
        )
    }

    @Test
    fun `d53 offered - our lone room selected without −, a + on every other row with its line, busy, pending and failed`() {
        val rows = groupSheet(view(alone, kjokken, barn, busy = setOf(b)), ours = s, live = true, playing = true, pending = setOf(k))
        assertEquals(
            listOf(
                SheetItem.Speaker(alone, RoomLine.PLAYING_NOW, selected = true),
                SheetItem.Speaker(kjokken, RoomLine.SWITCH_HERE, join = GroupMark.PENDING),
                SheetItem.Speaker(barn, RoomLine.BUSY, join = GroupMark.BUTTON),
            ),
            rows,
        )
        val paused = groupSheet(view(alone, kjokken), ours = s, live = true, playing = false, failed = setOf(k))
        assertEquals("a paused session counts as live (N4)", SheetItem.Speaker(alone, RoomLine.SONOS, selected = true), paused[0])
        assertEquals(SheetItem.Speaker(kjokken, RoomLine.NO_CONTACT, join = GroupMark.BUTTON), paused[1])
        assertTrue("no group block, no slider for a lone room", rows.none { it is SheetItem.Group })
    }

    @Test
    fun `d53 our group is one block - a − and a slider per member, the Begge slider at the mean, the accepted room among them`() {
        val rows = groupSheet(
            view(ourGroup, barn), ours = s, live = true, playing = true, pending = setOf(k),
            volumes = mapOf(s to 55, k to 35), groupVolume = 45,
        )
        assertEquals(
            SheetItem.Group(
                ourGroup, playing = true,
                listOf(GroupMember(s, "Stua", GroupMark.BUTTON, false, 55), GroupMember(k, "Kjøkken", GroupMark.PENDING, false, 35)),
                volume = 45,
            ),
            rows[0],
        )
        assertEquals(SheetItem.Speaker(barn, RoomLine.SWITCH_HERE, join = GroupMark.BUTTON), rows[1])
        // Q4: our row kept by the scope with the room the kids grouped in (allowedRooms' `ours`/`accepted`) - its − like any
        // member's; another out-of-scope row is gone, so it gets no +
        val scoped = allowedRooms(view(ourGroup, barn), listOf(s), ours = s, accepted = setOf(k))
        val sheet = groupSheet(scoped, ours = s, live = true, playing = true)
        assertEquals(1, sheet.size)
        assertEquals(listOf(GroupMark.BUTTON, GroupMark.BUTTON), (sheet[0] as SheetItem.Group).members.map { it.leave })
        // three rooms read "Spiller nå i alle 3" (the block's playing line counts its members)
        val three = Room("Stua + Kjøkken + Barnerommet", s, listOf(s to "Stua", k to "Kjøkken", b to "Barnerommet"), listOf(s, k, b))
        assertEquals(3, (groupSheet(view(three), s, true, true)[0] as SheetItem.Group).members.size)
    }

    @Test
    fun `d53 the order with grouping - phone, routed outputs, paired-not-routed, the group block and the + rows, Se etter hoyttalere`() {
        val rooms = groupSheet(view(ourGroup, barn), ours = s, live = true, playing = true, volumes = mapOf(s to 55, k to 35), groupVolume = 45)
        val rows = outputSheetItems(listOf(Output.PHONE, buds, wired), listOf(koble, bytt), rooms)
        assertEquals(
            listOf(
                SheetItem.Out(Output.PHONE), SheetItem.Out(buds), SheetItem.Out(wired),
                SheetItem.Headset(koble), SheetItem.Headset(bytt),
                rooms[0], SheetItem.Speaker(barn, RoomLine.SWITCH_HERE, join = GroupMark.BUTTON), SheetItem.Rescan,
            ),
            rows,
        )
        assertTrue(rooms[0] is SheetItem.Group)
        // no hint line exists (Q3): the sheet's items are these kinds only, and no string carries the sketch's captions
        val strings = moduleFile("src/main/res/values-nb/strings.xml").readText()
        for (caption in listOf("betyr «spill også her»", "tar et rom ut", "kan også legges til", "flytter alle rommene")) assertFalse(caption, strings.contains(caption))
    }

    @Test
    fun `d53 the sheet's wiring - the + joins, the − leaves, the sliders send through the view model's throttle`() {
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("onJoin = { actions.roomJoin(it.uid) },"))
        assertTrue(root.contains("onLeave = actions::roomLeave,"))
        assertTrue(root.contains("onVolume = { uid, group, volume, done -> vm.roomVolume(uid, group, volume, done, actions::roomVolume) },"))
        val sheet = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue("a tap on the row itself still switches there", sheet.contains(") { onPick(o) }"))
        assertTrue(sheet.contains("GroupButton(Icons.add, stringResource(R.string.room_join, r.label)"))
        assertTrue(sheet.contains("GroupButton(Icons.remove, stringResource(R.string.room_leave, m.name)"))
        val vm = moduleFile("src/main/java/me/vibb/music/ui/MusicViewModel.kt").readText()
        assertTrue(vm.contains("const val VOLUME_SEND_MS = 150L"))
        assertTrue(vm.contains("PlayerHub.sheetOpen.value = true") && vm.contains("PlayerHub.sheetOpen.value = false"))
    }

    /** Decision 3 (52-3, 52-5): the view model's wiring of the pure rules. */
    @Test
    fun `the view model - OUTPUT only from a routed attempt's plan, the closed sheet's hint, the proxy with the sheet`() {
        val vm = moduleFile("src/main/java/me/vibb/music/ui/MusicViewModel.kt").readText()
        val reduce = vm.substringAfter("private fun reduce(e: ConnectEvent) {").substringBefore("\n    }\n")
        assertTrue(reduce.contains("if (step.failed.isNotEmpty() && !sheetOpen) PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.HEADSET_NO_CONTACT)"))
        assertTrue(reduce.contains("if (headsetTapPlan(attempt.fromKey, PlayerHub.state.value.output.key, routedNow = true) == HeadsetMove.OUTPUT) {"))
        assertTrue(reduce.contains("routedIds[address]?.let { outputAsks.trySend(Output.deviceKey(it)) }"))
        assertTrue("the only OUTPUT the view model asks for", vm.split("outputAsks.trySend(").size == 2)
        val root = moduleFile("src/main/java/me/vibb/music/ui/MusicRoot.kt").readText()
        assertTrue(root.contains("LaunchedEffect(Unit) { vm.outputRequests.collect { actions.output(it) } }"))
        // the tap: no output change at the tap, the room's key of the moment carried in the attempt
        val tap = vm.substringAfter("fun headsetTapped(row: HeadsetRow, fromKey: String?) {").substringBefore("\n    }\n")
        assertTrue(tap.contains("reduce(ConnectEvent.Tapped(row.address, SystemClock.elapsedRealtime(), fromKey, row.action == HeadsetAction.MAKE_ACTIVE))"))
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
