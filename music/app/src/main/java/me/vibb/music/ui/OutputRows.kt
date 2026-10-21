package me.vibb.music.ui

import me.vibb.music.R
import me.vibb.music.bluetooth.HeadsetLine
import me.vibb.music.bluetooth.HeadsetRow
import me.vibb.music.playback.Output
import me.vibb.music.sonos.Room
import me.vibb.music.sonos.RoomsView
import me.vibb.music.sonos.groupingOffered

/** What a Sonos row says under its name (design 26a §5.1; design 53 §2.7). */
enum class RoomLine {
    /** "Sonos" (today's row; our own room while it doesn't play). */
    SONOS,

    /** "Sonos · svarer ikke": nobody answered. */
    STALE,

    /** "Spiller nå": our room alone, playing. */
    PLAYING_NOW,

    /** "Trykk for å bytte hit": another row while grouping is offered (a tap on the row still switches there). */
    SWITCH_HERE,

    /** "Spiller noe annet": that row's coordinator plays another stream. */
    BUSY,

    /** "Spilles i en annen gruppe": the row our room sits in, grouped away (the player's line). */
    GROUPED_AWAY,

    /** "Fikk ikke kontakt med X - er den på?": its "+" got no answer. */
    NO_CONTACT,
}

/** A row's "+" or a member's "−" (design 53 §2.7): none, the button, or the Spinner in its place until the topology shows it. */
enum class GroupMark { NONE, BUTTON, PENDING }

/** One room of our group's block (§2.7, §2.12): its name, its "−", its failure line and its own slider's volume. */
data class GroupMember(val uid: String, val name: String, val leave: GroupMark, val failed: Boolean, val volume: Int?)

/** One row of "Spill av på" (design 26a §5.1, design 52 decision 3, design 53 §2.7). */
sealed interface SheetItem {
    /** The phone, or a routed Bluetooth / wired output. */
    data class Out(val output: Output) : SheetItem

    /** A paired audio device not routed now: "Koble til", "Bytt til", "Kobler til…", "Fikk ikke kontakt …". */
    data class Headset(val row: HeadsetRow) : SheetItem

    /** A Sonos room (or a group by its coordinator): its line, whether it is ours, and its "+" (design 53 §2.7). */
    data class Speaker(val room: Room, val line: RoomLine = RoomLine.SONOS, val selected: Boolean = false, val join: GroupMark = GroupMark.NONE) : SheetItem

    /**
     * Our group's selected block (design 53 §2.7, §2.12): the group's label, "Spiller nå i begge" / "Spiller nå i alle
     * N" while [playing], the "Begge"/"Alle" slider at [volume] (the rooms' mean), and one line per member, coordinator
     * first, each with its "−" and its own slider. Always two or more members.
     */
    data class Group(val room: Room, val playing: Boolean, val members: List<GroupMember>, val volume: Int?) : SheetItem

    /** "Se etter høyttalere" (or "Søker etter høyttalere…" while a round runs). */
    data object Rescan : SheetItem
}

/**
 * The sheet's order (design 52 decision 3): the phone, the routed outputs ([outputs] is the phone first, then the
 * routed ones, as the service publishes them), the paired-not-routed headsets, the Sonos rooms, "Se etter høyttalere".
 */
fun outputSheetRows(outputs: List<Output>, headsets: List<HeadsetRow>, rooms: List<Room>): List<SheetItem> =
    outputSheetItems(outputs, headsets, rooms.map { SheetItem.Speaker(it) })

/**
 * The same order with the rooms as [groupSheet] decided them (design 53 §2.7, the user with Q3): the phone, the
 * wired and Bluetooth outputs and the headsets stay above the rooms; our group's block is one item among the rooms.
 */
fun outputSheetItems(outputs: List<Output>, headsets: List<HeadsetRow>, rooms: List<SheetItem>): List<SheetItem> =
    outputs.map { SheetItem.Out(it) } + headsets.map { SheetItem.Headset(it) } + rooms + SheetItem.Rescan

/**
 * The Sonos rows of "Spill av på" (design 53 §2.7; the approved sketch's boards 1-4, no hint lines - Q3). [view] is
 * the rows the scope allows (`allowedRooms`, our own row kept with the rooms it accepted), [ours] the uid that renders
 * (null: the phone or a headset), [live] the controller's live session, [playing] `showsPlaying`; [pending], [failed]
 * and the volumes are the service's published state.
 *
 * Grouping is offered only by [groupingOffered]; otherwise the rows are today's - a group is one row, no "+" or "−",
 * our own row selected; grouped away, the row holding our room says so and nothing is selected. Offered: our row is
 * selected - a lone room "Spiller nå" / "Sonos", a group its block with a "−" per member (a group always has two or
 * more, so a "−" never leaves nothing playing: Q2) - and every other row gets its "+", "Trykk for å bytte hit" or
 * "Spiller noe annet" (a tap on the row itself still switches there).
 */
fun groupSheet(
    view: RoomsView,
    ours: String?,
    live: Boolean,
    playing: Boolean,
    pending: Set<String> = emptySet(),
    failed: Set<String> = emptySet(),
    volumes: Map<String, Int> = emptyMap(),
    groupVolume: Int? = null,
): List<SheetItem> {
    if (!groupingOffered(view, ours, live)) {
        val today = if (view.stale) RoomLine.STALE else RoomLine.SONOS
        return view.rooms.map { r ->
            val away = ours != null && r.uid != ours && ours in r.members
            SheetItem.Speaker(r, if (away) RoomLine.GROUPED_AWAY else today, selected = ours != null && r.uid == ours)
        }
    }
    return view.rooms.map { r ->
        when {
            r.uid == ours && r.names.size >= 2 -> SheetItem.Group(
                r, playing,
                r.names.map { (u, n) -> GroupMember(u, n, if (u in pending) GroupMark.PENDING else GroupMark.BUTTON, u in failed, volumes[u]) },
                groupVolume,
            )
            r.uid == ours -> SheetItem.Speaker(r, if (playing) RoomLine.PLAYING_NOW else RoomLine.SONOS, selected = true)
            else -> SheetItem.Speaker(
                r,
                when {
                    r.uid in failed -> RoomLine.NO_CONTACT
                    r.uid in view.busy -> RoomLine.BUSY
                    else -> RoomLine.SWITCH_HERE
                },
                join = if (r.uid in pending) GroupMark.PENDING else GroupMark.BUTTON,
            )
        }
    }
}

/** A Sonos row's subtitle (the failure line takes the row's label). */
fun roomLineText(line: RoomLine): Int = when (line) {
    RoomLine.SONOS -> R.string.output_sonos
    RoomLine.STALE -> R.string.output_sonos_stale
    RoomLine.PLAYING_NOW -> R.string.room_playing_now
    RoomLine.SWITCH_HERE -> R.string.room_switch_here
    RoomLine.BUSY -> R.string.room_busy
    RoomLine.GROUPED_AWAY -> R.string.renderer_grouped_away
    RoomLine.NO_CONTACT -> R.string.room_no_contact
}

/** A headset row's subtitle (the failure line takes the device's name). */
fun headsetLineText(line: HeadsetLine): Int = when (line) {
    HeadsetLine.CONNECT -> R.string.headset_connect
    HeadsetLine.SWITCH -> R.string.headset_switch
    HeadsetLine.CONNECTING -> R.string.headset_connecting
    HeadsetLine.NO_CONTACT -> R.string.headset_no_contact_row
}
