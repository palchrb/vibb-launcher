package me.vibb.music.logic

import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.model.LibraryParser
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.storytel.Mint
import me.vibb.music.sync.downloadPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Storytel's rules on the phone (design 26b §3, §4.3, §4.5, §5.3, §5.4, §5.7), vibb
 * `tests/storytel_api.py` 2, `storytel_expand.py` 1, 6, 7, `storytel_sync.py` 4, 5, 7 and
 * `storytel_sonos.py` 1, 7, 14, plus the spec's new rows.
 */
class StorytelRulesTest {
    private val books = listOf("160661", "160662", "160663", "160664", "160665")
    private fun series(id: Long = 7, cache: Int = -1, order: String = "auto") =
        entry(id, Source.STORYTEL, target = "storytel:series:26175", cache = cache, order = order)

    @Test
    fun `2 target parsing is pure and rejects junk`() {
        assertTrue(isStorytel("storytel:series:26175"))
        assertTrue(isStorytel("storytel:junk"))
        assertFalse(isStorytel("https://open.spotify.com/x"))
        assertFalse(isStorytel(null))
        assertEquals(StorytelTarget(StorytelKind.SERIES, "26175"), storytelTarget("storytel:series:26175"))
        assertEquals(StorytelTarget(StorytelKind.BOOK, "160661"), storytelTarget("storytel:book:160661"))
        assertEquals(StorytelTarget(StorytelKind.BOOK, "a_B-9"), storytelTarget("storytel:book:a_B-9"))
        for (junk in listOf("storytel:junk", "nonsense", "", "storytel:series:", "storytel:book:1 2", "storytel:book:1/2", "storytel:author:1", " storytel:book:1", "storytel:book:1\n", "STORYTEL:book:1")) {
            assertNull(junk, storytelTarget(junk))
        }
        assertNull(storytelTarget(null))
        assertEquals("storytel:series:26175", seriesTarget("26175"))
        assertEquals("storytel:book:160661", bookTarget("160661"))
    }

    private fun libraryJson(entries: String) = """{"v":1,"version":"0123456789abcdef","categories":[],"entries":[$entries],"files":[],"covers":[]}"""

    private fun storytelEntry(id: Int, target: String, cache: Int, source: String = "storytel") =
        """{"id":$id,"key":"k$id","name":"Kokosbananas","source":"$source","target":"$target","cache":$cache,"items":"0123456789abcdef","lan":true}"""

    @Test
    fun `7 the library reads a Storytel entry - cache 0 is all, a podcast keeps 0, a bad target is hidden with its id kept`() {
        val lib = LibraryParser.parse(
            libraryJson(
                listOf(
                    storytelEntry(1, "storytel:series:26175", 0),
                    storytelEntry(2, "https://example.org/feed.xml", 0, source = "rss"),
                    storytelEntry(3, "storytel:junk", 5),
                    storytelEntry(4, "storytel:book:999", 5),
                    storytelEntry(5, "storytel:series:1", 101),
                ).joinToString(","),
            ),
        )!!
        val kokos = lib.entry(1)!!
        assertEquals(Source.STORYTEL, kokos.source)
        assertEquals("storytel cache 0 must become -1", -1, kokos.cache)
        assertEquals("a podcast keeps cache 0", 0, lib.entry(2)!!.cache)
        assertFalse("never lan", kokos.lan)
        assertTrue("Storytel's API and CDN are public", publicOnly(kokos))
        assertEquals("0123456789abcdef", kokos.items)
        assertEquals(5, lib.entry(4)!!.cache)
        assertEquals(listOf(1L, 2L, 4L), lib.entries.map { it.id })
        assertEquals("unusable ones keep their ids: nothing of them is pruned", setOf(1L, 2L, 3L, 4L, 5L), lib.entryIds)
    }

    @Test
    fun `6 a Storytel series plays book 1 first, and the order setting still applies`() {
        assertFalse(naturallyNewestFirst(series()))
        assertEquals(books, playOrder(series(), books))
        assertEquals(books.reversed(), playOrder(series(order = "newest_first"), books))
    }

