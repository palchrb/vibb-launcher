package com.kidslauncher.mdm.ui.settings.launcher

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
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
import com.kidslauncher.mdm.server.BatteryExemption
import com.kidslauncher.mdm.server.BatteryRow
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.batteryRowState
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.RelockTrigger
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.FingerprintEnrol
import com.kidslauncher.mdm.server.MediaLockScreen
import com.kidslauncher.mdm.server.SystemPageAccess
import com.kidslauncher.mdm.server.WriteSettingsAccess
import com.kidslauncher.mdm.server.fingerprintActionText
import com.kidslauncher.mdm.server.fingerprintRowShown
import com.kidslauncher.mdm.server.fingerprintStateText
import com.kidslauncher.mdm.server.mediaLockActionText
import com.kidslauncher.mdm.server.mediaLockStateText
import com.kidslauncher.mdm.server.mediaLockTapOpens
import com.kidslauncher.mdm.server.systemPageAccess
import com.kidslauncher.mdm.server.writeSettingsText
import com.kidslauncher.mdm.server.writeSettingsRowState
import com.kidslauncher.mdm.play.InstallModeStart
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.server.applyProvisioningExtras
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import com.kidslauncher.mdm.server.performMdmSync
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.LegalInfoActivity
import com.kidslauncher.mdm.ui.ParentPinDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SettingsFragmentLauncher"
private const val BACKGROUND_CONNECTION = "settings_mdm_background_connection"
private const val WRITE_SETTINGS = "settings_mdm_write_settings"
private const val FINGERPRINT = "settings_mdm_fingerprint"
private const val MEDIA_LOCK = "settings_mdm_media_lock"

/**
 * "Set"/"Not set" alone left no way to tell *which* key is configured, or to notice a stale one
 * from a prior scan - reported directly after the Tailscale key visibly worked but the summary
 * gave no indication anything had actually changed. Shows just enough (a masked prefix plus the
 * real last 4 characters) to recognize the value without displaying the secret itself on a screen
 * anyone glancing at the phone could read.
 */
