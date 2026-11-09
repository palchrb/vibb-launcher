package com.kidslauncher.mdm.calls

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.home.ContactBadge
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.home.MissedCallText
import com.kidslauncher.mdm.ui.home.PressFeedback
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.KidInk

/** One shown row: the model's [row], a contact's buttons ([actions], the card's rule) and the app a Melding leaves
 * Vibb for ([messageApp], TalkBack's "… i Element X"; `null` = our own thread or none). */
data class PhoneBookItem(val row: PhoneBookRow, val actions: ContactActions? = null, val messageApp: String? = null)

/**
 * The phone book's list (design 79 §3): contact rows with Ring and Melding, a request's answer, the "Venter på svar"
 * label and the open requests, or the fail-closed 112 row. A diff over the rows' stable keys, no item animator (the
 * activity), a decoded photo rebinds only the avatars ([PHOTO]). The row is TalkBack's one node: the full sentence, Ring
 * and Melding as its custom actions; the two buttons are focusable for a keyboard only.
 */
class PhoneBookAdapter(
    private val onContact: (PhoneBookRow.Contact) -> Unit,
    private val onCall: (PhoneBookItem) -> Unit,
    private val onMessage: (PhoneBookItem) -> Unit,
    private val onEmergencyDialer: () -> Unit,
) : ListAdapter<PhoneBookItem, RecyclerView.ViewHolder>(DIFF) {

    /** Where the buttons sit ([rowLayout]): the same for every row of the page. */
    var layout: RowLayout = RowLayout.INLINE
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            // A new view type: every contact row is made again in the other arrangement.
            notifyDataSetChanged()
        }

    /** The wallpaper's ink for the one text on it, the "Venter på svar" label. */
    var ink: InkChoice? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = stableIds.getOrPut(getItem(position).row.key) { stableIds.size.toLong() }

    private val stableIds = HashMap<String, Long>()

    override fun getItemViewType(position: Int): Int = when (getItem(position).row) {
        is PhoneBookRow.Contact, PhoneBookRow.EmergencyDialer -> if (layout == RowLayout.STACKED) TYPE_CONTACT_STACKED else TYPE_CONTACT
        is PhoneBookRow.Answer, is PhoneBookRow.Waiting -> TYPE_REQUEST
        PhoneBookRow.WaitingLabel -> TYPE_LABEL
    }

    /** Every decoded photo: the avatars only. */
    fun photosChanged() = notifyItemRangeChanged(0, itemCount, PHOTO)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CONTACT, TYPE_CONTACT_STACKED -> ContactHolder(
                inflater.inflate(R.layout.item_phone_book_row, parent, false),
                if (viewType == TYPE_CONTACT_STACKED) RowLayout.STACKED else RowLayout.INLINE,
            )
            TYPE_REQUEST -> RequestHolder(inflater.inflate(R.layout.item_phone_book_request, parent, false))
            else -> LabelHolder(inflater.inflate(R.layout.item_phone_book_label, parent, false) as TextView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PHOTO }) {
            when (holder) {
                is ContactHolder -> holder.bindAvatar(getItem(position).row)
                is RequestHolder -> holder.bindAvatar(getItem(position).row)
            }
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ContactHolder -> holder.bind(item)
            is RequestHolder -> holder.bind(item.row)
            is LabelHolder -> ink?.let { KidInk.label(holder.label, it, dim = true) }
        }
    }

    /** A recycled row's buttons start at rest, whatever their last press left. */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ContactHolder) {
            PressFeedback.reset(holder.call)
            PressFeedback.reset(holder.message)
        }
    }

    inner class ContactHolder(view: View, private val rowLayout: RowLayout) : RecyclerView.ViewHolder(view) {
        private val photo: ImageView = view.findViewById(R.id.contact_photo)
        private val initial: TextView = view.findViewById(R.id.contact_initial)
        private val badge: TextView = view.findViewById(R.id.contact_badge)
        private val name: TextView = view.findViewById(R.id.row_name)
        private val line: TextView = view.findViewById(R.id.row_line)
        private val buttons: LinearLayout = view.findViewById(R.id.row_buttons)
        val call: View = view.findViewById(R.id.row_call)
        private val callDisc: ImageView = view.findViewById(R.id.row_call_disc)
        val message: View = view.findViewById(R.id.row_message)
        private val actionIds = mutableListOf<Int>()

        init {
            val context = view.context
            if (rowLayout == RowLayout.STACKED) {
                // Large text: the buttons get their own line under the text (design 79 §3.4).
                (buttons.parent as ViewGroup).removeView(buttons)
                view.findViewById<LinearLayout>(R.id.row_stack).apply {
                    addView(buttons)
                    visibility = View.VISIBLE
                }
                (view.findViewById<View>(R.id.row_text).layoutParams as ViewGroup.MarginLayoutParams).marginEnd =
                    KidAvatars.dp(context, ROW_STACKED_END_DP)
            }
            val disc = KidAvatars.dp(context, BUTTON_DISC_DP)
            val inset = KidAvatars.dp(context, (BUTTON_TARGET_DP - BUTTON_DISC_DP) / 2)
            for ((cell, target) in listOf(call to callDisc, message to view.findViewById<View>(R.id.row_message_disc))) {
                // The press on the disc (visual only, design 34's PressMotion), an oval ripple and a peach focus ring.
                PressFeedback.cellBackground(cell, disc, inset)
                PressFeedback.attach(cell, target, scrolling = true)
            }
            view.setOnClickListener {
                when (val row = currentRow()) {
                    is PhoneBookRow.Contact -> onContact(row)
                    PhoneBookRow.EmergencyDialer -> onEmergencyDialer()
                    else -> {}
                }
            }
            call.setOnClickListener { currentItem()?.let(onCall) }
            message.setOnClickListener { currentItem()?.let(onMessage) }
            // One node, role button: the sentence is its description, Ring and Melding its custom actions.
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            })
        }

        private fun currentItem(): PhoneBookItem? = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { getItem(it) }

        private fun currentRow(): PhoneBookRow? = currentItem()?.row

        fun bindAvatar(row: PhoneBookRow) {
            when (row) {
                is PhoneBookRow.Contact -> KidAvatars.bindContact(photo, initial, row.contact, row.emergency, initialSp = 18f, thumb = true)
                PhoneBookRow.EmergencyDialer -> KidAvatars.bindContact(
                    photo, initial, RuleContact(number = EMERGENCY_NUMBER), isEmergency = true, initialSp = 18f,
                )
                else -> {}
            }
        }

        fun bind(item: PhoneBookItem) {
            val context = itemView.context
            bindAvatar(item.row)
            actionIds.forEach { ViewCompat.removeAccessibilityAction(itemView, it) }
            actionIds.clear()
            when (val row = item.row) {
                PhoneBookRow.EmergencyDialer -> {
                    KidAvatars.bindContactBadge(badge, ContactBadge(0, 0))
                    name.setText(R.string.calls_emergency)
                    line.visibility = View.GONE
                    buttons.visibility = View.GONE
                    itemView.contentDescription = context.getString(R.string.calls_emergency)
                }
                is PhoneBookRow.Contact -> bindContact(context, row, item)
                else -> {}
            }
        }

        private fun bindContact(context: Context, row: PhoneBookRow.Contact, item: PhoneBookItem) {
            val contact = row.contact
            val badgeCounts = ContactBadge(row.missed?.count ?: 0, row.unread)
            KidAvatars.bindContactBadge(badge, badgeCounts)
            name.text = com.kidslauncher.mdm.ui.search.highlightMatch(context, contact.name, row.match)
            val text = rowLine(context, row)
            line.visibility = if (text == null) View.GONE else View.VISIBLE
            line.text = text
            buttons.visibility = View.VISIBLE
            val actions = item.actions ?: ContactActions(callEnabled = true, message = MessageAction.None)
            callDisc.setBackgroundResource(if (row.emergency) R.drawable.bg_phone_book_button_emergency else R.drawable.bg_phone_book_button)
            callDisc.imageTintList = android.content.res.ColorStateList.valueOf(
                context.getColor(if (row.emergency) R.color.kid_emergency_text else R.color.kid_call_glyph),
            )
            // Dimmed and inert while another call is live (an emergency number excepted).
            call.isEnabled = actions.callEnabled
            call.alpha = if (actions.callEnabled) 1f else 0.4f
            val hasMessage = actions.message != MessageAction.None
            message.visibility = when {
                hasMessage -> View.VISIBLE
                rowLayout == RowLayout.STACKED -> View.GONE
                // Inline, the empty slot keeps every Ring in one column.
                else -> View.INVISIBLE
            }
            itemView.contentDescription = sentence(context, row)
            if (actions.callEnabled) {
                actionIds += ViewCompat.addAccessibilityAction(itemView, context.getString(R.string.phone_book_action_call, contact.name)) { _, _ ->
                    onCall(item)
                    true
                }
            }
            if (hasMessage) {
                val label = item.messageApp?.let { context.getString(R.string.phone_book_action_message_in, contact.name, it) }
                    ?: context.getString(R.string.phone_book_action_message, contact.name)
                actionIds += ViewCompat.addAccessibilityAction(itemView, label) { _, _ ->
                    onMessage(item)
                    true
                }
            }
        }
    }

    inner class RequestHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val photo: ImageView = view.findViewById(R.id.request_photo)
        private val initial: TextView = view.findViewById(R.id.request_initial)
        private val name: TextView = view.findViewById(R.id.request_name)
        private val line: TextView = view.findViewById(R.id.request_line)

        fun bindAvatar(row: PhoneBookRow) {
            val request = when (row) {
                is PhoneBookRow.Answer -> row.request
                is PhoneBookRow.Waiting -> row.request
                else -> return
            }
            // Design 79 §5.5: an open request's picture (the kid's, on the phone only), decoded off the main thread.
            val picture = if (row is PhoneBookRow.Waiting && request.photo) {
                ContactPhotos.cachedFile(itemView.context, "req:${request.id}", thumb = true) { java.io.File(ContactRequests.photoDir(it), "${request.id}.jpg") }
            } else {
                null
            }
            photo.setImageDrawable(picture?.let { KidAvatars.circular(itemView.context, it) })
            initial.visibility = if (picture == null) View.VISIBLE else View.GONE
            initial.text = request.name.trim().take(1).uppercase()
            initial.setTextColor(itemView.context.getColor(if (row is PhoneBookRow.Waiting) R.color.kid_accent else R.color.kid_core_dim))
        }

        fun bind(row: PhoneBookRow) {
            val context = itemView.context
            bindAvatar(row)
            val (request, match) = when (row) {
                is PhoneBookRow.Answer -> row.request to null
                is PhoneBookRow.Waiting -> row.request to row.match
                else -> return
            }
            itemView.setBackgroundResource(if (row is PhoneBookRow.Waiting) R.drawable.bg_phone_book_waiting else R.drawable.bg_phone_book_answer)
            name.text = com.kidslauncher.mdm.ui.search.highlightMatch(context, request.name, match)
            val words = context.getString(requestLineWords(request.line))
            line.text = words
            itemView.contentDescription = context.getString(R.string.contact_request_row_description, request.name, words)
        }
    }

    class LabelHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    companion object {
        /** An emergency number on every GSM phone (Telecom routes it to the preloaded dialer). */
        const val EMERGENCY_NUMBER = "112"

        /** The payload of a photo that finished decoding: only the avatars rebind. */
        const val PHOTO = "photo"

        private const val TYPE_CONTACT = 0
        private const val TYPE_REQUEST = 1
        private const val TYPE_LABEL = 2
        private const val TYPE_CONTACT_STACKED = 3
        private const val BUTTON_DISC_DP = 38f
        private const val BUTTON_TARGET_DP = 48f

        private val DIFF = object : DiffUtil.ItemCallback<PhoneBookItem>() {
            override fun areItemsTheSame(oldItem: PhoneBookItem, newItem: PhoneBookItem) = oldItem.row.key == newItem.row.key
            override fun areContentsTheSame(oldItem: PhoneBookItem, newItem: PhoneBookItem) = oldItem == newItem
        }

        /** The words of a request's line. */
        fun requestLineWords(line: RequestLine): Int = when (line) {
            RequestLine.WAITING -> R.string.contact_request_waiting
            RequestLine.NOT_ADDED -> R.string.contact_request_not_added
            RequestLine.CAPPED -> R.string.contact_request_capped
            RequestLine.KNOWN -> R.string.contact_request_known
            RequestLine.NOT_SENT -> R.string.contact_request_not_sent
        }

        /**
         * The row's second line (design 79 §3.4), short - the full sentence stays on the card and in TalkBack:
         * "Nødnummer"; else the unseen missed calls in red ("2 tapte anrop 14:02"), then " · 1 ulest" in the dim tone; or
         * alone "1 ulest melding"; else none.
         */
        fun rowLine(context: Context, row: PhoneBookRow.Contact): CharSequence? {
            if (row.emergency) return context.getString(R.string.phone_book_emergency_line)
            val missed = row.missed?.takeIf { it.count > 0 }
            if (missed == null) {
                return context.resources.getQuantityString(R.plurals.contact_unread, row.unread, row.unread).takeIf { row.unread > 0 }
            }
            val out = SpannableStringBuilder()
            val red = MissedCallText.short(context, missed.count, missed.lastAtMs)
            out.append(red, ForegroundColorSpan(context.getColor(R.color.kid_missed_text)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (row.unread > 0) out.append(" · ").append(context.resources.getQuantityString(R.plurals.contact_unread_short, row.unread, row.unread))
            return out
        }

        /** TalkBack's sentence (design 79 §3.6): "Pappa, 2 tapte anrop i dag 14:02, 1 ulest melding"; "Ambulanse, nødnummer". */
        fun sentence(context: Context, row: PhoneBookRow.Contact): String {
            val name = row.contact.name
            if (row.emergency) return context.getString(R.string.phone_book_emergency_description, name)
            return listOfNotNull(
                name,
                KidAvatars.missedText(context, row.missed)?.replace("\n", ", "),
                context.resources.getQuantityString(R.plurals.contact_unread, row.unread, row.unread).takeIf { row.unread > 0 },
            ).joinToString(", ")
        }
    }
}
