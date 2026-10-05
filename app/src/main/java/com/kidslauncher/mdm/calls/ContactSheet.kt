package com.kidslauncher.mdm.calls

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
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
 * The contact sheet (mockup ContactCard.dc.html): photo, name, missed calls, Call, Message (only
 * when [resolveMessageButton] finds an app that can work, with "Message opens in <app>") and
 * Close. Opening it marks the contact's missed calls as seen; [onDone] runs when it closes.
 */
object ContactSheet {

    fun show(activity: Activity, contact: RuleContact, missed: MissedSummary?, onDone: () -> Unit) {
        MissedCallsRepo.markSeen(activity, contact.number)
        val emergency = CallSystem.isEmergencyOutgoing(activity, contact.number)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_contact, null)
        val dialog = Dialog(activity, R.style.KidSheetDialog)
        dialog.setContentView(view)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        dialog.setOnDismissListener { onDone() }

        KidAvatars.bindContact(
            view.findViewById<ImageView>(R.id.sheet_photo),
            view.findViewById<TextView>(R.id.sheet_initial),
            contact, emergency, initialSp = 52f,
        )
        view.findViewById<ImageView>(R.id.sheet_ring).setImageDrawable(KidAvatars.ring(activity, contact, emergency))
        KidAvatars.bindBadge(view.findViewById(R.id.sheet_badge), missed?.count ?: 0)
        view.findViewById<TextView>(R.id.sheet_name).text = contact.name
        view.findViewById<TextView>(R.id.sheet_missed).apply {
            val text = KidAvatars.missedText(activity, missed)
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text
        }

        view.findViewById<View>(R.id.sheet_call).setOnClickListener {
            CallSystem.placeCall(activity, contact.number)
            dialog.dismiss()
        }
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        val message = rules?.let {
            resolveMessageButton(contact, it.smsEnabled, CallSystem.defaultSmsPackage(activity), usablePackages(activity))
        }
        val messageButton = view.findViewById<View>(R.id.sheet_message)
        val messageApp = view.findViewById<TextView>(R.id.sheet_message_app)
        if (message == null) {
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
    }

    private fun appLabel(activity: Activity, packageName: String): CharSequence = try {
        val pm = activity.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    fun openMessage(activity: Activity, message: MessageIntent) {
        try {
            activity.startActivity(Intent(message.action, Uri.parse(message.uri)).setPackage(message.packageName))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.calls_could_not_message, Toast.LENGTH_LONG).show()
        }
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