private fun maskedSecretSummary(value: String?, notSet: String): String {
    if (value.isNullOrBlank()) return notSet
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

    /** Design 19b: the exemption before Android's dialog opened - a change on return is reported
     * at once (a sync), so the parent's page follows within seconds. */
    private var exemptBeforeDialog: Boolean? = null
    private val batteryDialogLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val context = context ?: return@registerForActivityResult
        if (BatteryExemption.exempt(context) != exemptBeforeDialog) {
            com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, "battery_exemption")
        }
        updateBatteryRow()
    }

    /** "Modify system settings" (PLAN fix backlog, 2026-10-24): the app-op before Android's page
     * opened - a change on return is reported at once, so the PWA's warning follows. */
    private var writeSettingsBeforePage: Boolean? = null
    /** Every page threw ActivityNotFoundException on the last tap: the row says so. */
    private var writeSettingsPageMissing = false
    private val writeSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val context = context ?: return@registerForActivityResult
        if (WriteSettingsAccess.granted(context) != writeSettingsBeforePage) {
            com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, "write_settings")
        }
        updateWriteSettingsRow()
    }

    /** "Fingerprint" (the user, 2026-10-24): every enrolment page threw ActivityNotFoundException on the last tap. */
    private var fingerprintPageMissing = false
    private val fingerprintLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateFingerprintRow()
    }

    /** "Media on the lock screen" (PLAN, "Android's lock screen shows the media card"): the page threw
     * ActivityNotFoundException on the last tap. Read again on return. */
    private var mediaLockPageMissing = false
    private val mediaLockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateMediaLockRow()
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
        tailscaleAuthKey?.summary = tailscaleKeySummary()
        tailscaleAuthKey?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.settings_mdm_tailscale_auth_key),
                currentValue = null,
            ) { value ->
                mdm.tailscaleAuthKey(value)
                tailscaleAuthKey.summary = maskedSecretSummary(value, getString(R.string.settings_mdm_not_set))
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

        // Design 19b: Android's battery-optimisation dialog, opened only when it resolves to an
        // unsuspended system package - on a managed phone that is during the pause; otherwise
        // the summary says to pause first. Never through the parent-PIN gate itself.
        findPreference<Preference>(BACKGROUND_CONNECTION)?.setOnPreferenceClickListener {
            val context = requireContext()
            val intent = BatteryExemption.dialogIntent(context)
            val exempt = BatteryExemption.exempt(context)
            if (intent != null && batteryRowState(exempt, dialogAvailable = true) == BatteryRow.CAN_ASK) {
                exemptBeforeDialog = exempt
                try {
                    batteryDialogLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't open the battery dialog", e)
                }
            }
            updateBatteryRow()
            true
        }

        // Android's "Modify system settings" page for Vibb One (the touch sounds row, the clock
        // format), on the battery row's path: only an unsuspended system page - during the pause on
        // a managed phone. Our own page first, then Android's list; neither there: the row says so.
        findPreference<Preference>(WRITE_SETTINGS)?.setOnPreferenceClickListener {
            val context = requireContext()
            if (WriteSettingsAccess.granted(context) == false) {
                val pages = WriteSettingsAccess.pageIntents(context)
                writeSettingsPageMissing = false
                if (pages.isNotEmpty()) {
                    writeSettingsBeforePage = false
                    writeSettingsPageMissing = !openFirstPage(pages, writeSettingsLauncher)
                }
            }
            updateWriteSettingsRow()
            true
        }

        // Android's fingerprint enrolment for the kid (design 59d §8.4: the parent enrols it again during a pause),
        // on the same path; Android asks for the Child PIN first. Shown only while Android's credential is ours and
        // the phone has a sensor ([updateFingerprintRow]).
        findPreference<Preference>(FINGERPRINT)?.setOnPreferenceClickListener {
            val pages = FingerprintEnrol.pageIntents(requireContext())
            fingerprintPageMissing = false
            if (pages.isNotEmpty()) fingerprintPageMissing = !openFirstPage(pages, fingerprintLauncher)
            updateFingerprintRow()
            true
        }

        // Android's "Show media on lock screen" (its media card on SystemUI's face, whatever our keyguard features say):
        // its Sound & vibration page on the same path - the summary names the way to the Media page. Opens unless the
        // switch already reads off.
        findPreference<Preference>(MEDIA_LOCK)?.setOnPreferenceClickListener {
            val context = requireContext()
            if (mediaLockTapOpens(MediaLockScreen.shown(context))) {
                val pages = MediaLockScreen.pageIntents(context)
                mediaLockPageMissing = false
                if (pages.isNotEmpty()) mediaLockPageMissing = !openFirstPage(pages, mediaLockLauncher)
            }
            updateMediaLockRow()
            true
        }

        val restrictionsPaused = findPreference<SwitchPreference>(mdm.keys().restrictionsPaused())
        // isActive() clears a pause that has run out, so the switch never shows a stale "on".
        restrictionsPaused?.isChecked = RestrictionsPause.isActive()
        restrictionsPaused?.setOnPreferenceChangeListener { _, newValue ->
            val context = requireContext()
            if (newValue == true) {
                // Turning it on always takes the PIN again, on top of the Settings gate: Settings
                // is open without one during setup (no policy yet), and a pause must never be.
                // The switch only flips once the PIN checks out; the gate has started the pause
                // (and re-applied) by then. No PIN, or a lockout: the dialog says so.
                ParentPinDialog.show(
                    context,
                    lifecycleScope,
                    ParentPinEntry.PAUSE,
                    title = R.string.settings_mdm_restrictions_paused_pin_title,
                    notConfigured = R.string.toast_mdm_pause_needs_pin,
                ) {
                    restrictionsPaused.isChecked = true
                    // The window is open now; its apply follows on a background thread (appliedListener).
                    updateSystemPageRows()
                }
                false
            } else {
                // Design 24b: ending the pause here locks the phone again (and ends an override).
                WindowRelock.lockAgainAsync(context, RelockTrigger.SETTINGS)
                true
            }
        }

        // Play install mode (handy step 7): the parent opens the Play Store for 15 minutes with
        // the PIN (again, on top of the Settings gate). New apps stay hidden until allowlisted.
        val installMode = findPreference<Preference>("settings_play_install_mode")
        updateInstallModeSummary(installMode)
        installMode?.setOnPreferenceClickListener {
            val context = requireContext()
            if (PlayRuntime.installModeActive(context)) {
                PlayRuntime.endInstallMode(context)
                installMode.summary = getString(R.string.settings_play_install_mode_summary)
                return@setOnPreferenceClickListener true
            }
            // The PIN refusals (no PIN, a lockout) show in the PIN dialog; the others keep their
            // toasts (design 24 §2.4).
            val refusal = when (PlayRuntime.canStart(context)) {
                InstallModeStart.OK, InstallModeStart.NO_PIN, InstallModeStart.LOCKED_OUT -> null
                InstallModeStart.NOT_NEEDED -> R.string.toast_play_install_mode_not_needed
                InstallModeStart.TIME_LOCKED -> R.string.toast_play_install_mode_time_locked
                InstallModeStart.NO_PLAY_STORE -> R.string.toast_play_install_mode_no_store
            }
            if (refusal != null) {
                Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
            } else {
                // The gate starts install mode on a correct PIN.
                ParentPinDialog.show(
                    context,
                    lifecycleScope,
                    ParentPinEntry.INSTALL_MODE,
                    title = R.string.settings_play_install_mode_pin_title,
                    notConfigured = R.string.toast_play_install_mode_needs_pin,
                ) { updateInstallModeSummary(installMode) }
            }
            true
        }
    }

    /** The key masked while it is stored; once the phone joined, it is gone (security review M12). */
    private fun tailscaleKeySummary(): String {
        val key = LauncherPreferences.mdm().tailscaleAuthKey()
        return when {
            !key.isNullOrBlank() -> maskedSecretSummary(key, getString(R.string.settings_mdm_not_set))
            com.kidslauncher.mdm.server.TsnetClient.joined(requireContext()) -> getString(R.string.settings_mdm_tailscale_joined)
            else -> getString(R.string.settings_mdm_not_set)
        }
    }

    private fun String?.orNotSet(): String = this?.takeIf { it.isNotBlank() }
        ?: getString(R.string.settings_mdm_not_set)

    override fun onResume() {
        super.onResume()
        // Re-read every time: a pause started or ended, or the dialog answered; and after each apply while shown.
        AppEnforcer.addAppliedListener(appliedListener)
        updateSystemPageRows()
    }

    override fun onPause() {
        AppEnforcer.removeAppliedListener(appliedListener)
        super.onPause()
    }

    /** Starts the first of Android's [pages] that Android finds, through [launcher]; false only when every one
     * threw ActivityNotFoundException (any other failure is logged and counts as tried). */
    private fun openFirstPage(pages: List<Intent>, launcher: ActivityResultLauncher<Intent>): Boolean {
        for (page in pages) {
            try {
                launcher.launch(page)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(LOG_TAG, "No page for ${page.action}, trying the next")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't open the page for ${page.action}", e)
                return true
            }
        }
        return false
    }

    /** Whether a row's Android page can open now ([systemPageAccess]): "no page" only once the open window's plan
     * is applied (code QA Q-2). */
    private fun pageAccess(pages: List<Intent>, pageMissing: Boolean): SystemPageAccess = systemPageAccess(
        pageAvailable = pages.isNotEmpty(),
        windowOpen = RestrictionsPause.isActive() || com.kidslauncher.mdm.server.OfflineOverride.isActive(),
        overrideApplied = AppEnforcer.lastEnforcedOverride == true,
        pageMissing = pageMissing,
    )

    /** The four rows that open Android's pages, read again: on resume, after a tap, when the pause starts and
     * after each apply (the window's apply unhides Android's Settings - code QA Q-2). */
    private fun updateSystemPageRows() {
        updateBatteryRow()
        updateWriteSettingsRow()
        updateFingerprintRow()
        updateMediaLockRow()
    }

    private val appliedListener: () -> Unit = { updateSystemPageRows() }

    private fun updateFingerprintRow() {
        val context = context ?: return
        val row = findPreference<Preference>(FINGERPRINT) ?: return
        row.isVisible = fingerprintRowShown(
            com.kidslauncher.mdm.lock.AndroidLock.fingerprintLockState(context),
            FingerprintEnrol.hasSensor(context),
        )
        if (!row.isVisible) return
        val access = pageAccess(FingerprintEnrol.pageIntents(context), fingerprintPageMissing)
        row.summary = getString(
            R.string.settings_mdm_fingerprint_summary,
            getString(fingerprintStateText(FingerprintEnrol.enrolled(context))),
            getString(fingerprintActionText(access)),
        )
    }

    private fun updateMediaLockRow() {
        val context = context ?: return
        val row = findPreference<Preference>(MEDIA_LOCK) ?: return
        val shown = MediaLockScreen.shown(context)
        val action = mediaLockActionText(shown, pageAccess(MediaLockScreen.pageIntents(context), mediaLockPageMissing))
        row.summary = if (action == null) {
            getString(mediaLockStateText(shown))
        } else {
            getString(R.string.settings_mdm_media_lock_summary, getString(mediaLockStateText(shown)), getString(action))
        }
    }

    private fun updateWriteSettingsRow() {
        val context = context ?: return
        val row = findPreference<Preference>(WRITE_SETTINGS) ?: return
        val access = pageAccess(WriteSettingsAccess.pageIntents(context), writeSettingsPageMissing)
        row.summary = getString(writeSettingsText(writeSettingsRowState(WriteSettingsAccess.granted(context), access)))
    }

    private fun updateBatteryRow() {
        val context = context ?: return
        val row = findPreference<Preference>(BACKGROUND_CONNECTION) ?: return
        val state = batteryRowState(BatteryExemption.exempt(context), BatteryExemption.dialogIntent(context) != null)
        row.summary = getString(
            when (state) {
                BatteryRow.EXEMPT -> R.string.settings_mdm_background_connection_exempt
                BatteryRow.CAN_ASK -> R.string.settings_mdm_background_connection_ask
                BatteryRow.PAUSE_FIRST -> R.string.settings_mdm_background_connection_pause_first
                BatteryRow.UNKNOWN -> R.string.settings_mdm_background_connection_unknown
            },
        )
    }

    private fun updateInstallModeSummary(preference: Preference?) {
        preference ?: return
        val until = PlayRuntime.installModeUntil(requireContext())
        preference.summary = if (until != null) {
            getString(
                R.string.settings_play_install_mode_active,
                com.kidslauncher.mdm.ui.ClockFormat.formatUntil(requireContext(), until),
            )
        } else {
            getString(R.string.settings_play_install_mode_summary)
        }
    }

    /**
     * [androidx.preference.EditTextPreference]'s built-in dialog doesn't pick up this app's
     * custom (dark) theme - it renders with invisible text/buttons. Uses the same themed
     * AlertDialog approach as the app-rename dialog instead.
     */
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
                    // The queued parent-PIN events were about the old enrollment (design 24).
                    com.kidslauncher.mdm.server.ParentPin.clearEvents(context)
                    com.kidslauncher.mdm.server.CommandAnswers.clear(context)
                    com.kidslauncher.mdm.server.InstallNow.clear(context)
                    // First policy now, not at the next backstop (up to 30 min away).
                    com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, "enrolled")
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
                    findPreference<Preference>(mdm.keys().tailscaleAuthKey())?.summary = tailscaleKeySummary()
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
     * setting on the admin site). [performMdmSync]'s return value (whether the policy fetch
     * succeeded) is the "did this reach the server at all" signal the toast shows.
     */
    private fun syncNowWithServer(context: Context) {
        val mdm = LauncherPreferences.mdm()
        if (mdm.serverUrl().isNullOrBlank() || mdm.deviceToken().isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val reachedServer = try {
                performMdmSync(context).also {
                    com.kidslauncher.mdm.push.BackstopAlarm.schedule(context, afterSync = true)
                }
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
