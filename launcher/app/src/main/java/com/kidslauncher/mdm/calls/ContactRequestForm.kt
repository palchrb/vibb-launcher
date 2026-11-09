package com.kidslauncher.mdm.calls

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityPhoneBookBinding
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.ui.NoTextActions

/**
 * The kid's contact request in the phone book (design 77 §14, design 79 §7): the header's "+" opens the form in the
 * list's place, "Send til mamma og pappa" keeps the request ([ContactRequests.add]) and asks for a sync, which sends
 * it; a calm "Sendt" follows in the form's place ("Ferdig" back to the list - in a long list the new waiting row is
 * off screen). The requests themselves are rows of the list ([phoneBookRows]): an answer once at the top, the open ones
 * under "Venter på svar". Reachable exactly where the phone book is, under the same time rules, and only while calls
 * are managed and on ([canAsk], QA L2). The number field is the kid's only free number entry on the phone, and it only
 * asks: nothing here calls, texts or opens anything, and no field has a menu or a text action ([NoTextActions]).
 *
 * Kept apart from [PhoneBookActivity] (its layout `view_contact_request_form.xml`) so the look can change without
 * touching the phone book.
 */
class ContactRequestForm(
    private val activity: AppCompatActivity,
    private val binding: ActivityPhoneBookBinding,
    /** The form opened or closed: the phone book binds its header and list again. */
    private val onModeChanged: () -> Unit,
) {
    private val form = binding.phoneBookRequestForm
    var isOpen = false
        private set

    /** "Sendt" is showing in the form's place. */
    private var sent = false

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        for (field in listOf(form.contactRequestName, form.contactRequestNumber, form.contactRequestChat)) {
            NoTextActions.apply(field)
            // A line for why not goes once the kid changes what was typed.
            field.doAfterTextChanged { if (isOpen) showErrors(null, focus = false) }
        }
        form.contactRequestCancel.setOnClickListener { close() }
        form.contactRequestSend.setOnClickListener { send() }
        form.contactRequestDone.setOnClickListener { close() }
        form.contactRequestChat.setOnEditorActionListener { _, _, _ ->
            send()
            true
        }
    }

    /** The "+", the form and "Be om å legge til" (design 79 §3.5, QA L2): while calls are managed and on - with calls
     * off the phone book lists only the emergency numbers, and the kid can't ask. */
    fun canAsk(): Boolean = (CallPolicyStore.effectiveState() as? CallPolicyState.Managed)?.rules?.callsEnabled == true

    /** Opens the form, its name field holding [name] ("Fant ingenting"'s "Be om å legge til «Lina»"). */
    fun open(name: String? = null) {
        if (isOpen || !canAsk()) return
        isOpen = true
        sent = false
        for (field in listOf(form.contactRequestName, form.contactRequestNumber, form.contactRequestChat)) field.text = null
        if (name != null) form.contactRequestName.setText(name)
        showErrors(null)
        apply()
        (if (name != null) form.contactRequestNumber else form.contactRequestName).requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        sent = false
        activity.currentFocus?.let { focused ->
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        apply()
    }

    fun save(out: Bundle) {
        out.putBoolean(STATE_OPEN, isOpen)
        out.putBoolean(STATE_SENT, sent)
        out.putString(STATE_SENT_NAME, form.contactRequestSentBody.tag as? String)
    }

    /** After a rotation: the form or "Sendt" as it was (the fields keep their own text). */
    fun restore(state: Bundle?) {
        if (state?.getBoolean(STATE_OPEN) != true || !canAsk()) return
        isOpen = true
        sent = state.getBoolean(STATE_SENT)
        state.getString(STATE_SENT_NAME)?.let(::bindSent)
        apply()
    }

    private fun apply() {
        back.isEnabled = isOpen
        form.root.visibility = if (isOpen) View.VISIBLE else View.GONE
        form.contactRequestFields.visibility = if (sent) View.GONE else View.VISIBLE
        form.contactRequestSent.visibility = if (sent) View.VISIBLE else View.GONE
        binding.phoneBookList.visibility = if (isOpen) View.GONE else View.VISIBLE
        // The phone book renders again: its info line, the header and the rows follow [isOpen].
        onModeChanged()
    }

    private fun send() {
        val state = CallPolicyStore.effectiveState()
        val rules = (state as? CallPolicyState.Managed)?.rules?.takeIf { it.callsEnabled } ?: return close()
        val check = checkContactRequest(
            name = form.contactRequestName.text.toString(),
            number = form.contactRequestNumber.text.toString(),
            matrixId = form.contactRequestChat.text.toString(),
            defaultCc = rules.defaultCc,
            knownNumbers = rules.contacts.mapTo(mutableSetOf()) { it.number },
            records = ContactRequests.records(activity),
            // Code QA L5: the platform's answer too, so a SIM's or a roaming country's own emergency number is refused.
        ) { CallSystem.isEmergencyOutgoing(activity, it) }
        when (check) {
            is RequestCheck.Refused -> showErrors(check)
            is RequestCheck.Ok -> {
                ContactRequests.add(activity, check)
                SyncRunner.request(activity.applicationContext, "contact_request")
                activity.currentFocus?.let { focused ->
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        ?.hideSoftInputFromWindow(focused.windowToken, 0)
                }
                sent = true
                bindSent(check.name)
                apply()
            }
        }
    }

    private fun bindSent(name: String) {
        form.contactRequestSentBody.tag = name
        form.contactRequestSentBody.text = activity.getString(R.string.contact_request_sent_body, name)
    }

    private fun showErrors(refused: RequestCheck.Refused?, focus: Boolean = true) {
        val lines = mapOf(
            RequestField.NAME to form.contactRequestNameError,
            RequestField.NUMBER to form.contactRequestNumberError,
            RequestField.MATRIX to form.contactRequestChatError,
        )
        val tooMany = refused?.why == RequestRefusal.TOO_MANY
        for ((field, line) in lines) {
            val why = refused?.takeIf { it.field == field && !tooMany }?.why
            line.visibility = if (why == null) View.GONE else View.VISIBLE
            line.text = why?.let { activity.getString(words(it)) }
        }
        form.contactRequestFormError.visibility = if (tooMany) View.VISIBLE else View.GONE
        form.contactRequestFormError.text = if (tooMany) activity.getString(R.string.contact_request_too_many) else null
        if (focus) when (refused?.field) {
            RequestField.NAME -> form.contactRequestName.requestFocus()
            RequestField.NUMBER -> form.contactRequestNumber.requestFocus()
            RequestField.MATRIX -> form.contactRequestChat.requestFocus()
            null -> {}
        }
    }

    /** The phone book showed the answers: each shows once ([ContactRequests.markShown]). */
    fun answersShown() = ContactRequests.markShown(activity)

    /** The phone book was left: the answers shown go. */
    fun left() = ContactRequests.dropShown(activity)

    private fun words(why: RequestRefusal): Int = when (why) {
        RequestRefusal.NAME_EMPTY -> R.string.contact_request_name_empty
        RequestRefusal.NAME_TOO_LONG -> R.string.contact_request_name_long
        RequestRefusal.NUMBER_INVALID -> R.string.contact_request_number_invalid
        RequestRefusal.NUMBER_EMERGENCY -> R.string.contact_request_number_emergency
        RequestRefusal.NUMBER_KNOWN -> R.string.contact_request_number_known
        RequestRefusal.MATRIX_INVALID -> R.string.contact_request_chat_invalid
        RequestRefusal.TOO_MANY -> R.string.contact_request_too_many
    }

    private companion object {
        const val STATE_OPEN = "contact_request_open"
        const val STATE_SENT = "contact_request_sent"
        const val STATE_SENT_NAME = "contact_request_sent_name"
    }
}
