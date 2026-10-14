package com.kidslauncher.mdm.ui

import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.widget.TextClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * One clock format on the whole phone (design 34 P12): every clock time Vibb shows follows the
 * phone's system format, exactly as the status bar does - Vibb never has a format of its own. The
 * system's choice is `Settings.System.TIME_12_24` when it is "24" or "12", else the default of the
 * **system** locale (`Resources.getSystem()`), not Vibb's per-app language, which can differ from
 * the status bar's. A parent's "24 h"/"12 h" on the PWA's Home Screen page writes `TIME_12_24`
 * ([com.kidslauncher.mdm.server.ClockFormatSetting]), so the status bar and Vibb change together.
 *
 * This is the only file that may ask Android for a time format (`is24HourFormat`, `getTimeFormat`,
 * `getTimeInstance`) or spell a time pattern; `ClockFormatTest`'s source scan holds every other
 * file to it. Durations (the call screen, Home's call card, the music position, the PIN back-off)
 * are not clocks.
 */
object ClockFormat {
    const val PATTERN_24 = "HH:mm"
    const val PATTERN_12 = "h:mm"

    // ---- pure ----------------------------------------------------------------------------------

    /** The system's choice: `TIME_12_24` when it says "24"/"12", else the locale's default. */
    fun is24(setting: String?, localeIs24: Boolean): Boolean = when (setting) {
        "24" -> true
        "12" -> false
        else -> localeIs24
    }

    /** A locale's best "jm" pattern is 24 h when it has an `H` or `k` outside quoted text. */
    fun patternIs24(pattern: String): Boolean {
        var quoted = false
        for (c in pattern) {
            if (c == '\'') quoted = !quoted else if (!quoted && (c == 'H' || c == 'k')) return true
        }
        return false
    }

    fun timePattern(is24: Boolean): String = if (is24) PATTERN_24 else PATTERN_12

    /** "06:15" / "18:40" in 24 h; "6:15" / "6:40" / "12:05" in 12 h (no AM/PM, as the status bar). */
    fun formatTime(hour: Int, minute: Int, is24: Boolean): String {
        val h = if (is24) hour else (hour % 12).let { if (it == 0) 12 else it }
        val hh = if (is24) h.toString().padStart(2, '0') else h.toString()
        return hh + ":" + minute.toString().padStart(2, '0')
    }

    // ---- the phone -----------------------------------------------------------------------------

    /** The phone's format now: `TIME_12_24`, else the system locale's default. */
    fun systemIs24(context: Context): Boolean {
        val setting = try {
            Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        } catch (e: Exception) {
            null
        }
        if (setting == "24" || setting == "12") return setting == "24"
        val locale = try {
            Resources.getSystem().configuration.locales[0]
        } catch (e: Exception) {
            null
        } ?: Locale.getDefault()
        return is24(setting, patternIs24(DateFormat.getBestDateTimePattern(locale, "jm")))
    }

    fun timePattern(context: Context): String = timePattern(systemIs24(context))

    /** A wall-clock instant in [zone] (the phone's by default), in the phone's format. */
    fun formatTime(context: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatTime(context, LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone))

    fun formatTime(context: Context, t: LocalDateTime): String = formatTime(t.hour, t.minute, systemIs24(context))

    /**
     * A time [TextClock]: both formats set to the system's pattern. A TextClock picks between its
     * two formats by its own context's locale, which is Vibb's per-app language - so it is bound
     * again on every start/resume and on a change of `TIME_12_24` ([watch]).
     */
    fun bind(clock: TextClock) {
        val pattern = timePattern(clock.context)
        if (clock.format24Hour?.toString() != pattern) clock.format24Hour = pattern
        if (clock.format12Hour?.toString() != pattern) clock.format12Hour = pattern
    }

    /** Calls [onChange] on the main thread whenever `TIME_12_24` changes; [unwatch] it on stop. */
    fun watch(context: Context, onChange: () -> Unit): ContentObserver? {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer,
            )
            observer
        } catch (e: Exception) {
            Log.w("ClockFormat", "Couldn't watch the clock format", e)
            null
        }
    }

    fun unwatch(context: Context, observer: ContentObserver?) {
        observer ?: return
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.w("ClockFormat", "Couldn't stop watching the clock format", e)
        }
    }
}
