package com.kidslauncher.mdm.lock

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ViewLockGlanceBinding
import com.kidslauncher.mdm.databinding.ViewLockWeatherMarkBinding
import com.kidslauncher.mdm.server.dto.WeatherAnswer
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.weather.WEATHER_HIDE_AFTER_MS
import com.kidslauncher.mdm.weather.SpokenText
import com.kidslauncher.mdm.weather.WEATHER_NOW_INSERT_MS
import com.kidslauncher.mdm.weather.weatherNowAsk
import com.kidslauncher.mdm.weather.WeatherCard
import com.kidslauncher.mdm.weather.WeatherEvent
import com.kidslauncher.mdm.weather.WeatherLine
import com.kidslauncher.mdm.weather.WeatherPlace
import com.kidslauncher.mdm.weather.WeatherPull
import com.kidslauncher.mdm.weather.WeatherStore
import com.kidslauncher.mdm.weather.WeatherSymbols
import com.kidslauncher.mdm.weather.WeatherWords
import com.kidslauncher.mdm.weather.WeatherWordsKey
import com.kidslauncher.mdm.weather.beside
import com.kidslauncher.mdm.weather.spokenCardText
import com.kidslauncher.mdm.weather.weatherCard
import com.kidslauncher.mdm.weather.weatherDegrees
import com.kidslauncher.mdm.weather.weatherFold
import com.kidslauncher.mdm.weather.weatherHour
import com.kidslauncher.mdm.weather.weatherLine
import com.kidslauncher.mdm.weather.weatherMm
import com.kidslauncher.mdm.weather.weatherNowLine
import com.kidslauncher.mdm.weather.weatherNowShown
import com.kidslauncher.mdm.weather.weatherNumber
import com.kidslauncher.mdm.weather.weatherPlace
import com.kidslauncher.mdm.weather.weatherWordsFit
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The weather on the lock's glance (design 75 §5.5), the glue under [GlancePanel] as `MusicLockPanel` is: the mark by
 * the date or on its own line under it (two instances, at most one shown, nothing reparented - launcher QA L5), the
 * day's card, the open state and the mark's 48 dp hit area. The model is the pure `weather/WeatherModel.kt`'s.
 *
 * - **The place** (D15; the calmer glance, 2026-11-04, mockup boards A and B) is decided only at a lock-up, a refit
 *   (font scale), a change of the page's width and the first bind ([decidePlace]): its own line when the weather has
 *   words to say now and they fit there, else beside the date after a quiet " · " when the date's line holds it, else
 *   its own line without words; between those moments a model whose words don't fit the place shows without them -
 *   the mark never moves while the screen is on, and words are never cut.
 * - **The card** opens and folds by [weatherFold]: a tap on the mark or the card; a lock-up, leaving the glance, `stop`,
 *   the line hiding, the card going and the VoIP ring card fold it. Opening asks for the nowcast line (3 s), inserted
 *   only within [WEATHER_NOW_INSERT_MS] of the open, for the card opened, with no finger on the rows ([weatherNowShown]).
 * - **Re-picks** at every minute of the clock and at resume ([repick]); a bind only when the model changed.
 *
 * Opens nothing, unlocks nothing, asks no host itself (`WeatherGuardTest`; the nowcast goes through [WeatherPull], our
 * server's client). Every entry point catches everything and logs the class (a weather fault never trips the lock's
 * crash guard). Logs carry no place, temperature or time.
 */
