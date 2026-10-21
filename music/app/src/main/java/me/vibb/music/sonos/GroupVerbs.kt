package me.vibb.music.sonos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/*
 * Grouping in "Spill av på" (design 53 §2.6-§2.7, §2.12; ours - vibb has no grouping verbs, the household groups in
 * the Sonos app). The verbs are soco's (`SoCo.join`: `SetAVTransportURI` to `x-rincon:<coordinator>` with empty
 * metadata on the joining room; `SoCo.unjoin`: `BecomeCoordinatorOfStandaloneGroup` on the leaving one), over UPnP
 * like every other command - never the speaker's WebSocket (`groups:1 modifyGroupMembers` works, but the local socket
 * is no official API and may close with a firmware update: without it we lose speed, never a feature).
 *
 * Verbs on OTHER rooms run here, on IO, one at a time (one Mutex), never in our room's actor: polls, Pause, the volume
 * keys and a hand-off never wait behind a switched-off room. Only a "−" on our own coordinator runs in our actor
 * (`SonosTransport.leave`): it orders against our plays and starts the follow. Android-free; GroupVerbsTest.
 */

/** "+": the rooms of a row in the order they join - its members first, its coordinator last ([row] is coordinator first). */
fun joinOrder(row: List<String>): List<String> = row.drop(1) + row.take(1)

class GroupVerbs(private val speakers: SpeakerResolver, private val log: (String) -> Unit = {}) {
    private val mutex = Mutex()

    /** A verb's result: the rooms that answered, those that didn't, and whether its gate ended it with the rest unsent. */
    data class Outcome(val done: List<String>, val failed: List<String>, val stopped: Boolean)

    /**
     * "+" (§2.7, §2.9): each room of [row] (a row's members, coordinator first) joins [coordinator] in [joinOrder] - a
     * row's coordinator joined first would hand its stream to a member, which would then sound for a moment. Each room
     * is brought down to [cap] first when louder (it never sounds above it) and then joined; [joining] hears it before
     * its cap (the controller's `knownMembers`: never counted as a joiner from outside). [still] - the coordinator and
     * the controller's generation the job captured, and the gates - is asked before each room: false ends the job with
     * the rest of the row unsent (no room ever joins a coordinator we left). [VERB_BUDGET_MS] a call.
     */
    suspend fun join(
        coordinator: String,
        row: List<String>,
        cap: Int?,
        still: suspend () -> Boolean,
        joining: suspend (String) -> Unit = {},
    ): Outcome = mutex.withLock {
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (coordinator in row) {
            // a row that holds our coordinator is ours (or we sit in it, grouped away): nothing to join
            log("group: a join refused - the row holds our coordinator")
            return@withLock Outcome(done, failed, stopped = true)
        }
        val order = joinOrder(row.distinct()).filter { it != coordinator }
        for (uid in order) {
            if (!still()) {
                log("group: the join stopped (the session moved on) - ${order.size - done.size - failed.size} room(s) unsent")
                return@withLock Outcome(done, failed, stopped = true)
            }
            joining(uid)
            try {
                val spk = speakers.speaker(uid, verify = true, budgetMs = VERB_BUDGET_MS)
                capJoiner(spk, cap)
                speakers.speaker(uid, verify = false, budgetMs = VERB_BUDGET_MS).joinGroup(coordinator)
                log("group: $uid joined $coordinator")
                done += uid
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("group: $uid didn't join (${e.javaClass.simpleName})")
                failed += uid
            }
        }
        Outcome(done, failed, stopped = false)
    }

    /** "−" on a member that isn't our coordinator (§2.7): `BecomeCoordinatorOfStandaloneGroup` on it; it stops, the rest play on. */
    suspend fun leave(uid: String): Boolean = mutex.withLock {
        try {
            speakers.speaker(uid, verify = true, budgetMs = VERB_BUDGET_MS)
            speakers.speaker(uid, verify = false, budgetMs = VERB_BUDGET_MS).becomeStandalone()
            log("group: $uid left the group")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("group: $uid didn't leave (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * §2.9: a joining room louder than the cap is set to it before its `x-rincon:` (with no cap nothing is read). A room
     * that doesn't answer its `GetVolume` is no contact - its join would fail too; a fault on the volume alone is
     * logged and the join goes on.
     */
    private fun capJoiner(spk: SpeakerControl, cap: Int?) {
        if (cap == null || cap !in 1..99) return
        val was = try {
            spk.volume()
        } catch (e: UpnpFault) {
            log("group: a joining room's volume unknown (${e.javaClass.simpleName}) - joining anyway")
            return
        }
        if (was > cap) {
            spk.setVolume(cap)
            log("group: a joining room at $was brought down to the cap $cap")
        }
    }

    companion object {
        /** Design 53 §2.6: each SOAP call of a grouping verb within this (a switched-off room fails in seconds). */
        const val VERB_BUDGET_MS = 2_000L
    }
}

/**
 * The volumes of our group's other rooms (design 53 §2.12) as the controller sees them: [set] is latest-wins per room
 * and never blocks; [read] asks each room in parallel and hands the answers ([then]) back on the main thread.
 */
interface MemberVolumes {
    fun set(uid: String, volume: Int)

    fun read(uids: List<String>, then: (Map<String, Int>) -> Unit)

    companion object {
        val NONE = object : MemberVolumes {
            override fun set(uid: String, volume: Int) {}

            override fun read(uids: List<String>, then: (Map<String, Int>) -> Unit) {}
        }
    }
}

/**
 * [MemberVolumes] over UPnP (§2.12): one conflated channel per room consumed by one coroutine on IO - a drag sends at
 * most one `SetVolume` per room at a time, the latest - [SET_BUDGET_MS] a set; reads in parallel, [READ_PER_MS]
 * each. Never our actor: a switched-off member never delays a Pause. [post] runs on the main thread.
 */
class RoomVolumes(
    private val speakers: SpeakerResolver,
    private val scope: CoroutineScope,
    private val post: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) : MemberVolumes {
    private val sets = ConcurrentHashMap<String, Channel<Int>>()

    override fun set(uid: String, volume: Int) {
        val ch = sets.computeIfAbsent(uid) { u ->
            Channel<Int>(Channel.CONFLATED).also { c ->
                scope.launch(Dispatchers.IO) {
                    for (v in c) {
                        try {
                            speakers.speaker(u, verify = false, budgetMs = SET_BUDGET_MS).setVolume(v)
                        } catch (e: SonosException) {
                            log("group: a room's volume not set (${e.javaClass.simpleName})")
                        }
                    }
                }
            }
        }
        ch.trySend(volume)
    }

    override fun read(uids: List<String>, then: (Map<String, Int>) -> Unit) {
        if (uids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val read = coroutineScope {
                uids.distinct().map { u ->
                    async {
                        try {
                            u to speakers.speaker(u, verify = false, budgetMs = READ_PER_MS).volume()
                        } catch (_: SonosException) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            post { then(read) }
        }
    }

    companion object {
        const val SET_BUDGET_MS = 1_500L
        const val READ_PER_MS = 1_000L
    }
}
