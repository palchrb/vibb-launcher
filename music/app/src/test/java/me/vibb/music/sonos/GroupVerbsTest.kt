package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 53 §2.6-§2.7, §2.9: the grouping verbs on other rooms (soco's `join`/`unjoin` over UPnP) - off our actor,
 * one at a time, each call within its 2 s budget, the join's cap before its `x-rincon:`, members first.
 */
class GroupVerbsTest {
    private val shared = mutableListOf<String>()
    private val stua = FakeSpeaker("RINCON_STUA01400").apply { this.shared = this@GroupVerbsTest.shared }
    private val kjokken = FakeSpeaker("RINCON_KJOKKEN01400").apply { vol = 80; this.shared = this@GroupVerbsTest.shared }
    private val bad = FakeSpeaker("RINCON_BAD01400").apply { vol = 20; this.shared = this@GroupVerbsTest.shared }
    private val byUid = listOf(stua, kjokken, bad).associateBy { it.uid }

    /** Every resolve: (uid, verify, budget). */
    private val resolved = mutableListOf<Triple<String, Boolean, Long?>>()
    private val verbs = GroupVerbs({ uid, verify, budget ->
        resolved += Triple(uid, verify, budget)
        byUid[uid] ?: throw UnknownUid(uid)
    })

    private fun commands() = shared.filter { !it.contains(" get") }

    @Test
    fun `a join caps a louder room first, then x-rincon with empty metadata on the joining room - 2 s a call, the uid verified`() {
        val out = runBlocking { verbs.join(stua.uid, listOf(kjokken.uid), cap = 40, still = { true }) }
        assertEquals(listOf("${kjokken.uid} volume 40", "${kjokken.uid} join ${stua.uid}"), commands())
        assertEquals("x-rincon:${stua.uid}", kjokken.lastUri)
        assertEquals("empty metadata", "", kjokken.lastMeta)
        assertEquals(listOf(kjokken.uid), out.done)
        assertTrue(resolved.all { it.third == GroupVerbs.VERB_BUDGET_MS })
        assertTrue("the uid at the ip is verified before the verbs (as a play)", resolved.first().second)
        assertTrue("nothing to our coordinator", stua.log.isEmpty())
    }

    @Test
    fun `a quieter room keeps its volume, no cap reads nothing - and a group row joins members first, its coordinator last`() {
        // Kjøkken + Bad, Kjøkken coordinating: Bad first (a coordinator joined first would hand its stream to Bad)
        runBlocking { verbs.join(stua.uid, listOf(kjokken.uid, bad.uid), cap = 40, still = { true }) }
        assertEquals(listOf("${bad.uid} join ${stua.uid}", "${kjokken.uid} volume 40", "${kjokken.uid} join ${stua.uid}"), commands())
        shared.clear()
        runBlocking { verbs.join(stua.uid, listOf(bad.uid), cap = null, still = { true }) }
        assertEquals("no cap: no GetVolume", listOf("${bad.uid} join ${stua.uid}"), shared)
        assertEquals(listOf(bad.uid, kjokken.uid), joinOrder(listOf(kjokken.uid, bad.uid)))
    }

    @Test
    fun `a closed gate, a follow between two rooms, or our uid among the row's members sends nothing more`() {
        runBlocking { verbs.join(stua.uid, listOf(kjokken.uid), cap = 40, still = { false }) }
        assertTrue("suspended, released, another play: nothing", shared.isEmpty())
        // N2: the session moved on (a follow) after the first room - the rest of the row stays unsent
        var asks = 0
        val out = runBlocking { verbs.join(stua.uid, listOf(kjokken.uid, bad.uid), cap = 40, still = { asks++ == 0 }) }
        assertEquals(listOf("${bad.uid} join ${stua.uid}"), commands())
        assertTrue(out.stopped)
        shared.clear()
        runBlocking { verbs.join(stua.uid, listOf(kjokken.uid, stua.uid), cap = 40, still = { true }) }
        assertTrue("a row that holds our coordinator is never joined", shared.isEmpty())
    }

    @Test
    fun `a switched-off room is no contact - its join isn't sent, the next room still goes`() {
        kjokken.failOn = mapOf("getvolume" to { SpeakerUnreachable("off") })
        val out = runBlocking { verbs.join(stua.uid, listOf(bad.uid, kjokken.uid), cap = 40, still = { true }) }
        assertEquals(listOf(kjokken.uid), out.failed)
        assertFalse(kjokken.log.any { it.startsWith("join") })
        bad.failOn = mapOf("join" to { SpeakerUnreachable("off") })
        assertEquals(listOf(bad.uid), runBlocking { verbs.join(stua.uid, listOf(bad.uid), cap = null, still = { true }) }.failed)
    }

    @Test
    fun `a leave is BecomeCoordinatorOfStandaloneGroup on that room only`() {
        assertTrue(runBlocking { verbs.leave(kjokken.uid) })
        assertEquals(listOf("standalone"), kjokken.log)
        assertTrue(stua.log.isEmpty() && bad.log.isEmpty())
        kjokken.failOn = mapOf("standalone" to { SpeakerUnreachable("off") })
        assertFalse("no answer: the failure line", runBlocking { verbs.leave(kjokken.uid) })
        assertFalse("an unknown room", runBlocking { verbs.leave("RINCON_GONE01400") })
    }
}
