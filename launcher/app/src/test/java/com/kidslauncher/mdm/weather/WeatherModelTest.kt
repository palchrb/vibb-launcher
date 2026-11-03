package com.kidslauncher.mdm.weather

import com.kidslauncher.mdm.lock.weatherHitRect
import com.kidslauncher.mdm.server.dto.WeatherAnswer
import com.kidslauncher.mdm.server.dto.WeatherAnswerDark
import com.kidslauncher.mdm.server.dto.WeatherAnswerDay
import com.kidslauncher.mdm.server.dto.WeatherAnswerHour
import com.kidslauncher.mdm.server.dto.WeatherAnswerPeriod
import com.kidslauncher.mdm.server.dto.WeatherAnswerWords
import com.kidslauncher.mdm.server.dto.WeatherNow
import com.kidslauncher.mdm.server.dto.WeatherNowLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** Design 75 §5.2-5.3, §7.3: the lock weather's pure rules - the entry for the clock, stale handling, when to pull (and
 * never with the switch off), the keep and policy steps, the fold, the nowcast's 1 s window, the numbers and the hour,
 * the card, the mark's place at §5.3's budgets and its hit rectangle. */
class WeatherModelTest {
    private val oslo: ZoneId = ZoneId.of("Europe/Oslo")

    /** 2026-11-03 15:00 in Oslo. */
    private val h0 = 1_793_714_400_000L
    private val hour = 3_600_000L

    private fun answer(checkedAt: Long = h0 - 20 * 60_000L, words: WeatherAnswerWords? = WeatherAnswerWords("rain", "from", h0 + hour), card: String = "2026-11-03") = WeatherAnswer(
        v = 1,
        status = "ok",
        checkedAtMs = checkedAt,
        place = "Bjølsen",
        hours = listOf(
            WeatherAnswerHour(h0, "partlycloudy_day", 7, words, card, 1),
            WeatherAnswerHour(h0 + hour, "rain", 6, WeatherAnswerWords("rain", "now"), card, 1),
        ),
        days = listOf(
            WeatherAnswerDay(
                "2026-11-03",
                listOf(
                    WeatherAnswerPeriod("morning", null, null, null),
                    WeatherAnswerPeriod("afternoon", "rain", 7, 1.6),
                    WeatherAnswerPeriod("evening", "partlycloudy_night", 3, 0.0),
                    WeatherAnswerPeriod("night", "rain", 1, 0.0),
                ),
                2, 7, WeatherAnswerDark("at", h0 + 2 * hour),
            ),
        ),
    )

    private val known: (String) -> Boolean = { it in setOf("partlycloudy_day", "rain", "partlycloudy_night") }
    private val words: (String, String) -> Boolean = { k, f -> k == "rain" && f in setOf("from", "now", "today") }
    private fun line(a: WeatherAnswer?, now: Long, on: Boolean = true) = weatherLine(a, on, now, known, words)

    @Test
    fun `the entry whose hour holds the clock`() {
        assertNull("before the first entry", line(answer(), h0 - 1))
        assertEquals(WeatherWordsKey("rain", "from", h0 + hour), line(answer(), h0)!!.words)
        assertEquals("partlycloudy_day", line(answer(), h0 + hour - 1)!!.symbol)
        val next = line(answer(), h0 + hour)!!
        assertEquals("the hour turned: 'Regn nå' without a pull", WeatherWordsKey("rain", "now", null), next.words)
        assertNull("past the last entry", line(answer(), h0 + 2 * hour))
    }

