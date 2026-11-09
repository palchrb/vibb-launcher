package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.sms.engine.BlockedRow
import com.kidslauncher.mdm.sms.engine.ConversationRow
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.EventRow
import com.kidslauncher.mdm.sms.engine.HeldRow
import com.kidslauncher.mdm.sms.engine.InsertOutgoing
import com.kidslauncher.mdm.sms.engine.MessageRow
import com.kidslauncher.mdm.sms.engine.MessageStore
import com.kidslauncher.mdm.sms.engine.MessageVerdict
import com.kidslauncher.mdm.sms.engine.MmsRow
import com.kidslauncher.mdm.sms.engine.MmsState
import com.kidslauncher.mdm.sms.engine.Part
import com.kidslauncher.mdm.sms.engine.PendingPushRow

/** The engine's store in memory, with SQLite's contract: a transaction is all or nothing, `client_id` is unique
 * ([insertOutgoing] decides, QA L8). */
class FakeMessageStore : MessageStore {
    private var seq = 0L
    private val conversations = linkedMapOf<String, ConversationRow>()
    private val messages = linkedMapOf<String, MessageRow>()
    private val events = mutableListOf<EventRow>()
    private val held = linkedMapOf<String, HeldRow>()
    private val blocked = linkedMapOf<String, BlockedRow>()
    val pushes = mutableListOf<PendingPushRow>()
    private val counters = mutableMapOf<String, Long>()
    var deletePasses = 0

