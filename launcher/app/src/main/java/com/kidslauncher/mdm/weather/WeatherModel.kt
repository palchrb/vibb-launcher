package com.kidslauncher.mdm.weather

import com.kidslauncher.mdm.server.dto.WeatherAnswer
import com.kidslauncher.mdm.server.dto.WeatherAnswerDark
import com.kidslauncher.mdm.server.dto.WeatherNow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/*
 * The lock glance's weather (design 75 §5), pure: what the date's line shows now ([weatherLine]), when to pull
 * ([weatherPullDue]), what a pull's result keeps ([weatherKeep], [weatherPolicyStep]), the card ([weatherCard]), its
 * open and fold ([weatherFold]), the nowcast line's insert window ([weatherNowShown]) and the line's place by the
 * date ([weatherPlace], [weatherWordsFit]). The server computed the words, the hours and the darkness; the phone only
 * picks the entry for its clock and maps keys to its own words (`WeatherWords`) and symbols (`WeatherSymbols`).
 */

/** D11: the answer is pulled again once it is this old (with the 30-min backstop: about hourly). */
const val WEATHER_PULL_AFTER_MS = 55 * 60_000L

/** D11: the card says "Oppdatert kl. HH:MM" past this. */
const val WEATHER_UPDATED_AFTER_MS = 60 * 60_000L

/** D11: the line hides (and an open card folds) past this - never a stale forecast as if it were today's. */
const val WEATHER_HIDE_AFTER_MS = 3 * 3_600_000L

/** A failed pull is not tried again for this long. */
const val WEATHER_TRY_GAP_MS = 5 * 60_000L

/** The R1 gap (server QA of design 75): after a nudged sync (a zone saved or deleted only nudges), a pull at most
 * this often, so a parent's change reaches the lock within a sync. */
const val WEATHER_NUDGE_GAP_MS = 3 * 60_000L

/** E8's L3: a new cell's place-less first answer is pulled once more this much later (Kartverket is asked after it). */
const val WEATHER_PLACE_RETRY_MS = 2 * 60_000L

/** The card's nowcast answer is reused this long (memory only). */
const val WEATHER_NOW_REUSE_MS = 2 * 60_000L

/** The nowcast line goes into the open card only when its answer came within this of the open (launcher QA M2): a
 * later one waits for the next open, so the card never grows under a finger. */
const val WEATHER_NOW_INSERT_MS = 1_000L

/** The card's nowcast call gives up after this. */
const val WEATHER_NOW_TIMEOUT_MS = 3_000L

/** The pull's own call timeout (launcher QA M3): a hung server never spends the sync's budget. */
const val WEATHER_PULL_TIMEOUT_MS = 15_000L

/** A rule key and its form, as the server sent them; [atMs] only with `from`. */
data class WeatherWordsKey(val key: String, val form: String, val atMs: Long?)

/** What the date's line shows at this moment: MET's symbol code (known to the phone, else null), the degrees, the
 * words (a pair the phone has words for, else null), the card's date (one of the answer's days, else null - no card,
 * no chevron) and the current period on today's card. */
data class WeatherLine(val symbol: String?, val temp: Int?, val words: WeatherWordsKey?, val card: String?, val current: Int?)

/**
 * The line now (§5.2): null when the switch is off, the answer isn't `ok`, MET last answered more than 3 h ago, or no
 * hour entry holds [nowMs] (a clock before the first entry counts as none), or the entry has neither a symbol nor
 * degrees. A symbol the phone doesn't draw, a key/form pair it has no words for and a card date the answer has no day
 * for are left out, one by one.
 */