    @Test
    fun `1 locally only downloaded books show, in reading order, keyed by id - and an entry with none shows nothing`() {
        val e = series()
        val listed = items(e.id, books, gone = books.toSet())
        val none = snapshot(library(listOf(e)), listed)
        assertTrue("download-only: nothing on the phone, nothing shown", none.visible(e).isEmpty())
        assertTrue("the carousel leaves it out", none.entries.isEmpty())
        assertEquals("every book is still listed", books, none.listed[e.id]!!.map { it.key })

        val some = snapshot(library(listOf(e)), listed, downloads = listOf(done(e.id, "160663", url = "storytel:book:160663"), done(e.id, "160661", url = "storytel:book:160661")))
        assertEquals(listOf("160661", "160663"), some.visible(e).map { it.key })
        assertEquals(listOf("160661", "160663"), some.order(e).map { it.key })
        assertEquals(listOf(e), some.entries)
    }

    @Test
    fun `step 1 - the download plan isn't wired yet - a listed Storytel entry fetches and prunes nothing`() {
        val e = series()
        val snap = snapshot(library(listOf(e)), items(e.id, books, gone = books.toSet()))
        val plan = downloadPlan(snap, emptyList(), 0)
        assertTrue(plan.wanted.isEmpty())
        assertTrue(plan.prune.isEmpty())
        assertEquals(listOf(DownloadState(e.id, "0123456789abcdef", 0, 0, null)), plan.states)
    }

    @Test
    fun `7 a never-synced target expands to nothing`() {
        val e = series()
        val snap = snapshot(library(listOf(e)), emptyList(), listings = emptyMap())
        assertTrue(snap.visible(e).isEmpty())
        assertTrue(snap.entries.isEmpty())
    }

    @Test
    fun `fetchable - a url, or a Storytel book`() {
        val book = PlayItem(7, "160661", "En", null, false, 700_000)
        assertTrue(fetchable(series(), book))
        assertFalse("a withdrawn episode can't be fetched", fetchable(entry(1), book))
        assertTrue(fetchable(entry(1), book.copy(url = "https://cdn.example.org/1.mp3")))
    }

    @Test
    fun `4 the keep set is the first N in reading order plus the bookmark and the next`() {
        assertEquals(books.toSet(), storytelKeepSet(series(cache = -1), books, null))
        assertEquals(setOf("160661", "160662"), storytelKeepSet(series(cache = 2), books, null))
        assertEquals("the first N whatever the play order", setOf("160661", "160662"), storytelKeepSet(series(cache = 2, order = "newest_first"), books, null))
        assertEquals(setOf("160661", "160662", "160664", "160665"), storytelKeepSet(series(cache = 2), books, "160664"))
        assertEquals("the next wraps", setOf("160661", "160662", "160665"), storytelKeepSet(series(cache = 2), books, "160665"))
        assertEquals("next in play order", setOf("160661", "160662", "160664", "160663"), storytelKeepSet(series(cache = 2, order = "newest_first"), books, "160664"))
        assertEquals("a bookmark the list no longer names", setOf("160661"), storytelKeepSet(series(cache = 1), books, "999"))
        assertTrue("depth 0 keeps nothing", storytelKeepSet(series(cache = 0), books, "160662").isEmpty())
        assertEquals(setOf("160661"), storytelKeepSet(series(cache = 1), listOf("160661"), "160661"))
    }

    @Test
    fun `4 the download order - the bookmarked book first, then reading order, what is on the phone skipped`() {
        val keep = books.toSet()
        assertEquals(books, storytelWanted(keep, books, emptySet(), null))
        assertEquals(listOf("160663", "160661", "160662", "160664", "160665"), storytelWanted(keep, books, emptySet(), "160663"))
        assertEquals("3 a re-sync skips what is on the phone", listOf("160662", "160665"), storytelWanted(keep, books, setOf("160661", "160663", "160664"), "160663"))
        assertEquals(listOf("160661", "160662"), storytelWanted(setOf("160661", "160662"), books, emptySet(), null))
    }

    @Test
    fun `the source id - a Storytel book's marker, else the url`() {
        val book = PlayItem(7, "160661", "En", null, false, 700_000)
        assertEquals("storytel:book:160661", sourceId(series(), book))
        assertEquals("never a url, even if one were listed", "storytel:book:160661", sourceId(series(), book.copy(url = "https://cdn.example.invalid/x?token=FAKE-SIG-1")))
        assertEquals("https://cdn.example.org/1.mp3", sourceId(entry(1), book.copy(url = "https://cdn.example.org/1.mp3")))
        assertNull(sourceId(entry(1), book))
        // A re-mint never reads as a new url: the retry waits, the partial stays.
        assertFalse(mayRetry("storytel:book:160661", "http_503", 1_000, 1, sourceId(series(), book)!!, 1_000 + 60_000))
    }

