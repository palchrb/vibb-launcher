package com.kidslauncher.mdm.calls

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.home.KidAvatars

/** One phone-book tile: a contact, or the "Emergency call" tile when no rules can be read. */
sealed interface PhoneBookTile {
    data class Contact(val contact: RuleContact, val emergency: Boolean, val missed: MissedSummary?) : PhoneBookTile
    data object Emergency : PhoneBookTile
}

/** The phone book's grid of 64 dp avatars with missed-call badges (mockup PhoneBook.dc.html). */
@SuppressLint("NotifyDataSetChanged")
class PhoneBookAdapter(
    private val onContact: (PhoneBookTile.Contact) -> Unit,
    private val onEmergency: () -> Unit,
) : RecyclerView.Adapter<PhoneBookAdapter.ViewHolder>() {

    private var tiles: List<PhoneBookTile> = emptyList()

    fun submit(tiles: List<PhoneBookTile>) {
        this.tiles = tiles
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.contact_photo)
        val initial: TextView = view.findViewById(R.id.contact_initial)
        val badge: TextView = view.findViewById(R.id.contact_badge)
        val name: TextView = view.findViewById(R.id.contact_name)

        init {
            view.setOnClickListener {
                when (val tile = tiles.getOrNull(bindingAdapterPosition)) {
                    is PhoneBookTile.Contact -> onContact(tile)
                    PhoneBookTile.Emergency -> onEmergency()
                    null -> {}
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_kid_contact, parent, false)
        val context = parent.context
        // 64 dp avatars, three to a row.
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.findViewById<View>(R.id.contact_frame).layoutParams.apply {
            width = KidAvatars.dp(context, 76f)
            height = KidAvatars.dp(context, 70f)
        }
        view.findViewById<View>(R.id.contact_avatar).layoutParams.apply {
            width = KidAvatars.dp(context, 64f)
            height = KidAvatars.dp(context, 64f)
        }
        view.findViewById<TextView>(R.id.contact_name).textSize = 14f
        view.setPadding(0, KidAvatars.dp(context, 4f), 0, KidAvatars.dp(context, 12f))
        return ViewHolder(view)
    }

    override fun getItemCount() = tiles.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        when (val tile = tiles[position]) {
            is PhoneBookTile.Contact -> {
                KidAvatars.bindContact(holder.photo, holder.initial, tile.contact, tile.emergency, initialSp = 26f)
                val count = tile.missed?.count ?: 0
                KidAvatars.bindBadge(holder.badge, count)
                holder.name.text = tile.contact.name
                holder.itemView.contentDescription = if (count > 0) {
                    context.resources.getQuantityString(R.plurals.contact_missed, count, tile.contact.name, count)
                } else {
                    tile.contact.name
                }
            }
            PhoneBookTile.Emergency -> {
                KidAvatars.bindContact(
                    holder.photo, holder.initial, RuleContact(number = EMERGENCY_NUMBER), isEmergency = true, initialSp = 26f,
                )
                KidAvatars.bindBadge(holder.badge, 0)
                holder.name.setText(R.string.calls_emergency)
                holder.itemView.contentDescription = context.getString(R.string.calls_emergency)
            }
        }
    }

    companion object {
        /** An emergency number on every GSM phone (Telecom routes it to the preloaded dialer). */
        const val EMERGENCY_NUMBER = "112"
    }
}
