package com.kidslauncher.mdm.ui

import android.app.LocaleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One clock format on the whole phone (design 34 P12): every clock time Vibb shows follows the
 * phone's system format, exactly as the status bar does - Vibb never has a format of its own.
 *
 * - **12 or 24 h**: `Settings.System.TIME_12_24` when it is "24" or "12", else the default of the
 *   **system** locale - `LocaleManager.systemLocales` (qa-34-s6-code M1), never Vibb's per-app
 *   language, which is also what `Resources.getSystem()` and `Locale.getDefault()` report in our
 *   process on Android 13+.
 * - **The pattern** is the system locale's own (`getBestDateTimePattern` with the hour field "H" or
 *   "h" - so fi's `H.mm` matches its status bar; N6): clock faces drop the AM/PM marker, as the
 *   status bar does ([formatTime], [bind]); an end time ("until 9:30 PM") keeps it in 12 h
 *   ([formatUntil], L1) - a future time without it is ambiguous.
 * - **The marker's word** is Vibb's language, not the system's (emulator round 2026-10-14 N9): the
 *   status bar on an en_US system shows "11:30" with no marker, so it has no word to match, and the
 *   marker is text in a sentence that is Vibb's ("Til 11:30 p.m.", CLDR's bokmål marker, not
 *   "Til 11:30 PM"). The hour cycle, the separator and the order stay the system's pattern
 *   ([textLocale], the context's own locale; java.time's `ofPattern(pattern, locale)` takes only text
 *   fields from the locale).
 *
 * A parent's "24 h"/"12 h" on the PWA's Home Screen page writes `TIME_12_24`
 * ([com.kidslauncher.mdm.server.ClockFormatSetting]), so the status bar and Vibb change together.
 * This is the only file that may ask Android for a time format or spell a time pattern;
 * `ClockFormatTest`'s source scan holds every other file to it. Durations (the call screen, Home's
 * call card, the music position, the PIN back-off) are not clocks.
 */
object ClockFormat {
    /** The XML's first-frame formats and the fallbacks when a locale's pattern can't be used. */
    const val PATTERN_24 = "HH:mm"
    const val PATTERN_12 = "h:mm"
    private const val PATTERN_12_AM_PM = "h:mm a"

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

    /**
     * The system locale: the [LocaleManager]'s first system locale; only when it can't tell, the
     * system resources' (which on Android 13+ may already be the per-app language), else [default].
     */
    fun chooseSystemLocale(fromLocaleManager: Locale?, fromSystemResources: Locale?, default: Locale): Locale =
        fromLocaleManager ?: fromSystemResources ?: default

    /** The language of the marker's word (N9): the context's locale (Vibb's language), else the system's. */
    fun chooseTextLocale(contextLocale: Locale?, system: Locale): Locale = contextLocale ?: system

    /** The skeleton that asks the locale for its own time pattern with the hour field forced. */
    fun skeleton(is24: Boolean): String = if (is24) "Hm" else "hm"

    /** A clock face's pattern: the locale's, without the AM/PM (or day-period) marker. */
    fun facePattern(pattern: String): String {
        val out = StringBuilder()
        var quoted = false
        for (c in pattern) {
            if (c == '\'') quoted = !quoted
            if (!quoted && (c == 'a' || c == 'b' || c == 'B')) continue
            out.append(c)
        }
        return out.toString().trim { it.isWhitespace() || it == ' ' || it == ' ' }
    }

    /** [hour]:[minute] in [pattern] for [locale]; a pattern java.time can't read falls back to ours. */
    fun format(pattern: String, hour: Int, minute: Int, is24: Boolean, locale: Locale, amPm: Boolean): String {
        val time = LocalTime.of(hour, minute)
        return try {
            time.format(DateTimeFormatter.ofPattern(pattern, locale))
        } catch (e: Exception) {
            val fallback = if (is24) PATTERN_24 else if (amPm) PATTERN_12_AM_PM else PATTERN_12
            time.format(DateTimeFormatter.ofPattern(fallback, locale))
        }
    }

    // ---- the phone -----------------------------------------------------------------------------

    /** The phone's system locale (M1) - never the per-app language. */
    fun systemLocale(context: Context): Locale {
        val fromManager = try {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.get(0)
        } catch (e: Exception) {
            null
        }
        val fromResources = try {
            Resources.getSystem().configuration.locales[0]
        } catch (e: Exception) {
            null
        }
        return chooseSystemLocale(fromManager, fromResources, Locale.getDefault())
    }

