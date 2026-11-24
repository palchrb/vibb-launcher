package com.kidslauncher.mdm.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.DeviceCredentials
import com.kidslauncher.mdm.server.KeyBook
import com.kidslauncher.mdm.server.Enrollment
import com.kidslauncher.mdm.server.ParentPin
import com.kidslauncher.mdm.server.EnrolWait
import com.kidslauncher.mdm.server.EnrollWaiting
import com.kidslauncher.mdm.server.QrExtras
import com.kidslauncher.mdm.server.enrolFailureLine
import com.kidslauncher.mdm.server.showReEnrol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Ny oppsettkode" in both parent-code dialogs (design r10 §5): the dialog's neutral button, shown only in the
 * deadlock ([showReEnrol]: the device token unreadable **and** the parent PIN unavailable until a sync or not set,
 * a server URL stored). The condition is read off the main thread when the dialog opens and again after every result
 * (revision check N2: the vault's `untilSync` shows only after a failed check). Its code dialog runs the one
 * [Enrollment] and a sync - it never unlocks; the lock opens only by the kid or parent PIN, as always.
 */
object ReEnrolDialog {

    /** The enrolments' scope: the process's, not an activity's (Q-8). */
    private val ENROL_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * After `dialog.show()`, for a dialog built with `setNeutralButton(R.string.re_enrol_button, null)`. [track]: the
     * lock's dialog bookkeeping, [showLine]: the parent dialog's error line. Returns the re-check to call after every
     * result.
     */
    fun bind(dialog: AlertDialog, scope: CoroutineScope, track: (Dialog) -> Unit = {}, showLine: (String?) -> Unit): () -> Unit {
        val context = dialog.context
        val app = context.applicationContext
        val button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        button?.visibility = View.GONE
        val recheck: () -> Unit = {
            scope.launch {
                val due = withContext(Dispatchers.IO) { due(app) }
                button?.visibility = if (due) View.VISIBLE else View.GONE
            }
        }
        button?.setOnClickListener {
            open(context, track) { line ->
                showLine(line)
                recheck()
            }
        }
        recheck()
        return recheck
    }

    /** Off the main thread (a Keystore call). Never throws. */
    private fun due(context: Context): Boolean = try {
        showReEnrol(
            DeviceCredentials.tokenState(), ParentPin.status(context), LauncherPreferences.mdm().serverUrl(),
            keyAdopted = KeyBook.adoptedStored(context), keyUnusable = DeviceCredentials.keyUnusable(context),
        )
    } catch (e: Exception) {
        false
    }

    private fun open(context: Context, track: (Dialog) -> Unit, done: (String) -> Unit) {
        val app = context.applicationContext
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_re_enrol, null)
        val field = view.findViewById<EditText>(R.id.re_enrol_code)
        val error = view.findViewById<TextView>(R.id.re_enrol_error)
        // No selection, paste, share or "process text" menu (revision check N4): nothing it offers can start an
        // activity over the lock.
        NoTextActions.apply(field)
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(R.string.re_enrol_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        ParentPinKeypad.secure(dialog)
        dialog.setOnCancelListener { hideIme(field) }
        dialog.show()
        track(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            hideIme(field)
            dialog.dismiss()
        }
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        // Design 129b (code QA L3): while the dialog shows it follows a waiting enrolment - enrolled closes it as any
        // enrolment does; run out says so and gives OK back.
        val watch = ENROL_SCOPE.launch(Dispatchers.Main) {
            EnrolWait.state.drop(1).collect { state ->
                if (!dialog.isShowing) return@collect
                when (state) {
                    EnrolWait.State.Enrolled -> {
                        hideIme(field)
                        dialog.dismiss()
                        done(context.getString(R.string.re_enrol_done))
                    }
                    EnrolWait.State.RanOut -> {
                        error.text = context.getString(R.string.enrol_code_ran_out)
                        error.visibility = View.VISIBLE
                        ok.isEnabled = true
                    }
                    is EnrolWait.State.Waiting -> {
                        error.text = EnrolWait.lines(context).orEmpty()
                        error.visibility = View.VISIBLE
                    }
                    null -> Unit
                }
            }
        }
        dialog.setOnDismissListener { watch.cancel() }
        ok.setOnClickListener {
            val code = field.text.toString().trim()
            val url = LauncherPreferences.mdm().serverUrl()
            if (code.isEmpty() || url.isNullOrBlank()) return@setOnClickListener
            ok.isEnabled = false
            // Code QA Q-8: the enrolment runs in the process's scope - a one-shot code the server consumed must not be
            // lost to the activity going away mid-call; the dialog hears the result only while it still shows.
            ENROL_SCOPE.launch {
                // Design 129b (code QA M2): the code already waits - nothing is sent again; the lines show.
                if (EnrolWait.waitingFor(code)) {
                    withContext(Dispatchers.Main) {
                        error.text = EnrolWait.lines(context).orEmpty()
                        error.visibility = View.VISIBLE
                    }
                    return@launch
                }
                // Under QrExtras' mutex, as every enrolment and the waiting one's tries are.
                val result = QrExtras.underLock { Enrollment.enroll(app, url, code) }
                if (result.isSuccess) SyncRunner.request(app, "re_enrolled")
                // Design 129b D11: the server waits for a parent - the same code keeps trying in the process.
                (result.exceptionOrNull() as? EnrollWaiting)?.let { waiting ->
                    EnrolWait.start(app, code, waiting, tryOnce = { Enrollment.enroll(app, url, code) })
                }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    if (result.isSuccess) {
                        hideIme(field)
                        dialog.dismiss()
                        done(context.getString(R.string.re_enrol_done))
                    } else {
                        // Q-1: only a refused code is "that code didn't work"; design 129b D12: waiting, the line and
                        // the number.
                        val line = enrolFailureLine(result.exceptionOrNull())
                        error.text = if (line == null) EnrolWait.lines(context).orEmpty() else context.getString(line)
                        error.visibility = View.VISIBLE
                        ok.isEnabled = line != null
                    }
                }
            }
        }
    }

    private fun hideIme(field: View) {
        field.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }
}