class WeatherLockPanel(
    private val activity: AppCompatActivity,
    private val glance: ViewLockGlanceBinding,
    private val face: LockClockFace,
    /** A finger is on the rows (the glance's dismiss): the nowcast line waits. */
    private val rowsHeld: () -> Boolean,
    /** The card opened or folded, or the own line came or went: the glance places the list and the "Neste" line. */
    private val onChanged: () -> Unit,
) {
    private val beside: ViewLockWeatherMarkBinding = glance.lockGlanceWeather

    /** The " · " between the date and the mark beside it: shown with that mark. */
    private val sep: TextView = glance.lockGlanceWeatherSep
    private val ownLine: ViewLockWeatherMarkBinding = glance.lockGlanceWeatherLine
    private val card = glance.lockWeatherCard
    private val columns = listOf(
        Column(card.weatherCol0, card.weatherCol0Part, card.weatherCol0Symbol, card.weatherCol0Temp, card.weatherCol0Mm),
        Column(card.weatherCol1, card.weatherCol1Part, card.weatherCol1Symbol, card.weatherCol1Temp, card.weatherCol1Mm),
        Column(card.weatherCol2, card.weatherCol2Part, card.weatherCol2Symbol, card.weatherCol2Temp, card.weatherCol2Mm),
    )

    private class Column(val root: View, val part: TextView, val symbol: ImageView, val temp: TextView, val mm: TextView)

    private var started = false
    private var line: WeatherLine? = null
    private var shownFor: Any? = null
    private var place: WeatherPlace? = null
    private var placedWidth = 0
    private var open = false

    /** Bumped at every fold, lock-up and stop: a late nowcast answer for an older open is never inserted. */
    private var generation = 0

    /** The own line's slot held (INVISIBLE) through a screen-on pull (launcher QA L2 of the design review): a line
     * that comes a second after the lock-up never moves the rows. Until the next lock-up. */
    private var slotHeld = false

    private val changed: () -> Unit = { guarded("store") { repick() } }

    init {
        for (mark in listOf(beside, ownLine)) {
            mark.root.setOnClickListener { guarded("tap") { tap() } }
            mark.weatherMarkText.setHorizontallyScrolling(true)
            markAccessibility(mark.root)
        }
        card.root.setOnClickListener { guarded("tap") { tap() } }
        // A new minute: the line's entry for this hour (D2), and the line hiding past its age.
        glance.lockGlanceClock.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = guarded("tick") { if (started) repick() }
        })
        // The hit area follows the mark wherever the layout puts it.
        glance.lockGlanceTop.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> guarded("hit") { hitArea() } }
    }

    /** onStart: watching the store. */
    fun start() = guarded("start") {
        if (started) return@guarded
        started = true
        // L8: the cached switch now (the date short from the first frame), the kept answer once the phone is unlocked.
        WeatherStore.ensureLoaded(activity)
        WeatherStore.addListener(changed)
    }

    /** onStop: nothing watched; the card folds. */
    fun stop() = guarded("stop") {
        fold(WeatherEvent.STOP)
        if (!started) return@guarded
        started = false
        WeatherStore.removeListener(changed)
    }

    /** Every lock-up: the card folds, the place is decided again, a line held for the screen-on pull reserves its slot. */
    fun lockUp() = guarded("lock-up") {
        fold(WeatherEvent.LOCK_UP)
        place = null
        // Only a line that was there and went stale: a phone whose weather never comes keeps no empty room.
        // L4: and only while that line showed recently - an answer past its 3 h and one more hour holds no room.
        val recent = WeatherStore.answer?.checkedAtMs?.let { System.currentTimeMillis() - it <= WEATHER_HIDE_AFTER_MS + HOLD_EXTRA_MS } == true
        slotHeld = WeatherStore.policyOn && WeatherStore.answer?.status == "ok" && recent && WeatherPull.let { it.running() || it.due() }
        shownFor = null
        repick()
    }

    /** Resume (no lock-up): the line again, before the first frame (a line stale for 3 h hides; launcher QA L12). */
    fun resume() = guarded("resume") { repick() }

    /** The font scale changed: the mark's size and its place again. */
    fun refit() = guarded("refit") {
        place = null
        shownFor = null
        repick()
    }

    /** The glance's stage left the page (the keypad, Android's pad) or the VoIP ring card came: the card folds. */
    fun leftPage() = guarded("leave") { fold(WeatherEvent.STAGE_LEFT) }

    fun voip() = guarded("voip") { fold(WeatherEvent.VOIP) }

    /** The open card's height with its margins (the glance's "Neste" rule measures without it), 0 while folded. */
    fun cardHeight(): Int {
        val view = card.root
        if (view.visibility != View.VISIBLE) return 0
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
        return view.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
    }

    /** Whether the card is open (the glance's list sits 14 dp under it). */
    fun cardOpen(): Boolean = open

    // ---- the model ------------------------------------------------------------------------------

    private fun repick() {
        val now = System.currentTimeMillis()
        face.shortDate(WeatherStore.policyOn)
        val answer = WeatherStore.answer
        val picked = weatherLine(answer, WeatherStore.policyOn, now)
        val cardModel = picked?.let { l -> answer?.let { weatherCard(it, l, now, ZoneId.systemDefault()) } }
        val key = listOf(picked, cardModel, open, place, contentWidth(), slotHeld, answer?.checkedAtMs)
        if (picked == null && line != null) fold(WeatherEvent.LINE_GONE)
        if (picked != null && cardModel == null && open) fold(WeatherEvent.CARD_GONE)
        line = picked
        if (key == shownFor) return
        shownFor = key
        bind(picked, cardModel, answer, now)
    }

    private fun bind(model: WeatherLine?, cardModel: WeatherCard?, answer: WeatherAnswer?, now: Long) {
        val width = contentWidth()
        if (width > 0 && width != placedWidth) {
            placedWidth = width
            place = null
        }
        if (place == null && width > 0 && (model != null || slotHeld)) place = decidePlace(answer, model, now)
        if (model == null) {
            showBeside(View.GONE)
            // A line due from the screen-on pull keeps its own line's room until it lands (or the next lock-up).
            ownLine.root.visibility = if (slotHeld && place?.beside() == false) View.INVISIBLE else View.GONE
            card.root.visibility = View.GONE
            onChanged()
            return
        }
        // Not laid out yet: nothing shown until the place is known (the mark never shows in one place and moves).
        val at = place ?: run {
            showBeside(View.GONE)
            ownLine.root.visibility = View.GONE
            return
        }
        val mark = if (at.beside()) beside else ownLine
        if (at.beside()) ownLine.root.visibility = View.GONE else showBeside(View.GONE)
        bindMark(mark, model, at, cardModel != null)
        if (at.beside()) showBeside(View.VISIBLE) else mark.root.visibility = View.VISIBLE
        slotHeld = false
        if (open && cardModel != null) bindCard(cardModel, now) else card.root.visibility = View.GONE
        onChanged()
    }

    /** The mark beside the date and its " · ", shown or gone together. */
    private fun showBeside(visibility: Int) {
        beside.root.visibility = visibility
        sep.visibility = visibility
    }

    /** The mark: the symbol (1.4 x the text size, at least 22 dp), the degrees in the ink, the dot and the words when the
     * place takes them and they fit, the chevron with a card; the date's colour and size. */
    private fun bindMark(mark: ViewLockWeatherMarkBinding, model: WeatherLine, at: WeatherPlace, hasCard: Boolean) {
        val text = mark.weatherMarkText
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, glance.lockGlanceDate.textSize)
        sep.setTextSize(TypedValue.COMPLEX_UNIT_PX, glance.lockGlanceDate.textSize)
        val symbolPx = maxOf(ceil(glance.lockGlanceDate.textSize * SYMBOL_SCALE).toInt(), dp(SYMBOL_MIN_DP))
        // L3 (code QA): the mark is as tall as the date's line - the symbol centred on the text, overhanging it by its
        // negative margins (nothing clips it) -, so the row never grows when the mark comes and the text's baseline is
        // the row's honestly (a horizontal LinearLayout's baseline ignores a centred child's offset).
        val metrics = text.paint.fontMetricsInt
        val overhang = minOf(0, ((metrics.bottom - metrics.top) - symbolPx) / 2)
        (mark.weatherMarkSymbol.layoutParams as ViewGroup.MarginLayoutParams).let {
            it.width = symbolPx
            it.height = symbolPx
            it.topMargin = overhang
            it.bottomMargin = overhang
            mark.weatherMarkSymbol.layoutParams = it
        }
        val drawable = model.symbol?.let { WeatherSymbols.drawable(it) }
        if (drawable != null) mark.weatherMarkSymbol.setImageResource(drawable)
        mark.weatherMarkSymbol.visibility = if (drawable != null) View.VISIBLE else View.GONE
        val words = model.words?.let(::wordsText)
        val shownWords = words?.takeIf { w ->
            val laid = lineMeasure(mark) ?: return@takeIf false
            weatherWordsFit(at, laid.lineDp, laid.coreDp, wordsDp(mark, w))
        }
        val built = SpannableStringBuilder()
        model.temp?.let {
            built.append(weatherDegrees(it))
            built.setSpan(ForegroundColorSpan(activity.getColor(R.color.kid_ink)), 0, built.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (shownWords != null) {
            val start = built.length
            built.append(if (built.isEmpty()) shownWords else "$DOT$shownWords")
            built.setSpan(RelativeSizeSpan(WORDS_SCALE), start, built.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = built
        mark.weatherMarkChevron.visibility = if (hasCard) View.VISIBLE else View.GONE
        if (SystemClock.elapsedRealtime() >= turningUntil) mark.weatherMarkChevron.rotation = if (open) 180f else 0f
        mark.root.isClickable = hasCard
        mark.root.isFocusable = hasCard
        mark.root.contentDescription = spokenMark(model, shownWords)
        mark.root.stateDescription = if (hasCard) activity.getString(if (open) R.string.weather_expanded else R.string.weather_collapsed) else null
        mark.root.tag = hasCard
    }

    private fun wordsText(words: WeatherWordsKey): String? {
        val res = WeatherWords.res(words.key, words.form) ?: return null
        return if (words.form == "from") {
            val at = words.atMs ?: return null
            activity.getString(res, hourText(at))
        } else {
            activity.getString(res)
        }
    }

    private fun hourText(atMs: Long): String =
        weatherHour(atMs, ZoneId.systemDefault(), ClockFormat.systemIs24(activity), catalogNb())

    /** L5: the strings' catalog itself (`values-nb` sets it), not a guess from the first locale. */
    /** "Mørkt kl. 17:02", "Oppdatert kl. 13:41": in the nb catalog always 24-hour (E8: "kl." with "5:02 p.m." reads
     * wrong); in English the phone's own clock format. */
    private fun clockText(ms: Long): String =
        if (catalogNb()) ClockFormat.weatherClockNb(ms, ZoneId.systemDefault()) else ClockFormat.formatClock(activity, ms)

    private fun catalogNb(): Boolean = activity.resources.getBoolean(R.bool.weather_catalog_nb)

    // ---- the place (D15) ---------------------------------------------------------------------------

    private class LineMeasure(val lineDp: Float, val dateDp: Float, val gapDp: Float, val coreDp: Float)

    /** The room on the date's line and the date's, the " · "'s and the core's widths, at the mark's paint (launcher QA
     * L3: `getDesiredWidth` of what is shown, plus slack). */
    private fun lineMeasure(mark: ViewLockWeatherMarkBinding): LineMeasure? {
        val width = contentWidth().takeIf { it > 0 } ?: return null
        val density = activity.resources.displayMetrics.density
        val date = glance.lockGlanceDate
        val shownDate = date.transformationMethod?.getTransformation(date.text, date) ?: date.text
        val dateDp = Layout.getDesiredWidth(shownDate, date.paint) / density + SLACK_DP
        val start = if (face.profile != null) ((glance.lockGlanceDateRow.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0) else 0
        val lineDp = (width - start) / density
        val paint = mark.weatherMarkText.paint
        val widestDegrees = listOf("${WEATHER_MINUS_SAMPLE}°", line?.temp?.let(::weatherDegrees) ?: "").maxOf { Layout.getDesiredWidth(it, paint) }
        val symbolDp = maxOf(date.textSize * SYMBOL_SCALE / density, SYMBOL_MIN_DP)
        val coreDp = symbolDp + 4f + widestDegrees / density + 4f + CHEVRON_DP + SLACK_DP
        val gapDp = Layout.getDesiredWidth(sep.text, android.text.TextPaint(sep.paint).apply { textSize = date.textSize }) / density
        return LineMeasure(lineDp, dateDp, gapDp, coreDp)
    }

    /** The dot and [words] at the words' size, in dp (plus slack). */
    private fun wordsDp(mark: ViewLockWeatherMarkBinding, words: String): Float {
        val paint = android.text.TextPaint(mark.weatherMarkText.paint).apply { textSize = mark.weatherMarkText.textSize * WORDS_SCALE }
        return Layout.getDesiredWidth("$DOT$words", paint) / activity.resources.displayMetrics.density + SLACK_DP
    }

    /** D15 (the calmer glance): the place from the words the weather has now - the [model]'s, or with none yet (a line
     * held for the screen-on pull) the answer's entry for [now] -, none = beside the date when it fits. */
    private fun decidePlace(answer: WeatherAnswer?, model: WeatherLine?, now: Long): WeatherPlace? {
        val mark = beside
        mark.weatherMarkText.setTextSize(TypedValue.COMPLEX_UNIT_PX, glance.lockGlanceDate.textSize)
        val measure = lineMeasure(mark) ?: return null
        val words = model?.words ?: answer?.hours?.firstOrNull { it.atMs <= now && now < it.atMs + 3_600_000L }?.words?.let { WeatherWordsKey(it.key, it.form, it.atMs) }
        val wordsDp = words?.let(::wordsText)?.let { wordsDp(mark, it) }
        return weatherPlace(measure.lineDp, measure.dateDp, measure.gapDp, measure.coreDp, wordsDp).also {
            Log.i(LOG_TAG, "Weather mark: $it (line ${measure.lineDp.roundToInt()} dp)")
        }
    }

    private fun contentWidth(): Int {
        val top = glance.lockGlanceTop
        return top.width - top.paddingStart - top.paddingEnd
    }

    // ---- the card --------------------------------------------------------------------------------

    private fun bindCard(model: WeatherCard, now: Long) {
        val view = card.root
        val tonight = model.tonight
        // The evening card (18:00 to midnight): "I kveld" in the first group, on the raised tone, its label held empty;
        // "I morgen" over tomorrow's morning and afternoon; a line between them; no title.
        card.weatherCardTomorrow.visibility = if (model.tomorrow && tonight == null) View.VISIBLE else View.GONE
        val evening = if (tonight != null) View.VISIBLE else View.GONE
        card.weatherGroup0Head.visibility = evening
        card.weatherGroup1Head.visibility = evening
        card.weatherCardSplit.visibility = evening
        card.weatherGroup0.setBackgroundResource(if (tonight != null) R.drawable.bg_weather_now else 0)
        val shown = if (tonight != null) listOf(tonight) + model.columns else model.columns
        val anyMm = shown.any { (it.mm ?: 0.0) >= 0.1 }
        val locale = activity.resources.configuration.locales[0] ?: Locale.getDefault()
        columns.forEachIndexed { i, col ->
            val c = shown.getOrNull(i)
            col.root.visibility = if (c != null) View.VISIBLE else View.GONE
            if (c == null) return@forEachIndexed
            val isTonight = tonight != null && i == 0
            col.part.text = if (isTonight) "" else WeatherWords.partRes(c.part)?.let(activity::getString).orEmpty()
            val symbol = c.symbol?.let { WeatherSymbols.drawable(it) }
            if (symbol != null) col.symbol.setImageResource(symbol) else col.symbol.setImageDrawable(null)
            col.temp.text = c.temp?.let(::weatherDegrees) ?: "–"
            val mm = c.mm?.let { weatherMm(it, locale) }
            col.mm.text = mm?.let { activity.getString(R.string.weather_mm, it) }.orEmpty()
            // Under 0.1 mm while any column has some: kept in its place (the card's height holds within a day).
            col.mm.visibility = when {
                !anyMm -> View.GONE
                mm == null -> View.INVISIBLE
                else -> View.VISIBLE
            }
            col.root.setBackgroundResource(if (c.current && !isTonight) R.drawable.bg_weather_now else 0)
        }
        // The groups keep the columns equal: each weighs its shown columns (a group with none is gone). The evening card
        // keeps its 1 : 2 even when a tomorrow column is missing (QA L3): the lone column sits in its group's room.
        for ((group, cols) in listOf(card.weatherGroup0 to columns.take(1), card.weatherGroup1 to columns.drop(1))) {
            val count = cols.count { it.root.visibility == View.VISIBLE }
            group.visibility = if (count > 0) View.VISIBLE else View.GONE
            val weight = if (tonight != null) (if (group === card.weatherGroup0) 1f else 2f) else count.toFloat()
            val params = group.layoutParams as LinearLayout.LayoutParams
            if (count > 0 && params.weight != weight) {
                params.weight = weight
                group.layoutParams = params
            }
        }
        // E8's L2: a label that wraps ("Etter-/middag" at a large font) gives the three the same lines, so the symbols
        // and degrees stay on one line across the card.
        card.root.post {
            guarded("labels") {
                val lines = columns.filter { it.root.visibility == View.VISIBLE }.maxOfOrNull { it.part.lineCount } ?: 1
                for (col in columns) if (col.part.minLines != lines) col.part.minLines = lines
            }
        }
        val lowHigh = if (model.low != null && model.high != null) {
            val res = if (tonight != null) R.string.weather_low_high_tomorrow else R.string.weather_low_high
            activity.getString(res, weatherDegrees(model.low), weatherDegrees(model.high))
        } else null
        val dark = darkText(model).orEmpty()
        card.weatherCardDark.text = dark
        card.weatherCardFoot.text = lowHigh.orEmpty()
        val updated = model.updatedAtMs
        card.weatherCardUpdated.visibility = if (updated != null) View.VISIBLE else View.GONE
        if (updated != null) card.weatherCardUpdated.text = activity.getString(R.string.weather_updated, clockText(updated))
        view.visibility = View.VISIBLE
        view.contentDescription = withNow(spokenCard(model, lowHigh != null))
        view.stateDescription = activity.getString(R.string.weather_expanded)
    }

    private fun darkText(model: WeatherCard): String? {
        val dark = model.dark ?: return null
        return when (dark.kind) {
            "at" -> dark.atMs?.let { activity.getString(R.string.weather_dark_at, clockText(it)) }
            "never" -> activity.getString(R.string.weather_dark_never)
            "all_day" -> activity.getString(R.string.weather_dark_all_day)
            else -> null
        }
    }

    private fun tap() {
        val hasCard = line?.card != null && WeatherStore.answer?.let { a -> line?.let { weatherCard(a, it, System.currentTimeMillis(), ZoneId.systemDefault()) } } != null
        val next = weatherFold(open, WeatherEvent.TAP, hasCard)
        if (next == open) return
        if (next) openCard() else fold(WeatherEvent.TAP)
    }

    private fun openCard() {
        open = true
        generation++
        val opened = SystemClock.elapsedRealtime()
        val asked = generation
        card.weatherCardNow.visibility = View.GONE
        // L7: the chevron turns before the rebind (which then leaves the turning one alone).
        turnChevron()
        shownFor = null
        repick()
        // The open card below the fold comes into view (no animation).
        card.root.post { guarded("scroll") { card.root.requestRectangleOnScreen(Rect(0, 0, card.root.width, card.root.height), true) } }
        // The nowcast (D9, launcher QA M2, code QA M1): a kept good answer of the last 2 min shows at once; an ask only
        // by [weatherNowAsk] - none while one runs, none within 2 min of the last one's start, whatever it brought.
        WeatherStore.freshNow()?.let(::showNow)
        if (!weatherNowAsk(WeatherStore.nowAskedAtElapsed, WeatherStore.nowAsking, opened)) return
        WeatherStore.nowAskStarted()
        nowJob = activity.lifecycleScope.launch {
            var got: com.kidslauncher.mdm.server.dto.WeatherNow? = null
            try {
                got = WeatherPull.nowcast()
            } finally {
                // Cancelled (the card folded, the screen went off) or done: the ask has ended; a failure keeps the old.
                WeatherStore.nowAskEnded(got)
                nowJob = null
            }
            guarded("nowcast") {
                if (open && weatherNowShown(opened, SystemClock.elapsedRealtime(), asked, generation, rowsHeld())) showNow(got)
            }
        }
    }

    /** The card's nowcast ask while it runs: cancelled when the card folds or the lock stops. */
    private var nowJob: kotlinx.coroutines.Job? = null

    private fun showNow(answer: com.kidslauncher.mdm.server.dto.WeatherNow?) {
        val (key, minutes) = weatherNowLine(answer) ?: return
        val res = WeatherWords.nowRes(key) ?: return
        card.weatherCardNow.text = if (key.endsWith("_in")) activity.getString(res, minutes ?: 0) else activity.getString(res)
        card.weatherCardNow.visibility = View.VISIBLE
        card.root.contentDescription = withNow(lastSpokenCard)
        onChanged()
    }

    /** The card's spoken text without the nowcast line (kept for [showNow]). */
    private var lastSpokenCard = ""

    /** The nowcast line's sentence first when it shows. */
    private fun withNow(spoken: String): String {
        lastSpokenCard = spoken
        val now = card.weatherCardNow.takeIf { it.visibility == View.VISIBLE }?.text?.toString()
        return WeatherWords.spoken(listOfNotNull(now?.let(::sentence), spoken).joinToString(" "))
    }

    private fun fold(event: WeatherEvent) {
        nowJob?.cancel()
        nowJob = null
        val next = weatherFold(open, event, line?.card != null)
        if (!open && !next) {
            if (event != WeatherEvent.TAP) generation++
            return
        }
        open = next
        generation++
        card.root.visibility = View.GONE
        card.weatherCardNow.visibility = View.GONE
        shownFor = null
        turnChevron()
        for (mark in listOf(beside, ownLine)) {
            if (mark.root.tag == true) mark.root.stateDescription = activity.getString(R.string.weather_collapsed)
        }
        onChanged()
    }

    /** The chevron's turn (150 ms; none with "Remove animations"). */
    /** Until when a turn runs (a rebind leaves the chevron alone meanwhile). */
    private var turningUntil = 0L

    private fun turnChevron() {
        val to = if (open) 180f else 0f
        turningUntil = SystemClock.elapsedRealtime() + TURN_MS + 50
        for (mark in listOf(beside, ownLine)) {
            val chevron = mark.weatherMarkChevron
            if (ValueAnimator.areAnimatorsEnabled() && mark.root.isShown) chevron.animate().rotation(to).setDuration(TURN_MS).start()
            else chevron.rotation = to
        }
    }

    // ---- the hit area (launcher QA H1) -------------------------------------------------------------

    /** The visible mark's touch rectangle: at least 48 dp tall - its bounds extended evenly, the top capped at the
     * clock's baseline above (the design review's L1: the rest goes below) - and 8 dp wider at each side, through one
     * [TouchDelegate] on the page's top block; the layout and the rows don't move. A delegate sees only touches inside
     * its host (E8's M1: with nothing under the mark the top block ended at its bottom, 36 dp of the 48 reached): the top
     * block is padded at its bottom by what the rectangle reaches past its content ([weatherHostPadBottom]) - room
     * nothing else uses, so nothing moves. */
    private fun hitArea() {
        val top = glance.lockGlanceTop
        val mark = listOf(beside, ownLine).firstOrNull { it.root.visibility == View.VISIBLE && it.root.isClickable }?.root
        if (mark == null || mark.width == 0) {
            if (top.touchDelegate != null) top.touchDelegate = null
            padHost(top, 0)
            return
        }
        val rect = Rect(0, 0, mark.width, mark.height)
        top.offsetDescendantRectToMyCoords(mark, rect)
        val clock = glance.lockGlanceClock
        val capTop = clock.top + clock.baseline
        val area = weatherHitRect(rect.left, rect.top, rect.right, rect.bottom, dp(HIT_MIN_DP), dp(HIT_SIDE_DP), capTop)
        padHost(top, weatherHostPadBottom(area[3], top.height - top.paddingBottom))
        top.touchDelegate = TouchDelegate(Rect(area[0], area[1], area[2], area[3]), mark)
    }

    private fun padHost(top: View, bottom: Int) {
        if (top.paddingBottom != bottom) top.setPadding(top.paddingLeft, top.paddingTop, top.paddingRight, bottom)
    }

    // ---- TalkBack (launcher QA M5) -------------------------------------------------------------------

    /** A whole sentence: the text and its full stop - never two ("9 p.m.."). */
    private fun sentence(text: String): String =
        if (text.trimEnd().endsWith(".")) text.trimEnd() else activity.getString(R.string.weather_spoken_sentence, text)

    private fun spokenMark(model: WeatherLine, words: String?): String {
        val parts = mutableListOf<String>()
        val name = model.symbol?.let { WeatherSymbols.name(it) }?.let(activity::getString)
        parts += if (name != null) activity.getString(R.string.weather_spoken_now, name) else activity.getString(R.string.weather_spoken_now_plain)
        model.temp?.let { parts += activity.resources.getQuantityString(R.plurals.weather_spoken_degrees, abs(it), weatherNumber(it)) }
        words?.let { parts += sentence(it) }
        return WeatherWords.spoken(parts.joinToString(" "))
    }

    private fun spokenCard(model: WeatherCard, lowHigh: Boolean): String {
        val res = activity.resources
        val text = object : SpokenText {
            override fun string(id: Int, vararg args: Any) = activity.getString(id, *args)
            override fun plural(id: Int, quantity: Int, vararg args: Any) = res.getQuantityString(id, quantity, *args)
        }
        val locale = res.configuration.locales[0] ?: Locale.getDefault()
        return spokenCardText(model, lowHigh, darkText(model), locale, text)
    }

    /** The mark is a button with expand/collapse actions and its state (launcher QA M5). */
    private fun markAccessibility(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (host.tag != true) return
                info.className = android.widget.Button::class.java.name
                info.addAction(
                    if (open) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND,
                )
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, activity.getString(if (open) R.string.weather_card_close else R.string.weather_card_hint)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val id = when (action) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id -> !open
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id -> open
                    else -> null
                } ?: return super.performAccessibilityAction(host, action, args)
                if (id) guarded("tap") { tap() }
                return true
            }
        }
    }

    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private inline fun guarded(what: String, work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Weather $what failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "WeatherLockPanel"
        const val SYMBOL_SCALE = 1.4f
        const val SYMBOL_MIN_DP = 22f
        const val CHEVRON_DP = 14f
        const val SLACK_DP = 2f
        const val WORDS_SCALE = 0.93f
        const val HIT_MIN_DP = 48f
        const val HIT_SIDE_DP = 8f
        const val TURN_MS = 150L

        /** L4: the own line's room is held for a line that went stale less than this past its 3 h. */
        const val HOLD_EXTRA_MS = 3_600_000L
        const val DOT = " · "

        /** The widest degrees a day plans for. */
        const val WEATHER_MINUS_SAMPLE = "−88"
    }
}

/**
 * The mark's touch rectangle (launcher QA H1, the design review's L1), pure: [left]..[bottom] the mark in the top
 * block's coordinates; at least [minHeight] tall - extended evenly, but never above [capTop] (the clock's baseline;
 * what can't go above goes below) - and [side] wider at each side. Returns left, top, right, bottom.
 */
/**
 * The mark's touch rectangle must lie inside its host (E8's M1: a `TouchDelegate` sees only the host's touches): the
 * bottom padding the host needs so its height reaches [areaBottom] from [contentBottom] (its height without that
 * padding); 0 when its content already reaches there.
 */
fun weatherHostPadBottom(areaBottom: Int, contentBottom: Int): Int = maxOf(0, areaBottom - contentBottom)

fun weatherHitRect(left: Int, top: Int, right: Int, bottom: Int, minHeight: Int, side: Int, capTop: Int): IntArray {
    val need = maxOf(0, minHeight - (bottom - top))
    var up = need / 2
    if (top - up < capTop) up = maxOf(0, top - capTop)
    val down = need - up
    return intArrayOf(left - side, top - up, right + side, bottom + down)
}