    @Test
    fun `stale, off and unknown pieces`() {
        // 3 h + 1 ms after MET last answered: hidden, whatever the entries say.
        val late = answer(checkedAt = h0 - WEATHER_HIDE_AFTER_MS - 1)
        assertNull(line(late, h0))
        assertTrue(line(answer(checkedAt = h0 - WEATHER_HIDE_AFTER_MS), h0) != null)
        assertNull("the switch off", line(answer(), h0, on = false))
        assertNull("not ok", line(answer().copy(status = "unavailable"), h0))
        assertNull(line(null, h0))
        // An unknown symbol: degrees alone; an unknown pair: no words; `from` without its hour: no words.
        assertNull(line(answer().copy(hours = listOf(WeatherAnswerHour(h0, "newcode", 7, null, "2026-11-03", 1))), h0)!!.symbol)
        assertNull(line(answer(words = WeatherAnswerWords("hail", "now")), h0)!!.words)
        assertNull(line(answer(words = WeatherAnswerWords("rain", "from", null)), h0)!!.words)
        // A card date the answer has no day for: no card (no chevron).
        assertNull(line(answer(card = "2026-11-04"), h0)!!.card)
        // "Oppdatert kl." only past 60 min.
        assertNull(weatherUpdatedAt(answer(checkedAt = h0), h0 + WEATHER_UPDATED_AFTER_MS))
        assertEquals(h0, weatherUpdatedAt(answer(checkedAt = h0), h0 + WEATHER_UPDATED_AFTER_MS + 1))
    }

    @Test
    fun `when to pull - never off, the hour, the try gap, a nudge`() {
        val now = h0
        assertFalse("off: no pull, ever", weatherPullDue(false, null, null, now))
        assertFalse("off: not even nudged", weatherPullDue(false, null, null, now, nudged = true))
        assertTrue("nothing kept", weatherPullDue(true, null, null, now))
        assertFalse(weatherPullDue(true, now - WEATHER_PULL_AFTER_MS + 1, now - WEATHER_PULL_AFTER_MS + 1, now))
        assertTrue(weatherPullDue(true, now - WEATHER_PULL_AFTER_MS, now - WEATHER_PULL_AFTER_MS, now))
        assertFalse("a failed try waits 5 min", weatherPullDue(true, null, now - WEATHER_TRY_GAP_MS + 1, now))
        assertTrue(weatherPullDue(true, null, now - WEATHER_TRY_GAP_MS, now))
        // The R1 gap: a nudged sync pulls when the last try is 3 min old, even with a fresh answer.
        assertTrue(weatherPullDue(true, now - 60_000L, now - WEATHER_NUDGE_GAP_MS, now, nudged = true))
        assertFalse(weatherPullDue(true, now - 60_000L, now - WEATHER_NUDGE_GAP_MS + 1, now, nudged = true))
        assertTrue("a clock set back", weatherPullDue(true, now + hour, now + hour, now))
    }

    @Test
    fun `what a pull keeps and what a policy change clears`() {
        val kept = answer()
        val fresh = answer(checkedAt = h0)
        assertEquals(fresh, weatherKeep(kept, WeatherPullResult.Answered(fresh)))
        assertNull(weatherKeep(kept, WeatherPullResult.Answered(WeatherAnswer(1, "off"))))
        assertNull(weatherKeep(kept, WeatherPullResult.Answered(WeatherAnswer(1, "no_position"))))
        assertEquals(kept, weatherKeep(kept, WeatherPullResult.Answered(WeatherAnswer(1, "unavailable"))))
        assertEquals(kept, weatherKeep(kept, WeatherPullResult.Failed))
        val on = WeatherPolicy(true, "interval")
        assertEquals(WeatherPolicyStep.NONE, weatherPolicyStep(null, on))
        assertEquals(WeatherPolicyStep.NONE, weatherPolicyStep(on, on))
        assertEquals(WeatherPolicyStep.CLEAR, weatherPolicyStep(on, on.copy(on = false)))
        assertEquals(WeatherPolicyStep.NONE, weatherPolicyStep(on.copy(on = false), WeatherPolicy(false, "off")))
        assertEquals(WeatherPolicyStep.CLEAR_AND_PULL, weatherPolicyStep(on.copy(on = false), on))
        assertEquals("Off never leaves the last place on the lock", WeatherPolicyStep.CLEAR_AND_PULL, weatherPolicyStep(on, on.copy(locationMode = "off")))
    }

