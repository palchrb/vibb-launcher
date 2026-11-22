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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.calls.PhoneBookActivity
import com.kidslauncher.mdm.preferences.LauncherPreferences
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
 * runs its normal launch action. The kid's own order (2026-11-22, [arrangedTiles]): a long-press lifts any tile but
 * Settings - dragged, it moves and the drop stores the order; let go in place, an app shows the drawer's menu
 * (hide/rename) with "Flytt", any other tile goes straight to the move bar ([startMoving]: ◀ Ferdig ▶, Home's).
 */
@SuppressLint("NotifyDataSetChanged")
class HomeGridAdapter(
    private val activity: Activity,
    /** Home keeps a way to close the long-press menu and its rename dialog (design 16c), and the move bar. */
    private val onOverlay: (close: () -> Unit) -> Unit = {},
    /** The move bar's state while a tile moves by the buttons, `null` = hidden. */
    private val onMoving: (MoveBar?) -> Unit = {},
    /** Home's content is in front and resumed: only then does a drop open a menu or the move bar (code QA M1 - the lock's
     * night ground takes the content off the window, which ends a held tile in a CANCEL after Home's overlays closed). */
    private val inFront: () -> Boolean = { true },
) : RecyclerView.Adapter<HomeGridAdapter.ViewHolder>() {

    /** The tiles as shown: [submit]'s, arranged by [order]. */
    private var tiles: List<GridTile> = emptyList()

    /** The kid's order ([tileOrderId]s, [storedOrder]), on the phone only, beside the kid's own names. */
    private var order: List<String> = decodeOrder(LauncherPreferences.apps().homeOrder())

    /** What the long-press menu opened (the menu, its rename dialog): closed again when Home stops ([closeMenus]). */
    private val menuClosers = ArrayDeque<() -> Unit>()

    /** A finger is down on the grid (its own DOWN, not a D-pad's or TalkBack's long-press): only then a long-press
     * lifts the tile. Cleared at UP or CANCEL, or at the drop once the drag took the touch. */
    private var fingerDown = false
    private val fingerWatch = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> fingerDown = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> fingerDown = false
            }
            return false
        }
    }

    /** The tile a finger lifted, until it is let go ([dropped]); [Drag.moved]: it went past the touch slop. */
    private class Drag(val id: String, var moved: Boolean = false)

    private var drag: Drag? = null

    /** A [submit] that came during a drag, applied at the drop: the grid never changes under a finger. */
    private var pending: (() -> Unit)? = null

    /** The tile the move bar moves ([gridTileId]), `null` = none. */
    private var movingId: String? = null

    private var recycler: RecyclerView? = null
    private val touchHelper = ItemTouchHelper(DragCallback())
    private val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop

    /** A tile is lifted by a finger: Home's swipes stand aside. */
    val dragging: Boolean get() = drag != null
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
        if (drag != null) {
            pending = { submit(tiles, infos, icons, metrics, ink) }
            return
        }
        show(arrangedTiles(tiles, order), infos, icons, metrics, ink)
        // The moving tile left the grid (a rule, the parent): the bar goes with it.
        if (movingId != null) reportMoving()
    }

    private fun show(
        tiles: List<GridTile>,
        infos: Map<String, AbstractDetailedAppInfo>,
        icons: Map<String, Bitmap>,
        metrics: GridMetrics,
        ink: InkChoice,
    ) {
        val oldTiles = this.tiles
        val oldIcons = this.icons
        val labelPx = labelPxFor(tiles, metrics)
        if (labelPx != this.labelPx) {
            // For the device checks: the labels' own size against the shared one (a normal grid never shrinks).
            val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, metrics.labelSp, activity.resources.displayMetrics)
            android.util.Log.i("HomeGrid", "Labels: ${metrics.labelSp} sp = $base px, shared $labelPx px")
        }
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
            // Every tile but Settings (QA 08 #10: no menu). A finger's long-press ([fingerDown]) lifts the tile and the
            // drop decides ([dropped]); TalkBack's or a D-pad's long-press has no finger and opens the menu at once.
            view.setOnLongClickListener { v ->
                val tile = tiles.getOrNull(bindingAdapterPosition) ?: return@setOnLongClickListener false
                if (tile is GridTile.Settings) return@setOnLongClickListener false
                if (fingerDown && drag == null && recycler?.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                    endMoving()
                    touchHelper.startDrag(this)
                } else {
                    openMenu(v, tile)
                }
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
        lift(holder.itemView, gridTileId(tiles[position]) == movingId, smooth = false)
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

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
        recyclerView.addOnItemTouchListener(fingerWatch)
        touchHelper.attachToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        touchHelper.attachToRecyclerView(null)
        recyclerView.removeOnItemTouchListener(fingerWatch)
        recycler = null
    }

    /** A long-press let go in place, or TalkBack's: an app's menu with "Flytt", any other tile the move bar at once. */
    private fun openMenu(view: View, tile: GridTile) {
        val id = gridTileId(tile)
        val info = (tile as? GridTile.App)?.let { infos[it.app.key] }
        if (info == null) startMoving(id) else showAppContextMenu(activity, view, info, ::trackMenu) { startMoving(id) }
    }

    /** Home's overlays (closed at the lock, design 16c), and the menu's own list for [closeMenus]. */
    private fun trackMenu(close: () -> Unit) {
        onOverlay(close)
        menuClosers.addLast(close)
        while (menuClosers.size > MAX_MENU_CLOSERS) menuClosers.removeFirst()
    }

    /** Home stopped (an app over it): the long-press menu, its rename dialog and the move bar close. */
    fun closeMenus() {
        endMoving()
        while (menuClosers.isNotEmpty()) {
            val close = menuClosers.removeFirst()
            try {
                close()
            } catch (e: Exception) {
                android.util.Log.w("HomeGridAdapter", "Couldn't close the menu", e)
            }
        }
    }

    /** The move bar for the tile [id]: lifted until "Ferdig", Home's press, the lock or Home's stop ([endMoving]). */
    private fun startMoving(id: String) {
        endMoving()
        val index = tiles.indexOfFirst { gridTileId(it) == id }
        if (index < 0) return
        movingId = id
        liftShown(id, true)
        recycler?.scrollToPosition(index)
        onOverlay { endMoving() }
        reportMoving()
    }

    /** ◀ ([step] -1) or ▶ (+1): one step for the moving tile, stored at once. */
    fun stepMoving(step: Int) {
        val from = tiles.indexOfFirst { gridTileId(it) == movingId }
        val to = stepTarget(tiles, from, step) ?: return
        moveTile(from, to)
        storeShown()
        recycler?.scrollToPosition(to)
        reportMoving()
    }

    fun endMoving() {
        val id = movingId ?: return
        movingId = null
        liftShown(id, false)
        onMoving(null)
    }

    private fun reportMoving() {
        val index = tiles.indexOfFirst { gridTileId(it) == movingId }
        if (index < 0) endMoving() else onMoving(moveBar(tiles, index))
    }

    private fun moveTile(from: Int, to: Int) {
        tiles = movedItem(tiles, from, to)
        notifyItemMoved(from, to)
        // A moved cell isn't rebound: its column's gaps (GridGapDecoration) are worked out again.
        recycler?.invalidateItemDecorations()
    }

    private fun storeShown() {
        order = storedOrder(order, tiles.map(::tileOrderId))
        LauncherPreferences.apps().homeOrder(encodeOrder(order))
    }

    /** The finger let go, or the touch was cancelled (ItemTouchHelper says IDLE for both): a drag stores the order; a
     * long-press let go in place opens [openMenu] - only while Home's content is [inFront] (a CANCEL from the lock's
     * night ground opens nothing, code QA M1). A [submit] held during the drag comes now. */
    private fun dropped() {
        val done = drag ?: return
        drag = null
        fingerDown = false
        if (done.moved) storeShown()
        pending?.also { pending = null }?.invoke()
        if (done.moved || !inFront()) return
        val tile = tiles.firstOrNull { gridTileId(it) == done.id } ?: return
        // By item id: still found after a held update's notifyDataSetChanged (code QA L1).
        val view = ids[done.id]?.let { recycler?.findViewHolderForItemId(it) }?.itemView ?: return
        openMenu(view, tile)
    }

    private fun liftShown(id: String, on: Boolean) {
        val itemId = ids[id] ?: return
        recycler?.findViewHolderForItemId(itemId)?.itemView?.let { lift(it, on, smooth = true) }
    }

    /** A lifted tile is a little larger - no wobble, no glow (calm, adult-but-for-kids). */
    private fun lift(view: View, on: Boolean, smooth: Boolean) {
        val scale = if (on) LIFT_SCALE else 1f
        view.animate().cancel()
        if (smooth) {
            view.animate().scaleX(scale).scaleY(scale).setDuration(LIFT_MS).start()
        } else {
            view.scaleX = scale
            view.scaleY = scale
        }
    }

    /** The drag: started by the cell's own long-press ([ViewHolder]), never ItemTouchHelper's; Settings never moves and
     * nothing is dropped on it. */
    private inner class DragCallback : ItemTouchHelper.Callback() {
        private fun movable(holder: RecyclerView.ViewHolder) =
            tiles.getOrNull(holder.bindingAdapterPosition).let { it != null && it !is GridTile.Settings }

        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(if (movable(viewHolder)) ALL_DIRECTIONS else 0, 0)

        override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            movable(target)

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            moveTile(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (isCurrentlyActive && kotlin.math.hypot(dX, dY) > touchSlop) drag?.moved = true
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            // The drag exists only once the helper took the tile, so a refused start can't hold the grid's updates.
            val tile = viewHolder?.let { tiles.getOrNull(it.bindingAdapterPosition) }
            when (actionState) {
                ItemTouchHelper.ACTION_STATE_DRAG -> if (viewHolder != null && tile != null) {
                    drag = Drag(gridTileId(tile))
                    lift(viewHolder.itemView, true, smooth = true)
                }
                ItemTouchHelper.ACTION_STATE_IDLE -> dropped()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val tile = tiles.getOrNull(viewHolder.bindingAdapterPosition)
            lift(viewHolder.itemView, tile != null && gridTileId(tile) == movingId, smooth = true)
        }
    }

    private companion object {
        /** The payload of a badge-only change. */
        const val BADGE = "badge"

        const val LIFT_SCALE = 1.08f
        const val LIFT_MS = 120L
        const val MAX_MENU_CLOSERS = 4
        const val ALL_DIRECTIONS = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END
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