    /** Vibb's language for the marker's word (N9): the context's own locale - since Android 13 the
     * per-app language, or the system's when Vibb follows the phone; the very locale the sentence
     * around the time ("Til ...") was resolved in, so the two never differ (qa-34-emu-launcher-code N2).
     * The only read of the context's configuration here: the format itself stays the system's. */
    private fun textLocale(context: Context, system: Locale): Locale =
        chooseTextLocale(context.resources.configuration.locales[0], system)

    /** The phone's format now: `TIME_12_24`, else the system locale's default. */
    fun systemIs24(context: Context): Boolean = systemIs24(context, systemLocale(context))

    private fun systemIs24(context: Context, locale: Locale): Boolean {
        val setting = try {
            Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        } catch (e: Exception) {
            null
        }
        if (setting == "24" || setting == "12") return setting == "24"
        return is24(setting, patternIs24(bestPattern(locale, "jm") ?: PATTERN_24))
    }

    private fun bestPattern(locale: Locale, skeleton: String): String? = try {
        DateFormat.getBestDateTimePattern(locale, skeleton)
    } catch (e: Exception) {
        null
    }

    /** The system locale's own time pattern with the hour field the phone's format says (N6). */
    private fun localePattern(locale: Locale, is24: Boolean): String =
        bestPattern(locale, skeleton(is24)) ?: if (is24) PATTERN_24 else PATTERN_12_AM_PM

    /** A clock face's pattern now (TextClocks, [formatTime]). */
    fun timePattern(context: Context): String {
        val locale = systemLocale(context)
        return facePattern(localePattern(locale, systemIs24(context, locale)))
    }

    /** A wall-clock instant in [zone] (the phone's by default), as a clock face shows it. */
    fun formatTime(context: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatTime(context, at(ms, zone))

    fun formatTime(context: Context, t: LocalDateTime): String = render(context, t, amPm = false)

    /** An end time - "until 21:30" / "until 9:30 PM": in 12 h always with AM/PM (L1). */
    fun formatUntil(context: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatUntil(context, at(ms, zone))

    fun formatUntil(context: Context, t: LocalDateTime): String = render(context, t, amPm = true)

    private fun at(ms: Long, zone: ZoneId) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    private fun render(context: Context, t: LocalDateTime, amPm: Boolean): String {
        val locale = systemLocale(context)
        val is24 = systemIs24(context, locale)
        val pattern = localePattern(locale, is24).let { if (amPm) it else facePattern(it) }
        return format(pattern, t.hour, t.minute, is24, textLocale(context, locale), amPm)
    }

    /**
     * A time [TextClock]: both formats set to the system's face pattern. A TextClock picks between
     * its two formats by its own context's locale, which is Vibb's per-app language - so it is bound
     * again on every start/resume and on a change ([watch]).
     */
    fun bind(clock: TextClock) {
        val pattern = timePattern(clock.context)
        if (clock.format24Hour?.toString() != pattern) clock.format24Hour = pattern
        if (clock.format12Hour?.toString() != pattern) clock.format12Hour = pattern
    }

    /** What [watch] registered; [unwatch] it on stop. */
    class Watch internal constructor(val observer: ContentObserver, val receiver: BroadcastReceiver?)

    /**
     * Calls [onChange] on the main thread whenever `TIME_12_24` or the system locale changes (N6:
     * the lock handles `locale` itself, so it isn't recreated).
     */
    fun watch(context: Context, onChange: () -> Unit): Watch? {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer,
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) = onChange()
            }
            val registered = try {
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
                receiver
            } catch (e: Exception) {
                Log.w("ClockFormat", "Couldn't watch the system locale", e)
                null
            }
            Watch(observer, registered)
        } catch (e: Exception) {
            Log.w("ClockFormat", "Couldn't watch the clock format", e)
            null
        }
    }

    fun unwatch(context: Context, watch: Watch?) {
        watch ?: return
        try {
            context.contentResolver.unregisterContentObserver(watch.observer)
        } catch (e: Exception) {
            Log.w("ClockFormat", "Couldn't stop watching the clock format", e)
        }
        try {
            watch.receiver?.let(context::unregisterReceiver)
        } catch (e: Exception) {
            Log.w("ClockFormat", "Couldn't stop watching the system locale", e)
        }
    }
}
