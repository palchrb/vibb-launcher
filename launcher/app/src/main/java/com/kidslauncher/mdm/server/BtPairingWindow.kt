package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.assistant.SETTINGS_PACKAGE
import com.kidslauncher.mdm.lock.LockTaskChrome
import com.kidslauncher.mdm.lock.PinLockRuntime

private const val LOG_TAG = "BtPairingWindow"
private const val PREFS = "bt_pairing_window"
private const val KEY_END = "end_elapsed"

/** `BluetoothDevice.ACTION_PAIRING_CANCEL` (hidden): Android's own end of a pairing request. */
private const val ACTION_PAIRING_CANCEL = "android.bluetooth.device.action.PAIRING_CANCEL"

/** Ahead of Settings' `BluetoothPairingRequest` (priority 0) in the ordered `PAIRING_REQUEST`: the pin lands first. */
private const val REQUEST_PRIORITY = 999

/** Settings' package events after a close are still the window's (its re-hide's `PACKAGE_REMOVED`). */
private const val QUIET_AFTER_MS = 5_000L

/**
 * Design 122: the Bluetooth pairing window. Android's pairing dialog is Settings' `BluetoothPairingDialog`, started by
 * Settings' receiver of the ordered `ACTION_PAIRING_REQUEST`; in the kiosk lock task turns that start into
 * `BlockedAppActivity`, and where Settings is hidden its receiver never runs. So for a pair tap on Vibb's own page:
 * - [arm] (before `createBond`): the device remembered, Settings unhidden when the plan hides it (the broadcast's
 *   receivers are fixed when it is sent), not pinned - lock task still blocks it;
 * - the request for that device (this receiver, ahead of Settings'): a confirm-only variant with the page started pins
 *   Settings ([LockTaskChrome.routeWindow], the shade closed) before Settings' receiver starts the dialog; a PIN or
 *   passkey variant stops the request and the page says "Trenger en voksen";
 * - [close], once, on the first of: the bond BONDED or NONE, the pairing cancelled, the page stopped, the screen off,
 *   a time rule or a pause, 30 s - Settings out of the list (two passes), hidden again if the arm unhid it.
 * One lock of its own (like design 119's login window), never the enforcer's: the order is AppEnforcer, then this,
 * then LockTaskChrome. A process death leaves the DE marker: [putBackIfMarked] closes it at the next start.
 */
object BtPairingWindow {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** This process's window (`null` = none). Written under [lock]. */
    @Volatile
    private var record: BtPairingRecord? = null

    @Volatile
    private var pageStarted = false

    @Volatile
    private var quietUntil = 0L

    /** The devices that asked for a PIN or a passkey ("Trenger en voksen"), until their next pair tap. Main thread. */
    private val grownUp = mutableSetOf<String>()

    /** The page's listener for [grownUp] (main thread). */
    private var grownUpListener: (() -> Unit)? = null

    private var receiver: BroadcastReceiver? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var appContext: Context? = null

    private val tick = Runnable { handler?.post { appContext?.let { event(it, BtPairingEvent.CHECK, null) } } }

    /** Settings' package events are the window's now (Home's list and the install receiver leave them). */
    val inFlight: Boolean
        get() = record != null || SystemClock.elapsedRealtime() < quietUntil

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    private fun lifted(): Boolean = try {
        RestrictionsPause.isActive() || OfflineOverride.isActive()
    } catch (e: Exception) {
        false
    }

    // --- the page ---

    /** The Bluetooth page started (main thread): [listener] hears of a device that needs a grown-up. */
    fun pageStarted(listener: () -> Unit) {
        pageStarted = true
        grownUpListener = listener
    }

    /** The page stopped (main thread): the window closes. */
    fun pageStopped(context: Context) {
        pageStarted = false
        grownUpListener = null
        val app = context.applicationContext
        handler?.post { event(app, BtPairingEvent.PAGE_STOPPED, null) }
    }

    /** Whether [address] asked for a PIN or a passkey since its last pair tap. Main thread. */
    fun needsGrownUp(address: String): Boolean = address in grownUp

    /** The devices that need a grown-up. Main thread. */
    fun grownUpDevices(): Set<String> = grownUp.toSet()

    // --- the arm ---

    /** A pair tap on [address] (main thread): its "Trenger en voksen" goes until the request asks again. */
    fun pairTapped(address: String) {
        grownUp.remove(address)
    }