    @Test
    fun `retry classes - no_audio a day, every http code transient (M7)`() {
        assertTrue(storytelPermanentFailure(NO_AUDIO))
        assertFalse(storytelPermanentFailure("http_403"))
        assertFalse(storytelPermanentFailure("http_404"))
        assertFalse(storytelPermanentFailure("network"))
        assertFalse(storytelPermanentFailure(null))
        assertEquals(RETRY_PERMANENT_MS, storytelRetryDelayMs(NO_AUDIO, 1))
        assertEquals("a 403 from an expired token heals on the next mint", RETRY_FIRST_MS, storytelRetryDelayMs("http_403", 1))
        assertEquals(RETRY_FIRST_MS * 4, storytelRetryDelayMs("http_429", 3))
        assertEquals(RETRY_MAX_MS, storytelRetryDelayMs("http_500", 30))
        assertEquals(RETRY_FIRST_MS, retryDelayFor(series(), "http_404", 1))
        assertEquals("phase 1's rule for everything else", RETRY_PERMANENT_MS, retryDelayFor(entry(1), "http_404", 1))
    }

    @Test
    fun `a mint's item error, and which mints stop Storytel for the run`() {
        assertEquals(NO_AUDIO, mintItemError(Mint.NoAudio(403)))
        assertEquals("http_503", mintItemError(Mint.Failed(503)))
        assertEquals("http_401", mintItemError(Mint.Failed(401)))
        assertEquals("network", mintItemError(Mint.Unreachable("deadline")))
        for (m in listOf(Mint.Url("https://cdn.example.invalid/x"), Mint.NotConfigured, Mint.Refused(1), Mint.LoginFailed("no jwt"))) {
            assertNull("$m", mintItemError(m))
        }
        assertTrue(mintStopsRun(Mint.NotConfigured))
        assertTrue(mintStopsRun(Mint.Refused(1)))
        assertTrue("no JWT or no login server: one login per run, not per book", mintStopsRun(Mint.LoginFailed("no jwt")))
        for (m in listOf(Mint.NoAudio(404), Mint.Failed(500), Mint.Unreachable("unreachable"), Mint.Url("https://cdn.example.invalid/x"))) {
            assertFalse("$m", mintStopsRun(m))
        }
    }

    @Test
    fun `5 the 1_5 GB floor`() {
        assertEquals(1_500_000_000L, STORYTEL_FREE_FLOOR)
        assertTrue(storytelRoom(1_500_000_000L))
        assertFalse(storytelRoom(1_499_999_999L))
        assertFalse(storytelRoom(1000))
    }

    @Test
    fun `what a Storytel entry waits for (M4)`() {
        val gb = 1_000_000_000L
        assertEquals(Waiting.LOGIN, storytelWaiting("none", 10 * gb, null))
        assertEquals(Waiting.LOGIN, storytelWaiting("refused", 10 * gb, Waiting.WIFI))
        assertEquals("storage between 0.5 and 1.5 GB", Waiting.STORAGE, storytelWaiting("ok", gb, null))
        assertEquals(Waiting.STORAGE, storytelWaiting("ok", gb, Waiting.WIFI))
        assertEquals("else the shared rule", Waiting.WIFI, storytelWaiting("ok", 10 * gb, Waiting.WIFI))
        assertNull(storytelWaiting("ok", 10 * gb, null))
        assertEquals("login", Waiting.LOGIN.wire)
    }

