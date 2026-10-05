package com.kidslauncher.mdm.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.ui.list.apps.showAppContextMenu

/**
 * The home grid (mockup Main.dc.html): the phone book first, then round app icons with their
 * unread badge. What is shown is decided by [homeGrid]; tapping an app runs its normal launch
 * action, long-press shows the drawer's menu (hide/rename).
 */
@SuppressLint("NotifyDataSetChanged")
class HomeGridAdapter(private val activity: Activity) : RecyclerView.Adapter<HomeGridAdapter.ViewHolder>() {

    private var tiles: List<GridTile> = emptyList()
    private var infos: Map<String, AbstractDetailedAppInfo> = emptyMap()

    fun submit(tiles: List<GridTile>, infos: Map<String, AbstractDetailedAppInfo>) {
        this.tiles = tiles
        this.infos = infos
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tile_icon)
        val badge: TextView = view.findViewById(R.id.tile_badge)
        val label: TextView = view.findViewById(R.id.tile_label)

        init {
            view.setOnClickListener { v ->
                when (val tile = tiles.getOrNull(bindingAdapterPosition)) {
                    GridTile.PhoneBook -> activity.startActivity(PhoneBookActivity.intent(activity))
                    is GridTile.App -> {
                        val rect = Rect().also { v.getGlobalVisibleRect(it) }
                        infos[tile.app.key]?.getAction()?.invoke(activity, rect)
                    }
                    null -> {}
                }
            }
            view.setOnLongClickListener { v ->
                val tile = tiles.getOrNull(bindingAdapterPosition) as? GridTile.App ?: return@setOnLongClickListener false
                val info = infos[tile.app.key] ?: return@setOnLongClickListener false
                showAppContextMenu(activity, v, info)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_kid_tile, parent, false))

    override fun getItemCount() = tiles.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        val size = KidAvatars.dp(context, 60f)
        when (val tile = tiles[position]) {
            GridTile.PhoneBook -> {
                holder.icon.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.kid_phone_book))
                holder.icon.setImageResource(R.drawable.ic_kid_phone_book)
                val pad = KidAvatars.dp(context, 16f)
                holder.icon.setPadding(pad, pad, pad, pad)
                holder.label.setText(R.string.calls_phone_book)
                holder.itemView.contentDescription = context.getString(R.string.calls_phone_book)
                KidAvatars.bindBadge(holder.badge, 0)
            }
            is GridTile.App -> {
                holder.icon.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                holder.icon.setPadding(0, 0, 0, 0)
                val info = infos[tile.app.key]
                holder.icon.setImageDrawable(
                    info?.let { KidAvatars.roundAppIcon(context, tile.app.key, { it.getIcon(context) }, size) }
                )
                holder.label.text = tile.app.label
                KidAvatars.bindBadge(holder.badge, tile.badge)
                holder.itemView.contentDescription = if (tile.badge > 0) {
                    context.resources.getQuantityString(R.plurals.app_unread, tile.badge, tile.app.label, tile.badge)
                } else {
                    tile.app.label
                }
            }
        }
    }
}
