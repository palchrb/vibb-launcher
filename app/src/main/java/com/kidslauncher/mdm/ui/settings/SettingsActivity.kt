package com.kidslauncher.mdm.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.SettingsBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * The [SettingsActivity] holds all of the app's settings on a single page.
 *
 * Gated by [settingsAccess] on every `onCreate`/`onStart`: open only before the phone's first
 * policy (setup), otherwise behind the offline-override PIN - and shut entirely when the server
 * has no PIN for this phone or the PIN is locked out - so a kid can't tamper with
 * enrollment/sync/the restrictions-pause switch below through any entry point (drawer, the
 * exported `APPLICATION_PREFERENCES` filter, an explicit intent, recents, a restore after process
 * death). A passed gate lasts until `onStop` (kept across a configuration change only): leaving
 * Settings and coming back through recents asks again. Recents screenshots are off.
 *
 * The gate is a non-cancelable modal dialog shown on top of the normally-inflated content rather
 * than deferring content/binding setup, since [com.kidslauncher.mdm.ui.UIObject]'s `onStart()`
 * unconditionally calls [setOnClicks], which needs [binding] to already exist. The content stays
 * `INVISIBLE` (no touches, nothing to read) until the PIN checks out.
 */
class SettingsActivity : UIObjectActivity() {

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("theme.") == true ||
                prefKey?.startsWith("display.") == true
            ) {
                recreate()
            }
        }
    private lateinit var binding: SettingsBinding
    private var gatePassed = false
    private var pinDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRecentsScreenshotEnabled(false)
        // Only a recreate() for a configuration/theme change keeps a passed gate.
        gatePassed = savedInstanceState?.getBoolean(STATE_GATE_PASSED_FOR_RECREATE) == true

        // Initialise layout
        binding = SettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Invisible (not gone - still needs to be measured/laid out for setOnClicks() to work
        // once onStart() runs) until the gate below passes, so a kid can't glimpse or tap the
        // settings list behind the dialog before entering the code.
        if (currentAccess() != SettingsAccess.OPEN && !gatePassed) {
            binding.root.visibility = View.INVISIBLE
        }
    }

    private fun currentAccess(): SettingsAccess {
        val mdm = LauncherPreferences.mdm()
        return settingsAccess(
            // A cached policy also counts, for a phone whose cache predates the flag.
            policyEverApplied = mdm.policyEverApplied() || !mdm.kidModePolicy().isNullOrBlank(),
            pinConfigured = OfflineOverride.isConfigured(),
            lockedOut = OfflineOverride.isLockedOut(),
        )
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        enforceGate()
    }

    private fun enforceGate() {
        when (currentAccess()) {
            SettingsAccess.OPEN -> binding.root.visibility = View.VISIBLE
            SettingsAccess.REQUIRE_PIN -> if (gatePassed) {
                binding.root.visibility = View.VISIBLE
            } else {
                binding.root.visibility = View.INVISIBLE
                if (pinDialog?.isShowing != true) showPinGate()
            }
            SettingsAccess.REFUSE_NO_PIN -> refuse(R.string.settings_gate_no_pin)
            SettingsAccess.REFUSE_LOCKED_OUT -> if (!gatePassed) refuse(R.string.lock_unlock_code_locked_out)
        }
    }

    private fun refuse(message: Int) {
        binding.root.visibility = View.INVISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showPinGate() {
        val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom).apply {
            setTitle(R.string.settings_gate_dialog_title)
            setView(R.layout.dialog_offline_override_pin)
            setCancelable(false)
            setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            setPositiveButton(android.R.string.ok, null)
        }.create()
        pinDialog = dialog
        dialog.show()

        // Overridden after show() so a wrong PIN re-prompts instead of dismissing/finishing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
            val pin = input?.text?.toString().orEmpty()
            if (OfflineOverride.verifyPin(pin)) {
                gatePassed = true
                binding.root.visibility = View.VISIBLE
                dialog.dismiss()
            } else if (OfflineOverride.isLockedOut()) {
                Toast.makeText(this, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
                dialog.dismiss()
                finish()
            } else {
                Toast.makeText(this, R.string.lock_unlock_code_wrong, Toast.LENGTH_SHORT).show()
                input?.text?.clear()
            }
        }
    }

    override fun onPause() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onPause()
    }

    override fun onStop() {
        // Leaving Settings (Home, recents, another activity, screen off) ends the session: the
        // next onStart asks for the PIN again. A configuration change keeps it (see onCreate).
        if (!isChangingConfigurations) {
            gatePassed = false
            pinDialog?.dismiss()
            pinDialog = null
            if (currentAccess() != SettingsAccess.OPEN) binding.root.visibility = View.INVISIBLE
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_GATE_PASSED_FOR_RECREATE, gatePassed && isChangingConfigurations)
    }

    override fun onDestroy() {
        pinDialog?.dismiss()
        pinDialog = null
        super.onDestroy()
    }

    override fun setOnClicks() {
        // As older APIs somehow do not recognize the xml defined onClick
        binding.settingsClose.setOnClickListener { finish() }
        // open device settings (see https://stackoverflow.com/a/62092663/12787264)
        binding.settingsSystem.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // The system Settings app is suspended/hidden when it's not in the KidMode
                // allowlist - there is then nothing to resolve this intent to.
                Toast.makeText(this, R.string.toast_system_settings_unavailable, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private companion object {
        const val STATE_GATE_PASSED_FOR_RECREATE = "settings_gate_passed_for_recreate"
    }
}
