package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.ui.wallpaper.KidInk
import com.kidslauncher.mdm.ui.wallpaper.WallpaperGround
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityPhoneBookBinding
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone book - the kid's place to call from, and the DIAL / `tel:` handler the dialer role
 * requires. It shows the contacts the parent allowed for outgoing calls as a grid of round avatars
 * with missed-call badges (design 05, mockup PhoneBook.dc.html); tapping one opens [ContactSheet]
 * with Call (straight away) and, where it can work, Message ([resolveMessageButton]). There is no
 * free keypad and no built-in emergency buttons; emergency numbers still work, from here via a
 * `tel:` link or a contact the parent added, and from the lock screen's Emergency button.
 *
 * A `tel:` number from another app is checked with [decideOutgoing]: allowed asks "Call X?",
 * anything else says it isn't allowed.
 *
 * Missed calls (design 12): it is also the call-log viewer (`VIEW` `vnd.android.cursor.dir/calls`,
 * pinned as a persistent preferred activity) - while calls are unmanaged it passes that on to the
 * system dialer. Our missed-call notification opens it, with [EXTRA_MISSED_CONTACT] when the calls
 * came from one contact (that contact's sheet opens, once). Being opened clears the notification
 * ([MissedCallNotifier.dismiss]).
 */
class PhoneBookActivity : UIObjectActivity() {

    private lateinit var binding: ActivityPhoneBookBinding
    private lateinit var adapter: PhoneBookAdapter
    private var missed: Map<String, MissedSummary> = emptyMap()
    /** The contact whose sheet the missed-call notification asked for, opened once the missed
     * calls are loaded. */
    private var pendingSheet: String? = null
    private val photoListener: () -> Unit = { render() }
    private val wallpaperListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyStore.ensureLoaded(this)
        if (passOnCallLog(intent)) {
            finish()
            return
        }
        binding = ActivityPhoneBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = PhoneBookAdapter(
            onContact = { tile -> ContactSheet.show(this, tile.contact, tile.missed) { loadMissedCalls() } },
            onEmergency = ::confirmEmergencyCall,
        )
        binding.phoneBookGrid.layoutManager = GridLayoutManager(this, 3)
        binding.phoneBookGrid.adapter = adapter
        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)
        com.kidslauncher.mdm.ui.KidHeader.bind(this, binding.phoneBookHeader, R.string.calls_phone_book)
        // Once: not again after a recreation.
        if (savedInstanceState == null) takeMissedContact(intent)
        handleNumber(intent)
    }

    /** The kid's wallpaper behind the phone book, like Home (design 08 §3). */
    override fun showsSystemWallpaper() = true

    override fun onStart() {
        super.onStart()
        ContactPhotos.addListener(photoListener)
        WallpaperStore.addListener(wallpaperListener)
    }

    override fun onResume() {
        super.onResume()
        // Also refreshes whether the system wallpaper is still ours (in the background).
        WallpaperStore.refreshAsync(this)
        render()
        loadMissedCalls()
        // Opening the phone book deals with the missed-call notification (design 12, QA #3).
        MissedCallNotifier.dismiss(this)
    }

    override fun onStop() {
        ContactPhotos.removeListener(photoListener)
        WallpaperStore.removeListener(wallpaperListener)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (passOnCallLog(intent)) return
        takeMissedContact(intent)
        handleNumber(intent)
    }

    /** The notification's contact, only when it is in the phone book now (QA #8); the extra is
     * removed, so it opens once. */
    private fun takeMissedContact(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_MISSED_CONTACT) ?: return
        intent.removeExtra(EXTRA_MISSED_CONTACT)
        pendingSheet = number
    }

    /**
     * The call log asked for while calls are unmanaged: the system dialer's, explicitly - our pin
     * is permanent, so it stays ours to pass on (as the Play link blocker does). Managed (or
     * rules unknown): the phone book is the call log. A fresh intent: never the caller's extras.
     */
    private fun passOnCallLog(intent: Intent?): Boolean {
        if (intent == null || !isCallLogView(intent.action, intent.type, intent.dataString)) return false
        if (CallPolicyStore.effectiveState() != CallPolicyState.Unmanaged) return false
        val dialer = com.kidslauncher.mdm.server.systemDialerPackage(this)
        if (dialer != null && dialer != packageName) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setType(CALL_LOG_TYPE).setPackage(dialer)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Log.w("PhoneBookActivity", "The system dialer has no call log to pass on to", e)
            }
        }
        return true
    }

    private fun loadMissedCalls() {
        val state = CallPolicyStore.effectiveState()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { MissedCallsRepo.summaries(this@PhoneBookActivity, state) }
            if (!isDestroyed) {
                missed = result
                render()
                openPendingSheet()
            }
        }
    }

    private fun openPendingSheet() {
        val number = pendingSheet ?: return
        pendingSheet = null
        if (isFinishing) return
        val contact = missedCallContact(number, phoneBookView(CallPolicyStore.effectiveState()) { CallSystem.isEmergencyOutgoing(this, it) })
            ?: return
        ContactSheet.show(this, contact, missed[contact.number]) { loadMissedCalls() }
    }

    private fun render() {
        val ink = WallpaperGround.apply(this, binding.root).ink
        KidInk.label(binding.phoneBookHeader.kidHeaderTitle, ink)
        KidInk.label(binding.phoneBookInfo, ink, dim = true)
        val state = CallPolicyStore.effectiveState()
        val rules = (state as? CallPolicyState.Managed)?.rules
        val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(this, it) }
        val info = when {
            rules == null || !rules.callsEnabled -> getString(R.string.calls_off)
            view.contacts.isEmpty() -> getString(R.string.calls_phone_book_empty)
            else -> null
        }
        binding.phoneBookInfo.visibility = if (info == null) View.GONE else View.VISIBLE
        binding.phoneBookInfo.text = info
        val tiles = mutableListOf<PhoneBookTile>()
        if (view.emergencyDialer) tiles += PhoneBookTile.Emergency
        view.contacts.mapTo(tiles) {
            PhoneBookTile.Contact(it, CallSystem.isEmergencyOutgoing(this, it.number), missed[it.number])
        }
        adapter.submit(tiles, ink)
    }

    /** Only when no call rules can be read at all: 112 (an emergency number on every GSM phone;
     * Telecom routes it to the preloaded dialer, exempt from every call restriction), confirmed
     * first; if Telecom refuses, the platform's emergency dialer (explicit, pinned in kiosk). */
    private fun confirmEmergencyCall() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calls_confirm_title, PhoneBookAdapter.EMERGENCY_NUMBER))
            .setPositiveButton(R.string.calls_call) { _, _ ->
                if (!CallSystem.placeCall(this, PhoneBookAdapter.EMERGENCY_NUMBER)) {
                    EmergencyDialer.open(this, PhoneBookAdapter.EMERGENCY_NUMBER)
                }
            }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
    }

    private fun handleNumber(intent: Intent?) {
        if (intent == null || isCallLogView(intent.action, intent.type, intent.dataString)) return
        val data = intent.data ?: return
        val raw = PhoneNumbers.numberFromHandle(data.toString())
        val state = CallPolicyStore.effectiveState()
        if (decideOutgoing(raw, state, CallSystem.isEmergencyOutgoing(this, raw)) == Verdict.BLOCK || raw == null) {
            AlertDialog.Builder(this)
                .setMessage(R.string.calls_not_allowed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val name = (state as? CallPolicyState.Managed)?.rules?.contactFor(raw)?.name ?: raw
        // The stored number we checked, not the string in the link (QA step 2 #6).
        val dial = outgoingDialTarget(raw, state, CallSystem.isEmergencyOutgoing(this, raw)) ?: raw
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calls_confirm_title, name))
            .setPositiveButton(R.string.calls_call) { _, _ -> CallSystem.placeCall(this, dial) }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
    }

    companion object {
        /** The phone number (as in the call rules) of the contact whose sheet to open. */
        const val EXTRA_MISSED_CONTACT = "com.kidslauncher.mdm.extra.MISSED_CONTACT"

        fun intent(context: Context) = Intent(context, PhoneBookActivity::class.java)

        /** The missed-call notification's tap: the phone book, with [contactNumber]'s sheet. */
        fun missedCallsIntent(context: Context, contactNumber: String?): Intent =
            intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (contactNumber != null) putExtra(EXTRA_MISSED_CONTACT, contactNumber)
            }
    }
}
