package com.kidslauncher.mdm.ui.minimalist

import android.annotation.SuppressLint
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.calls.RuleContact

/**
 * The call rows above the apps on Home: "Phone book", then a row per contact the parent flagged
 * "Home" (and allowed for outgoing calls). Tapping a contact calls straight away. Nothing is shown
 * unless calls are managed and on. Same row style as the app list.
 */
@SuppressLint("NotifyDataSetChanged")
class ContactsHomeAdapter(private val activity: Activity) : RecyclerView.Adapter<ContactsHomeAdapter.ViewHolder>() {

    /** `null` = the phone-book row. */
    private val rows = mutableListOf<RuleContact?>()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.list_apps_row_name)

        init {
            itemView.setOnClickListener {
                when (val row = rows.getOrNull(bindingAdapterPosition)) {
                    null -> activity.startActivity(PhoneBookActivity.intent(activity))
                    else -> CallSystem.placeCall(activity, row.number)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.list_apps_row_variant_text, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.textView.text = when (val row = rows[position]) {
            null -> activity.getString(R.string.calls_home_phone_book)
            else -> activity.getString(R.string.calls_home_contact, row.name)
        }
    }

    override fun getItemCount() = rows.size

    fun update() {
        rows.clear()
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        if (rules != null && rules.callsEnabled) {
            rows += null
            rows += rules.homeContacts
        }
        notifyDataSetChanged()
    }
}
