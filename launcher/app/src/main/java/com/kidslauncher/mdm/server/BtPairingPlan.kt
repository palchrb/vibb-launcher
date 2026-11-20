package com.kidslauncher.mdm.server

/**
 * Design 122: the Bluetooth pairing window's pure rules. The kid's pair tap on Vibb's own Bluetooth page arms the window
 * for that one device ([BtPairingRecord]); Android's pairing request for it opens the window (Settings pinned for its
 * pairing dialog) only for a confirm-only variant while the page is started; every end closes it.
 * [com.kidslauncher.mdm.server.BtPairingWindow] is the runtime; `BtPairingPlanTest` runs these.
 */

/** How long an armed window waits for the pairing request (from the tap). */
const val BT_PAIRING_ARM_MS = 30_000L

/** How long an open window lasts at most (from the request). */
const val BT_PAIRING_WINDOW_MS = 30_000L

/** `BluetoothDevice.PAIRING_VARIANT_*` (some are hidden), duplicated to stay Android-free. */
const val BT_VARIANT_PIN = 0
const val BT_VARIANT_PASSKEY = 1
const val BT_VARIANT_PASSKEY_CONFIRMATION = 2
const val BT_VARIANT_CONSENT = 3
const val BT_VARIANT_DISPLAY_PASSKEY = 4
const val BT_VARIANT_DISPLAY_PIN = 5
const val BT_VARIANT_OOB_CONSENT = 6
const val BT_VARIANT_PIN_16_DIGITS = 7

/**
 * The variants whose dialog only asks "Koble til?" (Settings' `CONFIRMATION_DIALOG`: two buttons, the phonebook switch,
 * no text field, no keyboard). Every other variant - a PIN or passkey to type, a code to type on the other device - opens
 * no window: "Trenger en voksen".
 */
val BT_WINDOW_VARIANTS = setOf(BT_VARIANT_PASSKEY_CONFIRMATION, BT_VARIANT_CONSENT, BT_VARIANT_OOB_CONSENT)

/**
 * The window's state: the device the page's tap bonds ([address]), the tap ([armedAt]) and the request that opened it
 * ([openedAt], `null` while only armed), all elapsed realtime; [unhid]: the arm unhid Settings (it hides it again).
 */
data class BtPairingRecord(val address: String, val armedAt: Long, val openedAt: Long? = null, val unhid: Boolean = false)

/** A pair tap arms the window only with the kiosk on, not LOCKED and no pause or override (Settings is shown then). */
fun btPairingArms(kioskOn: Boolean, locked: Boolean, lifted: Boolean): Boolean = kioskOn && !locked && !lifted

/** The window's deadline (elapsed realtime): the arm's 30 s from the tap, an open window's 30 s from the request. */
fun btPairingDeadline(record: BtPairingRecord): Long = record.openedAt?.let { it + BT_PAIRING_WINDOW_MS } ?: (record.armedAt + BT_PAIRING_ARM_MS)

/** What the pairing request does. */
enum class BtRequestAnswer {
    /** Not the armed device, or a window already open for it: as before (Settings' receiver decides). */
    PASS,

    /** The armed device, a confirm-only variant, the page started: Settings pinned, then the request goes on. */
    OPEN,

    /** The armed device needs a PIN or a passkey typed: no window, the request stops here, "Trenger en voksen". */
    GROWN_UP,

    /** The armed device but the window may not open (the page stopped, LOCKED, the kiosk off, past its time): closed. */
    END,
}

/** The window's events besides the request. */
enum class BtPairingEvent { BONDED, BOND_NONE, PAIRING_CANCEL, PAGE_STOPPED, SCREEN_OFF, CHECK }

private fun sameDevice(a: String?, b: String): Boolean = a != null && a.equals(b, ignoreCase = true)

/** Whether [now] is past the record's time, or before its tap (the elapsed clock can't run back but a reboot's can). */
private fun overdue(record: BtPairingRecord, now: Long): Boolean = now < record.armedAt || now >= btPairingDeadline(record)

/**
 * The pairing request for [address] with [variant] ([BtPairingRecord] = the armed window, `null` = none): [pageStarted]
 * = Vibb's Bluetooth page is started, [locked] = Vibb's lock is LOCKED, [kioskOn] = the kiosk pins, [now] elapsed.
 */
fun btPairingRequest(
    record: BtPairingRecord?,
    address: String?,
    variant: Int,
    pageStarted: Boolean,
    locked: Boolean,
    kioskOn: Boolean,
    now: Long,
): BtRequestAnswer = when {
    record == null || !sameDevice(address, record.address) -> BtRequestAnswer.PASS
    overdue(record, now) -> BtRequestAnswer.END
    variant !in BT_WINDOW_VARIANTS -> BtRequestAnswer.GROWN_UP
    !pageStarted || locked || !kioskOn -> BtRequestAnswer.END
    record.openedAt != null -> BtRequestAnswer.PASS
    else -> BtRequestAnswer.OPEN
}

/**
 * Whether [event] ends the window ([record]; `null` = nothing to end) and why: the armed device's bond BONDED or NONE or
 * its pairing cancelled, the page stopped, the screen off, or the time over ([CHECK][BtPairingEvent.CHECK] and every
 * other event check it). [address]: the device of a bond or cancel event. `null` = the window stays.
 */
fun btPairingEnd(record: BtPairingRecord?, event: BtPairingEvent, address: String?, now: Long): String? {
    if (record == null) return null
    val mine = sameDevice(address, record.address)
    return when {
        event == BtPairingEvent.BONDED && mine -> "bonded"
        event == BtPairingEvent.BOND_NONE && mine -> "bond_none"
        event == BtPairingEvent.PAIRING_CANCEL && mine -> "cancelled"
        event == BtPairingEvent.PAGE_STOPPED -> "page_stopped"
        event == BtPairingEvent.SCREEN_OFF -> "screen_off"
        overdue(record, now) -> "timeout"
        else -> null
    }
}

/** Whether the window keeps Settings unhidden now (the apply's hide step leaves it): armed or open and not over. */
fun btPairingHoldsSettings(record: BtPairingRecord?, now: Long): Boolean = record != null && !overdue(record, now)
