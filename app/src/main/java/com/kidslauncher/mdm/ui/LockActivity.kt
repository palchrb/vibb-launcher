package com.kidslauncher.mdm.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.EmergencyDialer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import com.kidslauncher.mdm.timerules.TimeRule
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.clockText

/**
 * Full-screen block shown while [LockReason] isn't [LockReason.NONE]: a time rule (school,
 * bedtime, a custom rule) or the used-up screen-time budget (handy step 6). Shows the time and the
 * rule's name, what still works - the rule's exempt apps or, for the budget, the contacts'
 * messaging apps, as buttons; the phone book while calls are managed and allowed - and always
 * Emergency call and "Enter unlock code" (a deliberately undisguised entry point for
 * [OfflineOverride]; the PIN is the security boundary, not the button being hard to find). No
 * timer: the boundary alarm updates `lock_reason`/`lock_key` and this re-renders or finishes.
 */
class LockActivity : UIObjectActivity() {
    private lateinit var binding: ActivityLockBinding

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            val keys = LauncherPreferences.mdm().keys()
            if (prefKey == keys.lockReason() || prefKey == keys.lockKey()) {
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        binding.lockUnlockCodeButton.setOnClickListener { showUnlockCodeDialog() }
        // Calls aren't part of the lock unless the rule says so (school): every other app is
        // suspended, but the phone book (our own package) still calls the allowed contacts.
        // With calls unmanaged only the Emergency call button and the keyguard's remain.
        binding.lockPhoneBookButton.setOnClickListener {
            startActivity(PhoneBookActivity.intent(this))
        }
        // Every other way to the system dialer is closed during the lock, and a phone without a
        // secure lock screen has no keyguard Emergency button - so this one is always shown,
        // whatever the call state (QA step 4 #1).
        binding.lockEmergencyButton.setOnClickListener { confirmEmergencyCall() }
    }

    private fun confirmEmergencyCall() {
        AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.calls_confirm_title, EMERGENCY_NUMBER))
            .setPositiveButton(R.string.calls_call) { _, _ -> callEmergency() }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
    }

    /**
     * 112 through Telecom (emergency calls are exempt from every call restriction). CALL_PHONE is
     * self-granted first - it's only held while calls are managed otherwise. If Telecom still
     * refuses, the platform's emergency dialer opens with 112 typed in - an explicit intent to the
     * resolved system component, which kiosk pins as a lock-task helper (QA 09 #1). Never
     * ACTION_DIAL: the default dialer isn't pinned while calls are managed or a rule blocks calls,
     * so with the kiosk app block it would be blocked.
     */
    private fun callEmergency() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(packageName) == true) {
            QuickControls.selfGrantPermission(this, dpm, ComponentName(this, MdmDeviceAdminReceiver::class.java), Manifest.permission.CALL_PHONE)
        }
        if (CallSystem.placeCall(this, EMERGENCY_NUMBER)) return
        if (!EmergencyDialer.open(this, EMERGENCY_NUMBER)) {
            Log.w("LockActivity", "Neither Telecom nor the emergency dialer took the emergency call")
        }
    }

    private fun showUnlockCodeDialog() {
        if (!OfflineOverride.isConfigured()) {
            Toast.makeText(this, R.string.lock_unlock_code_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        if (OfflineOverride.isLockedOut()) {
            Toast.makeText(this, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
            return
        }

        val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom).apply {
            setTitle(R.string.lock_unlock_code_dialog_title)
            setView(R.layout.dialog_offline_override_pin)
            setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            setPositiveButton(android.R.string.ok, null)
        }.create()
        dialog.show()

        // Overriding the positive button's listener after show() (rather than in the builder)
        // keeps the dialog open on a wrong code instead of dismissing - the whole point of a
        // failsafe is not making the parent re-open the dialog and re-type everything after one
        // typo.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
            val pin = input?.text?.toString().orEmpty()
            if (OfflineOverride.verifyPin(pin)) {
                OfflineOverride.activate(this)
                dialog.dismiss()
                finish()
            } else {
                Toast.makeText(this, R.string.lock_unlock_code_wrong, Toast.LENGTH_SHORT).show()
                input?.text?.clear()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        // A fresh look at the clock on top of the boundary alarm; the listener re-renders.
        reevaluateLockReasonFromCache(this)
        render()
    }

    override fun onStop() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onStop()
    }

    private fun kindLabel(rule: TimeRule): String = rule.name.takeIf { it.isNotBlank() } ?: getString(
        when (rule.kind) {
            KIND_SCHOOL -> R.string.lock_kind_school
            KIND_BEDTIME -> R.string.lock_kind_bedtime
            else -> R.string.lock_kind_rule
        }
    )

    private fun render() {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) {
            finish()
            return
        }
        val snap = try {
            TimeRulesRuntime.snapshot(this, currentPolicyDecision().policy)
        } catch (e: Exception) {
            Log.w("LockActivity", "Couldn't evaluate the time rules", e)
            null
        }
        val lock = snap?.lock
        val rule = lock?.rules?.firstOrNull()
        binding.lockTitle.text = when {
            rule != null -> kindLabel(rule)
            else -> getString(R.string.lock_title_screen_time)
        }
        val callsManaged = CallPolicyStore.state.managed
        val lines = mutableListOf<String>()
        lock?.until?.let { lines += getString(R.string.lock_until, it.clockText()) }
        val budget = snap?.budget
        if (rule == null && budget?.effectiveMinutes != null) {
            lines += getString(R.string.lock_budget_used, (budget.usedMs / 60_000L).toInt(), budget.effectiveMinutes!!)
        } else if (lock?.budgetExhausted == true) {
            lines += getString(R.string.lock_budget_also_used)
        }
        if (lock?.callsAllowed == false) {
            lines += getString(R.string.lock_calls_emergency_only)
        } else if (callsManaged) {
            lines += getString(R.string.lock_calls_allowed)
        }
        binding.lockMessage.text = lines.joinToString("\n")
        binding.lockPhoneBookButton.visibility =
            if (callsManaged && lock?.callsAllowed != false) View.VISIBLE else View.GONE
        renderApps(lock?.usableApps.orEmpty())
    }

    /** A button per usable app that can actually be opened (installed, launchable, not suspended -
     * the allowlist still decides, through AppEnforcer's suspension). */
    private fun renderApps(packages: Set<String>) {
        val container = binding.lockApps
        container.removeAllViews()
        val pm = packageManager
        for (pkg in packages.sorted()) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val usable = try {
                !pm.isPackageSuspended(pkg)
            } catch (e: Exception) {
                false
            }
            if (!usable) continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            val button = LayoutInflater.from(this).inflate(R.layout.item_lock_app, container, false) as TextView
            button.text = label
            button.setOnClickListener {
                try {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w("LockActivity", "Couldn't open $pkg", e)
                }
            }
            container.addView(button)
        }
    }

    companion object {
        /** An emergency number on every GSM phone, also the ones without a SIM. */
        private const val EMERGENCY_NUMBER = "112"

        fun start(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
