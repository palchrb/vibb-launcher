package com.kidslauncher.mdm.calls

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.ui.home.KidAvatars

/**
 * The contact sheet (mockup ContactCard.dc.html): photo, name, missed calls, Call, Message (our own thread in SMS
 * "Styrt av Vibb", [opensOwnThread]; else only when [resolveMessageButton] finds an app that can work, with "Message
 * opens in <app>") and
 * Close. Opening it marks the contact's missed calls as seen - the phone's own and, since round L5, the missed
 * Element X calls ([elementMissedSummaries] counts after the same mark); [onDone] runs when it closes.
 */
object ContactSheet {

    /** Returns the sheet (Home dismisses it when the lock engages - design 16c). */
    fun show(activity: Activity, contact: RuleContact, missed: ContactMissed?, onDone: () -> Unit): Dialog {
        MissedCallsRepo.markSeen(activity, contact.number)
        // Any contact sheet deals with the missed-call notification (design 12, QA #3).
        MissedCallNotifier.dismiss(activity)
        val emergency = CallSystem.isEmergencyOutgoing(activity, contact.number)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_contact, null)
        val dialog = Dialog(activity, R.style.KidSheetDialog)
        dialog.setContentView(view)
        dialog.setUpKidSheet(view)
        // The photo may still be decoding in the background: show it as soon as it's ready.
        val bindPhoto: () -> Unit = {
            KidAvatars.bindContact(
                view.findViewById<ImageView>(R.id.sheet_photo),
                view.findViewById<TextView>(R.id.sheet_initial),
                contact, emergency, initialSp = 52f,
            )
        }
        bindPhoto()
        ContactPhotos.addListener(bindPhoto)
        dialog.setOnDismissListener {
            ContactPhotos.removeListener(bindPhoto)
            onDone()
        }
        view.findViewById<ImageView>(R.id.sheet_ring).setImageDrawable(KidAvatars.ring(activity, contact, emergency))
        // Design 77 §6.7: missed calls plus unread messages.
        KidAvatars.bindContactBadge(
            view.findViewById(R.id.sheet_badge),
            com.kidslauncher.mdm.ui.home.ContactBadge(missed?.count ?: 0, com.kidslauncher.mdm.sms.SmsHome.unreadFrom(contact.number)),
        )
        view.findViewById<TextView>(R.id.sheet_name).text = contact.name
        view.findViewById<TextView>(R.id.sheet_missed).apply {
            val text = KidAvatars.missedText(activity, missed)
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text
        }

        view.findViewById<View>(R.id.sheet_call).apply {
            // One call at a time: Call is off while another call exists (emergency numbers excepted).
            val allowed = secondCallAllowed(OngoingCalls.states, emergency)
            isEnabled = allowed
            alpha = if (allowed) 1f else 0.4f
            setOnClickListener {
                CallSystem.placeCall(activity, contact.number)
                dialog.dismiss()
            }
        }
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        // Design 77 §6.7: our own thread in "Styrt av Vibb" (no "opens in" line - it is Vibb One's own screen).
        val ownThread = rules != null && opensOwnThread(contact, rules.smsMode)
        val message = rules?.takeUnless { ownThread }?.let {
            resolveMessageButton(
                contact, it.smsEnabled, CallSystem.defaultSmsPackage(activity), usablePackages(activity),
                learnedRoom = ElementRoomStore.lookup(activity),
            )
        }
        val messageButton = view.findViewById<View>(R.id.sheet_message)
        val messageApp = view.findViewById<TextView>(R.id.sheet_message_app)
        if (ownThread) {
            messageApp.visibility = View.GONE
            messageButton.setOnClickListener {
                com.kidslauncher.mdm.ui.messages.MessagesScreens.startThread(activity, null, contact.number)
                dialog.dismiss()
            }
        } else if (message == null) {
            messageButton.visibility = View.GONE
            messageApp.visibility = View.GONE
        } else {
            messageApp.text = activity.getString(R.string.calls_message_opens_in, appLabel(activity, message.packageName))
            messageButton.setOnClickListener {
                openMessage(activity, message)
                dialog.dismiss()
            }
        }
        view.findViewById<View>(R.id.sheet_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
        return dialog
    }

    /** "Message opens in Chat": the parent's name, else the kid's, else the app's own (design 14). */
    private fun appLabel(activity: Activity, packageName: String): CharSequence =
        com.kidslauncher.mdm.appdisplay.AppDisplay.shownLabel(activity, packageName)

    fun openMessage(activity: Activity, message: MessageIntent) {
        for (uri in message.uris) {
            try {
                activity.startActivity(Intent(message.action, Uri.parse(uri)).setPackage(message.packageName))
                return
            } catch (e: Exception) {
                // Not found, or refused (SecurityException): try the next form (Element X: the
                // learned room's elementx:// link, then the profile's matrix:u/ and element://user/
                // links - design 15), then the toast - never a crash (qa-09-code #10). Never logged:
                // the links carry MXIDs and room IDs.
            }
        }
        Toast.makeText(activity, R.string.calls_could_not_message, Toast.LENGTH_LONG).show()
    }

    /** Installed, not suspended, and allowed by the app allowlist (or the override/pause). */
    fun usablePackages(activity: Activity): (String) -> Boolean {
        val allowlist = currentPolicyDecision().policy?.allowlist
        val overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()
        val pm = activity.packageManager
        return { pkg ->
            try {
                val suspended = pm.isPackageSuspended(pkg)
                pm.getApplicationInfo(pkg, 0)
                !suspended && (allowlist == null || overrideActive || pkg in allowlist)
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
