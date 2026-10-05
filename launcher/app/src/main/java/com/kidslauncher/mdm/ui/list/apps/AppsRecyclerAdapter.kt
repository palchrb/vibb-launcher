package com.kidslauncher.mdm.ui.list.apps

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.apps.AppFilter

/**
 * A [RecyclerView] (efficient scrollable list) containing all apps on the users device.
 *
 * @param activity - the activity this is in
 */
@SuppressLint("NotifyDataSetChanged")
class AppsRecyclerAdapter(
    val activity: Activity,
    private var appFilter: AppFilter = AppFilter(activity),
) :
    RecyclerView.Adapter<AppsRecyclerAdapter.ViewHolder>() {


    private val apps = (activity.applicationContext as Application).apps
    private val appsListDisplayed: MutableList<AbstractDetailedAppInfo> = mutableListOf()

    init {
        apps.observe(this.activity as AppCompatActivity) {
            updateAppsList()
        }
        updateAppsList()
    }


    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnClickListener {
        var textView: TextView = itemView.findViewById(R.id.list_apps_row_name)
        var img: ImageView = itemView.findViewById(R.id.list_apps_row_icon)

        override fun onClick(v: View) {
            val rect = Rect()
            img.getGlobalVisibleRect(rect)
            selectItem(bindingAdapterPosition, rect)
        }

        init {
            itemView.setOnClickListener(this)
        }
    }


    override fun onBindViewHolder(viewHolder: ViewHolder, i: Int) {
        val appLabel = appsListDisplayed[i].getCustomLabel(activity)

        val appIcon = appsListDisplayed[i].getIcon(activity)

        viewHolder.img.setImageDrawable(appIcon.constantState?.newDrawable() ?: appIcon)

        viewHolder.textView.text = appLabel

        viewHolder.textView.setOnLongClickListener {
            showAppContextMenu(activity, viewHolder.img, appsListDisplayed[i]); true
        }
        viewHolder.img.setOnLongClickListener {
            showAppContextMenu(activity, viewHolder.img, appsListDisplayed[i]); true
        }
        // ensure onClicks are actually caught
        viewHolder.textView.setOnClickListener { viewHolder.onClick(viewHolder.textView) }
        viewHolder.img.setOnClickListener { viewHolder.onClick(viewHolder.img) }
    }

    override fun getItemCount(): Int {
        return appsListDisplayed.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view: View = inflater.inflate(R.layout.list_apps_row_variant_text, parent, false)
        val viewHolder = ViewHolder(view)
        return viewHolder
    }

    fun selectItem(pos: Int, rect: Rect = Rect()) {
        val appInfo = appsListDisplayed.getOrNull(pos) ?: return
        appInfo.getAction().invoke(activity, rect)
    }

    fun updateAppsList() {
        appsListDisplayed.clear()
        apps.value?.let { appsListDisplayed.addAll(appFilter(it)) }

        notifyDataSetChanged()
    }

}