    private var depth = 0

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        val saved = snapshot()
        depth++
        try {
            return block()
        } catch (e: Throwable) {
            restore(saved)
            throw e
        } finally {
            depth--
        }
    }

    private class Snapshot(
        val seq: Long, val c: Map<String, ConversationRow>, val m: Map<String, MessageRow>, val e: List<EventRow>,
        val h: Map<String, HeldRow>, val b: Map<String, BlockedRow>, val n: Map<String, Long>,
        val p: Map<String, List<Part>>, val mm: Map<String, MmsRow>, val pu: List<PendingPushRow>,
    )

    private fun snapshot() = Snapshot(
        seq, LinkedHashMap(conversations), LinkedHashMap(messages), events.toList(), LinkedHashMap(held), LinkedHashMap(blocked), counters.toMap(),
        LinkedHashMap(parts), LinkedHashMap(mms), pushes.toList(),
    )

    private fun restore(s: Snapshot) {
        parts.clear(); parts.putAll(s.p)
        mms.clear(); mms.putAll(s.mm)
        pushes.clear(); pushes.addAll(s.pu)
        seq = s.seq
        conversations.clear(); conversations.putAll(s.c)
        messages.clear(); messages.putAll(s.m)
        events.clear(); events.addAll(s.e)
        held.clear(); held.putAll(s.h)
        blocked.clear(); blocked.putAll(s.b)
        counters.clear(); counters.putAll(s.n)
    }

    override fun nextSeq(): Long = ++seq

    override fun conversationByKey(key: String) = conversations.values.firstOrNull { it.key == key }
    override fun conversation(id: String) = conversations[id]
    override fun conversations() = conversations.values.toList()
    override fun putConversation(row: ConversationRow) {
        conversations[row.id] = row
    }
    override fun deleteConversationRow(id: String) {
        conversations.remove(id)
    }

    override fun insertMessage(row: MessageRow) {
        check(row.id !in messages)
        messages[row.id] = row
    }

    override fun insertOutgoing(row: MessageRow): InsertOutgoing {
        row.clientId?.let(::messageByClientId)?.let { return InsertOutgoing.Existing(it) }
        messages[row.id] = row
        return InsertOutgoing.Inserted(row)
    }

    override fun messageByClientId(clientId: String) = messages.values.firstOrNull { it.clientId == clientId }
    override fun message(id: String) = messages[id]
    override fun updateMessage(row: MessageRow) {
        if (row.id in messages) messages[row.id] = row
    }
    override fun deleteMessageRow(id: String) {
        messages.remove(id)
        parts.remove(id)
        mms.remove(id)
    }

    private fun shown() = messages.values.filter { it.verdict == MessageVerdict.SHOWN }

    override fun messages(conversationId: String, beforeSeq: Long?, limit: Int) =
        shown().filter { it.conversationId == conversationId && (beforeSeq == null || it.seq < beforeSeq) }.sortedByDescending { it.seq }.take(limit)

    override fun messagesHeldForRules() = messages.values.filter { it.verdict == MessageVerdict.HELD_RULES }.sortedBy { it.at }
    override fun messagesInDelivery(states: Set<Delivery>) = shown().filter { it.delivery in states }.sortedBy { it.seq }
    override fun unread(conversationId: String) = shown().count { it.conversationId == conversationId && it.direction == Direction.IN && !it.read }
    override fun lastMessage(conversationId: String) = messages(conversationId, null, 1).firstOrNull()

    override fun markReadUpTo(conversationId: String, upToSeq: Long): Int {
        val unread = shown().filter { it.conversationId == conversationId && it.direction == Direction.IN && !it.read && it.seq <= upToSeq }
        unread.forEach { messages[it.id] = it.copy(read = true) }
        return unread.size
    }

    private val received = linkedMapOf<String, Long>()
    override fun receivedBefore(key: String) = key in received
    override fun rememberReceived(key: String, at: Long) {
        received.putIfAbsent(key, at)
    }
    override fun pruneReceived(before: Long) {
        received.entries.removeAll { it.value < before }
    }

    override fun insertEvent(row: EventRow) {
        events += row
    }
    override fun events(afterSeq: Long, limit: Int) = events.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit)
    override fun oldestEventSeq() = events.minOfOrNull { it.seq }
    override fun pruneEvents(before: Long) {
        events.removeAll { it.at < before }
    }

    override fun insertHeld(row: HeldRow) {
        held[row.id] = row
    }
    override fun held(id: String) = held[id]
    override fun heldRows() = held.values.sortedBy { it.at }
    override fun updateHeld(row: HeldRow) {
        held[row.id] = row
    }
    override fun deleteHeld(id: String) {
        held.remove(id)
    }

    override fun insertBlocked(row: BlockedRow) {
        blocked[row.id] = row
    }
    override fun blockedRows() = blocked.values.sortedBy { it.at }
    override fun updateBlocked(row: BlockedRow) {
        blocked[row.id] = row
    }
    override fun deleteBlocked(id: String) {
        blocked.remove(id)
    }

    override fun insertPendingPush(row: PendingPushRow) {
        pushes += row
    }
    override fun pendingPushCount() = pushes.size
    override fun prunePendingPush(before: Long) {
        pushes.removeAll { it.at < before && it.heldId == null }
    }
    override fun pendingPushes() = pushes.sortedBy { it.at }
    override fun deletePendingPush(id: String) {
        pushes.removeAll { it.id == id }
    }
    override fun countPushTry(id: String) {
        val i = pushes.indexOfFirst { it.id == id }
        if (i >= 0) pushes[i].let { pushes[i] = PendingPushRow(it.id, it.pdu, it.subId, it.at, it.heldId, it.tries + 1) }
    }

    val parts = linkedMapOf<String, List<Part>>()
    val mms = linkedMapOf<String, MmsRow>()
    override fun parts(messageId: String) = parts[messageId].orEmpty().sortedBy { it.index }
    override fun putParts(messageId: String, parts: List<Part>) {
        this.parts[messageId] = parts
    }
    override fun partHashes() = parts.values.flatten().mapNotNull { it.sha256 }.toSet()
    override fun insertMms(row: MmsRow) {
        check(row.messageId !in mms)
        mms[row.messageId] = row
    }
    override fun mms(messageId: String) = mms[messageId]
    override fun updateMms(row: MmsRow) {
        if (row.messageId in mms) mms[row.messageId] = row
    }
    override fun mmsIn(states: Set<MmsState>) = mms.values.filter { it.state in states }

    override fun addCounter(key: String, by: Long) {
        counters[key] = (counters[key] ?: 0) + by
    }
    override fun counters() = counters.toMap()

    override fun afterDeletes() {
        deletePasses++
    }
}