fun weatherLine(
    answer: WeatherAnswer?,
    policyOn: Boolean,
    nowMs: Long,
    symbolKnown: (String) -> Boolean = { WeatherSymbols.drawable(it) != null },
    wordsKnown: (String, String) -> Boolean = { key, form -> WeatherWords.res(key, form) != null },
): WeatherLine? {
    if (!policyOn || answer == null || answer.status != "ok") return null
    val checked = answer.checkedAtMs ?: return null
    if (nowMs - checked > WEATHER_HIDE_AFTER_MS) return null
    val hour = answer.hours.firstOrNull { it.atMs <= nowMs && nowMs < it.atMs + 3_600_000L } ?: return null
    val symbol = hour.symbol?.takeIf(symbolKnown)
    if (symbol == null && hour.temp == null) return null
    val words = hour.words?.let { w ->
        WeatherWordsKey(w.key, w.form, w.atMs).takeIf { wordsKnown(w.key, w.form) && (w.form != "from" || w.atMs != null) }
    }
    val card = hour.card?.takeIf { date -> answer.days.any { it.date == date } }
    return WeatherLine(symbol, hour.temp, words, card, hour.current?.takeIf { it in 0..2 })
}

/**
 * Whether to pull now (§5.4): never with the switch off; not within [WEATHER_TRY_GAP_MS] of the last try; else when
 * the kept answer is older than [WEATHER_PULL_AFTER_MS] or there is none. [nudged] (the R1 gap: a sync the server
 * nudged - a zone's save or delete changes nothing in the policy): a pull when the last try is [WEATHER_NUDGE_GAP_MS]
 * old.
 */
fun weatherPullDue(policyOn: Boolean, pulledAtMs: Long?, triedAtMs: Long?, nowMs: Long, nudged: Boolean = false): Boolean {
    if (!policyOn) return false
    val sinceTry = triedAtMs?.let { nowMs - it }
    if (nudged && (sinceTry == null || sinceTry < 0 || sinceTry >= WEATHER_NUDGE_GAP_MS)) return true
    if (sinceTry != null && sinceTry in 0 until WEATHER_TRY_GAP_MS) return false
    return pulledAtMs == null || nowMs - pulledAtMs >= WEATHER_PULL_AFTER_MS || nowMs < pulledAtMs
}

/** The policy's part the weather follows: the parent's switch and the phone's location mode. */
data class WeatherPolicy(val on: Boolean, val locationMode: String)

/** What a policy change does to the kept answer (launcher QA M4). */
enum class WeatherPolicyStep { NONE, CLEAR, CLEAR_AND_PULL }

/**
 * The switch turning off clears the kept answer; the switch turning on, or any change of the location mode while on,
 * clears it and makes a pull due at once - so "Off" never leaves the last place's weather on the lock, and a zone
 * stand-in answers from the next pull. The first policy a process sees ([previous] null) changes nothing.
 */
fun weatherPolicyStep(previous: WeatherPolicy?, now: WeatherPolicy): WeatherPolicyStep = when {
    previous == null -> WeatherPolicyStep.NONE
    !now.on -> if (previous.on) WeatherPolicyStep.CLEAR else WeatherPolicyStep.NONE
    !previous.on || previous.locationMode != now.locationMode -> WeatherPolicyStep.CLEAR_AND_PULL
    else -> WeatherPolicyStep.NONE
}

/** A pull's outcome: the server's answer, or no usable one (no network, a timeout, a 429, a 5xx, a body that doesn't
 * decode). */
sealed class WeatherPullResult {
    data class Answered(val answer: WeatherAnswer) : WeatherPullResult()
    object Failed : WeatherPullResult()
}

/** A pull's answer that waits the hour ([WEATHER_PULL_AFTER_MS]): `ok` and `off`. `no_position` doesn't (E8's M2: a
 * zone picked just after it is tried at the next screen-on or nudge), nor `unavailable` or a failure - the try gaps
 * govern those. */
fun weatherSettled(status: String): Boolean = status == "ok" || status == "off"

/** A nudge waits until a pull actually runs (E8's M2): kept while [ran] is false. */
fun weatherNudgeKept(pending: Boolean, ran: Boolean): Boolean = pending && !ran

/** What the store keeps after a pull: an `ok` answer replaces the kept one; `off` and `no_position` clear it;
 * `unavailable`, an unknown status and a failure keep the old one (its own age still hides it after 3 h). */
