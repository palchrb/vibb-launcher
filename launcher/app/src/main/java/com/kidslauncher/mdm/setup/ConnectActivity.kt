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
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityConnectBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.calls.EmergencyCall
import com.kidslauncher.mdm.server.DeviceCredentials
import com.kidslauncher.mdm.server.EnrolmentWitness
import com.kidslauncher.mdm.server.KeyBook
import com.kidslauncher.mdm.server.QrExtras
import com.kidslauncher.mdm.server.EnrolWait
import com.kidslauncher.mdm.server.enrolFailureLine
import com.kidslauncher.mdm.server.serverUrlUsable
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LOG_TAG = "ConnectActivity"

/**
 * "Koble til" (2026-11-20, the user): Home's resume brings it up on a Device Owner phone that isn't enrolled
 * ([connectScreenDue] - only before the first policy, never wider than the Settings gate; or when the enrolment's
 * witness says managed and the preferences were lost, an adopted hardware key or not). An emergency button opens
 * [EmergencyCall.confirm]. The server address (the stored one filled in), the setup code, one button: S-M1's rule first ([serverUrlUsable], the whole reason as
 * `server_url_refused`), then the QR's one apply through [QrExtras.enrolTyped] on the process's scope (a one-shot code
 * the server consumed is never lost to the screen going away). Enrolled, it finishes and Home's resume leads on to the
 * setup flow (the hold starts at the enrolment).
 *
 * The busy state and the last failure are the process's, not this instance's (QA M2, L6): any apply or enrolment in
 * flight ([QrExtras.running] - a QR delivery or retry too) shows busy, and when none is, the rule is read again and an
 * enrolled phone's screen finishes. Home's `singleTask` and a recreation make new instances; one tap is one enrolment
 * ([typing]), and [QrExtras.enrolTyped] sends nothing on an enrolled phone.
 *
 * Back closes it for this visit of Home; it comes back the next time Home comes to the front. Not exported, out of
 * Recents, FLAG_SECURE (the code). The rule is checked again at every start: an enrolment by any other path closes it.
 * No tailnet key field (QA L5): the kept QR extras' key is used, and Settings (open before the first policy) has the
 * "Tailscale auth key" row for a tailnet-only server.
 */
class ConnectActivity : UIObjectActivity() {
    private lateinit var binding: ActivityConnectBinding
    private var watch: Job? = null

