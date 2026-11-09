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
        bindKidPhoto(activity, view.findViewById(R.id.sheet_photo_frame), contact, emergency)
        // Design 77 §6.7: missed calls plus unread messages.
        KidAvatars.bindContactBadge(
            view.findViewById(R.id.sheet_badge),
            com.kidslauncher.mdm.ui.home.ContactBadge(missed?.count ?: 0, com.kidslauncher.mdm.sms.SmsHome.unreadFrom(contact.number)),
        )
        view.findViewById<TextView>(R.id.sheet_name).text = contact.name
        // Verification N8: the missed calls and the unread messages each in words; the badge is only their sum, so
        // TalkBack skips it.
        val unread = com.kidslauncher.mdm.sms.SmsHome.unreadFrom(contact.number)
        view.findViewById<View>(R.id.sheet_badge).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.findViewById<TextView>(R.id.sheet_missed).apply {
            val text = KidAvatars.sheetLines(activity, missed, unread)
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text
        }

        // One rule for the card and the phone book's row (design 79 §3.5, [contactActions]).
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        val actions = contactActions(
            contact, rules, emergency, OngoingCalls.states, CallSystem.defaultSmsPackage(activity), usablePackages(activity),
            learnedRoom = ElementRoomStore.lookup(activity),
        )
        view.findViewById<View>(R.id.sheet_call).apply {
            // One call at a time: Call is off while another call exists (emergency numbers excepted).
            isEnabled = actions.callEnabled
            alpha = if (actions.callEnabled) 1f else 0.4f
            setOnClickListener {
                CallSystem.placeCall(activity, contact.number)
                dialog.dismiss()
            }
        }
        val messageButton = view.findViewById<View>(R.id.sheet_message)
        val messageApp = view.findViewById<TextView>(R.id.sheet_message_app)
        when (val message = actions.message) {
            // Design 77 §6.7: our own thread in "Styrt av Vibb" (no "opens in" line - it is Vibb One's own screen).
            MessageAction.OwnThread -> {
                messageApp.visibility = View.GONE
                messageButton.setOnClickListener {
                    // Design 79 §3.2 rule 3: Melding deals with the contact's missed calls too.
                    MissedCallsRepo.markSeen(activity, contact.number)
                    com.kidslauncher.mdm.ui.messages.MessagesScreens.startThread(activity, null, contact.number)
                    dialog.dismiss()
                }
            }
            MessageAction.None -> {
                messageButton.visibility = View.GONE
                messageApp.visibility = View.GONE
            }
            is MessageAction.App -> {
                messageApp.text = activity.getString(R.string.calls_message_opens_in, appLabel(activity, message.intent.packageName))
                messageButton.setOnClickListener {
                    MissedCallsRepo.markSeen(activity, contact.number)
                    openMessage(activity, message.intent)
                    dialog.dismiss()
                }
            }
        }
        view.findViewById<View>(R.id.sheet_close).setOnClickListener { dialog.dismiss() }
        dialog.show()
        return dialog
    }

    /**
     * Design 79 §5.1: a long-press on the card's photo opens "Bilde av …" ([KidPhotoSheet]) - haptic, the photo dimmed
     * 10 % while held; a plain tap does nothing, as before. TalkBack: the photo is a node "Bilde av Mamma" with the action
     * "Endre bilde av Mamma". Offered only for a phone-book contact, not an emergency number, after the first unlock,
     * with the picker or an allowed camera ([kidPhotoOffered]); else the photo has no listener at all.
     */
    private fun bindKidPhoto(activity: Activity, frame: View, contact: RuleContact, emergency: Boolean) {
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        if (!kidPhotoOffered(contact, rules, emergency, CallPolicyStore.userUnlocked(activity), KidPhotoSheet.canPick(activity))) return
        val open = {
            KidPhotoSheet.show(
                activity, activity.getString(R.string.kid_photo_title, contact.name),
                onChoose = { camera -> activity.startActivity(KidPhotoActivity.forContact(activity, contact, camera)) },
                onRemove = if (KidContactPhotos.recordFor(activity, contact) != null) ({ KidContactPhotos.remove(activity, contact.id) }) else null,
            )
        }
        frame.foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x1A000000)
                },
            )
        }
        frame.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            open()
            true
        }
        frame.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        frame.contentDescription = activity.getString(R.string.kid_photo_title, contact.name)
        androidx.core.view.ViewCompat.addAccessibilityAction(frame, activity.getString(R.string.kid_photo_change_action, contact.name)) { _, _ ->
            open()
            true
        }
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
