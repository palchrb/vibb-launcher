package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.ui.search.MatchRank
import com.kidslauncher.mdm.ui.search.listCollator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Design 79 §3-§4: the phone book's order, the visit's frozen top, the search and the seen rule. */
class PhoneBookListTest {
    private val nb = listCollator(Locale.forLanguageTag("nb"))

    private var nextId = 1L
    private fun c(name: String, number: String) = RuleContact(nextId++, name, number, inbound = true, outbound = true)

    private val anna = c("Anna", "+4790000001")
    private val bestemor = c("Bestemor", "+4790000002")
    private val mamma = c("Mamma", "+4790000003")
    private val ola = c("Ola", "+4790000004")
    private val pappa = c("Pappa", "+4790000005")
    private val orjan = c("Ørjan", "+4790000006")
    private val aase = c("Åse", "+4790000007")
    private val aerlig = c("Ærlig", "+4790000008")
    private val ambulanse = c("Ambulanse", "113")
    private val politi = c("Politi", "112")
    private val everyone = listOf(pappa, ola, aase, ambulanse, mamma, orjan, anna, aerlig, bestemor, politi)
    private val alarm = setOf("113", "112")

    private fun missed(atMs: Long, count: Int = 1) = ContactMissed(MissedSummary(count, atMs))

    private fun names(list: PhoneBookList) = list.rows.map {
        when (it) {
            is PhoneBookRow.Contact -> it.contact.name
            is PhoneBookRow.Answer -> "answer:${it.request.name}"
            is PhoneBookRow.Waiting -> "waiting:${it.request.name}"
            PhoneBookRow.WaitingLabel -> "--venter--"
            PhoneBookRow.EmergencyDialer -> "112-row"
        }
    }

    private fun rows(
        contacts: List<RuleContact> = everyone,
        missed: Map<String, ContactMissed> = emptyMap(),
        top: Map<String, Long>? = visitTopAfter(null, missed),
        requests: List<RequestRow> = emptyList(),
        query: String = "",
        canAsk: Boolean = true,
        emergencyDialer: Boolean = false,
    ) = phoneBookRows(
        PhoneBookView(contacts, emergencyDialer), alarm.intersect(contacts.map { it.number }.toSet()), missed, emptyMap(), top,
        requests, query, nb, canAsk,
    )

    @Test
    fun `unseen missed calls first, newest first, then A-Å with nb's Æ Ø Å, the emergency numbers last`() {
        val list = rows(missed = mapOf(ola.number to missed(100), pappa.number to missed(200, 2)))
        assertEquals(
            listOf("Pappa", "Ola", "Anna", "Bestemor", "Mamma", "Ærlig", "Ørjan", "Åse", "Ambulanse", "Politi"),
            names(list),
        )
        val first = list.rows.first() as PhoneBookRow.Contact
        assertTrue(first.top)
        assertEquals(200L, first.shownMissedAtMs)
        assertFalse((list.rows[2] as PhoneBookRow.Contact).top)
        assertTrue(list.search)
    }

    @Test
    fun `an emergency number with an unseen missed call sits in the top like anyone`() {
        val list = rows(missed = mapOf(ambulanse.number to missed(300), ola.number to missed(100)))
        assertEquals(listOf("Ambulanse", "Ola", "Anna"), names(list).take(3))
        assertEquals("Politi", names(list).last())
        assertEquals(1, names(list).count { it == "Ambulanse" })
    }

    @Test
    fun `a request's answer once at the very top, the open ones under Venter på svar before the emergency numbers`() {
        val requests = listOf(
            RequestRow("r1", "Lina", "+4791111111", RequestLine.WAITING),
            RequestRow("r2", "Kim", "+4792222222", RequestLine.NOT_ADDED),
        )
        val list = rows(missed = mapOf(pappa.number to missed(5)), requests = requests)
        assertEquals(
            listOf(
                "answer:Kim", "Pappa", "Anna", "Bestemor", "Mamma", "Ola", "Ærlig", "Ørjan", "Åse",
                "--venter--", "waiting:Lina", "Ambulanse", "Politi",
            ),
            names(list),
        )
    }