    private val busy: Boolean get() = typing.value || QrExtras.running.value > 0 ||
        EnrolWait.state.value is EnrolWait.State.Waiting

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (savedInstanceState == null) binding.connectServerUrl.setText(prefilledUrl(this))
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                lastError.value = null
                render()
            }
        }
        binding.connectServerUrl.addTextChangedListener(watcher)
        binding.connectCode.addTextChangedListener(watcher)
        binding.connectButton.setOnClickListener { connect() }
        // Always there, as on every locked screen: the confirm (or the parent's emergency menu) decides what calls.
        binding.connectEmergency.setOnClickListener { EmergencyCall.confirm(this) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                skipNextHomeResume = true
                finish()
            }
        })
        render()
    }

    override fun onStart() {
        super.onStart()
        if (!busy && !due(this)) {
            finish()
            return
        }
        // QA M2: the process's state, whichever instance started the enrolment.
        watch = lifecycleScope.launch {
            launch { QrExtras.running.collect { render() } }
            launch { typing.collect { render() } }
            launch { lastError.collect { render() } }
            launch { EnrolWait.state.collect { render() } }
            launch { restored.collect { render() } }
        }
    }

    override fun onStop() {
        watch?.cancel()
        watch = null
        super.onStop()
    }

    private fun render() {
        val busyNow = busy
        if (!busyNow && !isFinishing && !due(this)) {
            finish()
            return
        }
        binding.connectButton.isEnabled = connectButtonEnabled(
            binding.connectServerUrl.text.toString(), binding.connectCode.text.toString(), busyNow,
        )
        binding.connectButton.setText(if (busyNow) R.string.connect_busy else R.string.connect_title)
        // Design 129b D12: while the enrolment waits for a parent, one line and the attempt's number; after the 45-minute
        // stop, "Koden er utløpt. Be om en ny." (L7).
        // Design 142 M4: else why the QR didn't connect, read again at every render (QrExtras.running moves it).
        val line = EnrolWait.lines(this) ?: lastError.value?.let { getString(it) } ?: qrLine()?.let { getString(it) }
        binding.connectError.text = line.orEmpty()
        binding.connectError.visibility = if (line == null) View.GONE else View.VISIBLE
    }

    /** Design 142 M4: the QR's line, from the kept extras' store and the witness (no Keystore call). */
    private fun qrLine(): Int? {
        val kept = QrExtras.keptState(this)
        val witnessEnrolled = try {
            EnrolmentWitness.enrolled(this)
        } catch (e: Exception) {
            true
        }
        return when (qrConnectLine(kept.refused, kept.pending, running = QrExtras.running.value > 0, witnessEnrolled)) {
            QrConnectLine.RETRYING -> R.string.connect_qr_retrying
            QrConnectLine.REFUSED -> R.string.connect_qr_refused
            null -> null
        }
    }

    private fun connect() {
        val url = binding.connectServerUrl.text.toString().trim()
        val code = binding.connectCode.text.toString().trim()
        if (!connectButtonEnabled(url, code, busy)) return
        // Security review S-M1: an address the phone won't use changes nothing; the whole reason shows.
        // A phone the witness says was enrolled goes back to that server only (gate rc.4 finding 4: its own line).
        connectUrlRefusal(serverUrlUsable(url, BuildConfig.DEBUG), connectUrlAllowed(url, EnrolmentWitness.serverUrl(this)))?.let {
            lastError.value = when (it) {
                ConnectUrlRefusal.ADDRESS_RULE -> R.string.server_url_refused
                ConnectUrlRefusal.OTHER_SERVER -> R.string.connect_other_server
            }
            return
        }
        if (!typing.compareAndSet(false, true)) return
        lastError.value = null
        EnrolWait.clearEnded()
        render()
        hideIme()
        val app = applicationContext
        CONNECT_SCOPE.launch {
            try {
                val result = QrExtras.enrolTyped(app, url, code)
                // Code QA Q-1 of design r10: only a refused code is "that code didn't work".
                if (result.isFailure) lastError.value = enrolFailureLine(result.exceptionOrNull())
            } finally {
                // The started instance's watchers render; an enrolled phone's screen finishes there.
                typing.value = false
            }
        }
    }

    private fun hideIme() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    companion object {
        /** The enrolments' scope: the process's, not the screen's (as `ReEnrolDialog`'s). */
        private val CONNECT_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** A typed enrolment between the tap and its end - the process's, so a new instance shows busy (QA M2). */
        private val typing = MutableStateFlow(false)

        /** The last typed enrolment's failure line (a string resource), the process's; cleared by an edit or a tap. */
        private val lastError = MutableStateFlow<Int?>(null)

        /** Bumped when a sync restored `enrolled` ([enrolmentRestoredBySync]): an open screen reads the rule again. */
        private val restored = MutableStateFlow(0)

        /** Gate rc.4 finding 2: the sync restored `enrolled` - any thread. */
        fun enrolmentRestored() {
            restored.update { it + 1 }
        }

        /** Back closed the screen: Home's next resume (the one right after) shows Home. Main thread only. */
        private var skipNextHomeResume = false

        /** The rule's live inputs; main-thread safe (a binder call, prefs reads and the witness's small file, no
         * Keystore). Never throws. */
        fun due(context: Context): Boolean = try {
            val mdm = LauncherPreferences.mdm()
            connectScreenDue(
                deviceOwner = SetupReadings.deviceOwner(context),
                enrolled = mdm.enrolled(),
                tokenStored = DeviceCredentials.tokenStored(),
                keyAdopted = KeyBook.adoptedStored(context),
                policyEverApplied = mdm.policyEverApplied(),
                witnessEnrolled = EnrolmentWitness.enrolled(context),
            )
        } catch (e: Exception) {
            false
        }

        /** The stored address, else the one the enrolment's witness names (the preferences lost). */
        private fun prefilledUrl(context: Context): String =
            LauncherPreferences.mdm().serverUrl()?.takeIf { it.isNotBlank() } ?: EnrolmentWitness.serverUrl(context).orEmpty()

        /** From Home's resume: true when the screen was started (Home stops there). */
        fun startFromHome(activity: Activity): Boolean {
            if (skipNextHomeResume) {
                skipNextHomeResume = false
                return false
            }
            // Gate rc.4 finding 3: never over parent Settings (the drawer's, its gate's dialog up) - it comes back at the
            // next resume of Home with Settings gone.
            if (com.kidslauncher.mdm.ui.settings.SettingsFront.shown) return false
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
