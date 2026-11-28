package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.ui.quickcontrols.BtRowStatus
import com.kidslauncher.mdm.ui.quickcontrols.bluetoothRowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 122: the Bluetooth pairing window's rules, and by source the glue that must hold them. */
class BtPairingPlanTest {
    private val jabra = "AA:BB:CC:00:11:22"
    private val other = "AA:BB:CC:00:11:99"
    private val armed = BtPairingRecord(jabra, armedAt = 10_000)
    private val open = armed.copy(openedAt = 12_000)

    private fun req(
        record: BtPairingRecord? = armed,
        address: String? = jabra,
        variant: Int = BT_VARIANT_CONSENT,
        page: Boolean = true,
        locked: Boolean = false,
        kiosk: Boolean = true,
        now: Long = 12_000,
    ) = btPairingRequest(record, address, variant, page, locked, kiosk, now)

    @Test
    fun `the tap arms only with the kiosk on, unlocked and no pause`() {
        assertTrue(btPairingArms(kioskOn = true, locked = false, lifted = false))
        assertFalse(btPairingArms(kioskOn = false, locked = false, lifted = false))
        assertFalse(btPairingArms(kioskOn = true, locked = true, lifted = false))
        assertFalse(btPairingArms(kioskOn = true, locked = false, lifted = true))
    }

    @Test
    fun `a confirm-only request for the armed device opens the window while the page is started`() {
        for (variant in listOf(BT_VARIANT_CONSENT, BT_VARIANT_PASSKEY_CONFIRMATION, BT_VARIANT_OOB_CONSENT)) {
            assertEquals(BtRequestAnswer.OPEN, req(variant = variant))
        }
        // The address's case doesn't matter.
        assertEquals(BtRequestAnswer.OPEN, req(address = jabra.lowercase()))
    }

    @Test
    fun `a PIN, a passkey or a code to type on the device never opens a window`() {
        for (variant in listOf(BT_VARIANT_PIN, BT_VARIANT_PASSKEY, BT_VARIANT_PIN_16_DIGITS, BT_VARIANT_DISPLAY_PASSKEY, BT_VARIANT_DISPLAY_PIN, -1, 99)) {
            assertEquals("variant $variant", BtRequestAnswer.GROWN_UP, req(variant = variant))
            // Not even with the page gone: the line waits for the page.
            assertEquals(BtRequestAnswer.GROWN_UP, req(variant = variant, page = false))
        }
    }

    @Test
    fun `a request that isn't the armed device's changes nothing`() {
        assertEquals(BtRequestAnswer.PASS, req(record = null))
        assertEquals(BtRequestAnswer.PASS, req(address = other))
        assertEquals(BtRequestAnswer.PASS, req(address = null))
        assertEquals(BtRequestAnswer.PASS, req(record = null, variant = BT_VARIANT_PIN))
        // A second request for an open window keeps it as it is.
        assertEquals(BtRequestAnswer.PASS, req(record = open, now = 13_000))
    }

    @Test
    fun `the armed device's request ends the arm when the page stopped, LOCKED, the kiosk off or too late`() {
        assertEquals(BtRequestAnswer.END, req(page = false))
        assertEquals(BtRequestAnswer.END, req(locked = true))
        assertEquals(BtRequestAnswer.END, req(kiosk = false))
        assertEquals(BtRequestAnswer.END, req(now = armed.armedAt + BT_PAIRING_ARM_MS))
        assertEquals(BtRequestAnswer.OPEN, req(now = armed.armedAt + BT_PAIRING_ARM_MS - 1))
        // A clock before the tap (a reboot's elapsed time) is over too.
        assertEquals(BtRequestAnswer.END, req(now = armed.armedAt - 1))
        assertEquals(BtRequestAnswer.END, req(variant = BT_VARIANT_PIN, now = armed.armedAt + BT_PAIRING_ARM_MS))
    }

    @Test
    fun `the deadline is 30 s from the tap while armed and 30 s from the request once open`() {
        assertEquals(40_000, btPairingDeadline(armed))
        assertEquals(42_000, btPairingDeadline(open))
        assertEquals(30_000, BT_PAIRING_ARM_MS)
        assertEquals(30_000, BT_PAIRING_WINDOW_MS)
    }