    @Test
    fun `the card opens on a tap and every other event folds it`() {
        assertTrue(weatherFold(false, WeatherEvent.TAP, hasCard = true))
        assertFalse(weatherFold(true, WeatherEvent.TAP, hasCard = true))
        assertFalse("no card, nothing to open", weatherFold(false, WeatherEvent.TAP, hasCard = false))
        for (event in WeatherEvent.entries - WeatherEvent.TAP) assertFalse(event.name, weatherFold(true, event, hasCard = true))
    }

    @Test
    fun `the nowcast line goes in within 1 s, for the card opened, with no finger on the rows`() {
        assertTrue(weatherNowShown(1_000, 1_000 + WEATHER_NOW_INSERT_MS, 3, 3, rowsHeld = false))
        assertFalse("later: kept for the next open", weatherNowShown(1_000, 1_001 + WEATHER_NOW_INSERT_MS, 3, 3, rowsHeld = false))
        assertFalse("folded and opened again since", weatherNowShown(1_000, 1_200, 3, 4, rowsHeld = false))
        assertFalse("a finger on the rows", weatherNowShown(1_000, 1_200, 3, 3, rowsHeld = true))
        assertTrue(WEATHER_NOW_TIMEOUT_MS == 3_000L && WEATHER_NOW_INSERT_MS == 1_000L)
        // The line's keys: `_in` with its minutes, `_all` without; an unknown key or a dry answer is nothing.
        val k: (String) -> Boolean = { it in setOf("rain_in", "rain_all") }
        assertEquals("rain_in" to 25, weatherNowLine(WeatherNow(1, "ok", WeatherNowLine("rain_in", 25)), k))
        assertEquals("rain_all" to null, weatherNowLine(WeatherNow(1, "ok", WeatherNowLine("rain_all")), k))
        assertNull(weatherNowLine(WeatherNow(1, "ok", null), k))
        assertNull(weatherNowLine(WeatherNow(1, "ok", WeatherNowLine("hail_in", 5)), k))
        assertNull(weatherNowLine(WeatherNow(1, "none"), k))
    }

    @Test
    fun `degrees, millimetres and the hour`() {
        assertEquals("4°", weatherDegrees(4))
        assertEquals("−3°", weatherDegrees(-3))
        assertEquals("0°", weatherDegrees(0))
        assertEquals("2,1", weatherMm(2.08, Locale.forLanguageTag("nb-NO")))
        assertEquals("2.1", weatherMm(2.08, Locale.US))
        assertEquals("1.0", weatherMm(1.0, Locale.US))
        assertNull(weatherMm(0.04, Locale.US))
        val at = 1_793_718_000_000L // 2026-11-03 16:00 in Oslo
        assertEquals("16", weatherHour(at, oslo, twentyFour = true, catalogNb = true))
        assertEquals("16:00", weatherHour(at, oslo, twentyFour = true, catalogNb = false))
        assertEquals("4 PM", weatherHour(at, oslo, twentyFour = false, catalogNb = false))
        val half = at + 30 * 60_000L
        assertEquals("16:30", weatherHour(half, oslo, twentyFour = true, catalogNb = true))
        assertEquals("4:30 PM", weatherHour(half, oslo, twentyFour = false, catalogNb = false))
    }

