package com.kidslauncher.mdm.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.ParentPin
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.server.ParentPinResult
import com.kidslauncher.mdm.server.lockoutText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The parent-PIN dialog of the time lock, the Settings gate, the pause and install mode (design 24
 * §2.4; the PIN lock's "Parent code" has its own layout and uses [resultText]). Every result shows
 * in the dialog's error line - never a toast, which doesn't show while a screen-time budget sets
 * `DISALLOW_CREATE_WINDOWS`. A refusal before any try (no PIN, a running lockout) opens the dialog
 * in that state with OK disabled; after a lockout OK stays disabled - Cancel and reopen.
 */
object ParentPinDialog {

    /**
     * Shows the dialog for [entry]. The check runs on [scope] off the main thread with OK disabled
     * meanwhile; [onOk] runs on the main thread after a correct PIN, whose window (override,
     * pause, install mode) [ParentPin.check] has started already. [onCancel]: Cancel was tapped.
     */
    fun show(
        context: Context,
        scope: CoroutineScope,
        entry: ParentPinEntry,
        title: Int,
        notConfigured: Int,
        cancelable: Boolean = true,
        onCancel: () -> Unit = {},
        onOk: () -> Unit,
    ): AlertDialog {
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(title)
            .setView(R.layout.dialog_offline_override_pin)
            .setCancelable(cancelable)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
        val error = dialog.findViewById<TextView>(R.id.dialog_offline_override_pin_error)
        fun showError(text: String?) {
            error?.text = text
            error?.visibility = if (text == null) View.GONE else View.VISIBLE
        }
        refusal(context, notConfigured, R.string.lock_unlock_code_locked_out)?.let {
            showError(it)
            ok.isEnabled = false
        }
        // Set after show() so a wrong PIN keeps the dialog open.
        ok.setOnClickListener {
            val pin = input?.text?.toString().orEmpty()
            ok.isEnabled = false
            scope.launch {
                val result = withContext(Dispatchers.Default) { ParentPin.check(context, entry, pin) }
                if (result == ParentPinResult.Ok) {
                    dialog.dismiss()
                    onOk()
                    return@launch
                }
                if (result is ParentPinResult.Wrong || result is ParentPinResult.LockedOut) input?.text?.clear()
                showError(resultText(context, result, R.string.lock_unlock_code_wrong, notConfigured, R.string.lock_unlock_code_locked_out))
                ok.isEnabled = okAfter(result)
            }
        }
        return dialog
    }

    /** The dialog's state before any try: the refusal text (no PIN, a running lockout), or null.
     * Main thread: no hashing, but it may wait for a running check. */
    fun refusal(context: Context, notConfigured: Int, lockedOut: Int): String? {
        if (!OfflineOverride.isConfigured()) return context.getString(notConfigured)
        val remaining = ParentPin.lockoutRemainingMs(context)
        return if (remaining > 0L) lockedOutText(context, lockedOut, remaining) else null
    }

    /** The error line for [result] (null for Ok). */
    fun resultText(context: Context, result: ParentPinResult, wrong: Int, notConfigured: Int, lockedOut: Int): String? =
        when (result) {
            ParentPinResult.Ok -> null
            is ParentPinResult.Wrong -> if (result.triesLeft == 1) {
                context.getString(R.string.parent_pin_last_try, duration(context, result.nextLockoutMs))
            } else {
                context.getString(wrong)
            }
            is ParentPinResult.LockedOut -> lockedOutText(context, lockedOut, result.remainingMs)
            ParentPinResult.TooShort -> context.getString(R.string.parent_pin_too_short)
            ParentPinResult.NotConfigured -> context.getString(notConfigured)
            is ParentPinResult.Unavailable ->
                context.getString(if (result.storageFull) R.string.parent_pin_storage_full else R.string.parent_pin_unavailable)
        }

    /** OK stays usable after a wrong, too-short or uncounted try - not after a lockout or with no PIN. */
    fun okAfter(result: ParentPinResult): Boolean =
        result is ParentPinResult.Wrong || result == ParentPinResult.TooShort || result is ParentPinResult.Unavailable

    private fun lockedOutText(context: Context, text: Int, remainingMs: Long): String =
        context.getString(text, duration(context, remainingMs))

    private fun duration(context: Context, ms: Long): String =
        lockoutText(ms, context.getString(R.string.parent_pin_unit_hours), context.getString(R.string.parent_pin_unit_minutes))
}