    /**
     * The page's pair tap for [address], right before `createBond`, off the main thread: the window armed for it, Settings
     * unhidden before this returns (so the request finds its receiver). Nothing with the kiosk off, LOCKED or in a pause
     * ([btPairingArms]) - Settings' dialog shows as before then, or not at all.
     */
    fun arm(context: Context, address: String) {
        if (!btPairingArms(LockTaskChrome.kioskOn, PinLockRuntime.chromeLocked, lifted())) return
        val app = context.applicationContext
        appContext = app
        ensureHandler()
        armNow(app, address)
    }

    private fun armNow(app: Context, address: String) {
        synchronized(lock) {
            // A window for another device closes first (one window at a time).
            if (record != null) closeLocked(app, "rearmed")
            val now = SystemClock.elapsedRealtime()
            var armed = BtPairingRecord(address, now)
            // Written before the unhide: a death after it leaves the marker, and the next start's apply hides Settings.
            prefs(app).edit().putLong(KEY_END, btPairingDeadline(armed)).commit()
            record = armed
            registerLocked(app)
            val unhid = try {
                val dpm = dpm(app)
                dpm.isApplicationHidden(admin(app), SETTINGS_PACKAGE) && dpm.setApplicationHidden(admin(app), SETTINGS_PACKAGE, false)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't unhide Settings: ${e.javaClass.simpleName}")
                false
            }
            armed = armed.copy(unhid = unhid)
            record = armed
            Log.i(LOG_TAG, "Armed (Settings unhidden: $unhid)")
        }
        schedule()
    }

    /** `createBond` was refused: the arm closes. */
    fun bondNotStarted(context: Context) {
        val app = context.applicationContext
        handler?.post { synchronized(lock) { closeLocked(app, "bond_refused") } }
    }

    // --- the receiver ---

    private fun ensureHandler(): Handler = synchronized(lock) {
        handler ?: HandlerThread("bt-pairing-window").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
    }

