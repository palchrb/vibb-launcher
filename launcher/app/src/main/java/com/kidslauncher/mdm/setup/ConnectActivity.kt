package com.kidslauncher.mdm.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityConnectBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.DeviceToken
import com.kidslauncher.mdm.server.QrExtras
import com.kidslauncher.mdm.server.enrolFailureRefused
import com.kidslauncher.mdm.server.serverUrlUsable
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "ConnectActivity"

/**
 * "Koble til" (2026-11-20, the user): Home's resume brings it up on a Device Owner phone that isn't enrolled
 * ([connectScreenDue] - only before the first policy, never wider than the Settings gate). The server address (the
 * stored one filled in), the setup code, one button: S-M1's rule first
 * ([serverUrlUsable], the whole reason as `server_url_refused`), then the QR's one apply through
 * [QrExtras.enrolTyped] on the process's scope (a one-shot code the server consumed is never lost to the screen going
 * away). Enrolled, it finishes and Home's resume leads on to the setup flow (the hold starts at the enrolment).
 * Back closes it for this visit of Home; it comes back the next time Home comes to the front. Not exported, out of
 * Recents, FLAG_SECURE (the code). The rule is checked again at every start: an enrolment by any other path closes it.
 */
class ConnectActivity : UIObjectActivity() {
    private lateinit var binding: ActivityConnectBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) binding.connectServerUrl.setText(prefilledUrl())
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                binding.connectError.visibility = View.GONE
                updateButton()
            }
        }
        binding.connectServerUrl.addTextChangedListener(watcher)
        binding.connectCode.addTextChangedListener(watcher)
        binding.connectButton.setOnClickListener { connect() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                skipNextHomeResume = true
                finish()
            }
        })
        updateButton()
    }

    override fun onStart() {
        super.onStart()
        if (!busy && !due(this)) finish()
    }

    private fun updateButton() {
        binding.connectButton.isEnabled = connectButtonEnabled(
            binding.connectServerUrl.text.toString(), binding.connectCode.text.toString(), busy,
        )
        binding.connectButton.setText(if (busy) R.string.connect_busy else R.string.connect_title)
    }

    private fun showError(text: String) {
        binding.connectError.text = text
        binding.connectError.visibility = View.VISIBLE
    }

    private fun connect() {
        val url = binding.connectServerUrl.text.toString().trim()
        val code = binding.connectCode.text.toString().trim()
        if (!connectButtonEnabled(url, code, busy)) return
        // Security review S-M1: an address the phone won't use changes nothing; the whole reason shows.
        if (!serverUrlUsable(url, BuildConfig.DEBUG)) {
            showError(getString(R.string.server_url_refused))
            return
        }
        busy = true
        updateButton()
        hideIme()
        val app = applicationContext
        CONNECT_SCOPE.launch {
            val result = QrExtras.enrolTyped(app, url, code)
            withContext(Dispatchers.Main) {
                busy = false
                if (isFinishing || isDestroyed) return@withContext
                if (result.isSuccess) {
                    finish()
                } else {
                    updateButton()
                    // Code QA Q-1 of design r10: only a refused code is "that code didn't work".
                    val line = if (enrolFailureRefused(result.exceptionOrNull())) R.string.re_enrol_failed else R.string.re_enrol_unreachable
                    showError(getString(line))
                }
            }
        }
    }

    private fun hideIme() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    companion object {
        /** The enrolments' scope: the process's, not the screen's (as `ReEnrolDialog`'s). */
        private val CONNECT_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Back closed the screen: Home's next resume (the one right after) shows Home. Main thread only. */
        private var skipNextHomeResume = false

        /** The rule's live inputs; main-thread safe (a binder call and prefs reads, no Keystore). Never throws. */
        fun due(context: Context): Boolean = try {
            val mdm = LauncherPreferences.mdm()
            connectScreenDue(
                deviceOwner = SetupReadings.deviceOwner(context),
                enrolled = mdm.enrolled(),
                tokenStored = DeviceToken.present(),
                policyEverApplied = mdm.policyEverApplied(),
            )
        } catch (e: Exception) {
            false
        }

        private fun prefilledUrl(): String =
            LauncherPreferences.mdm().serverUrl()?.takeIf { it.isNotBlank() }.orEmpty()

        /** From Home's resume: true when the screen was started (Home stops there). */
        fun startFromHome(activity: Activity): Boolean {
            if (skipNextHomeResume) {
                skipNextHomeResume = false
                return false
            }
            if (!due(activity)) return false
            return try {
                activity.startActivity(Intent(activity, ConnectActivity::class.java))
                true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't start Koble til: ${e.javaClass.simpleName}")
                false
            }
        }
    }
}
