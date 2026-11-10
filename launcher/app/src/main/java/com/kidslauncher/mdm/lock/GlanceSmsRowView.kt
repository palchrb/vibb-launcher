package com.kidslauncher.mdm.lock

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.badges.GlanceSmsRow
import com.kidslauncher.mdm.badges.glanceGroupText
import com.kidslauncher.mdm.databinding.ViewLockGlanceRowBinding
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.messages.MessagesScreens
import com.kidslauncher.mdm.ui.messages.contactOf
import com.kidslauncher.mdm.ui.messages.whoOf
import com.kidslauncher.mdm.ui.messages.whoOfAddress

/**
 * How an SMS conversation's row looks on the lock glance (design 77 §6.8, step 5) - calm, as the missed-call row (design
 * 25): the name as the message screens say it (the contact's name now, a sender name, the number, a group's members),
 * "Ny melding" / "3 nye meldinger" under it, the time of the newest unread message; at the parent's level Full text
 * for Meldinger (`lock_glance.sms`, design 82 - `smsLockLevel`) the newest text in the subtitle's place ("Bilde" for a picture alone, "Pappa: ..." in a
 * group, "Ukjent avsender: ..." for one the callback window let in) and the count after the name ("· 3 nye"). The
 * contact's Home avatar with a small teal message badge in the corner (the missed call's red phone badge's place);
 * without a contact the chat glyph on Home's Meldinger teal - both in the parent's glyph and colour where chosen (design
 * 82 D9, the row's [GlanceSmsRow.look]). One TalkBack text ("3 nye meldinger fra Mamma, ..., 14:05").
 */
internal object GlanceSmsRowView {
    /** Binds [row] into the reused [view] (every part it shows is set, the others hidden) and returns the TalkBack text;
     * [time] is the row's time as the panel shows times. */
    fun bind(activity: Activity, view: ViewLockGlanceRowBinding, row: GlanceSmsRow, time: String): String {
        val res = activity.resources
        val rules = MessagesScreens.rules()
        val name = MessagesScreens.name(activity, whoOf(row.conversation, rules))
        val text = line(activity, row)
        view.root.visibility = View.VISIBLE
        view.glanceRowTitle.text = name
        // "Full text"'s count after the name, as an app's row ("Mamma · 3 nye"); with the text off the subtitle says it.
        val suffix = if (text != null && row.count > 1) res.getQuantityString(R.plurals.glance_new_count, row.count, row.count) else null
        view.glanceRowSuffix.text = suffix?.let { activity.getString(R.string.glance_name_suffix, it) }
        view.glanceRowSuffix.visibility = if (suffix != null) View.VISIBLE else View.GONE
        view.glanceRowSubtitle.text = text ?: if (row.count == 1) {
            activity.getString(R.string.glance_sms_new)
        } else {
            res.getQuantityString(R.plurals.glance_sms_new_many, row.count, row.count)
        }
        view.glanceRowSubtitle.visibility = View.VISIBLE
        view.glanceRowTime.text = time
        val icon = view.glanceRowIcon
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.imageTintList = null
        icon.backgroundTintList = null
        val contact = contactOf(row.conversation, rules)
        if (contact != null) {
            KidAvatars.bindContact(icon, view.glanceRowInitial, contact, isEmergency = false, initialSp = 16f, thumb = true)
            badge(activity, view, row)
        } else {
            view.glanceRowInitial.visibility = View.GONE
            view.glanceRowBadge.visibility = View.GONE
            icon.scaleType = ImageView.ScaleType.CENTER
            icon.backgroundTintList = ColorStateList.valueOf(row.look.tileColor(activity))
            icon.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_ink))
            icon.setImageResource(row.look.glyph)
        }
        val from = if (row.count == 1) {
            activity.getString(R.string.glance_sms_from, name)
        } else {
            res.getQuantityString(R.plurals.glance_sms_from_many, row.count, row.count, name)
        }
        return listOfNotNull(from, text, time).joinToString(", ")
    }

    /** The newest message's line - only what [GlanceSmsRow] carries, which has a text or a picture only while the
     * parent's switch is on; `null` = the count instead. */
    private fun line(activity: Activity, row: GlanceSmsRow): String? {
        val shown = row.text ?: activity.getString(R.string.messages_picture).takeIf { row.picture } ?: return null
        val sender = row.sender?.let { MessagesScreens.name(activity, whoOfAddress(it, MessagesScreens.rules())) }
        val group = glanceGroupText(row.conversation.participants.size > 1, sender, shown) ?: shown
        return if (row.unknownSender) activity.getString(R.string.sms_unknown_sender_line, group) else group
    }

    /** The corner's message badge on a contact's avatar - the layout's own badge view, put in this look on a reused view.
     * The drawable's fill takes Meldinger's colour (design 82 D9); its ring in the row's colour stays. */
    private fun badge(activity: Activity, view: ViewLockGlanceRowBinding, row: GlanceSmsRow) {
        val badge = view.glanceRowBadge
        val pad = KidAvatars.dp(activity, 4f)
        badge.setBackgroundResource(R.drawable.bg_glance_sms_badge)
        (badge.background?.mutate() as? GradientDrawable)?.setColor(row.look.tileColor(activity))
        badge.setPadding(pad, pad, pad, pad)
        badge.scaleType = ImageView.ScaleType.FIT_CENTER
        badge.imageTintList = ColorStateList.valueOf(activity.getColor(R.color.kid_ink))
        badge.setImageResource(row.look.glyph)
        badge.visibility = View.VISIBLE
    }
}