    @Test
    fun `the card - its day, the current column, unknown parts left out`() {
        val l = line(answer(), h0)!!
        val card = weatherCard(answer(), l, h0, oslo, known)!!
        assertFalse(card.tomorrow)
        assertEquals(listOf("morning", "afternoon", "evening"), card.columns.map { it.part })
        assertEquals(listOf(false, true, false), card.columns.map { it.current })
        assertEquals("Bjølsen", card.place)
        assertEquals("at", card.dark!!.kind)
        // Tomorrow's card (from 18:00): "I morgen", no current column.
        val tomorrow = answer().let { a -> a.copy(days = a.days.map { it.copy(date = "2026-11-04") }) }
        val t = weatherCard(tomorrow, l.copy(card = "2026-11-04"), h0, oslo, known)!!
        assertTrue(t.tomorrow)
        assertTrue(t.columns.none { it.current })
        assertNull("no card date, no card", weatherCard(answer(), l.copy(card = null), h0, oslo, known))
        // An unknown dark kind is left out, the polar kinds kept.
        val polar = answer().let { a -> a.copy(days = a.days.map { it.copy(dark = WeatherAnswerDark("all_day")) }) }
        assertEquals("all_day", weatherCard(polar, l, h0, oslo, known)!!.dark!!.kind)
        val odd = answer().let { a -> a.copy(days = a.days.map { it.copy(dark = WeatherAnswerDark("eclipse")) }) }
        assertNull(weatherCard(odd, l, h0, oslo, known)!!.dark)
    }

    /** §5.3's budget table, exactly as the design writes it. */
    @Test
    fun `the mark's place at the design's budgets`() {
        // 360 dp, no profile (328 dp of line): date 92, core 70, words 125.
        assertEquals(WeatherPlace.BESIDE_WORDS, weatherPlace(328f, 92f, 8f, 70f, 125f))
        assertEquals(WeatherPlace.LINE_WORDS, weatherPlace(328f, 92f * 1.3f, 8f, 75f, 163f))
        assertEquals(WeatherPlace.BESIDE, weatherPlace(328f, 184f, 8f, 100f, 250f))
        // The Jelly Star with its profile (≈269 dp): the reference alone keeps it on its own line every day.
        assertEquals(WeatherPlace.LINE_WORDS, weatherPlace(269f, 105f, 8f, 74f, 125f))
        assertEquals(WeatherPlace.LINE_WORDS, weatherPlace(269f, 105f * 1.3f, 8f, 96f, 163f))
        assertEquals(WeatherPlace.LINE, weatherPlace(269f, 210f, 8f, 148f, 250f))
        assertEquals(WeatherPlace.BESIDE, weatherPlace(328f, 92f, 8f, 70f, null))
        // Between lock-ups the place holds; words that no longer fit are dropped, never cut or moved.
        assertTrue(weatherWordsFit(WeatherPlace.BESIDE_WORDS, 328f, 92f, 8f, 70f, 150f))
        assertFalse(weatherWordsFit(WeatherPlace.BESIDE_WORDS, 328f, 92f, 8f, 70f, 160f))
        assertTrue(weatherWordsFit(WeatherPlace.LINE_WORDS, 269f, 105f, 8f, 74f, 190f))
        assertFalse(weatherWordsFit(WeatherPlace.BESIDE, 328f, 92f, 8f, 70f, 10f))
        assertFalse(weatherWordsFit(WeatherPlace.LINE, 328f, 92f, 8f, 70f, 10f))
    }

    /** Launcher QA H1 and the design review's L1: 48 dp tall, 8 dp wider each side; the top capped at the clock's
     * baseline, the rest below. */
    @Test
    fun `the mark's hit rectangle`() {
        // A 20 px tall mark, room above: 14 up, 14 down.
        assertEquals(listOf(92, 86, 208, 134), weatherHitRect(100, 100, 200, 120, 48, 8, capTop = 0).toList())
        // The clock's baseline 4 px above: 4 up, the other 24 below.
        assertEquals(listOf(92, 96, 208, 144), weatherHitRect(100, 100, 200, 120, 48, 8, capTop = 96).toList())
        // Already tall enough: only wider.
        assertEquals(listOf(92, 100, 208, 160), weatherHitRect(100, 100, 200, 160, 48, 8, capTop = 0).toList())
    }
}
