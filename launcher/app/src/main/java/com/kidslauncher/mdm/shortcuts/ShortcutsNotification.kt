package com.kidslauncher.mdm.shortcuts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.AnchorNotice
import com.kidslauncher.mdm.timerules.TimePart

/** [ShortcutsReceiver]'s action and its only extra, the item's key (§2.1). */
const val ACTION_SHORTCUT = "com.kidslauncher.mdm.action.SHORTCUT"
const val EXTRA_SHORTCUT_KEY = "key"

private val CELLS = intArrayOf(R.id.shortcut_cell_0, R.id.shortcut_cell_1, R.id.shortcut_cell_2, R.id.shortcut_cell_3)
private val DISCS = intArrayOf(R.id.shortcut_disc_0, R.id.shortcut_disc_1, R.id.shortcut_disc_2, R.id.shortcut_disc_3)
private val GLYPHS = intArrayOf(R.id.shortcut_glyph_0, R.id.shortcut_glyph_1, R.id.shortcut_glyph_2, R.id.shortcut_glyph_3)

/**
 * Snarveier's view of the anchor's notification (design 99 §2.1, §3; design 100 §3): decorated custom views - one row
 * of icons, no names (revision 3) -, colorized in `kid_ground`, shown at once, ongoing and silent, secret on a lock
 * screen, no content intent and no action (design 100 D1). On a day with a screen-time budget the big view has the time
 * line over the row ([time]'s text, r13's words) and the note under it ("Spurt kl. 14:02 · venter på svar", the
 * parent's answer: `AnchorNotice.noteText`); the folded view is the
 * row alone. The small icon is the Vibb mark (`ic_stat_vibb`, literal colours: design 100 M10), as the plain notice's (the user, 2026-11-16). [AnchorNotification] posts it as id
 * 1002, on the panel's channel.
 * Each cell's tap is an explicit, immutable broadcast to [ShortcutsReceiver] with the key alone; Flymodus On is the one
 * activity, [ShortcutAirplaneActivity] (§4.1).
 */
object ShortcutsNotification {
    fun build(context: Context, channelId: String, panel: ShortcutsPanel, time: TimePart): Notification {
        val ground = context.getColor(R.color.kid_ground)
        return Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_vibb)
            .setStyle(Notification.DecoratedCustomViewStyle())
            // The row both ways: Android shows the top notification unfolded (S2), and the kid sees no change in the
            // row when it folds. The time and the note only unfolded (the folded content is 48 dp, design 99 S8).
            .setCustomContentView(views(context, panel, R.layout.shortcuts_row))
            .setCustomBigContentView(bigViews(context, panel, time))
            .setColor(ground)
            .setColorized(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    /** The big view: without a budget today (or in the pause) exactly the row; else the time line, the note line when
     * there is one, and the row (`shortcuts_panel_big.xml`). Text only, never a button (D1). */
    private fun bigViews(context: Context, panel: ShortcutsPanel, time: TimePart): RemoteViews {
        val text = time.text ?: return views(context, panel, R.layout.shortcuts_row)
        val views = views(context, panel, R.layout.shortcuts_panel_big)
        views.setTextViewText(R.id.shortcut_time_line, AnchorNotice.timeText(context, text))
        val note = AnchorNotice.noteText(context, time.note)
        if (note != null) {
            views.setTextViewText(R.id.shortcut_note_line, note)
            views.setViewVisibility(R.id.shortcut_note_line, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.shortcut_note_line, View.GONE)
        }
        return views
    }

    private fun views(context: Context, panel: ShortcutsPanel, layout: Int): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        val ground = context.getColor(R.color.kid_ground)
        val white = 0xFFFFFFFF.toInt()
        for (i in 0 until SHORTCUTS_MAX) {
            val item = panel.items.getOrNull(i)
            if (item == null) {
                // An empty quarter keeps its room: a button never moves when another leaves.
                views.setViewVisibility(CELLS[i], View.INVISIBLE)
                continue
            }
            views.setViewVisibility(CELLS[i], View.VISIBLE)
            views.setImageViewResource(DISCS[i], if (item.on) R.drawable.shortcut_disc_on else R.drawable.shortcut_disc_off)
            views.setImageViewResource(GLYPHS[i], glyphRes(item))
            views.setInt(GLYPHS[i], "setColorFilter", if (item.on) ground else white)
            views.setFloat(CELLS[i], "setAlpha", if (item.busy) 0.5f else if (!item.usable) 0.4f else 1.0f)
            views.setContentDescription(CELLS[i], description(context, item))
            views.setOnClickPendingIntent(CELLS[i], tapIntent(context, item))
        }
        return views
    }

