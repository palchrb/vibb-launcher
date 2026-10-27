package com.kidslauncher.mdm.day

/*
 * A colour per calendar (design 69, "A colour per calendar instead of a 'Skole:' prefix", 2026-10-27): the policy's
 * `calendar.calendars[].color` is one of eight names the server spells (its `calendar::COLORS`; the PWA's swatches
 * are these values), and Dagen draws it as the 8 dp dot in its own column before the time, in the day's key under the
 * list, and in the lock's reminder row. Pure; CalendarPolicyTest, DayModelTest.
 */

/** A calendar's colour. The eight the parent may pick, and [OWN] - the kid's own calendar's peach (`kid_accent`),
 * never a policy value. Dagen draws on the night ground only, so one table. */
enum class CalendarColor(val wire: String?, val argb: Int) {
    SKY("sky", 0xFF6FA8DC.toInt()),
    FOREST("forest", 0xFF7FC28F.toInt()),
    SUN("sun", 0xFFE8B86D.toInt()),
    BERRY("berry", 0xFFE8908C.toInt()),
    SEA("sea", 0xFF7FC9C4.toInt()),
    LAVENDER("lavender", 0xFFC98BD1.toInt()),
    SAND("sand", 0xFFD9C4A6.toInt()),
    SLATE("slate", 0xFFB0B0BC.toInt()),

    /** The kid's own calendar (the parent's pick, or the phone's "Min kalender"): peach, whatever the policy says. */
    OWN(null, 0xFFF0A884.toInt()),
    ;

    companion object {
        /** The policy's name as a colour; `null` for none, JSON null, or a name this launcher doesn't know (a newer
         * server's): no dot, never a wrong one. */
        fun of(name: String?): CalendarColor? = name?.let { n -> entries.firstOrNull { it.wire == n } }
    }
}