    @Test
    fun `the visit's top is frozen - nothing leaves, a newcomer joins`() {
        val atStart = mapOf(pappa.number to missed(200), ola.number to missed(100))
        val top = visitTopAfter(null, atStart)
        // The kid opened Pappa's card (seen now): Pappa stays first, without a red line.
        val afterCard = mapOf(ola.number to missed(100))
        val kept = visitTopAfter(top, afterCard)
        val list = rows(missed = afterCard, top = kept)
        assertEquals(listOf("Pappa", "Ola", "Anna"), names(list).take(3))
        val pappaRow = list.rows.first() as PhoneBookRow.Contact
        assertTrue(pappaRow.top)
        assertNull(pappaRow.shownMissedAtMs)
        // Mamma calls during the visit: she joins the top, at her place by time (newest first).
        val withMamma = afterCard + (mamma.number to missed(300))
        val joined = visitTopAfter(kept, withMamma)
        assertEquals(listOf("Mamma", "Pappa", "Ola", "Anna"), names(rows(missed = withMamma, top = joined)).take(4))
        // Ola calls again during the visit: he stays where he is (his time in the top is the visit's).
        val olaAgain = withMamma + (ola.number to missed(400, 2))
        assertEquals(listOf("Mamma", "Pappa", "Ola"), names(rows(missed = olaAgain, top = visitTopAfter(joined, olaAgain))).take(3))
        // Before the visit's first missed calls are loaded there is no top.
        assertEquals("Anna", names(rows(missed = atStart, top = null)).first())
    }

    @Test
    fun `the fail-closed row and calls off`() {
        assertEquals(listOf("112-row"), names(rows(contacts = emptyList(), emergencyDialer = true)))
        assertFalse(rows(contacts = emptyList(), emergencyDialer = true).search)
        // Calls off: the emergency contacts only, no search, no requests, the query ignored.
        val off = rows(
            contacts = listOf(politi, ambulanse), canAsk = false, query = "x",
            requests = listOf(RequestRow("r1", "Lina", "+4791111111", RequestLine.WAITING)),
        )
        assertEquals(listOf("Ambulanse", "Politi"), names(off))
        assertFalse(off.search)
        assertNull(off.askName)
    }

    @Test
    fun `the search pill from 8 contacts, and it stays while it holds text`() {
        assertTrue(rows(contacts = everyone.take(8)).search)
        assertFalse(rows(contacts = everyone.take(7)).search)
        // A sync took the list under 8 while the kid was typing (QA N3).
        assertTrue(rows(contacts = everyone.take(3), query = "p").search)
        // Spaces alone keep the pill but don't search.
        assertEquals(3, rows(contacts = everyone.take(3), query = "  ").rows.size)
    }

    @Test
    fun `while searching the top dissolves - by rank, each A-Å, the waiting requests too, the emergency numbers last`() {
        val trener = c("Trener Kari", "+4790000010")
        val kamilla = c("Kamilla", "+4790000011")
        val skam = c("Oskar", "+4790000012")
        val katastrofe = c("Katastrofe", "114")
        val contacts = everyone + listOf(trener, kamilla, skam, katastrofe)
        val requests = listOf(RequestRow("r1", "Kai", "+4791111111", RequestLine.WAITING))
        val list = phoneBookRows(
            PhoneBookView(contacts, false), setOf("112", "113", "114"), mapOf(trener.number to missed(9)), emptyMap(),
            mapOf(trener.number to 9L), requests, "ka", nb, canAsk = true,
        )
        assertEquals(listOf("Kamilla", "Trener Kari", "Oskar", "--venter--", "waiting:Kai", "Katastrofe"), names(list))
        val ranks = list.rows.filterIsInstance<PhoneBookRow.Contact>().map { it.match?.rank }
        assertEquals(listOf(MatchRank.PREFIX, MatchRank.WORD, MatchRank.INSIDE, MatchRank.PREFIX), ranks)
        assertFalse(list.rows.filterIsInstance<PhoneBookRow.Contact>().any { it.top })
        assertFalse(list.nothingFound)
    }

