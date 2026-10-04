package com.kidslauncher.mdm.ui.settings.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.copyToClipboard
import com.kidslauncher.mdm.getDeviceInfo
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.PolicyToApply
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.UnifiedPushRegistrationReceiver
import com.kidslauncher.mdm.server.UnifiedPushRelay
import com.kidslauncher.mdm.server.applyProvisioningExtras
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.choosePolicy
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import com.kidslauncher.mdm.server.performBrowserHistorySync
import com.kidslauncher.mdm.server.performJournalSync
import com.kidslauncher.mdm.server.performMdmSync
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.LegalInfoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SettingsFragmentLauncher"

/**
 * "Set"/"Not set" alone left no way to tell *which* key is configured, or to notice a stale one
 * from a prior scan - reported directly after the Tailscale key visibly worked but the summary
 * gave no indication anything had actually changed. Shows just enough (a masked prefix plus the
 * real last 4 characters) to recognize the value without displaying the secret itself on a screen
 * anyone glancing at the phone could read.
 */
private fun maskedSecretSummary(value: String?): String {
    if (value.isNullOrBlank()) return "Not set"
    val tail = value.takeLast(4)
    return "••••••••$tail"
}

/**
 * The [SettingsFragmentLauncher] holds all of the app's settings on a single screen.
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {

    // Must be registered unconditionally before the fragment reaches CREATED - registering this
    // lazily inside a click listener (e.g. only when the "Scan setup QR" preference is tapped)
    // throws, per the Activity Result API's own contract.
    private val scanSetupQrLauncher = registerForActivityResult(ScanContract()) { result ->
        handleSetupQrScanResult(result)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val hiddenApps = findPreference<Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), hidden = true)
            true
        }

        val licenses = findPreference<Preference>("settings_meta_licenses")
        licenses?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LegalInfoActivity::class.java))
            true
        }

        val version = findPreference<Preference>("settings_meta_version")
        version?.summary = BuildConfig.VERSION_NAME
        version?.setOnPreferenceClickListener {
            copyToClipboard(requireContext(), getDeviceInfo(requireContext()))
            true
        }

        val mdm = LauncherPreferences.mdm()

        val serverUrl = findPreference<Preference>(mdm.keys().serverUrl())
        serverUrl?.summary = mdm.serverUrl().orNotSet()
        serverUrl?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.settings_mdm_server_url),
                mdm.serverUrl()
            ) { value ->
                mdm.serverUrl(value)
                serverUrl.summary = value.orNotSet()
            }
            true
        }

        val tailscaleAuthKey = findPreference<Preference>(mdm.keys().tailscaleAuthKey())
        tailscaleAuthKey?.summary = maskedSecretSummary(mdm.tailscaleAuthKey())
        tailscaleAuthKey?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.settings_mdm_tailscale_auth_key),
                currentValue = null,
            ) { value ->
                mdm.tailscaleAuthKey(value)
                tailscaleAuthKey.summary = maskedSecretSummary(value)
            }
            true
        }

        val scanSetupQr = findPreference<Preference>("settings_mdm_scan_setup_qr")
        scanSetupQr?.setOnPreferenceClickListener {
            launchSetupQrScanner()
            true
        }

        val enrollNow = findPreference<Preference>("settings_mdm_enroll_now")
        enrollNow?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.dialog_enrollment_code_title),
                currentValue = null,
            ) { code ->
                enrollWithServer(requireContext(), code)
            }
            true
        }

        val syncNow = findPreference<Preference>("settings_mdm_sync_now")
        syncNow?.setOnPreferenceClickListener {
            syncNowWithServer(requireContext())
            true
        }

        val restrictionsPaused = findPreference<SwitchPreference>(mdm.keys().restrictionsPaused())
        // isActive() clears a pause that has run out, so the switch never shows a stale "on".
        restrictionsPaused?.isChecked = RestrictionsPause.isActive()
        restrictionsPaused?.setOnPreferenceChangeListener { _, newValue ->
            val context = requireContext()
            if (newValue == true) {
                // Turning it on always takes the PIN, even though Settings itself is PIN-gated:
                // without a PIN configured on the server, Settings is open to anyone, and a pause
                // must not be. The switch only flips once the PIN checks out (see below).
                if (!OfflineOverride.isConfigured()) {
                    Toast.makeText(context, R.string.toast_mdm_pause_needs_pin, Toast.LENGTH_LONG).show()
                } else {
                    showPausePinDialog(context) {
                        RestrictionsPause.start()
                        restrictionsPaused.isChecked = true
                        reapplyAfterPauseChange(context)
                    }
                }
                false
            } else {
                RestrictionsPause.clear()
                reapplyAfterPauseChange(context)
                true
            }
        }

        val unifiedPushEnabled =
            findPreference<Preference>(mdm.keys().unifiedpushDistributorEnabled())
        unifiedPushEnabled?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            val context = requireContext()
            // The receiver's manifest declaration is exported unconditionally (see its own doc
            // comment on why), but this component-enabled flip is a second, independent gate: a
            // parent who's never touched this toggle should never have their phone silently
            // discoverable as a UnifiedPush distributor. DONT_KILL_APP since flipping this off
            // shouldn't restart the whole launcher process.
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UnifiedPushRegistrationReceiver::class.java),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
            if (enabled) {
                UnifiedPushRelay.start(context.applicationContext)
            } else {
                UnifiedPushRelay.stop()
            }
            true
        }
    }

    private fun String?.orNotSet(): String = this?.takeIf { it.isNotBlank() }
        ?: getString(R.string.settings_mdm_not_set)

    /**
     * [androidx.preference.EditTextPreference]'s built-in dialog doesn't pick up this app's
     * custom (dark) theme - it renders with invisible text/buttons. Uses the same themed
     * AlertDialog approach as the app-rename dialog instead.
     */
    /**
     * Re-runs enforcement right away, offline, after the pause switch changes - rather than
     * waiting for the next sync. Hands off to a background coroutine: AppEnforcer.apply() can
     * (re)start KidVpnService, whose onCreate() reads the blocklist from disk synchronously -
     * confirmed live this froze the UI thread for seconds and caused an ANR when run on it.
     * Ending a pause with no usable cached policy (and a policy applied before) leaves things as
     * they are until the next good sync, same as every other KeepCurrentState case.
     */
    private fun reapplyAfterPauseChange(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            if (RestrictionsPause.isActive()) {
                AppEnforcer.apply(context, null)
            } else {
                val everApplied = LauncherPreferences.mdm().policyEverApplied()
                when (val decision = choosePolicy(null, cachedPolicy(), everApplied)) {
                    is PolicyToApply.Apply -> AppEnforcer.apply(context, decision.policy)
                    PolicyToApply.KeepCurrentState ->
                        Log.w(LOG_TAG, "Pause ended with no usable cached policy - waiting for the next sync")
                }
            }
            reevaluateLockReasonFromCache()
        }
    }

    /** Asks for the offline-override PIN; [onVerified] runs only on a match. Shares the PIN's
     * attempt counter and 15-minute lockout with the lock screen and the Settings gate. */
    private fun showPausePinDialog(context: Context, onVerified: () -> Unit) {
        if (OfflineOverride.isLockedOut()) {
            Toast.makeText(context, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom).apply {
            setTitle(R.string.settings_mdm_restrictions_paused_pin_title)
            setView(R.layout.dialog_offline_override_pin)
            setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            setPositiveButton(android.R.string.ok, null)
        }.create()
        dialog.show()
        // Overridden after show() so a wrong PIN re-prompts instead of dismissing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
            val pin = input?.text?.toString().orEmpty()
            when {
                OfflineOverride.verifyPin(pin) -> {
                    dialog.dismiss()
                    onVerified()
                }
                OfflineOverride.isLockedOut() -> {
                    Toast.makeText(context, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
                else -> {
                    Toast.makeText(context, R.string.lock_unlock_code_wrong, Toast.LENGTH_SHORT).show()
                    input?.text?.clear()
                }
            }
        }
    }

    private fun showEditTextDialog(
        context: Context,
        title: String,
        currentValue: String?,
        onSave: (String) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom).apply {
            setTitle(title)
            setView(R.layout.dialog_edit_text)
            setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            setPositiveButton(android.R.string.ok) { d, _ ->
                val input = (d as? AlertDialog)?.findViewById<EditText>(R.id.dialog_edit_text_input)
                onSave(input?.text?.toString().orEmpty())
            }
        }.create()
        dialog.show()
        dialog.findViewById<EditText>(R.id.dialog_edit_text_input)?.setText(currentValue)
    }

    /**
     * Dev-testing shortcut: enrolls directly against the server URL typed into the preference
     * field above and the one-shot code shown on the admin site, over the local network, without
     * needing the full factory-reset -> scan-QR provisioning flow. Only touches the server's
     * enroll endpoint - it doesn't grant Device Owner (that still needs
     * `adb shell dpm set-device-owner` or real provisioning).
     */
    private fun enrollWithServer(context: Context, enrollmentCode: String) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()

        if (serverUrl.isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }
        if (enrollmentCode.isBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_code, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val outcome = try {
                val response = createMdmApi(serverUrl).enroll(EnrollRequest(enrollmentCode))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            withContext(Dispatchers.Main) {
                outcome.onSuccess { enrollResponse ->
                    mdm.deviceToken(enrollResponse.deviceToken)
                    mdm.enrolled(true)
                    Toast.makeText(context, R.string.toast_mdm_enroll_success, Toast.LENGTH_LONG)
                        .show()
                }.onFailure { e ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_mdm_enroll_failure, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Launches ZXing's embedded scanner activity for the in-app "Scan setup QR" flow - the
     * GrapheneOS-friendly counterpart to Android's native zero-touch QR provisioning (which has
     * no trigger in that OS's setup wizard at all, see kid-phone-server's `handlers::provisioning`).
     * Only meaningful once Device Owner is already granted some other way (currently
     * `adb shell dpm set-device-owner`) - scanning here never touches Device Owner state itself,
     * only the server URL/Tailscale key/enrollment code that would otherwise need typing in by
     * hand across three separate preference dialogs.
     */
    private fun launchSetupQrScanner() {
        val context = requireContext()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            // Silent grant, same mechanism/precedent as QuickControls' other self-granted runtime
            // permissions - a kid-phone parent scanning this during setup shouldn't need to
            // navigate a system permission dialog first.
            QuickControls.selfGrantPermission(context, dpm, admin, android.Manifest.permission.CAMERA)
        }
        scanSetupQrLauncher.launch(
            ScanOptions()
                // false told CaptureActivity to dynamically recompute the camera preview's
                // rotation transform on the fly - but this app has no actual rotation handling of
                // its own to match, and letting the transform recalculate against an Activity that
                // in practice never rotates produced a badly skewed preview (a narrow off-center
                // strip with diagonal artifacts, no visible framing rectangle) reported live.
                // Locking to the orientation already in effect at launch sidesteps that
                // recalculation entirely.
                .setOrientationLocked(true)
                .setBeepEnabled(false)
                // Restricting to just QR (the only format this feature ever produces) skips
                // decoding every frame against every other barcode symbology ZXing supports by
                // default - a real, not cosmetic, difference in how fast/reliably a code is
                // recognized, not just a validation nicety.
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.settings_mdm_scan_setup_qr_prompt))
        )
    }

    private fun handleSetupQrScanResult(result: ScanIntentResult) {
        val context = requireContext()
        val contents = result.contents
        if (contents == null) {
            Toast.makeText(context, R.string.toast_mdm_scan_qr_cancelled, Toast.LENGTH_SHORT).show()
            return
        }

        val extras = ProvisioningExtras.fromQrJson(contents)
        if (extras == null) {
            Toast.makeText(context, R.string.toast_mdm_scan_qr_invalid, Toast.LENGTH_LONG).show()
            return
        }

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = applyProvisioningExtras(appContext, extras)
            withContext(Dispatchers.Main) {
                outcome.onSuccess {
                    Toast.makeText(context, R.string.toast_mdm_enroll_success, Toast.LENGTH_LONG)
                        .show()
                    // Refresh preference summaries in place - applyProvisioningExtras persisted
                    // new values this screen already read into local vals in onCreatePreferences,
                    // so those closures' captured Preference views need an explicit update rather
                    // than relying on a full screen recreation. Read the actual persisted values
                    // back rather than trusting extras.tailscaleAuthKey directly - a blank value in
                    // the scanned QR leaves whatever key was already configured untouched, and the
                    // summary should reflect that instead of falsely showing "Not set".
                    val mdm = LauncherPreferences.mdm()
                    findPreference<Preference>(mdm.keys().serverUrl())?.summary = mdm.serverUrl()
                    findPreference<Preference>(mdm.keys().tailscaleAuthKey())?.summary =
                        maskedSecretSummary(mdm.tailscaleAuthKey())
                }.onFailure { e ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_mdm_enroll_failure, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Dev-testing shortcut: runs the same policy fetch + enforcement cycle
     * [com.kidslauncher.mdm.server.CommandListenerService] runs periodically, immediately - avoids
     * waiting a full cycle per test iteration (e.g. right after changing the allowlist or kiosk
     * setting on the admin site). Also kicks off the journal/browser-history syncs the same way
     * [CommandListenerService] does off its own triggers - own coroutines, not awaited before the
     * toast below, since [performMdmSync]'s return value (whether policy fetch succeeded) is
     * already the more useful "did this reach the server at all" signal, and a slow media upload
     * from the journal sync shouldn't hold up that feedback.
     */
    private fun syncNowWithServer(context: Context) {
        val mdm = LauncherPreferences.mdm()
        if (mdm.serverUrl().isNullOrBlank() || mdm.deviceToken().isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch { performJournalSync(context) }
        CoroutineScope(Dispatchers.IO).launch { performBrowserHistorySync(context) }

        CoroutineScope(Dispatchers.IO).launch {
            val reachedServer = try {
                performMdmSync(context)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Manual sync failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                val messageRes =
                    if (reachedServer) R.string.toast_mdm_sync_done else R.string.toast_mdm_sync_failed
                Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