fun weatherKeep(kept: WeatherAnswer?, got: WeatherPullResult): WeatherAnswer? = when (got) {
    is WeatherPullResult.Failed -> kept
    is WeatherPullResult.Answered -> when (got.answer.status) {
        "ok" -> got.answer
        "off", "no_position" -> null
        else -> kept
    }
}

/** What opens and folds the card. */
enum class WeatherEvent { TAP, LOCK_UP, STAGE_LEFT, STOP, LINE_GONE, CARD_GONE, VOIP }

/** The card's next state: a tap on the mark or the card toggles it (a line without a card has nothing to open);
 * every other event folds it. */
fun weatherFold(open: Boolean, event: WeatherEvent, hasCard: Boolean): Boolean = when (event) {
    WeatherEvent.TAP -> hasCard && !open
    else -> false
}

/** The nowcast line goes in (launcher QA M2) only when its answer came within [WEATHER_NOW_INSERT_MS] of the open,
 * the card is still the one opened ([generation] = [current]) and no finger holds the rows. */
fun weatherNowShown(openedAtMs: Long, answeredAtMs: Long, generation: Int, current: Int, rowsHeld: Boolean): Boolean =
    generation == current && !rowsHeld && answeredAtMs >= openedAtMs && answeredAtMs - openedAtMs <= WEATHER_NOW_INSERT_MS

/** Whether opening the card asks for the nowcast (code QA M1, D9's "at most every 2 min"): not while an ask runs, and
 * not within [WEATHER_NOW_REUSE_MS] of the last ask's start, whatever it brought (a failure, a 429). A clock going back
 * (a reboot's elapsed realtime) asks. */
fun weatherNowAsk(askedAtElapsed: Long?, inFlight: Boolean, nowElapsed: Long): Boolean =
    !inFlight && (askedAtElapsed == null || nowElapsed < askedAtElapsed || nowElapsed - askedAtElapsed >= WEATHER_NOW_REUSE_MS)

/** The nowcast line's key and minutes, when the phone has words for it; else none. */
fun weatherNowLine(now: WeatherNow?, known: (String) -> Boolean = { WeatherWords.nowRes(it) != null }): Pair<String, Int?>? {
    val line = now?.takeIf { it.status == "ok" }?.line ?: return null
    if (!known(line.key)) return null
    val minutes = line.minutes
    if (line.key.endsWith("_in") && (minutes == null || minutes <= 0)) return null
    return line.key to minutes
}

/** "Oppdatert kl." (D11): the answer's `checked_at` once it is more than 60 min old, else null. */
fun weatherUpdatedAt(answer: WeatherAnswer, nowMs: Long): Long? =
    answer.checkedAtMs?.takeIf { nowMs - it > WEATHER_UPDATED_AFTER_MS }

/** The minus sign the phone shows (U+2212). */
const val WEATHER_MINUS = "−"

/** The number as shown: "4", "−3" (U+2212), never "−0". */
fun weatherNumber(temp: Int): String = if (temp < 0) "$WEATHER_MINUS${abs(temp)}" else temp.toString()

/** "4°", "−3°", never "−0°". */
fun weatherDegrees(temp: Int): String = "${weatherNumber(temp)}°"

/**
 * The hour of a `from` form (§5.2), keyed on the strings' catalog ([catalogNb]: `values-nb`, else English) and the
 * phone's one clock format: nb "14" always (the string carries "kl.", E8), en "14:00" in 24 hours, "2 PM" in 12; a
 * start not on :00 shows its minutes ("14:30", "2:30 PM"). In the phone's zone.
 */
fun weatherHour(atMs: Long, zone: ZoneId, twentyFour: Boolean, catalogNb: Boolean): String {
    val time = Instant.ofEpochMilli(atMs).atZone(zone)
    return com.kidslauncher.mdm.ui.ClockFormat.weatherHour(time.hour, time.minute, twentyFour, catalogNb)
}

/** A period's precipitation as shown: one decimal in the catalog's form ("2,1" / "2.1"); null under 0.1 mm. */
fun weatherMm(mm: Double, locale: Locale): String? =
    if (mm < 0.1) null else String.format(locale, "%.1f", mm)

