package com.kidslauncher.mdm.day

import android.content.Context
import android.content.res.Configuration

/*
 * A colour per calendar (design 69, "A colour per calendar instead of a 'Skole:' prefix", 2026-10-27): the policy's
 * `calendar.calendars[].color` is one of eight names the server spells (its `calendar::COLORS`; the PWA's swatches
 * are these values), and Dagen draws it as the 8 dp dot in its own column before the time, in the day's key under the
 * list, and in the lock's reminder row. Pure but for [CalendarColor.argb]'s reading of a context's night bit;
 * CalendarPolicyTest, DayModelTest.
 */

/** A calendar's colour. The eight the parent may pick, and [OWN] - the kid's own calendar's peach (`kid_accent`),
 * never a policy value. Two tables since design 78 §4.2: [argb] for the night ground (the lock's glance, Kalender in
 * Mørk, the PWA's swatches) and [dayArgb], the same hue deep enough for 3:1 on Lys's white tile and paper ground
 * (KidPaletteTest). */
enum class CalendarColor(val wire: String?, val argb: Int, val dayArgb: Int) {
    SKY("sky", 0xFF6FA8DC.toInt(), 0xFF3A76B0.toInt()),
    FOREST("forest", 0xFF7FC28F.toInt(), 0xFF3C8650.toInt()),
    SUN("sun", 0xFFE8B86D.toInt(), 0xFFA87414.toInt()),
    BERRY("berry", 0xFFE8908C.toInt(), 0xFFC24E48.toInt()),
    SEA("sea", 0xFF7FC9C4.toInt(), 0xFF2B8580.toInt()),
    LAVENDER("lavender", 0xFFC98BD1.toInt(), 0xFF9455A2.toInt()),
    SAND("sand", 0xFFD9C4A6.toInt(), 0xFF8C7650.toInt()),
    SLATE("slate", 0xFFB0B0BC.toInt(), 0xFF6C6C7A.toInt()),

    /** The kid's own calendar (the parent's pick, or the phone's "Min kalender"): peach, whatever the policy says. */
    OWN(null, 0xFFF0A884.toInt(), 0xFFC2622C.toInt()),
    ;

    /** The dot on a ground of that look. */
    fun argb(night: Boolean): Int = if (night) argb else dayArgb

    /** The dot as [context] draws it - the only way Kalender's screens read a dot colour (PageTokensScanTest): the
     * day table only where the context's night bit says "no", which only a page in Lys does. */
    fun argb(context: Context): Int = argb(
        night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_NO,
    )

    companion object {
        /** The policy's name as a colour; `null` for none, JSON null, or a name this launcher doesn't know (a newer
         * server's): no dot, never a wrong one. */
        fun of(name: String?): CalendarColor? = name?.let { n -> entries.firstOrNull { it.wire == n } }
    }
}