    @Test
    fun `every closer ends the window, the device's bond events only for that device`() {
        for (record in listOf(armed, open)) {
            assertEquals("bonded", btPairingEnd(record, BtPairingEvent.BONDED, jabra, 13_000))
            assertEquals("bond_none", btPairingEnd(record, BtPairingEvent.BOND_NONE, jabra, 13_000))
            assertEquals("cancelled", btPairingEnd(record, BtPairingEvent.PAIRING_CANCEL, jabra, 13_000))
            assertEquals("page_stopped", btPairingEnd(record, BtPairingEvent.PAGE_STOPPED, null, 13_000))
            assertEquals("screen_off", btPairingEnd(record, BtPairingEvent.SCREEN_OFF, null, 13_000))
            for (event in listOf(BtPairingEvent.BONDED, BtPairingEvent.BOND_NONE, BtPairingEvent.PAIRING_CANCEL)) {
                assertNull(btPairingEnd(record, event, other, 13_000))
                assertNull(btPairingEnd(record, event, null, 13_000))
            }
            assertNull(btPairingEnd(record, BtPairingEvent.CHECK, null, 13_000))
            assertEquals("timeout", btPairingEnd(record, BtPairingEvent.CHECK, null, btPairingDeadline(record)))
            // Another device's event still checks the time.
            assertEquals("timeout", btPairingEnd(record, BtPairingEvent.BONDED, other, btPairingDeadline(record)))
            assertEquals("timeout", btPairingEnd(record, BtPairingEvent.CHECK, null, record.armedAt - 1))
        }
        for (event in BtPairingEvent.values()) assertNull(btPairingEnd(null, event, jabra, 13_000))
    }

    @Test
    fun `the apply leaves Settings unhidden only while a window is armed or open and not over`() {
        assertFalse(btPairingHoldsSettings(null, 13_000))
        assertTrue(btPairingHoldsSettings(armed, 13_000))
        assertTrue(btPairingHoldsSettings(open, 41_999))
        assertFalse(btPairingHoldsSettings(open, 42_000))
        assertFalse(btPairingHoldsSettings(armed, 40_000))
        assertFalse(btPairingHoldsSettings(armed, 9_999))
    }

    @Test
    fun `a device that needs a grown-up says so on its row until it is paired`() {
        val info = QuickControls.BluetoothDeviceInfo(name = "Jabra", address = jabra, bonded = false, audio = true, state = BtLink.OFF)
        assertEquals(BtRowStatus.GROWN_UP, bluetoothRowStatus(info, pairing = true, attempting = false, failed = false, grownUp = true))
        assertEquals(BtRowStatus.PAIRING, bluetoothRowStatus(info, pairing = true, attempting = false, failed = false))
        val bonded = QuickControls.BluetoothDeviceInfo(name = "Jabra", address = jabra, bonded = true, audio = true, state = BtLink.OFF)
        assertEquals(BtRowStatus.PAIRED, bluetoothRowStatus(bonded, pairing = false, attempting = false, failed = false, grownUp = true))
    }

    // The glue, by source.

