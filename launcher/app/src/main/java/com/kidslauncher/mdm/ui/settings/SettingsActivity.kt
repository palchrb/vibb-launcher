package com.kidslauncher.mdm.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.SettingsBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.ParentPin
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.ui.ParentPinDialog
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * The [SettingsActivity] holds all of the app's settings on a single page.
 *
 * Gated by [settingsAccess] on every `onCreate`/`onStart`: open only before the phone's first
 * policy (setup), otherwise behind the parent PIN ([ParentPin], design 24) - and shut entirely
 * when the server has no PIN for this phone or the PIN is locked out (the gate's dialog then says
 * so, OK disabled) - so a kid can't tamper with
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

    /** Bumped by every real `onStop`: a check that comes back from an ended session sets nothing
     * (design 24 QA #4 - the parent taps OK and presses power within the ~0.5 s PBKDF2 takes). */
    private var session = 0

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
        val pin = ParentPin.status(this)
        return settingsAccess(
            // A cached policy also counts, for a phone whose cache predates the flag.
            policyEverApplied = mdm.policyEverApplied() || !mdm.kidModePolicy().isNullOrBlank(),
            pinConfigured = pin.configured,
            lockedOut = pin.lockoutMs > 0L,
        )
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        enforceGate()
    }

    private fun enforceGate() {
        val access = currentAccess()
        // No PIN on the server or a running lockout: the gate's dialog opens in that state (OK
        // disabled, Cancel finishes) instead of a toast (design 24 §2.4).
        val open = access == SettingsAccess.OPEN ||
            (gatePassed && access != SettingsAccess.REFUSE_NO_PIN)
        if (open) {
            binding.root.visibility = View.VISIBLE
        } else {
            binding.root.visibility = View.INVISIBLE
            if (pinDialog?.isShowing != true) showPinGate()
        }
    }

    private fun showPinGate() {
        val gateSession = session
        pinDialog = ParentPinDialog.show(
            this,
            lifecycleScope,
            ParentPinEntry.SETTINGS,
            title = R.string.settings_gate_dialog_title,
            notConfigured = R.string.settings_gate_no_pin,
            cancelable = false,
            onCancel = { finish() },
        ) {
            // Its `unlocked` event stays either way.
            if (gateSession == session && !isFinishing) {
                gatePassed = true
                binding.root.visibility = View.VISIBLE
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
            session++
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
