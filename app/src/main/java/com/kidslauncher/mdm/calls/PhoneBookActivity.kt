package com.kidslauncher.mdm.calls

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * The phone book - the kid's place to call from, and the DIAL / `tel:` handler the dialer role
 * requires. It lists the contacts the parent allowed for outgoing calls, each with a Call button
 * (calls straight away) and, where it can work, a Message button ([resolveMessageButton]). There is
 * no free keypad and no built-in emergency buttons; emergency numbers still work, from here via a
 * `tel:` link or a contact the parent added, and from the lock screen's Emergency button.
 *
 * A `tel:` number from another app is checked with [decideOutgoing]: allowed asks "Call X?",
 * anything else says it isn't allowed.
 */
class PhoneBookActivity : UIObjectActivity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyStore.ensureLoaded(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(list) })
        handleNumber(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNumber(intent)
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun text(content: CharSequence, size: Float) = TextView(this).apply {
        text = content
        textSize = size
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(16) }
    }

    private fun render() {
        list.removeAllViews()
        list.addView(text(getString(R.string.calls_phone_book), 28f))
        val state = CallPolicyStore.state
        val rules = (state as? CallPolicyState.Managed)?.rules
        val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(this, it) }
        if (rules == null || !rules.callsEnabled) list.addView(text(getString(R.string.calls_off), 18f))
        if (view.emergencyDialer) list.addView(emergencyRow())
        if (rules != null && rules.callsEnabled && view.contacts.isEmpty()) {
            list.addView(text(getString(R.string.calls_phone_book_empty), 18f))
        }
        val usable = usablePackages()
        val smsPackage = CallSystem.defaultSmsPackage(this)
        for (contact in view.contacts) {
            val message = rules?.let { resolveMessageButton(contact, it.smsEnabled, smsPackage, usable) }
            list.addView(contactRow(contact, message))
        }
    }

    /** Only when no call rules can be read at all: 112 (an emergency number on every GSM phone;
     * Telecom routes it to the preloaded dialer, exempt from every call restriction), confirmed
     * first. The platform's own emergency-dialer intent isn't public API. */
    private fun emergencyRow() = Button(this).apply {
        setText(R.string.calls_emergency)
        setOnClickListener {
            AlertDialog.Builder(this@PhoneBookActivity)
                .setTitle(getString(R.string.calls_confirm_title, "112"))
                .setPositiveButton(R.string.calls_call) { _, _ -> CallSystem.placeCall(this@PhoneBookActivity, "112") }
                .setNegativeButton(R.string.calls_cancel, null)
                .show()
        }
    }

    private fun contactRow(contact: RuleContact, message: MessageIntent?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) }
        addView(TextView(context).apply {
            text = contact.name
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (message != null) {
            addView(Button(context).apply {
                setText(R.string.calls_message)
                setOnClickListener { openMessage(message) }
            })
        }
        addView(Button(context).apply {
            setText(R.string.calls_call)
            setOnClickListener { CallSystem.placeCall(this@PhoneBookActivity, contact.number) }
        })
    }

    private fun openMessage(message: MessageIntent) {
        try {
            startActivity(Intent(message.action, Uri.parse(message.uri)).setPackage(message.packageName))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.calls_could_not_message, Toast.LENGTH_LONG).show()
        }
    }

    /** Installed, not suspended, and allowed by the app allowlist (or the override/pause). */
    private fun usablePackages(): (String) -> Boolean {
        val allowlist = currentPolicyDecision().policy?.allowlist
        val overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()
        return { pkg ->
            try {
                val suspended = packageManager.isPackageSuspended(pkg)
                packageManager.getApplicationInfo(pkg, 0)
                !suspended && (allowlist == null || overrideActive || pkg in allowlist)
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
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