    private fun src(path: String) =
        listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }.readText()

    private fun code(text: String) = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private val window by lazy { code(src("java/com/kidslauncher/mdm/server/BtPairingWindow.kt")) }

    @Test
    fun `the window pins Settings only through LockTaskChrome under its own owner, and the page arms before the bond`() {
        assertEquals(1, Regex("LockTaskChrome\\.routeWindow\\(app, SETTINGS_PACKAGE, LockTaskChrome\\.RouteOwner\\.BT_PAIRING\\)").findAll(window).count())
        assertFalse(window.contains("setLockTaskPackages"))
        val page = code(src("java/com/kidslauncher/mdm/ui/quickcontrols/BluetoothDevicesActivity.kt"))
        val tap = page.substringAfter("private fun onPairTapped(").substringBefore("private fun onForgetTapped(")
        assertTrue(tap.indexOf("BtPairingWindow.arm(app, device.address)") in 0 until tap.indexOf("QuickControls.pairBluetoothDevice(app, device.address)"))
        assertTrue(page.substringAfter("override fun onStop() {").substringBefore("}").contains("BtPairingWindow.pageStopped(this)"))
        assertTrue(page.substringAfter("override fun onStart() {").contains("BtPairingWindow.pageStarted {"))
    }

    @Test
    fun `the receiver runs ahead of Settings' and stops a request that needs a grown-up`() {
        assertTrue(window.contains("private const val REQUEST_PRIORITY = 999"))
        assertTrue(window.contains("priority = REQUEST_PRIORITY"))
        assertTrue(window.contains("app.registerReceiver(r, filter, null, handler, Context.RECEIVER_EXPORTED)"))
        for (action in listOf("BluetoothDevice.ACTION_PAIRING_REQUEST", "BluetoothDevice.ACTION_BOND_STATE_CHANGED", "ACTION_PAIRING_CANCEL", "Intent.ACTION_SCREEN_OFF")) {
            assertTrue(action, window.contains("addAction($action)"))
        }
        // Code QA M1: the abort runs as soon as the answer is known, before the close (which would end the delivery
        // without its result), and the receiver goes only after its onReceive has returned.
        val receive = window.substringAfter("BluetoothDevice.ACTION_PAIRING_REQUEST -> {").substringBefore("BluetoothDevice.ACTION_BOND_STATE_CHANGED ->")
        assertTrue(receive.contains("request(app, device?.address, variant) { answer ->"))
        assertTrue(receive.contains("if (answer == BtRequestAnswer.GROWN_UP && isOrderedBroadcast) abortBroadcast()"))
        assertEquals(1, Regex("abortBroadcast\\(\\)").findAll(window).count())
        val request = window.substringAfter("internal fun request(").substringBefore("internal fun event(")
        assertTrue(request.indexOf("decided(answer)") in 0 until request.indexOf("when (answer) {"))
        assertTrue(request.indexOf("when (answer) {") < request.indexOf("closeLocked(app, \"grown_up\")"))
        val close = window.substringAfter("private fun closeLocked(").substringBefore("private fun rehide(")
        assertTrue(close.contains("unregisterLater(app)"))
        assertFalse(close.contains("unregisterLocked(app)"))
        val later = window.substringAfter("private fun unregisterLater(").substringBefore("private fun unregisterLocked(")
        assertTrue(later.contains("h.post { synchronized(lock) { if (record == null) unregisterLocked(app) } }"))
    }

    @Test
    fun `the arm needs the page still started, and the deadline ticks on the window's thread`() {
        // Code QA L2 and L3.
        val arm = window.substringAfter("private fun armNow(").substringBefore("fun bondNotStarted(")
        assertTrue(arm.indexOf("if (!pageStarted) {") in 0 until arm.indexOf("prefs(app).edit().putLong(KEY_END"))
        val schedule = window.substringAfter("private fun schedule() {").substringBefore("\n    }")
        assertTrue(schedule.contains("h.postDelayed(tick,"))
        assertFalse(window.contains("main.postDelayed(tick"))
        assertFalse(window.contains("main.removeCallbacks(tick)"))
    }

    @Test
    fun `the close takes Settings out, hides it again when the arm unhid it, and the start puts a left window back`() {
        val close = window.substringAfter("private fun closeLocked(").substringBefore("private fun rehide(")
        assertTrue(close.contains("val r = record ?: return"))
        assertTrue(close.contains("LockTaskChrome.routeWindow(app, null, LockTaskChrome.RouteOwner.BT_PAIRING)"))
        assertTrue(close.contains("if (r.unhid && !lifted()) {"))
        assertTrue(close.contains("runCatching { rehide(app) }"))
        // Code QA L1: the marker goes only after the unpin and the re-hide went through.
        assertTrue(close.contains("if (done) {\n            runCatching { prefs(app).edit().remove(KEY_END).commit() }"))
        val putBack = window.substringAfter("fun putBackIfMarked(")
        assertFalse(putBack.contains("finally"))
        assertTrue(putBack.indexOf("AppEnforcer.applyCurrent(app)") in 0 until putBack.indexOf("} catch (e: Exception) {"))
        assertTrue(putBack.indexOf("return\n            }") < putBack.indexOf("prefs(app).edit().remove(KEY_END).commit()"))
        val app = code(src("java/com/kidslauncher/mdm/Application.kt"))
        assertTrue(app.contains("com.kidslauncher.mdm.server.BtPairingWindow.putBackIfMarked(this)"))
        assertEquals(3, Regex("AirplaneRoute\\.inFlight \\|\\| com\\.kidslauncher\\.mdm\\.server\\.BtPairingWindow\\.inFlight").findAll(app).count())
        assertTrue(code(src("java/com/kidslauncher/mdm/server/MdmSyncWorker.kt")).contains("BtPairingWindow.putBackIfMarked(context)"))
        val enforcer = code(src("java/com/kidslauncher/mdm/server/AppEnforcer.kt"))
        assertTrue(enforcer.contains("BtPairingWindow.settingsHideStep(dpm, admin, shouldBeHidden)"))
        assertTrue(enforcer.contains("BtPairingWindow.duringApply(context, ruleEnds = scheduleLocked || overrideActive)"))
    }

    @Test
    fun `the line is short in both languages`() {
        val en = src("res/values/strings.xml")
        val nb = src("res/values-nb/strings.xml")
        assertTrue(en.contains("<string name=\"bluetooth_devices_grown_up\">Needs a grown-up</string>"))
        assertTrue(nb.contains("<string name=\"bluetooth_devices_grown_up\">Trenger en voksen</string>"))
    }
}
