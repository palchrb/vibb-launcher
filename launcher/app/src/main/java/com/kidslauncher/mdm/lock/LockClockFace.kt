package com.kidslauncher.mdm.lock

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.text.method.TransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.ClockFormat
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The lock's clock face - the clock, the date and the next alarm's line - as one piece shared by the glance page
 * ([GlancePanel]) and the time-rule screen (`ui/LockActivity`, launcher round L6: the two screens read as one design).
 * Without a clock profile the centred 56 sp clock and the long date; on a phone with one (design 59d step L,
 * [glanceClockProfile]) Android's small clock - Nunito Light at the profile's size, the short date capitalised, all
 * left-aligned and placed in the window's coordinates under the keyguard status bar ([place]), so the clock stands in
 * the same spot on both screens. Information only: nothing here is a control.
 *
 * [root] is the screen's root (it carries the system bars as padding), [top] the block the clock heads (its first
 * child); [extra] are lines under the date that follow it to the start (the glance's "Neste" line and the weather's own
 * line, design 75). [dateRow] (the glance's, design 75 §5.5): the row holding the date and the weather mark - placed,
 * measured and baselined instead of the date (the row's baseline is the date's); the time-rule screen passes none.
 * [dateAlarm] (the glance's, the calmer glance 2026-11-04): the alarm's place on the date line, in that row - the
 * [alarm] line then shows only when the date's line can't hold it ([placeAlarm]).
 */
class LockClockFace(
    private val context: Context,
    private val root: View,
    private val top: View,
    val clock: TextClock,
    val date: TextClock,
    val alarm: TextView,
    private val extra: List<View> = emptyList(),
    private val logTag: String,
    private val dateRow: View? = null,
    private val dateAlarm: TextView? = null,
) {
    /** What the face places where the date was: the glance's date row, else the date. */
    private val dateBox: View get() = dateRow ?: date
    /** Design 59d step L: Android's small clock's geometry on this phone, else `null` (the centred clock). */
    val profile: ClockGeometry? = try {
        val density = context.resources.displayMetrics.density
        val widthDp = context.resources.configuration.screenWidthDp
        glanceClockProfile(Build.MODEL, Build.MANUFACTURER, density, widthDp).also {
            // JL-6: a phone with no profile says so once (nothing here is personal).
            if (it == null) Log.i(logTag, "Glance clock: no profile (model ${Build.MODEL}, maker ${Build.MANUFACTURER}, density $density, width $widthDp dp) - the centred clock")
        }
    } catch (e: Throwable) {
        null
    }

    private val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

    init {
        datePattern(short = profile != null)
        profile?.let { profileLook(it, locale) }
        // The calmer glance: the wake-up alarm comes at the evening's start and goes at noon - checked at each minute
        // of the clock (once bound), not only at a render.
        clock.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                try {
                    if (alarmBound) bindAlarm()
                } catch (e: Throwable) {
                    Log.w(logTag, "Clock face alarm failed: ${e.javaClass.simpleName}")
                }
            }
        })
    }

    private fun datePattern(short: Boolean) {
        val pattern = DateFormat.getBestDateTimePattern(locale, glanceDateSkeleton(short))
        if (date.format12Hour?.toString() != pattern) date.format12Hour = pattern
        if (date.format24Hour?.toString() != pattern) date.format24Hour = pattern
    }

    /** Whether the no-profile date is the short one (design 75 D14, launcher QA M1/L2): while the policy's lock weather
     * is on, "Man. 3. nov." as the profile phones show - whether or not the line shows at the moment, so the date never
     * flips with the line's moods; the long date with the switch off. A profile's date is always the short one. */
    private var short = false

    fun shortDate(on: Boolean) {
        if (profile != null || on == short) return
        short = on
        datePattern(on)
        date.transformationMethod = if (on) FirstCapital(locale) else null
    }

    /**
     * The clock's size and, with a profile, its place (r11 §1.2: nothing else is measured): the no-profile clock's
     * 56 sp, or the profile's clock in dp placed under the keyguard status bar ([placeProfile]). [width] is the
     * block's content width. Returns whether a margin moved (the caller skips that frame).
     */
    fun place(width: Int): Boolean {
        val p = profile
        if (p != null) {
            // Design 59d step L: one clock, Android's size, placed under the keyguard status bar.
            clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, p.clockDp)
            return placeProfile(p, width)
        }
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, CLOCK_BIG_SP)
        return false
    }

    /**
     * For a screen with no layout pass of its own (the time-rule screen): [place] at the first layout and at every
     * change of the root's height or of the bars' padding; a frame that moved the clock is skipped, so the first frame
     * drawn is the placed one (as the glance's bug 25b-B/C fix).
     */
    fun follow() {
        var placedFor = -1
        var skip = false
        root.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val height = b - t
            val key = height * 31 + root.paddingTop * 7 + root.paddingBottom
            if (height > 0 && key != placedFor) {
                placedFor = key
                try {
                    val width = top.width - top.paddingStart - top.paddingEnd
                    if (width > 0 && place(width)) skip = true
                } catch (e: Throwable) {
                    Log.w(logTag, "Clock face placement failed: ${e.javaClass.simpleName}")
                }
            }
        }
        root.viewTreeObserver.addOnPreDrawListener {
            if (!skip) return@addOnPreDrawListener true
            skip = false
            false
        }
    }

    /** [bindAlarm] ran once: the minute's re-check may run. */
    private var alarmBound = false

    /** The alarm's time as shown ("07:00"), `null` = none shown. */
    private var alarmTime: String? = null

    /**
     * The next alarm (design 42 §1.3): the system clock app's or the pinned clock app's only
     * ([PinLockRuntime.clockPackages]), and since the calmer glance (2026-11-04) only the evening's wake-up alarm
     * ([glanceWakeAlarm]: from 18:00, ringing before noon the next day) - the alarm glyph and "07:00", TalkBack
     * "Vekkerklokke 07:00". On the date line when it holds it ([placeAlarm]), else on the line under the date.
     */
    fun bindAlarm() {
        alarmBound = true
        val next = try {
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        } catch (e: Exception) {
            null
        }
        val now = System.currentTimeMillis()
        val shown = glanceWakeAlarm(glanceAlarm(next?.triggerTime, next?.showIntent?.creatorPackage, PinLockRuntime.clockPackages(), now), now, ZoneId.systemDefault())
        alarmTime = shown?.let { alarmText(it) }
        placeAlarm()
    }

    /**
     * The alarm's line (the calmer glance): beside the date - after the weather mark when it is there - when the date's
     * line holds it ([glanceAlarmBeside]: the row's other parts as they are now, measured unbounded, and the alarm's
     * text, against the line's room), else the line under the date; nothing is cut. Run at every bind, a layout of the
     * page and a change of the weather's place. Returns whether a view's visibility changed (the caller skips that
     * frame).
     */
    fun placeAlarm(): Boolean {
        val time = alarmTime
        val row = dateAlarm
        val spoken = time?.let { context.getString(R.string.lock_alarm, it) }
        var beside = false
        if (time != null && row != null) {
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, date.textSize)
            val text = alarmLine(context.getString(R.string.glance_separator), time, row)
            if (row.text?.toString() != text.toString() || row.textSize != lastAlarmPx) {
                row.text = text
                lastAlarmPx = row.textSize
            }
            beside = besideFits(row)
        }
        if (time != null && !beside) {
            val line = alarmLine("", time, alarm)
            if (alarm.text?.toString() != line.toString()) alarm.text = line
        }
        alarm.contentDescription = spoken
        row?.contentDescription = spoken
        var changed = show(alarm, time != null && !beside)
        if (row != null) changed = show(row, beside) || changed
        return changed
    }

    /** The date-line alarm's text size its text was last built at (the glyph follows the size). */
    private var lastAlarmPx = 0f

    private fun show(view: View, on: Boolean): Boolean {
        val wanted = if (on) View.VISIBLE else View.GONE
        if (view.visibility == wanted) return false
        view.visibility = wanted
        return true
    }

    /** Whether [view]'s text fits after the date row's other shown parts on the line (unknown width: yes, placed again
     * at the first layout). */
    private fun besideFits(view: TextView): Boolean {
        val row = dateRow as? ViewGroup ?: return false
        val start = (row.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        val width = top.width - top.paddingStart - top.paddingEnd - (if (profile != null) start else 0)
        if (width <= 0) return true
        var used = 0
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child === view || child.visibility == View.GONE) continue
            child.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            used += child.measuredWidth + (margins?.marginStart ?: 0) + (margins?.marginEnd ?: 0)
        }
        val density = context.resources.displayMetrics.density
        val alarmDp = Layout.getDesiredWidth(view.text, view.paint) / density + ALARM_SLACK_DP
        return glanceAlarmBeside(width / density, used / density, alarmDp)
    }

    /** [prefix], the alarm glyph at the text's size in the text's colour, a space and [time]. */
    private fun alarmLine(prefix: String, time: String, view: TextView): CharSequence {
        val built = SpannableStringBuilder(prefix)
        val glyph = context.getDrawable(R.drawable.app_glyph_alarm)?.mutate()
        if (glyph != null) {
            val size = (view.textSize * ALARM_GLYPH_SCALE).roundToInt()
            glyph.setBounds(0, 0, size, size)
            glyph.setTint(view.currentTextColor)
            val at = built.length
            built.append(GLYPH_CHAR)
            built.setSpan(ImageSpan(glyph, DynamicDrawableSpan.ALIGN_CENTER), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            built.append(' ')
        }
        return built.append(time)
    }

    private fun alarmText(alarm: GlanceAlarm): String {
        val time = ClockFormat.formatUntil(context, alarm.atMs)
        if (!alarm.withWeekday) return time
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val day = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEE"), alarm.atMs).toString().trimEnd('.')
        return "$day $time"
    }

    /**
     * Design 59d step L: the clock, the date and the alarm line left-aligned as Android's small clock - Nunito Light for
     * the clock (JL-2), the short date capitalised as Android's ("Fre. 23. okt."), the date's
     * size; where they sit is [placeProfile]'s, at every layout.
     */
    private fun profileLook(p: ClockGeometry, locale: Locale) {
        Log.i(logTag, "Glance clock: ${p.name} profile")
        // JL-2: Android's small clock is thin - Nunito Light (300), bundled for this clock only (not in nunito.xml's family).
        ResourcesCompat.getFont(context, R.font.nunito_light)?.let { clock.typeface = it }
        date.transformationMethod = FirstCapital(locale)
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.dateSp)
        for (view in listOf(clock, dateBox, alarm) + extra) {
            (view.layoutParams as LinearLayout.LayoutParams).let { view.layoutParams = it.apply { gravity = Gravity.START } }
        }
        for (view in listOf(date, alarm) + extra) (view as? TextView)?.gravity = Gravity.START
        // The first digit's ink keeps its place when the hour's first digit changes (its side bearing does).
        clock.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                try {
                    if (root.height > 0) margins(clock, clockStartPx(p), (clock.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
                } catch (e: Throwable) {
                    Log.w(logTag, "Glance clock start failed: ${e.javaClass.simpleName}")
                }
            }
        })
    }

    /** Design 59d step L: the clock's start margin that puts its first digit's ink at the profile's
     * [ClockGeometry.clockInkStartDp] in the window ([startMarginPx] less the glyph's side bearing). */
    private fun clockStartPx(p: ClockGeometry): Int {
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val contentStart = (at[0] + root.paddingStart + top.paddingStart).toFloat()
        val text = clock.text?.toString().orEmpty()
        val first = android.graphics.Rect()
        if (text.isNotEmpty()) clock.paint.getTextBounds(text, 0, 1, first)
        return startMarginPx(p.clockInkStartDp * context.resources.displayMetrics.density - first.left, contentStart)
    }

    /** The date's first letter capitalised on screen ([glanceDateCapitalised]); the TextClock's own text is unchanged. */
    private class FirstCapital(private val locale: Locale) : TransformationMethod {
        override fun getTransformation(source: CharSequence?, view: View?): CharSequence? = source?.let { glanceDateCapitalised(it.toString(), locale) }

        override fun onFocusChanged(view: View?, sourceText: CharSequence?, focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) = Unit
    }

    /**
     * Design 59d step L: the clock's and the date's margins so their boxes start and their baselines sit where the
     * profile says, in the window's coordinates ([baselineMarginPx], [startMarginPx]) - the baselines under SystemUI's
     * keyguard status bar ([keyguardBarPx], the glance-topinset round); the alarm line under the date at the date's
     * start. Measured at [width] first; a margin is written only when it changed (no layout loop). Returns whether one
     * was.
     */
    private fun placeProfile(p: ClockGeometry, width: Int): Boolean {
        val density = context.resources.displayMetrics.density
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val contentTop = (at[1] + root.paddingTop + top.paddingTop).toFloat()
        val contentStart = (at[0] + root.paddingStart + top.paddingStart).toFloat()
        val bar = keyguardBarPx(density)
        measured(clock, width)
        // The clock's box may reach above the page's content (the emulator's 136 px status bar ends below Android's
        // clock's top), as long as its digits' ink stays inside: its floor is minus the empty room above the ink.
        val ink = android.graphics.Rect()
        clock.paint.getTextBounds(CLOCK_DIGITS, 0, CLOCK_DIGITS.length, ink)
        val clockTarget = profileBaselinePx(bar, p.clockBaselineBelowBarDp, density)
        val clockTop = baselineMarginPx(clockTarget, contentTop, clock.baseline.toFloat(), floorPx = -(clock.baseline + ink.top).toFloat())
        var moved = margins(clock, clockStartPx(p), clockTop)
        val box = dateBox
        measured(box, width)
        val clockBottom = contentTop + clockTop + clock.measuredHeight
        val descent = (clock.measuredHeight - clock.baseline).toFloat()
        val dateStart = startMarginPx(p.dateStartDp * density, contentStart)
        val dateTarget = profileBaselinePx(bar, p.dateBaselineBelowBarDp, density)
        val baseline = if (box === date) date.baseline else box.baseline.takeIf { it >= 0 } ?: date.baseline
        moved = margins(box, dateStart, baselineMarginPx(dateTarget, clockBottom, baseline.toFloat(), floorPx = -descent)) || moved
        if (bar != placedBarPx) {
            placedBarPx = bar
            Log.i(logTag, "Glance clock: keyguard status bar ${bar.roundToInt()} px, the clock's baseline at ${clockTarget.roundToInt()} px, the date's at ${dateTarget.roundToInt()} px")
        }
        for (line in extra) moved = margins(line, dateStart, (line.layoutParams as ViewGroup.MarginLayoutParams).topMargin) || moved
        return margins(alarm, dateStart, (alarm.layoutParams as ViewGroup.MarginLayoutParams).topMargin) || moved
    }

    /** The keyguard status bar [placeProfile] last placed under (logged once per value). */
    private var placedBarPx = -1f

    /**
     * SystemUI's keyguard status bar on this window ([keyguardStatusBarPx]): the status bar's top inset whether shown or
     * not (lock task may hide its content, never its height) and the cutout's, from the window's own insets - the root's
     * listener consumes them for the views, not for `rootWindowInsets`. No insets yet: the 40 dp floor alone.
     */
    private fun keyguardBarPx(density: Float): Float {
        val insets = root.rootWindowInsets
        val bar = insets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())?.top ?: 0
        val cutout = insets?.getInsets(WindowInsets.Type.displayCutout())?.top ?: 0
        return keyguardStatusBarPx(bar, cutout, density)
    }

    /** Writes the margins only when they differ; returns whether it did. */
    private fun margins(view: View, start: Int, top: Int): Boolean {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        if (params.marginStart == start && params.topMargin == top) return false
        params.marginStart = start
        params.topMargin = top
        view.layoutParams = params
        return true
    }

    private fun measured(view: View, width: Int) {
        val wide = view.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, if (wide) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
    }

    private companion object {
        const val CLOCK_BIG_SP = 56f

        /** The alarm glyph's size against the text's (the calmer glance). */
        const val ALARM_GLYPH_SCALE = 1.1f

        /** What the measured alarm text may be off by. */
        const val ALARM_SLACK_DP = 2f

        /** The character the alarm glyph's span stands on. */
        const val GLYPH_CHAR = '\uFFFC'

        /** Design 59d step L: the digits whose ink the profile's clock keeps inside the page. */
        const val CLOCK_DIGITS = "0123456789"
    }
}
