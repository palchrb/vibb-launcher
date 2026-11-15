package com.kidslauncher.mdm.shortcuts

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.kidslauncher.mdm.R

/** The panel's notification id (design 99 §2.1): the foreground service's own. */
const val SHORTCUTS_NOTIFICATION_ID = 1011

/** [ShortcutsReceiver]'s action and its only extra, the item's key (§2.1). */
const val ACTION_SHORTCUT = "com.kidslauncher.mdm.action.SHORTCUT"
const val EXTRA_SHORTCUT_KEY = "key"

private val CELLS = intArrayOf(R.id.shortcut_cell_0, R.id.shortcut_cell_1, R.id.shortcut_cell_2, R.id.shortcut_cell_3)
private val DISCS = intArrayOf(R.id.shortcut_disc_0, R.id.shortcut_disc_1, R.id.shortcut_disc_2, R.id.shortcut_disc_3)
private val GLYPHS = intArrayOf(R.id.shortcut_glyph_0, R.id.shortcut_glyph_1, R.id.shortcut_glyph_2, R.id.shortcut_glyph_3)
private val NAMES = intArrayOf(R.id.shortcut_name_0, R.id.shortcut_name_1, R.id.shortcut_name_2, R.id.shortcut_name_3)

/**
 * Snarveier's notification (design 99 §2.1, §3): decorated custom views - the icons folded, the same row with names
 * unfolded -, colorized in `kid_ground`, shown at once, ongoing and silent, secret on a lock screen, no content intent.
 * Each cell's tap is an explicit, immutable broadcast to [ShortcutsReceiver] with the key alone; Flymodus On is the one
 * activity, [ShortcutAirplaneActivity] (§4.1).
 */
object ShortcutsNotification {
    fun build(context: Context, channelId: String, panel: ShortcutsPanel): Notification {
        val names = panel.items.map { context.getString(nameRes(it)) }
        val sp = shortcutNameSp(names.map { widthAt10SpDp(context, it) }, cellDp(context))
        val folded = views(context, R.layout.shortcuts_row, panel, null, sp)
        val unfolded = views(context, R.layout.shortcuts_names, panel, names, sp)
        val ground = context.getColor(R.color.kid_ground)
        return Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(folded)
            .setCustomBigContentView(unfolded)
            .setColor(ground)
            .setColorized(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_STATUS)
            .setSubText(context.getString(R.string.shortcuts_subtext))
            .build()
    }

    /** The unfolded content's cell width: 220 dp at 320 dp (S7), the shade's margins and the header's icon off. */
    private fun cellDp(context: Context): Float =
        ((context.resources.configuration.screenWidthDp - 100).coerceAtLeast(160)) / SHORTCUTS_MAX.toFloat()

    /** A name's width at 10 sp in dp, in the layout's font (Nunito Bold, `@font/nunito_bold`) at this phone's font scale. */
    private fun widthAt10SpDp(context: Context, text: String): Float {
        val metrics = context.resources.displayMetrics
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = runCatching { context.resources.getFont(R.font.nunito_bold) }.getOrDefault(Typeface.SANS_SERIF)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics)
        }
        return paint.measureText(text) / metrics.density
    }

    private fun views(context: Context, layout: Int, panel: ShortcutsPanel, names: List<String>?, sp: Float): RemoteViews {
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
            if (names != null) {
                views.setTextViewText(NAMES[i], names[i])
                views.setTextViewTextSize(NAMES[i], TypedValue.COMPLEX_UNIT_SP, sp)
            }
        }
        return views
    }

    fun nameRes(item: ShortcutItem): Int = if (item.asked) R.string.shortcut_name_asked else when (item.key) {
        ShortcutKey.TORCH -> R.string.shortcut_name_torch
        ShortcutKey.SOUND -> R.string.shortcut_name_sound
        ShortcutKey.AIRPLANE -> R.string.shortcut_name_airplane
        ShortcutKey.WIFI -> R.string.shortcut_name_wifi
        ShortcutKey.BLUETOOTH -> R.string.shortcut_name_bluetooth
        ShortcutKey.MOBILE_DATA -> R.string.shortcut_name_mobile_data
        ShortcutKey.BRIGHTNESS -> R.string.shortcut_name_brightness
        ShortcutKey.MORE_TIME -> R.string.shortcut_name_more_time
        ShortcutKey.MUSIC -> R.string.shortcut_name_music
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

    /** TalkBack's words (§3.1): the full name and its state; a dimmed item says it isn't available now (Q99-L10). */
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
     * The cell's tap. Flymodus while off: the window that closes the shade first ([ShortcutAirplaneActivity], §4.1) -
     * the only `getActivity` here. Everything else: our own receiver with the key alone.
     */
    private fun tapIntent(context: Context, item: ShortcutItem): PendingIntent =
        if (item.key == ShortcutKey.AIRPLANE && !item.on) {
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
