package me.vibb.music.ui

import me.vibb.music.R
import me.vibb.music.bluetooth.HeadsetLine
import me.vibb.music.bluetooth.HeadsetRow
import me.vibb.music.playback.Output
import me.vibb.music.sonos.Room

/** One row of "Spill av på" (design 26a §5.1, design 52 decision 3). */
sealed interface SheetItem {
    /** The phone, or a routed Bluetooth / wired output. */
    data class Out(val output: Output) : SheetItem

    /** A paired audio device not routed now: "Koble til", "Bytt til", "Kobler til…", "Fikk ikke kontakt …". */
    data class Headset(val row: HeadsetRow) : SheetItem

    /** A Sonos room (or a group by its coordinator). */
    data class Speaker(val room: Room) : SheetItem

    /** "Se etter høyttalere" (or "Søker etter høyttalere…" while a round runs). */
    data object Rescan : SheetItem
}

/**
 * The sheet's order (design 52 decision 3): the phone, the routed outputs ([outputs] is the phone first, then the
 * routed ones, as the service publishes them), the paired-not-routed headsets, the Sonos rooms, "Se etter høyttalere".
 */
fun outputSheetRows(outputs: List<Output>, headsets: List<HeadsetRow>, rooms: List<Room>): List<SheetItem> =
    outputs.map { SheetItem.Out(it) } + headsets.map { SheetItem.Headset(it) } + rooms.map { SheetItem.Speaker(it) } + SheetItem.Rescan

/** A headset row's subtitle (the failure line takes the device's name). */
fun headsetLineText(line: HeadsetLine): Int = when (line) {
    HeadsetLine.CONNECT -> R.string.headset_connect
    HeadsetLine.SWITCH -> R.string.headset_switch
    HeadsetLine.CONNECTING -> R.string.headset_connecting
    HeadsetLine.NO_CONTACT -> R.string.headset_no_contact_row
}
