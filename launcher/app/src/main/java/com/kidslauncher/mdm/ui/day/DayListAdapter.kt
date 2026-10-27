package com.kidslauncher.mdm.ui.day

import android.text.Layout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.day.DayRow
import com.kidslauncher.mdm.day.DayView
import com.kidslauncher.mdm.day.RowKind
import com.kidslauncher.mdm.day.secondLine
import com.kidslauncher.mdm.ui.ClockFormat

/** One day's rows (design 69 §2.1): all-day first, then the timed ones; the empty day is one calm tile; `null` = not
 * read yet (nothing shown). A tap opens the event's own page. */
class DayListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<DayRow> = emptyList()
    private var empty = false

    fun submit(view: DayView?) {
        rows = view?.let { it.allDay + it.timed }.orEmpty()
        empty = view?.empty == true
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (empty) 1 else rows.size

    override fun getItemViewType(position: Int): Int = if (empty) EMPTY else ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == EMPTY) {
            object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_day_empty, parent, false)) {}
        } else {
            Row(inflater.inflate(R.layout.item_day_event, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is Row) holder.bind(rows[position])
    }

    class Row(view: View) : RecyclerView.ViewHolder(view) {
        private val start: TextView = view.findViewById(R.id.day_event_start)
        private val end: TextView = view.findViewById(R.id.day_event_end)
        private val title: TextView = view.findViewById(R.id.day_event_title)
        private val second: TextView = view.findViewById(R.id.day_event_second)
        private val more: View = view.findViewById(R.id.day_event_more)
        private var cutByRule = false

        init {
            // The "›" shows when a text was cut: by the first-sentence rule, or by the two-line caps after layout.
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val cut = cutByRule || ellipsized(title) || ellipsized(second)
                val wanted = if (cut) View.VISIBLE else View.INVISIBLE
                if (more.visibility != wanted) more.visibility = wanted
            }
        }

        private fun ellipsized(text: TextView): Boolean {
            if (text.visibility != View.VISIBLE) return false
            val layout: Layout = text.layout ?: return false
            return layout.lineCount > 0 && layout.getEllipsisCount(layout.lineCount - 1) > 0
        }

        fun bind(row: DayRow) {
            val context = itemView.context
            val time = { ms: Long -> ClockFormat.formatTime(context, ms) }
            val arrow = context.getString(R.string.day_continues)
            val (from, until) = when (row.kind) {
                RowKind.ALL_DAY -> context.getString(R.string.day_all_day) to ""
                RowKind.TIMED -> time(row.begin) to if (row.end > row.begin) time(row.end) else ""
                RowKind.FIRST_DAY -> time(row.begin) to arrow
                RowKind.LAST_DAY -> arrow to time(row.end)
            }
            start.text = from
            start.maxLines = if (row.kind == RowKind.ALL_DAY) 2 else 1
            start.setTextColor(
                context.getColor(
                    when {
                        row.kind == RowKind.ALL_DAY -> R.color.kid_core_dim
                        row.ownedByVibb -> R.color.kid_day_mine
                        else -> R.color.kid_accent
                    },
                ),
            )
            end.text = until
            end.visibility = if (until.isEmpty()) View.GONE else View.VISIBLE
            title.text = row.title
            // The kid's own events say their calendar (§1b), every other the location, else the description's first
            // sentence; an ongoing one starts with "Pågår nå".
            val line = if (row.ownedByVibb && row.location.isBlank()) com.kidslauncher.mdm.day.Cut(row.calendarName, false)
            else secondLine(row.location, row.description)
            cutByRule = line.cut
            val shown = when {
                row.ongoing && line.text.isNotEmpty() -> context.getString(R.string.day_ongoing_with, line.text)
                row.ongoing -> context.getString(R.string.day_ongoing)
                else -> line.text
            }
            second.text = shown
            second.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            itemView.setBackgroundResource(if (row.ongoing) R.drawable.bg_day_event_now else R.drawable.bg_day_event)
            more.visibility = if (cutByRule) View.VISIBLE else View.INVISIBLE
            // One TalkBack node: "Pågår nå, 14:45 til 15:30, SFO: Leksehjelp".
            val spokenTime = if (until.isEmpty() || row.kind == RowKind.ALL_DAY) from
            else context.getString(R.string.day_spoken_time, from, until)
            val spoken = listOfNotNull(context.getString(R.string.day_ongoing).takeIf { row.ongoing }, spokenTime, row.title)
            itemView.contentDescription = spoken.joinToString(", ")
            ViewCompat.replaceAccessibilityAction(
                itemView,
                AccessibilityActionCompat.ACTION_CLICK,
                context.getString(R.string.day_open_event),
                null,
            )
            itemView.setOnClickListener { context.startActivity(EventActivity.intent(context, row)) }
        }
    }

    private companion object {
        const val EMPTY = 0
        const val ROW = 1
    }
}