/** One of the card's three columns: the part ("morning" ...), the symbol code (known, else null), degrees, mm. */
data class WeatherCardColumn(val part: String, val symbol: String?, val temp: Int?, val mm: Double?, val current: Boolean)

/** The card (§5.2): [tomorrow] when its day isn't the phone's today ("I morgen"), the columns, the foot (the place,
 * low, high, the dark), and the "Oppdatert kl." time when due. */
data class WeatherCard(
    val tomorrow: Boolean,
    val columns: List<WeatherCardColumn>,
    val place: String?,
    val low: Int?,
    val high: Int?,
    val dark: WeatherAnswerDark?,
    val updatedAtMs: Long?,
)

/** The three parts the card knows, in order. */
val WEATHER_PARTS = listOf("morning", "afternoon", "evening")

/** The dark kinds the card has words for. */
val WEATHER_DARK_KINDS = setOf("at", "never", "all_day")

/** The card for [line] (its day), or null when the line has no card. Unknown parts and dark kinds are left out. */
fun weatherCard(
    answer: WeatherAnswer,
    line: WeatherLine,
    nowMs: Long,
    zone: ZoneId,
    symbolKnown: (String) -> Boolean = { WeatherSymbols.drawable(it) != null },
): WeatherCard? {
    val date = line.card ?: return null
    val day = answer.days.firstOrNull { it.date == date } ?: return null
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val tomorrow = runCatching { LocalDate.parse(date) != today }.getOrDefault(false)
    val columns = WEATHER_PARTS.mapIndexedNotNull { i, part ->
        val period = day.periods.firstOrNull { it.part == part } ?: return@mapIndexedNotNull null
        WeatherCardColumn(part, period.symbol?.takeIf(symbolKnown), period.temp, period.precipMm, !tomorrow && line.current == i)
    }
    if (columns.isEmpty()) return null
    val dark = day.dark?.takeIf { it.kind in WEATHER_DARK_KINDS && (it.kind != "at" || it.atMs != null) }
    return WeatherCard(tomorrow, columns, answer.place, day.low, day.high, dark, weatherUpdatedAt(answer, nowMs))
}

/** Where the mark sits (D15): beside the date or on its own line under it, with or without the words. */
enum class WeatherPlace { BESIDE_WORDS, LINE_WORDS, BESIDE, LINE }

/**
 * The mark's place (§5.3), the first that fits [lineDp] (the date's line's room): beside the date with the words, on
 * its own line with the words, beside without, its own line without. [dateDp] the date's drawn width, [gapDp] the gap
 * between them, [coreDp] symbol, degrees and chevron with their gaps, [wordsDp] the dot and the widest words planned
 * for (null = none).
 */
fun weatherPlace(lineDp: Float, dateDp: Float, gapDp: Float, coreDp: Float, wordsDp: Float?): WeatherPlace = when {
    wordsDp != null && dateDp + gapDp + coreDp + wordsDp <= lineDp -> WeatherPlace.BESIDE_WORDS
    wordsDp != null && coreDp + wordsDp <= lineDp -> WeatherPlace.LINE_WORDS
    dateDp + gapDp + coreDp <= lineDp -> WeatherPlace.BESIDE
    else -> WeatherPlace.LINE
}

/** Whether [wordsDp] still fits the kept [place] (between lock-ups the mark never moves; words that don't fit are
 * left out). A place without words shows none. */
fun weatherWordsFit(place: WeatherPlace, lineDp: Float, dateDp: Float, gapDp: Float, coreDp: Float, wordsDp: Float): Boolean = when (place) {
    WeatherPlace.BESIDE_WORDS -> dateDp + gapDp + coreDp + wordsDp <= lineDp
    WeatherPlace.LINE_WORDS -> coreDp + wordsDp <= lineDp
    WeatherPlace.BESIDE, WeatherPlace.LINE -> false
}

/** Whether the place is beside the date (the row's mark) or on its own line (the slot's). */
fun WeatherPlace.beside(): Boolean = this == WeatherPlace.BESIDE_WORDS || this == WeatherPlace.BESIDE
