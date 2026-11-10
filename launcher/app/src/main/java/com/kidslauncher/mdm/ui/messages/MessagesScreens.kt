package com.kidslauncher.mdm.ui.messages

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.ui.day.DayActivity
import com.kidslauncher.mdm.ui.home.KidAvatars
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * What the message screens share (design 77 §6.7): the way in, the time-rule check, and the words for the pure model's
 * names and times.
 */
object MessagesScreens {
    /** The rules in force (both channels), `null` while SMS isn't under rules we can read. */
    fun rules(): CallRules? = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules

    /**
     * Under a time rule the time-rule screen comes back on top unless the rule allows calls ([messagesUsableUnder],
     * "as the phone book"); an error reading the rules is no (the time-rule screen). True when it redirected.
     */
    fun redirectIfLocked(activity: Activity): Boolean {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false
        if (usableNow(activity)) return false
        LockActivity.start(activity)
        return true
    }

    fun usableNow(context: Context): Boolean = try {
        messagesUsableUnder(
            com.kidslauncher.mdm.timerules.TimeRulesRuntime.snapshot(context, com.kidslauncher.mdm.server.currentPolicyDecision().policy).lock,
        )
    } catch (e: Exception) {
        android.util.Log.w("MessagesScreens", "Couldn't read the time rules: ${e.javaClass.simpleName}")
        false
    }

    /** The name as the kid reads it. */
    fun name(context: Context, who: Who): String = whoTitle(who, context.getString(R.string.messages_unknown))

    /** A row's avatar: the contact's own (photo, colour, the emergency red), else an initial on the palette. */
    fun bindAvatar(
        photo: ImageView,
        initial: TextView,
        contact: RuleContact?,
        name: String,
        address: String,
        isEmergency: (String) -> Boolean = { CallSystem.isEmergencyOutgoing(photo.context, it) },
    ) {
        val shown = contact ?: RuleContact(id = 0, name = name, number = address)
        val emergency = contact != null && isEmergency(contact.number)
        KidAvatars.bindContact(photo, initial, shown, emergency, initialSp = 20f, thumb = true)
    }

    /** The list's time ([listTimeLabel]): the one clock format (P12), "I går", the weekday, the date. */
    fun timeText(context: Context, label: TimeLabel): String {
        val locale = context.resources.configuration.locales[0]
        return when (label) {
            is TimeLabel.Clock -> ClockFormat.formatClock(context, label.ms)
            TimeLabel.Yesterday -> context.getString(R.string.messages_yesterday)
            is TimeLabel.Weekday -> label.day.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
            is TimeLabel.Date -> {
                val skeleton = if (label.withYear) "dMMMy" else "dMMM"
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
                label.date.format(DateTimeFormatter.ofPattern(pattern, locale))
            }
        }
    }

    /** A day separator ([dayLabel]): "I dag", "I går", else Dagen's day line. */
    fun dayText(context: Context, label: DayLabel): String = when (label) {
        DayLabel.Today -> context.getString(R.string.messages_today)
        DayLabel.Yesterday -> context.getString(R.string.messages_yesterday)
        is DayLabel.Day -> DayActivity.dayLine(context, label.date)
    }

    /** The compose bar's place when there is no bar ([ComposeState]); `null` for the bar itself. */
    fun composeLine(state: ComposeState): Int? = when (state) {
        ComposeState.OPEN -> null
        ComposeState.READ_ONLY -> R.string.messages_read_only
        ComposeState.NOT_NOW -> R.string.messages_compose_not_now
        ComposeState.NOT_ALLOWED -> R.string.messages_compose_not_allowed
        ComposeState.UNAVAILABLE -> R.string.messages_compose_unavailable
        ComposeState.GROUP_NOT_ALLOWED -> R.string.messages_group_not_allowed
        ComposeState.GROUPS_OFF -> R.string.messages_groups_off
    }

    /** The toast after a send the gate refused ([afterRefusal], code QA L5; a retry's, [afterRetryRefusal]); `null` =
     * nothing to say. */
    fun refusalLine(after: AfterRefusal): Int? = when (after) {
        AfterRefusal.NOT_NOW -> R.string.messages_compose_not_now
        AfterRefusal.NOT_ALLOWED -> R.string.messages_compose_not_allowed
        AfterRefusal.UNAVAILABLE -> R.string.messages_compose_unavailable
        AfterRefusal.READ_ONLY -> R.string.messages_read_only
        AfterRefusal.TOO_LONG -> R.string.messages_too_long
        AfterRefusal.GROUPS_OFF -> R.string.messages_groups_off
        AfterRefusal.PICTURE -> R.string.messages_picture_unusable
        AfterRefusal.SEND_AGAIN, AfterRefusal.NOTHING -> null
    }

    /** Shows the in-screen confirm (D18: one confirm, built into the screen, no system dialog) and moves TalkBack to
     * it; [onDelete] after "Slett". Cancel and Back hide it ([hideConfirm]). */
    fun showConfirm(panel: View, title: String, onCancel: () -> Unit, onDelete: () -> Unit) {
        panel.findViewById<TextView>(R.id.messages_confirm_title).text = title
        panel.findViewById<View>(R.id.messages_confirm_cancel).setOnClickListener { onCancel() }
        panel.findViewById<View>(R.id.messages_confirm_delete).setOnClickListener { onDelete() }
        panel.visibility = View.VISIBLE
        // TalkBack's focus moves to the question (code QA N2), not only its announcement.
        val question = panel.findViewById<View>(R.id.messages_confirm_title)
        question.post { question.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
    }

    fun hideConfirm(panel: View) {
        panel.visibility = View.GONE
    }

    /** The messages task's way in (Home's tile, the time-rule screen's row): a task of its own, as Dagen's - in Recents
     * as "Meldinger", and never destroyed with the lock's task. */
    fun listIntent(context: Context): Intent =
        Intent(context, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * A thread: [conversationId] when it exists, else the (normalised) [number] it would be with - a contact's Message
     * button, "Ny melding", `smsto:` - with an optional [draft] that is never sent without a tap. Over the list, in the
     * messages task.
     */
    fun threadIntent(context: Context, conversationId: String?, number: String?, draft: String? = null): Intent =
        Intent(context, ThreadActivity::class.java).apply {
            conversationId?.let { putExtra(ThreadActivity.EXTRA_CONVERSATION, it) }
            number?.let { putExtra(ThreadActivity.EXTRA_NUMBER, it) }
            draft?.let { putExtra(ThreadActivity.EXTRA_DRAFT, it) }
        }

    /** A thread from outside the messages task (the contact sheet, `smsto:`, the notification): the list under it, so
     * Back goes to the list. The list is brought back as it was (singleTop) and anything above it closes. */
    fun startThread(context: Context, conversationId: String?, number: String?, draft: String? = null) {
        context.startActivities(arrayOf(listUnder(context), threadIntent(context, conversationId, number, draft)))
    }

    fun listUnder(context: Context): Intent = listIntent(context).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

/** Design 81 D13: "Nylig brukt" - the emoji the kid picked from the picker, newest first, kept on this phone only (the
 * launcher's own preferences, CE storage); never sent anywhere. */
object ReactionRecents {
    private const val FILE = "sms_reactions"
    private const val KEY = "recent"

    fun read(context: Context): List<String> =
        prefs(context).getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }?.take(RECENT_MAX).orEmpty()

    fun write(context: Context, recent: List<String>) {
        prefs(context).edit().putString(KEY, recent.take(RECENT_MAX).joinToString("\n")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
