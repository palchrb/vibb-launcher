package com.kidslauncher.mdm.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.systemDialerPackage
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.preferences.LauncherPreferences

private const val LOCK_REASON_REFRESH_INTERVAL_MS = 60_000L

/**
 * Full-screen block shown while [LockReason] (from [com.kidslauncher.mdm.server.MdmSyncWorker])
 * is anything other than [LockReason.NONE]. No way to dismiss it besides the lock actually
 * clearing on its own, or the visible "Enter unlock code" button - a deliberately undisguised
 * entry point for [OfflineOverride], since the PIN itself is the actual security boundary here,
 * not the button being hard to find.
 */
class LockActivity : UIObjectActivity() {
    private lateinit var binding: ActivityLockBinding

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey == LauncherPreferences.mdm().keys().lockReason()) {
                finishIfUnlocked()
            }
        }

    private val refreshHandler = Handler(Looper.getMainLooper())

    // Re-checks the schedule against the device's own clock every minute while this screen is
    // showing - otherwise the lock would only ever clear whenever the next ~15-minute background
    // sync happens to land, which could leave someone stuck well after their allowed time began.
    private val refreshRunnable = object : Runnable {
        override fun run() {
            reevaluateLockReasonFromCache(this@LockActivity)
            refreshHandler.postDelayed(this, LOCK_REASON_REFRESH_INTERVAL_MS)
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
        // Calls aren't part of the lock: every other app is suspended, but the phone book (our
        // own package) still calls the allowed contacts, and lists emergency numbers when the
        // rules are unknown. With calls unmanaged only the keyguard's emergency button remains.
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
     * refuses, the system dialer (never suspended; allowed in kiosk by its emergency exemption)
     * opens with 112 typed in.
     */
    private fun callEmergency() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(packageName) == true) {
            QuickControls.selfGrantPermission(this, dpm, ComponentName(this, MdmDeviceAdminReceiver::class.java), Manifest.permission.CALL_PHONE)
        }
        if (CallSystem.placeCall(this, EMERGENCY_NUMBER)) return
        try {
            val dial = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", EMERGENCY_NUMBER, null))
            systemDialerPackage(this)?.let { dial.setPackage(it) }
            startActivity(dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("LockActivity", "Couldn't open the dialer for an emergency call", e)
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
        binding.lockPhoneBookButton.visibility =
            if (CallPolicyStore.state.managed) View.VISIBLE else View.GONE
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        refreshHandler.post(refreshRunnable)
        updateMessageOrFinish()
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onStop()
    }

    private fun finishIfUnlocked() {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) {
            finish()
        } else {
            updateMessageOrFinish()
        }
    }

    private fun updateMessageOrFinish() {
        when (LauncherPreferences.mdm().lockReason()) {
            LockReason.BEDTIME -> binding.lockMessage.setText(R.string.lock_reason_bedtime)
            LockReason.SCREEN_TIME -> binding.lockMessage.setText(R.string.lock_reason_screen_time)
            LockReason.NONE -> finish()
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