    @Test
    fun `7 the consumableId parameter, exact - never a substring of the token`() {
        val uri = "https://fastly-ng.storytel.example.invalid/mp3encoder-128/x.mp3?consumableId=331854&isbn=9788&token=aBc331767xyz"
        assertEquals("331854", consumableIdOf(uri))
        assertEquals(listOf("331854"), listOf("331854", "331855", "331767").filter { it == consumableIdOf(uri) })
        assertEquals("331855", consumableIdOf("https://x.example.invalid/y?token=abc331854xyz&consumableId=331855"))
        assertEquals("the first one", "1", consumableIdOf("https://x.example.invalid/y?consumableId=1&consumableId=2"))
        assertEquals("1", consumableIdOf("https://x.example.invalid/y?consumableId=1#frag"))
        assertEquals("names are decoded as parse_qs does", "7", consumableIdOf("https://x.example.invalid/y?consumable%49d=7"))
        assertNull(consumableIdOf("https://x.example.invalid/y?consumableId="))
        assertNull(consumableIdOf("https://x.example.invalid/y?consumableId"))
        assertNull(consumableIdOf("https://x.example.invalid/y?xconsumableId=1"))
        assertNull(consumableIdOf("https://x.example.invalid/consumableId=1"))
        assertNull(consumableIdOf(null))
        assertNull(consumableIdOf(""))
    }

    @Test
    fun `sameStream - a marker by consumableId, else 1b's normUri equality`() {
        val live = "https://cdn.example.invalid/mp3/a.mp3?consumableId=331854&token=FAKE-SIG-331767"
        assertTrue(sameStream("storytel:book:331854", live))
        assertFalse("the token's digits never match", sameStream("storytel:book:331767", live))
        assertFalse("no consumableId: not ours", sameStream("storytel:book:331854", "https://cdn.example.invalid/mp3/331854.mp3"))
        assertFalse("a series target is no marker", sameStream("storytel:series:331854", live))
        assertTrue(sameStream("https://cdn.example.org/a%20b.mp3", "http://cdn.example.org/a b.mp3"))
        assertFalse(sameStream("https://cdn.example.org/a.mp3", "https://cdn.example.org/b.mp3"))
        assertFalse(sameStream(null, live))
        assertFalse(sameStream("", ""))
        assertFalse(sameStream("storytel:book:1", null))
    }

    @Test
    fun `storedUri - a Storytel play stores the marker, never the signed URL (M1)`() {
        val signed = "https://cdn.example.invalid/mp3/a.mp3?consumableId=160661&token=FAKE-SIG-1"
        assertEquals("storytel:book:160661", storedUri(series(), "160661", signed))
        assertEquals("from the live uri when no item is known", "storytel:book:160661", storedUri(series(), null, signed))
        assertNull("nothing to name: nothing stored, never the URL", storedUri(series(), null, "https://cdn.example.invalid/x?token=FAKE-SIG-1"))
        assertEquals("https://cdn.example.org/1.mp3", storedUri(entry(1), "e1", "https://cdn.example.org/1.mp3"))
        assertNull(storedUri(entry(1), "e1", null))
    }

    @Test
    fun `redactUri - scheme, host and path, the token cut`() {
        assertEquals("https://cdn.example.invalid/mp3/a.mp3", redactUri("https://cdn.example.invalid/mp3/a.mp3?consumableId=1&token=FAKE-SIG-1"))
        assertEquals("https://cdn.example.invalid/a", redactUri("https://user:pw@cdn.example.invalid/a#x"))
        assertEquals("https://cdn.example.invalid", redactUri("https://u@cdn.example.invalid?token=FAKE-SIG-1"))
        assertEquals("x-rincon-queue:RINCON_000E58A0B1C201400", redactUri("x-rincon-queue:RINCON_000E58A0B1C201400#0"))
        assertEquals("storytel:book:1", redactUri("storytel:book:1"))
        assertEquals("", redactUri(null))
        assertFalse(redactUri("http://192.168.1.20:8000/a.mp3?token=FAKE-SIG-9@x").contains("FAKE-SIG"))
    }

    @Test
    fun `14 the length looked up by consumableId, scoped to Storytel`() {
        val queue = listOf(PlayItem(7, "331854", "HP1", null, false, 33_666_000), PlayItem(7, "331855", "HP2", null, false, 30_000_000))
        val uri = "https://cdn.example.invalid/x.mp3?consumableId=331855&token=abc331854xyz"
        assertEquals("by the parameter, not the token's digits", 30_000_000L, durationByConsumableId(series(), uri, queue))
        assertNull(durationByConsumableId(series(), "https://cdn.example.invalid/y?consumableId=999", queue))
        assertNull(durationByConsumableId(series(), null, queue))
        assertNull("scoped to Storytel entries", durationByConsumableId(entry(1), uri, queue))
    }
}
