package com.kidslauncher.mdm.ui.home

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.ui.kidsettings.KidSettingsActivity
import com.kidslauncher.mdm.ui.list.apps.showAppContextMenu
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.KidInk
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore

/**
 * The home grid (mockup Main.dc.html, design 08): the phone book first, Meldinger (design 77), then the apps as
 * coloured circles with their unread badge, then the flashlight (design 41) and the kid's Settings.
 * What is shown is decided by
 * [homeGrid], the sizes by [gridMetrics]. Icons arrive already rendered ([KidAvatars.renderAppIcon]
 * off the main thread); a missing one shows a grey circle until the next pass. Tapping an app
 * runs its normal launch action; long-press (apps only) shows the drawer's menu (hide/rename).
 */
@SuppressLint("NotifyDataSetChanged")
class HomeGridAdapter(
    private val activity: Activity,
    /** Home keeps a way to close the long-press menu and its rename dialog (design 16c). */
    private val onOverlay: (close: () -> Unit) -> Unit = {},
) : RecyclerView.Adapter<HomeGridAdapter.ViewHolder>() {

    private var tiles: List<GridTile> = emptyList()
    private var infos: Map<String, AbstractDetailedAppInfo> = emptyMap()
    private var icons: Map<String, Bitmap> = emptyMap()
    private var metrics: GridMetrics = gridMetrics(MOCKUP_CONTENT_DP, 3)
    private var ink: InkChoice = WallpaperStore.state.ink

    /** The one size of every label in the grid, in px ([sharedLabelPx]); 0 until the first [submit]. */
    private var labelPx: Float = 0f

    /** Nunito Bold, the labels' font (`item_kid_tile.xml`), for [labelPxFor]'s measuring. */
    private val labelPaint by lazy {
        android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.nunito_bold)
        }
    }

    /** A tile's label as [onBindViewHolder] shows it. */
    private fun labelOf(tile: GridTile): String = when (tile) {
        GridTile.PhoneBook -> activity.getString(R.string.calls_phone_book)
        is GridTile.Messages -> tile.look.shownName(activity)
        GridTile.Day -> activity.getString(R.string.day_title)
        GridTile.Settings -> activity.getString(R.string.kid_settings_title)
        is GridTile.App -> tile.app.label
    }

    /**
     * The grid's one label size (the Jelly Star's layout, 2026-11-20): every label on one line at the largest size at
     * which all of them fit the cell, the label's own [GridMetrics.labelSp] at most and [gridLabelFloorPx] at least.
     * The line is the cell less the label's 2 dp sides and 2 px for rounding; a label wider at the floor ends in "…".
     */
    private fun labelPxFor(tiles: List<GridTile>, metrics: GridMetrics): Float {
        val display = activity.resources.displayMetrics
        val basePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, metrics.labelSp, display)
        val linePx = (metrics.cellWidthDp - 4f) * display.density - 2f
        return sharedLabelPx(tiles.map(::labelOf), basePx, gridLabelFloorPx(basePx, display.density), linePx) { text, size ->
            labelPaint.textSize = size
            labelPaint.measureText(text)
        }
    }

    /** Stable ids per [gridTileId], handed out once per adapter (never a hash: ids must not clash). */
    private val ids = HashMap<String, Long>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = ids.getOrPut(gridTileId(tiles[position])) { ids.size.toLong() }

    /**
     * Launcher round 7 §4 (Fossify's drawer): one tile updates, not the grid - DiffUtil over the
     * tiles' ids ([gridTileId]) and [tileChange], a badge-only change as the [BADGE] payload. New
     * sizes or ink (and the first list) rebind every tile, as before. Home has no item animator,
     * so nothing moves.
     */
    fun submit(
        tiles: List<GridTile>,
        infos: Map<String, AbstractDetailedAppInfo>,
        icons: Map<String, Bitmap>,
        metrics: GridMetrics,
        ink: InkChoice,
    ) {
        val oldTiles = this.tiles
        val oldIcons = this.icons
        val labelPx = labelPxFor(tiles, metrics)
        val everything = oldTiles.isEmpty() || metrics != this.metrics || ink != this.ink || labelPx != this.labelPx
        this.labelPx = labelPx
        this.tiles = tiles
        this.infos = infos
        this.icons = icons
        this.metrics = metrics
        this.ink = ink
        if (everything) {
            notifyDataSetChanged()
            return
        }
        fun iconOf(tile: GridTile, map: Map<String, Bitmap>) = (tile as? GridTile.App)?.let { map[it.app.key] }
        fun change(oldPosition: Int, newPosition: Int): TileChange {
            val old = oldTiles[oldPosition]
            val new = tiles[newPosition]
            return tileChange(old, new, sameIcon = iconOf(old, oldIcons) === iconOf(new, icons))
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldTiles.size
            override fun getNewListSize() = tiles.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                gridTileId(oldTiles[oldItemPosition]) == gridTileId(tiles[newItemPosition])
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                change(oldItemPosition, newItemPosition) == TileChange.SAME
            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? =
                BADGE.takeIf { change(oldItemPosition, newItemPosition) == TileChange.BADGE }
        }, false).dispatchUpdatesTo(this)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val frame: FrameLayout = view.findViewById(R.id.tile_frame)
        val icon: ImageView = view.findViewById(R.id.tile_icon)
        val badge: TextView = view.findViewById(R.id.tile_badge)
        val label: TextView = view.findViewById(R.id.tile_label)

        init {
            // Design 34 P2: depth on the icon, the press, an oval ripple and a focus ring
            // (applyMetrics, built once per size); the listener never consumes, so the click,
            // long-press and Home's swipes are unchanged.
            PressFeedback.depth(icon)
            PressFeedback.attach(view, icon, scrolling = true)
            view.setOnClickListener { v ->
                when (val tile = tiles.getOrNull(bindingAdapterPosition)) {
                    GridTile.PhoneBook -> activity.startActivity(PhoneBookActivity.intent(activity))
                    // Meldinger's own task (design 77 §6.7), as Dagen's.
                    is GridTile.Messages -> activity.startActivity(com.kidslauncher.mdm.ui.messages.MessagesScreens.listIntent(activity))
                    // Dagen's own task (2026-10-27): the one intent the glance uses too.
                    GridTile.Day -> activity.startActivity(com.kidslauncher.mdm.ui.day.DayActivity.intent(activity))
                    // Kid Settings' own task (design 111), with the swipe's one intent.
                    GridTile.Settings -> activity.startActivity(KidSettingsActivity.intent(activity))
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
                showAppContextMenu(activity, v, info, onOverlay)
                true
            }
        }

        /** Sizes from [gridMetrics]: icon, a frame 4 dp taller for the badge; the label at the grid's shared size
         * ([labelPx], before the first [submit] the metrics' own). */
        fun applyMetrics(m: GridMetrics, sharedPx: Float) {
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
            if (sharedPx > 0f) {
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sharedPx)
            } else {
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, m.labelSp)
            }
            PressFeedback.cellBackground(itemView, iconPx, KidAvatars.dp(context, PressFeedback.TILE_ICON_TOP_DP))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_kid_tile, parent, false))
            .also { it.applyMetrics(metrics, labelPx) }

    override fun getItemCount() = tiles.size

    /** A recycled tile starts at rest, whatever its last gesture left (qa-34-s2-code L2). */
    override fun onViewRecycled(holder: ViewHolder) {
        PressFeedback.reset(holder.itemView)
    }

    /** [glyphTint]: the glyph's colour, `null` = its own white. */
    private fun glyphTile(holder: ViewHolder, color: Int, glyph: Int, label: Int, glyphTint: Int? = null) {
        val context = holder.itemView.context
        holder.icon.backgroundTintList = ColorStateList.valueOf(context.getColor(color))
        holder.icon.setImageResource(glyph)
        holder.icon.imageTintList = glyphTint?.let { ColorStateList.valueOf(context.getColor(it)) }
        // The mockup's 28 dp glyph in a 60 dp circle.
        val pad = KidAvatars.dp(context, metrics.iconDp * 16f / 60f)
        holder.icon.setPadding(pad, pad, pad, pad)
        holder.label.setText(label)
        holder.itemView.contentDescription = context.getString(label)
        KidAvatars.bindBadge(holder.badge, 0)
    }

    /** A badge-only change ([TileChange.BADGE]): the badge and the description, nothing else. */
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val tile = tiles[position]
        if (tile !is GridTile.App || payloads.isEmpty() || payloads.any { it != BADGE }) {
            onBindViewHolder(holder, position)
            return
        }
        bindAppBadge(holder, tile)
    }

    private fun bindAppBadge(holder: ViewHolder, tile: GridTile.App) {
        val context = holder.itemView.context
        KidAvatars.bindBadge(holder.badge, tile.badge)
        holder.itemView.contentDescription = if (tile.badge > 0) {
            context.resources.getQuantityString(R.plurals.app_unread, tile.badge, tile.app.label, tile.badge)
        } else {
            tile.app.label
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        holder.applyMetrics(metrics, labelPx)
        KidInk.label(holder.label, ink)
        // Only the flashlight has a state; a recycled view starts without one.
        holder.itemView.stateDescription = null
        when (val tile = tiles[position]) {
            GridTile.PhoneBook -> glyphTile(holder, R.color.kid_phone_book, R.drawable.ic_kid_phone_book, R.string.calls_phone_book)
            // Design 77 §6.7: the chat glyph on the teal tone, the unread total as its badge. Design 82 D9, W1: the
            // parent's glyph, colour and name where chosen.
            is GridTile.Messages -> {
                val name = tile.look.shownName(context)
                glyphTile(holder, R.color.kid_messages_tile, tile.look.glyph, R.string.messages_title)
                holder.icon.backgroundTintList = ColorStateList.valueOf(tile.look.tileColor(context))
                holder.label.text = name
                KidAvatars.bindBadge(holder.badge, tile.badge)
                holder.itemView.contentDescription = if (tile.badge > 0) {
                    context.resources.getQuantityString(R.plurals.app_unread, tile.badge, name, tile.badge)
                } else {
                    name
                }
            }
            GridTile.Day -> glyphTile(holder, R.color.kid_day_tile, R.drawable.app_glyph_calendar_month, R.string.day_title)
            GridTile.Settings -> glyphTile(holder, R.color.kid_settings_tile, R.drawable.ic_kid_settings, R.string.kid_settings_title)
            is GridTile.App -> {
                holder.icon.imageTintList = null
                holder.icon.setPadding(0, 0, 0, 0)
                val bitmap = icons[tile.app.key]
                if (bitmap != null) {
                    holder.icon.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                    holder.icon.setImageDrawable(KidAvatars.circular(context, bitmap))
                } else {
                    holder.icon.backgroundTintList = ColorStateList.valueOf(TONE_SLATE)
                    holder.icon.setImageDrawable(null)
                }
                holder.label.text = tile.app.label
                bindAppBadge(holder, tile)
            }
        }
    }

    private companion object {
        /** The payload of a badge-only change. */
        const val BADGE = "badge"
    }
}

/**
 * The grid's gaps (8 dp between columns, 14 dp between rows) without changing the cell widths
 * GridLayoutManager hands out: each column gives up the same width. The row gap is read at each layout: Home squeezes
 * it when the rows only just overflow ([rowGapToFit]).
 */
class GridGapDecoration(private val columnGapPx: Int, private val rowGapPx: () -> Int, private val columns: () -> Int) :
    RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val n = columns()
        val column = position % n
        outRect.left = column * columnGapPx / n
        outRect.right = columnGapPx - (column + 1) * columnGapPx / n
        outRect.top = if (position >= n) rowGapPx() else 0
    }
}

