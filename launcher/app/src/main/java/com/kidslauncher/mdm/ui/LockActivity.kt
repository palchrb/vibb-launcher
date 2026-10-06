package com.kidslauncher.mdm.ui

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
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.EmergencyCall
import com.kidslauncher.mdm.lock.PinLockRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // Status bar inset once, plus the layout's own <= 8 dp (fix round 2026-10-06).
        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)

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
        binding.lockEmergencyButton.setOnClickListener { EmergencyCall.confirm(this) }
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
            val pin = input?.text?.toString().orEmpty()
            // PBKDF2 takes about half a second: off the main thread (QA 10 #6).
            button.isEnabled = false
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.Default) { OfflineOverride.verifyPin(pin) }
                button.isEnabled = true
                if (ok) {
                    OfflineOverride.activate(this@LockActivity)
                    dialog.dismiss()
                    finish()
                } else {
                    Toast.makeText(this@LockActivity, R.string.lock_unlock_code_wrong, Toast.LENGTH_SHORT).show()
                    input?.text?.clear()
                }
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
        // The parent's names (design 14), sorted by what the kid reads (QA #6).
        val labelled = packages.map { pkg ->
            pkg to (
                com.kidslauncher.mdm.apps.AppDisplay.label(pkg) ?: try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                )
        }.sortedBy { it.second.lowercase() }
        for ((pkg, label) in labelled) {
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            val usable = try {
                // A camera the PIN lock holds counts as usable (it is back at the unlock).
                !com.kidslauncher.mdm.lock.CameraLock.suspendedForLists(pkg, pm.isPackageSuspended(pkg))
            } catch (e: Exception) {
                false
            }
            if (!usable) continue
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
        /** Starts the time-rule screen. It is never held back by handy's PIN lock (QA 10 #4): while
         * that is LOCKED it is brought back on top right away, so the kid sees the PIN first, then
         * this screen. */
        fun start(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            PinLockRuntime.afterTimeRuleShown(context)
        }
    }
}
