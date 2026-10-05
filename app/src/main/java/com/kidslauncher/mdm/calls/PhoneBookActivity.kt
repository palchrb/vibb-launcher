package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.Intent
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
 */
class PhoneBookActivity : UIObjectActivity() {

    private lateinit var binding: ActivityPhoneBookBinding
    private lateinit var adapter: PhoneBookAdapter
    private var missed: Map<String, MissedSummary> = emptyMap()
    private val photoListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyStore.ensureLoaded(this)
        binding = ActivityPhoneBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = PhoneBookAdapter(
            onContact = { tile -> ContactSheet.show(this, tile.contact, tile.missed) { loadMissedCalls() } },
            onEmergency = ::confirmEmergencyCall,
        )
        binding.phoneBookGrid.layoutManager = GridLayoutManager(this, 3)
        binding.phoneBookGrid.adapter = adapter
        binding.phoneBookBack.setOnClickListener { finish() }
        handleNumber(intent)
    }

    override fun onStart() {
        super.onStart()
        ContactPhotos.addListener(photoListener)
    }

    override fun onResume() {
        super.onResume()
        render()
        loadMissedCalls()
    }

    override fun onStop() {
        ContactPhotos.removeListener(photoListener)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNumber(intent)
    }

    private fun loadMissedCalls() {
        val state = CallPolicyStore.state
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { MissedCallsRepo.summaries(this@PhoneBookActivity, state) }
            if (!isDestroyed) {
                missed = result
                render()
            }
        }
    }

    private fun render() {
        val state = CallPolicyStore.state
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
        adapter.submit(tiles)
    }

    /** Only when no call rules can be read at all: 112 (an emergency number on every GSM phone;
     * Telecom routes it to the preloaded dialer, exempt from every call restriction), confirmed
     * first. The platform's own emergency-dialer intent isn't public API. */
    private fun confirmEmergencyCall() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calls_confirm_title, PhoneBookAdapter.EMERGENCY_NUMBER))
            .setPositiveButton(R.string.calls_call) { _, _ -> CallSystem.placeCall(this, PhoneBookAdapter.EMERGENCY_NUMBER) }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
    }

    private fun handleNumber(intent: Intent?) {
        val data = intent?.data ?: return
        val raw = PhoneNumbers.numberFromHandle(data.toString())
        val state = CallPolicyStore.state
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
        fun intent(context: Context) = Intent(context, PhoneBookActivity::class.java)
    }
}