    @Test
    fun `Be om å legge til - only from two letters, with nothing matching, requests included`() {
        val none = rows(query = "Lina")
        assertTrue(none.nothingFound)
        assertEquals("Lina", none.askName)
        assertTrue(none.rows.isEmpty())
        assertNull(rows(query = "L").askName)
        assertNull(rows(query = "4791").askName)
        assertTrue(rows(query = "4791").nothingFound)
        // A waiting request matches: no button (QA L1).
        val waiting = rows(query = "Lina", requests = listOf(RequestRow("r1", "Lina", "+4791111111", RequestLine.WAITING)))
        assertFalse(waiting.nothingFound)
        assertNull(waiting.askName)
        assertEquals(listOf("--venter--", "waiting:Lina"), names(waiting))
        // The answers don't show while searching.
        assertTrue(rows(query = "Kim", requests = listOf(RequestRow("r2", "Kim", "+4792222222", RequestLine.NOT_ADDED))).nothingFound)
        assertEquals("Mo Li", askName("  Mo Li "))
    }

    @Test
    fun `the row layout - inline at 288 dp below font 1_3, stacked from 1_3`() {
        assertEquals(128f, rowTextColumnDp(288f))
        assertEquals(RowLayout.INLINE, rowLayout(288f, 1.0f))
        assertEquals(RowLayout.INLINE, rowLayout(288f, 1.15f))
        assertEquals(RowLayout.STACKED, rowLayout(288f, 1.3f))
        assertEquals(RowLayout.STACKED, rowLayout(288f, 2.0f))
        // A screen too narrow for a 120 dp column.
        assertEquals(RowLayout.STACKED, rowLayout(270f, 1.0f))
        assertEquals(RowLayout.INLINE, rowLayout(280f, 1.0f))
    }

    @Test
    fun `seen - only rows shown at the top for a second, up to the call shown, a newer call still counts`() {
        val s = TopSightings()
        s.update(mapOf("pappa" to 200L, "ola" to 100L), active = true, nowMs = 0)
        // Ola scrolls out after half a second.
        s.update(mapOf("pappa" to 200L), active = true, nowMs = 500)
        // Pappa calls again at 1 200: his row shows the newer call from then on, for 0.3 s.
        s.update(mapOf("pappa" to 1_200L), active = true, nowMs = 1_200)
        assertEquals(mapOf("pappa" to 200L), s.seen(nowMs = 1_500))

        // Not resumed or focused: nothing counts.
        val t = TopSightings()
        t.update(mapOf("pappa" to 200L), active = false, nowMs = 0)
        assertEquals(emptyMap<String, Long>(), t.seen(nowMs = 5_000))

        // On screen in two stretches that add up to a second.
        val u = TopSightings()
        u.update(mapOf("ola" to 100L), active = true, nowMs = 0)
        u.update(emptyMap(), active = false, nowMs = 600)
        u.update(mapOf("ola" to 100L), active = true, nowMs = 2_000)
        val early = TopSightings().apply { restore(listOf(SightingState("ola", 100L, 900L, null))) }
        assertEquals(emptyMap<String, Long>(), early.seen(nowMs = 2_300))
        assertEquals(mapOf("ola" to 100L), u.seen(nowMs = 2_400))
    }

    @Test
    fun `the sightings survive a rotation`() {
        val s = TopSightings()
        s.update(mapOf("pappa" to 200L), active = true, nowMs = 0)
        val saved = s.state(nowMs = 700)
        val r = TopSightings().apply { restore(saved) }
        r.update(mapOf("pappa" to 200L), active = true, nowMs = 1_000)
        assertEquals(mapOf("pappa" to 200L), r.seen(nowMs = 1_300))
    }

    @Test
    fun `a seen mark only moves forward`() {
        assertEquals(500L, seenMarkAfter(null, 500))
        assertEquals(900L, seenMarkAfter(900, 500))
        assertEquals(900L, seenMarkAfter(500, 900))
    }

    @Test
    fun `the newest of a contact's missed calls`() {
        assertEquals(300L, ContactMissed(MissedSummary(1, 100), MissedSummary(1, 300)).lastAtMs)
        assertEquals(100L, ContactMissed(MissedSummary(1, 100), null).lastAtMs)
        assertEquals(mapOf("a" to 300L), visitTopAfter(null, mapOf("a" to ContactMissed(null, MissedSummary(1, 300)), "b" to ContactMissed())))
    }
}
