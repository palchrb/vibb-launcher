package com.kidslauncher.mdm.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.ui.kidsettings.KidSettingsActivity
import com.kidslauncher.mdm.ui.list.apps.showAppContextMenu

/**
 * The home grid (mockup Main.dc.html, design 08): the phone book first, then the apps as
 * coloured circles with their unread badge, then the kid's Settings. What is shown is decided by
 * [homeGrid], the sizes by [gridMetrics]. Icons arrive already rendered ([KidAvatars.renderAppIcon]
 * off the main thread); a missing one shows a grey circle until the next pass. Tapping an app
 * runs its normal launch action; long-press (apps only) shows the drawer's menu (hide/rename).
 */
@SuppressLint("NotifyDataSetChanged")
class HomeGridAdapter(private val activity: Activity) : RecyclerView.Adapter<HomeGridAdapter.ViewHolder>() {

    private var tiles: List<GridTile> = emptyList()
    private var infos: Map<String, AbstractDetailedAppInfo> = emptyMap()
    private var icons: Map<String, Bitmap> = emptyMap()
    private var metrics: GridMetrics = gridMetrics(MOCKUP_CONTENT_DP, 3)
    private var inkColor: Int = activity.getColor(R.color.kid_ink)

    fun submit(
        tiles: List<GridTile>,
        infos: Map<String, AbstractDetailedAppInfo>,
        icons: Map<String, Bitmap>,
        metrics: GridMetrics,
        ink: Int,
    ) {
        this.tiles = tiles
        this.infos = infos
        this.icons = icons
        this.metrics = metrics
        this.inkColor = ink
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val frame: FrameLayout = view.findViewById(R.id.tile_frame)
        val icon: ImageView = view.findViewById(R.id.tile_icon)
        val badge: TextView = view.findViewById(R.id.tile_badge)
        val label: TextView = view.findViewById(R.id.tile_label)

        init {
            view.setOnClickListener { v ->
                when (val tile = tiles.getOrNull(bindingAdapterPosition)) {
                    GridTile.PhoneBook -> activity.startActivity(PhoneBookActivity.intent(activity))
                    GridTile.Settings -> activity.startActivity(Intent(activity, KidSettingsActivity::class.java))
                    is GridTile.App -> {
                        val rect = Rect().also { v.getGlobalVisibleRect(it) }
                        infos[tile.app.key]?.getAction()?.invoke(activity, rect)
                    }
                    null -> {}
                }
            }
            // Apps only: the phone book and Settings tiles have no menu (QA 08 #10).
            view.setOnLongClickListener { v ->
                val tile = tiles.getOrNull(bindingAdapterPosition) as? GridTile.App ?: return@setOnLongClickListener false
                val info = infos[tile.app.key] ?: return@setOnLongClickListener false
                showAppContextMenu(activity, v, info)
                true
            }
        }

        /** Sizes from [gridMetrics]: icon, a frame 4 dp taller for the badge, label size. */
        fun applyMetrics(m: GridMetrics) {
            val context = itemView.context
            val iconPx = KidAvatars.dp(context, m.iconDp.toFloat())
            val framePx = KidAvatars.dp(context, minOf(m.cellWidthDp, m.iconDp + 24f))
            frame.layoutParams = frame.layoutParams.apply {
                width = framePx
                height = iconPx + KidAvatars.dp(context, 4f)
            }
            icon.layoutParams = (icon.layoutParams as FrameLayout.LayoutParams).apply {
                width = iconPx
                height = iconPx
            }
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.labelSp)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_kid_tile, parent, false))
            .also { it.applyMetrics(metrics) }

    override fun getItemCount() = tiles.size

    private fun glyphTile(holder: ViewHolder, color: Int, glyph: Int, label: Int) {
        val context = holder.itemView.context
        holder.icon.backgroundTintList = ColorStateList.valueOf(context.getColor(color))
        holder.icon.setImageResource(glyph)
        // The mockup's 28 dp glyph in a 60 dp circle.
        val pad = KidAvatars.dp(context, metrics.iconDp * 16f / 60f)
        holder.icon.setPadding(pad, pad, pad, pad)
        holder.label.setText(label)
        holder.itemView.contentDescription = context.getString(label)
        KidAvatars.bindBadge(holder.badge, 0)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        holder.applyMetrics(metrics)
        holder.label.setTextColor(inkColor)
        when (val tile = tiles[position]) {
            GridTile.PhoneBook -> glyphTile(holder, R.color.kid_phone_book, R.drawable.ic_kid_phone_book, R.string.calls_phone_book)
            GridTile.Settings -> glyphTile(holder, R.color.kid_settings_tile, R.drawable.ic_kid_settings, R.string.kid_settings_title)
            is GridTile.App -> {
                holder.icon.setPadding(0, 0, 0, 0)
                val bitmap = icons[tile.app.key]
                if (bitmap != null) {
                    holder.icon.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    holder.icon.setImageDrawable(KidAvatars.circular(context, bitmap))
                } else {
                    holder.icon.backgroundTintList = ColorStateList.valueOf(TILE_GREY)
                    holder.icon.setImageDrawable(null)
                }
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

/**
 * The grid's gaps (8 dp between columns, 14 dp between rows) without changing the cell widths
 * GridLayoutManager hands out: each column gives up the same width.
 */
class GridGapDecoration(private val columnGapPx: Int, private val rowGapPx: Int, private val columns: () -> Int) :
    RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val n = columns()
        val column = position % n
        outRect.left = column * columnGapPx / n
        outRect.right = columnGapPx - (column + 1) * columnGapPx / n
        outRect.top = if (position >= n) rowGapPx else 0
    }
}
