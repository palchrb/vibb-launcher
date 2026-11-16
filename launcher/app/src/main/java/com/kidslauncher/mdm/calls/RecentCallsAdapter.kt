package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.home.MissedCallText
import java.time.ZoneId
import java.time.format.TextStyle

/**
 * Siste (design 106 §3.6, B2): one flat list of day headings and call rows ([recentItems]). A day reads as one card -
 * each row's tile by its place in the day ([RowPlace]), a 1 dp divider between - on the page's tokens, so Mørk and Lys
 * follow. A row is TalkBack's one node, role button ("Mamma, 2 tapte anrop, i dag 14:02"); a tap opens the contact's
 * sheet ([onRow]), never a call.
 */
class RecentCallsAdapter(private val onRow: (RecentRow) -> Unit) : ListAdapter<RecentItem, RecyclerView.ViewHolder>(DIFF) {

    /** From font scale 1.3 the time moves under the word ([recentTimeBelow]); the same for every row. */
    var timeBelow: Boolean = false
        @android.annotation.SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** The name the kid sees for Element X (design 14's shown label): "Tapt anrop i Element X". */
    var elementLabel: String = ""

    override fun getItemViewType(position: Int): Int = if (getItem(position) is RecentItem.Day) TYPE_DAY else TYPE_CALL

    /** Every decoded photo: the avatars only. */
    fun photosChanged() = notifyItemRangeChanged(0, itemCount, PhoneBookAdapter.PHOTO)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY) {
            DayHolder(inflater.inflate(R.layout.item_recent_day, parent, false) as TextView)
        } else {
            CallHolder(inflater.inflate(R.layout.item_recent_call, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = getItem(position)
        if (payloads.isNotEmpty() && payloads.all { it == PhoneBookAdapter.PHOTO }) {
            if (holder is CallHolder && item is RecentItem.Call) holder.bindAvatar(item)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is RecentItem.Day -> (holder as DayHolder).bind(item)
            is RecentItem.Call -> (holder as CallHolder).bind(item)
        }
    }

    /** The row behind an adapter position (Siste's sightings), or `null` for a heading. */
    fun rowAt(position: Int): RecentRow? = (currentList.getOrNull(position) as? RecentItem.Call)?.row

    class DayHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(item: RecentItem.Day) {
            text.text = dayText(text.context, item.label)
        }
    }

    inner class CallHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val photo: ImageView = view.findViewById(R.id.recent_photo)
        private val initial: TextView = view.findViewById(R.id.recent_initial)
        private val name: TextView = view.findViewById(R.id.recent_name)
        private val glyph: ImageView = view.findViewById(R.id.recent_glyph)
        private val word: TextView = view.findViewById(R.id.recent_word)
        private val time: TextView = view.findViewById(R.id.recent_time)
        private val timeUnder: TextView = view.findViewById(R.id.recent_time_below)
        private val divider: View = view.findViewById(R.id.recent_divider)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) rowAt(position)?.let(onRow)
            }
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            })
        }

        fun bindAvatar(item: RecentItem.Call) {
            KidAvatars.bindContact(photo, initial, item.row.contact, item.emergency, initialSp = 15f, thumb = true)
        }

        fun bind(item: RecentItem.Call) {
            val context = itemView.context
            val row = item.row
            bindAvatar(item)
            name.text = row.contact.name
            val (glyphRes, colour) = when (row.kind) {
                RecentKind.MISSED -> R.drawable.ic_kid_missed_call to R.color.kid_page_alert
                RecentKind.IN -> R.drawable.ic_kid_call_in to R.color.kid_page_dim
                RecentKind.OUT -> R.drawable.ic_kid_call_out to R.color.kid_page_dim
            }
            glyph.setImageResource(glyphRes)
            glyph.imageTintList = ColorStateList.valueOf(context.getColor(colour))
            word.setTextColor(context.getColor(colour))
            word.text = shownWord(context, row)
            val clock = ClockFormat.formatTime(context, row.atMs)
            time.text = clock
            timeUnder.text = clock
            time.visibility = if (timeBelow) View.GONE else View.VISIBLE
            timeUnder.visibility = if (timeBelow) View.VISIBLE else View.GONE
            val (background, press) = when (item.place) {
                RowPlace.FIRST -> R.drawable.bg_recent_first to R.drawable.fg_recent_first
                RowPlace.MIDDLE -> R.drawable.bg_recent_middle to R.drawable.fg_recent_middle
                RowPlace.LAST -> R.drawable.bg_recent_last to R.drawable.fg_recent_last
                RowPlace.SINGLE -> R.drawable.bg_recent_single to R.drawable.fg_recent_single
            }
            itemView.setBackgroundResource(background)
            itemView.foreground = androidx.core.content.ContextCompat.getDrawable(context, press)
            val lastOfDay = item.place == RowPlace.LAST || item.place == RowPlace.SINGLE
            divider.visibility = if (lastOfDay) View.GONE else View.VISIBLE
            (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = if (lastOfDay) KidAvatars.dp(context, DAY_GAP_DP) else 0
                itemView.layoutParams = it
            }
            itemView.contentDescription = context.getString(
                R.string.recent_row, row.contact.name, spokenWord(context, row), MissedCallText.spokenWhen(context, row.atMs),
            )
        }

        private fun shownWord(context: Context, row: RecentRow): String {
            val res = context.resources
            return when (row.kind) {
                RecentKind.MISSED -> missedWord(context, row)
                RecentKind.IN -> res.getQuantityString(R.plurals.recent_in, row.count, row.count)
                RecentKind.OUT -> res.getQuantityString(R.plurals.recent_out, row.count, row.count)
            }
        }

        /** TalkBack's own plural for a merged row - "ringte deg 2 ganger", never the visible "(2)". */
        private fun spokenWord(context: Context, row: RecentRow): String {
            val res = context.resources
            return when (row.kind) {
                RecentKind.MISSED -> missedWord(context, row)
                RecentKind.IN -> res.getQuantityString(R.plurals.recent_described_in, row.count, row.count)
                RecentKind.OUT -> res.getQuantityString(R.plurals.recent_described_out, row.count, row.count)
            }
        }

        private fun missedWord(context: Context, row: RecentRow): String = when (row.source) {
            RecentSource.PHONE -> context.resources.getQuantityString(R.plurals.recent_missed, row.count, row.count)
            RecentSource.ELEMENT_X -> context.resources.getQuantityString(R.plurals.recent_missed_in_app, row.count, row.count, elementLabel)
        }
    }

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_CALL = 1

        /** The room under a day's last row (§3.6). */
        private const val DAY_GAP_DP = 10f

        private val DIFF = object : DiffUtil.ItemCallback<RecentItem>() {
            override fun areItemsTheSame(oldItem: RecentItem, newItem: RecentItem) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: RecentItem, newItem: RecentItem) = oldItem == newItem
        }

        /** A day heading's words (§3.2): "I dag", "I går", "Lørdag", "Mandag 3. nov." - the weekday and date from the
         * locale, capitalised. */
        fun dayText(context: Context, label: DayLabel): String {
            val locale = context.resources.configuration.locales[0]
            val text = when (label) {
                DayLabel.Today -> return context.getString(R.string.recent_today)
                DayLabel.Yesterday -> return context.getString(R.string.recent_yesterday)
                is DayLabel.Weekday -> label.day.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                is DayLabel.Date -> android.text.format.DateFormat.format(
                    android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMM"),
                    java.util.Date.from(label.date.atStartOfDay(ZoneId.systemDefault()).toInstant()),
                ).toString()
            }
            return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}