    fun fullNameRes(key: ShortcutKey): Int = when (key) {
        ShortcutKey.TORCH -> R.string.shortcut_full_torch
        ShortcutKey.SOUND -> R.string.shortcut_full_sound
        ShortcutKey.AIRPLANE -> R.string.shortcut_full_airplane
        ShortcutKey.WIFI -> R.string.shortcut_full_wifi
        ShortcutKey.BLUETOOTH -> R.string.shortcut_full_bluetooth
        ShortcutKey.MOBILE_DATA -> R.string.shortcut_full_mobile_data
        ShortcutKey.BRIGHTNESS -> R.string.shortcut_full_brightness
        ShortcutKey.MORE_TIME -> R.string.shortcut_full_more_time
        ShortcutKey.MUSIC -> R.string.shortcut_full_music
    }

    /** The glyph: Lyd's shows the mode (vibrate when off), Lysstyrke's the step; the disc's colour says on or off. */
    fun glyphRes(item: ShortcutItem): Int = when (item.key) {
        ShortcutKey.TORCH -> R.drawable.torch_glyph_on
        ShortcutKey.SOUND -> if (item.on) R.drawable.shortcut_glyph_sound else R.drawable.shortcut_glyph_vibrate
        ShortcutKey.AIRPLANE -> R.drawable.shortcut_glyph_airplane
        ShortcutKey.WIFI -> R.drawable.shortcut_glyph_wifi
        ShortcutKey.BLUETOOTH -> R.drawable.shortcut_glyph_bluetooth
        ShortcutKey.MOBILE_DATA -> R.drawable.shortcut_glyph_mobile_data
        ShortcutKey.BRIGHTNESS -> when (item.level) {
            BrightnessStep.LOW -> R.drawable.shortcut_glyph_brightness_low
            BrightnessStep.HIGH -> R.drawable.shortcut_glyph_brightness_high
            else -> R.drawable.shortcut_glyph_brightness_medium
        }
        ShortcutKey.MORE_TIME -> R.drawable.shortcut_glyph_more_time
        ShortcutKey.MUSIC -> R.drawable.shortcut_glyph_music
    }

    /** TalkBack's words (§3.1): the full name and its state; a dimmed item says it isn't available now (Q99-L10). The
     * names live here and in the picker only (revision 3: none under the buttons). */
    private fun description(context: Context, item: ShortcutItem): String {
        val name = context.getString(fullNameRes(item.key))
        return when {
            item.busy -> context.getString(R.string.shortcut_desc_busy, name)
            item.asked -> context.getString(R.string.shortcut_desc_asked, name)
            !item.usable -> context.getString(R.string.shortcut_desc_unavailable, name)
            item.key == ShortcutKey.MORE_TIME -> context.getString(R.string.shortcut_desc_ask, name)
            item.key == ShortcutKey.BRIGHTNESS -> context.getString(
                R.string.shortcut_desc_level,
                name,
                context.getString(
                    when (item.level) {
                        BrightnessStep.LOW -> R.string.shortcut_level_low
                        BrightnessStep.HIGH -> R.string.shortcut_level_high
                        else -> R.string.shortcut_level_medium
                    },
                ),
            )
            item.key == ShortcutKey.SOUND && !item.on -> context.getString(R.string.shortcut_desc_sound_off, name)
            item.on -> context.getString(R.string.shortcut_desc_on, name)
            else -> context.getString(R.string.shortcut_desc_off, name)
        }
    }

    /**
     * The cell's tap. Flymodus while off, usable and not busy ([shortcutOpensWindow]): the window that closes the shade
     * first ([ShortcutAirplaneActivity], §4.1) - the only `getActivity` here. Everything else, a dimmed or busy Flymodus
     * too (C99-M1: a dimmed tap does nothing and leaves the shade open, D10): our own receiver with the key alone.
     */
    private fun tapIntent(context: Context, item: ShortcutItem): PendingIntent =
        if (shortcutOpensWindow(item)) {
            PendingIntent.getActivity(
                context,
                AIRPLANE_WINDOW_REQUEST,
                Intent(context, ShortcutAirplaneActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                item.key.ordinal,
                Intent(context, ShortcutsReceiver::class.java).setAction(ACTION_SHORTCUT).putExtra(EXTRA_SHORTCUT_KEY, item.key.wire),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private const val AIRPLANE_WINDOW_REQUEST = 100
}
