package com.kidslauncher.mdm.lock

import android.content.Context
import android.view.View
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ViewLockRuleCardBinding
import com.kidslauncher.mdm.ui.ClockFormat
import java.time.LocalDateTime

/**
 * The time rule's card (`view_lock_rule_card.xml`, design r13 §1.3) as both lock screens bind it - the glance page
 * ([GlancePanel]) and the time-rule screen (`ui/LockActivity`, launcher round L6: one card, one look): the kind's glyph,
 * line 1 [label] (the rule's name or the kind's), line 2 "Til 14:30" only with a certain end ([until]), line 3 [note]
 * (what still works - the time-rule screen's only; gone without one). TalkBack reads one text ("Skole, til 14:30", then
 * the note). Information only: no tap, no action.
 */
object RuleCardView {
    fun bind(context: Context, card: ViewLockRuleCardBinding, glyph: Int, label: String, until: LocalDateTime?, note: String? = null) {
        val view = card.root
        val time = until?.let { ClockFormat.formatUntil(context, it) }
        card.lockRuleCardGlyph.setImageResource(glyph)
        card.lockRuleCardTitle.text = label
        card.lockRuleCardUntil.visibility = if (time != null) View.VISIBLE else View.GONE
        if (time != null) card.lockRuleCardUntil.text = context.getString(R.string.lock_until, time)
        card.lockRuleCardNote.visibility = if (note != null) View.VISIBLE else View.GONE
        card.lockRuleCardNote.text = note
        val spoken = if (time != null) context.getString(R.string.glance_rule_card_description, label, time) else label
        view.contentDescription = if (note != null) context.getString(R.string.lock_rule_card_spoken_note, spoken, note) else spoken
        view.visibility = View.VISIBLE
    }
}