    /** The request, the bond, the cancel and the screen, on the window's thread, while a window is armed or open. */
    private fun registerLocked(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                        if (request(app, device?.address, variant) == BtRequestAnswer.GROWN_UP && isOrderedBroadcast) {
                            // Settings would only meet lock task's "not available" screen; the bond ends at its timeout.
                            abortBroadcast()
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDED -> event(app, BtPairingEvent.BONDED, device?.address)
                            BluetoothDevice.BOND_NONE -> event(app, BtPairingEvent.BOND_NONE, device?.address)
                            else -> event(app, BtPairingEvent.CHECK, null)
                        }
                    }
                    ACTION_PAIRING_CANCEL -> event(app, BtPairingEvent.PAIRING_CANCEL, device?.address)
                    Intent.ACTION_SCREEN_OFF -> event(app, BtPairingEvent.SCREEN_OFF, null)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_PAIRING_CANCEL)
            addAction(Intent.ACTION_SCREEN_OFF)
            priority = REQUEST_PRIORITY
        }
        try {
            // EXPORTED: the Bluetooth app (uid 1002) sends these protected broadcasts (BluetoothDevicesActivity's note).
            app.registerReceiver(r, filter, null, handler, Context.RECEIVER_EXPORTED)
            receiver = r
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't register the window's receiver: ${e.javaClass.simpleName}")
        }
    }

    private fun unregisterLocked(app: Context) {
        val r = receiver ?: return
        receiver = null
        try {
            app.unregisterReceiver(r)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    /** The ordered request, on the window's thread: Settings pinned before this returns, so its receiver may start the
     * dialog. */
    internal fun request(app: Context, address: String?, variant: Int): BtRequestAnswer {
        val answer = synchronized(lock) {
            val answer = btPairingRequest(
                record, address, variant, pageStarted, PinLockRuntime.chromeLocked, LockTaskChrome.kioskOn, SystemClock.elapsedRealtime(),
            )
            Log.i(LOG_TAG, "Pairing request, variant $variant: $answer")
            when (answer) {
                BtRequestAnswer.OPEN -> {
                    val opened = record!!.copy(openedAt = SystemClock.elapsedRealtime())
                    record = opened
                    prefs(app).edit().putLong(KEY_END, btPairingDeadline(opened)).commit()
                    val pinned = try {
                        LockTaskChrome.routeWindow(app, SETTINGS_PACKAGE, LockTaskChrome.RouteOwner.BT_PAIRING)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "The pin failed: ${e.javaClass.simpleName}")
                        false
                    }
                    if (!pinned) closeLocked(app, "no_plan")
                }
                BtRequestAnswer.GROWN_UP -> {
                    val device = record!!.address
                    main.post {
                        grownUp.add(device)
                        grownUpListener?.invoke()
                    }
                    closeLocked(app, "grown_up")
                }
                BtRequestAnswer.END -> closeLocked(app, "request_refused")
                BtRequestAnswer.PASS -> Unit
            }
            answer
        }
        schedule()
        return answer
    }

    /** An event on the window's thread: an end closes it. */
    internal fun event(app: Context, event: BtPairingEvent, address: String?) {
        synchronized(lock) {
            val end = btPairingEnd(record, event, address, SystemClock.elapsedRealtime()) ?: return@synchronized
            closeLocked(app, end)
        }
    }

    // --- the close ---

    /**
     * The one closer, idempotent (no record = nothing to do): the record gone, Settings out of the kiosk's list in two
     * passes, hidden again when the arm unhid it (not in a pause or the override: every app is unhidden then), the
     * marker cleared, the receiver and the timer gone. Each step runs whatever the one before did.
     */
    private fun closeLocked(app: Context, reason: String) {
        val r = record ?: return
        record = null
        quietUntil = SystemClock.elapsedRealtime() + QUIET_AFTER_MS
        if (r.openedAt != null) {
            runCatching { LockTaskChrome.routeWindow(app, null, LockTaskChrome.RouteOwner.BT_PAIRING) }
                .onFailure { Log.w(LOG_TAG, "Unpin failed: ${it.javaClass.simpleName}") }
        }
        if (r.unhid && !lifted()) runCatching { rehide(app) }.onFailure { Log.w(LOG_TAG, "Re-hide failed: ${it.javaClass.simpleName}") }
        runCatching { prefs(app).edit().remove(KEY_END).commit() }
        unregisterLocked(app)
        main.removeCallbacks(tick)
        Log.i(LOG_TAG, "Closed: $reason (was ${if (r.openedAt != null) "open" else "armed"})")
    }

    private fun rehide(app: Context) {
        repeat(2) {
            if (dpm(app).setApplicationHidden(admin(app), SETTINGS_PACKAGE, true)) return
        }
        Log.w(LOG_TAG, "Settings didn't hide again: an apply follows")
        Thread { runCatching { AppEnforcer.apply(app, currentPolicyDecision()) } }.start()
    }

    private fun schedule() {
        val r = record ?: return
        main.removeCallbacks(tick)
        main.postDelayed(tick, (btPairingDeadline(r) - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 50L)
    }

    // --- the apply and the start ---

    /**
     * The apply's hide step for Settings (under the enforcer's lock): left unhidden while a window holds it
     * ([btPairingHoldsSettings]), else [hidden] written. Returns `setApplicationHidden`'s answer (`true` when left).
     */
    fun settingsHideStep(dpm: DevicePolicyManager, admin: ComponentName, hidden: Boolean): Boolean = synchronized(lock) {
        if (hidden && btPairingHoldsSettings(record, SystemClock.elapsedRealtime())) {
            Log.i(LOG_TAG, "Settings stays unhidden for the pairing window")
            true
        } else {
            dpm.setApplicationHidden(admin, SETTINGS_PACKAGE, hidden)
        }
    }

    /** From the apply (under the enforcer's lock): a window that is over closes, and every window when [ruleEnds] (a
     * time rule, a pause or the override). */
    fun duringApply(context: Context, ruleEnds: Boolean) {
        if (record == null) return
        val app = context.applicationContext
        synchronized(lock) {
            val r = record ?: return
            val now = SystemClock.elapsedRealtime()
            if (ruleEnds) closeLocked(app, "rule") else btPairingEnd(r, BtPairingEvent.CHECK, null, now)?.let { closeLocked(app, it) }
        }
    }

    /**
     * A window an earlier process left (its marker set, none of this process): Settings out of the list and the plan's
     * hide back - one apply - then the marker goes. At the main process's start and before each sync (background).
     */
    fun putBackIfMarked(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).contains(KEY_END)) return
        synchronized(AppEnforcer) {
            synchronized(lock) { if (record != null || !prefs(app).contains(KEY_END)) return }
            Log.w(LOG_TAG, "A pairing window was left open: putting it back")
            try {
                LockTaskChrome.routeWindow(app, null, LockTaskChrome.RouteOwner.BT_PAIRING)
                AppEnforcer.apply(app, currentPolicyDecision())
            } finally {
                synchronized(lock) { if (record == null) prefs(app).edit().remove(KEY_END).commit() }
            }
        }
    }
}
