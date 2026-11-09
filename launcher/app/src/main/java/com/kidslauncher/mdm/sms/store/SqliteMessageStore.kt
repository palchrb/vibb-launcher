package com.kidslauncher.mdm.sms.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kidslauncher.mdm.sms.engine.BlockedRow
import com.kidslauncher.mdm.sms.engine.ConversationRow
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.EventRow
import com.kidslauncher.mdm.sms.engine.EventType
import com.kidslauncher.mdm.sms.engine.HeldRow
import com.kidslauncher.mdm.sms.engine.HeldState
import com.kidslauncher.mdm.sms.engine.InsertOutgoing
import com.kidslauncher.mdm.sms.engine.MessageRow
import com.kidslauncher.mdm.sms.engine.MessageStore
import com.kidslauncher.mdm.sms.engine.MessageVerdict
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.Participant
import com.kidslauncher.mdm.sms.engine.PendingPushRow
import com.kidslauncher.mdm.sms.engine.SenderClass
import com.kidslauncher.mdm.sms.engine.SenderKind

/*
 * The message engine's SQLite store (design 77 D6, §6.6): the framework's SQLiteOpenHelper in CE storage, no new
 * dependency. `secure_delete` on, so a deleted body is overwritten, and the WAL checkpointed after each expiry, drop
 * or delete pass ([afterDeletes], QA R2-L3). Nothing is written to the Telephony provider; backup can't take it
 * (`allowBackup="false"`, the extraction rules exclude every domain - QA I3).
 */
class SqliteMessageStore(context: Context) : MessageStore {
    private val helper = Helper(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun <T> transaction(block: () -> T): T {
        val db = db
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun nextSeq(): Long = transaction {
        db.execSQL("UPDATE engine_state SET value = value + 1 WHERE key = 'seq'")
        db.rawQuery("SELECT value FROM engine_state WHERE key = 'seq'", null).use { it.moveToFirst(); it.getLong(0) }
    }

    // ---- conversation + participant ----

    override fun conversationByKey(key: String): ConversationRow? =
        db.query("conversation", null, "conv_key = ?", arrayOf(key), null, null, null).use { c -> if (c.moveToFirst()) conversationOf(c) else null }

    override fun conversation(id: String): ConversationRow? =
        db.query("conversation", null, "id = ?", arrayOf(id), null, null, null).use { c -> if (c.moveToFirst()) conversationOf(c) else null }

    override fun conversations(): List<ConversationRow> =
        db.query("conversation", null, null, null, null, null, "created_at").use { c -> generateSequence { if (c.moveToNext()) conversationOf(c) else null }.toList() }

    override fun putConversation(row: ConversationRow) {
        transaction {
            db.insertWithOnConflict(
                "conversation", null,
                ContentValues().apply {
                    put("id", row.id)
                    put("conv_key", row.key)
                    put("display_name", row.displayName)
                    put("sub_id", row.subId)
                    put("created_at", row.createdAt)
                    put("can_send", if (row.canSend) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("participant", "conversation_id = ?", arrayOf(row.id))
            for (p in row.participants) {
                db.insertOrThrow(
                    "participant", null,
                    ContentValues().apply {
                        put("conversation_id", row.id)
                        put("address", p.address)
                        put("contact_id", p.contactId)
                    },
                )
            }
        }
    }

    override fun deleteConversationRow(id: String) {
        transaction {
            db.delete("participant", "conversation_id = ?", arrayOf(id))
            db.delete("conversation", "id = ?", arrayOf(id))
        }
    }

    private fun conversationOf(c: Cursor): ConversationRow {
        val id = c.str("id")!!
        val participants = db.query("participant", arrayOf("address", "contact_id"), "conversation_id = ?", arrayOf(id), null, null, "address")
            .use { p -> generateSequence { if (p.moveToNext()) Participant(p.getString(0), if (p.isNull(1)) null else p.getLong(1)) else null }.toList() }
        return ConversationRow(
            id = id, key = c.str("conv_key")!!, displayName = c.str("display_name"), subId = c.intOrNull("sub_id"),
            createdAt = c.long("created_at"), participants = participants, canSend = c.long("can_send") != 0L,
        )
    }

    // ---- message ----

    override fun insertMessage(row: MessageRow) {
        db.insertOrThrow("message", null, valuesOf(row))
    }

    override fun insertOutgoing(row: MessageRow): InsertOutgoing = transaction {
        row.clientId?.let(::messageByClientId)?.let { return@transaction InsertOutgoing.Existing(it) }
        try {
            db.insertOrThrow("message", null, valuesOf(row))
            InsertOutgoing.Inserted(row)
        } catch (e: SQLiteConstraintException) {
            // The unique index on client_id decided (a concurrent send of the same command).
            InsertOutgoing.Existing(messageByClientId(row.clientId!!) ?: throw e)
        }
    }

    override fun messageByClientId(clientId: String): MessageRow? = one("client_id = ?", clientId)

    override fun message(id: String): MessageRow? = one("id = ?", id)

    private fun one(where: String, arg: String): MessageRow? =
        db.query("message", null, where, arrayOf(arg), null, null, null).use { c -> if (c.moveToFirst()) messageOf(c) else null }

    override fun updateMessage(row: MessageRow) {
        db.update("message", valuesOf(row), "id = ?", arrayOf(row.id))
    }

    override fun deleteMessageRow(id: String) {
        db.delete("message", "id = ?", arrayOf(id))
    }

    override fun messages(conversationId: String, beforeSeq: Long?, limit: Int): List<MessageRow> {
        val where = "conversation_id = ? AND verdict = 'SHOWN'" + if (beforeSeq != null) " AND seq < ?" else ""
        val args = listOfNotNull(conversationId, beforeSeq?.toString()).toTypedArray()
        return messageList(where, args, "seq DESC", limit.takeIf { it < Int.MAX_VALUE }?.toString())
    }

    override fun messagesHeldForRules(): List<MessageRow> = messageList("verdict = 'HELD_RULES'", emptyArray(), "at", null)

    override fun messagesInDelivery(states: Set<Delivery>): List<MessageRow> {
        if (states.isEmpty()) return emptyList()
        val marks = states.joinToString(",") { "?" }
        return messageList("delivery IN ($marks) AND verdict = 'SHOWN'", states.map { it.name }.toTypedArray(), "seq", null)
    }

    override fun unread(conversationId: String): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM message WHERE conversation_id = ? AND verdict = 'SHOWN' AND direction = 'IN' AND is_read = 0",
            arrayOf(conversationId),
        ).use { it.moveToFirst(); it.getInt(0) }

    override fun lastMessage(conversationId: String): MessageRow? = messages(conversationId, null, 1).firstOrNull()

    private fun messageList(where: String, args: Array<String>, order: String, limit: String?): List<MessageRow> =
        db.query("message", null, where, args, null, null, order, limit).use { c -> generateSequence { if (c.moveToNext()) messageOf(c) else null }.toList() }

    private fun valuesOf(row: MessageRow) = ContentValues().apply {
        put("id", row.id)
        put("conversation_id", row.conversationId)
        put("seq", row.seq)
        put("direction", row.direction.name)
        put("sender", row.sender)
        put("sender_kind", row.senderKind?.name)
        put("unknown_sender", if (row.unknownSender) 1 else 0)
        put("at", row.at)
        put("sent_at", row.sentAt)
        put("body", row.body)
        put("delivery", row.delivery.name)
        put("failure", row.failure)
        put("is_read", if (row.read) 1 else 0)
        put("client_id", row.clientId)
        put("origin", row.origin.name)
        put("verdict", row.verdict.name)
        put("sub_id", row.subId)
        put("parts_total", row.partsTotal)
        put("parts_sent", row.partsSent)
        put("sending_at", row.sendingAt)
    }

    private fun messageOf(c: Cursor) = MessageRow(
        id = c.str("id")!!,
        conversationId = c.str("conversation_id"),
        seq = c.long("seq"),
        direction = Direction.valueOf(c.str("direction")!!),
        sender = c.str("sender"),
        senderKind = c.str("sender_kind")?.let { runCatching { SenderKind.valueOf(it) }.getOrNull() },
        unknownSender = c.long("unknown_sender") != 0L,
        at = c.long("at"),
        sentAt = c.longOrNull("sent_at"),
        body = c.str("body"),
        delivery = Delivery.valueOf(c.str("delivery")!!),
        failure = c.str("failure"),
        read = c.long("is_read") != 0L,
        clientId = c.str("client_id"),
        origin = Origin.valueOf(c.str("origin")!!),
        verdict = MessageVerdict.valueOf(c.str("verdict")!!),
        subId = c.intOrNull("sub_id"),
        partsTotal = c.long("parts_total").toInt(),
        partsSent = c.long("parts_sent"),
        sendingAt = c.longOrNull("sending_at"),
    )

    // ---- event ----

    override fun insertEvent(row: EventRow) {
        db.insertOrThrow(
            "event", null,
            ContentValues().apply {
                put("seq", row.seq)
                put("type", row.type.name)
                put("message_id", row.messageId)
                put("conversation_id", row.conversationId)
                put("origin", row.origin.name)
                put("up_to_seq", row.upToSeq)
                put("at", row.at)
            },
        )
    }

    override fun events(afterSeq: Long, limit: Int): List<EventRow> =
        db.query("event", null, "seq > ?", arrayOf(afterSeq.toString()), null, null, "seq", limit.toString()).use { c ->
            generateSequence {
                if (!c.moveToNext()) null else EventRow(
                    c.long("seq"), EventType.valueOf(c.str("type")!!), c.str("message_id"), c.str("conversation_id"),
                    Origin.valueOf(c.str("origin")!!), c.longOrNull("up_to_seq"), c.long("at"),
                )
            }.toList()
        }

    override fun oldestEventSeq(): Long? =
        db.rawQuery("SELECT MIN(seq) FROM event", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    override fun pruneEvents(before: Long) {
        db.delete("event", "at < ?", arrayOf(before.toString()))
    }

    // ---- held ----

    override fun insertHeld(row: HeldRow) {
        db.insertOrThrow("held", null, valuesOf(row))
    }

    override fun held(id: String): HeldRow? =
        db.query("held", null, "id = ?", arrayOf(id), null, null, null).use { c -> if (c.moveToFirst()) heldOf(c) else null }

    override fun heldRows(): List<HeldRow> =
        db.query("held", null, null, null, null, null, "at").use { c -> generateSequence { if (c.moveToNext()) heldOf(c) else null }.toList() }

    override fun updateHeld(row: HeldRow) {
        db.update("held", valuesOf(row), "id = ?", arrayOf(row.id))
    }

    override fun deleteHeld(id: String) {
        db.delete("held", "id = ?", arrayOf(id))
    }

    private fun valuesOf(row: HeldRow) = ContentValues().apply {
        put("id", row.id)
        put("at", row.at)
        put("kind", row.kind.name)
        put("mms", if (row.mms) 1 else 0)
        put("sender_class", row.senderClass.wire)
        put("sender", row.sender)
        put("body", row.body)
        put("push", row.push)
        put("sub_id", row.subId)
        put("sent_at", row.sentAt)
        put("expires_at", row.expiresAt)
        put("state", row.state.wire)
        put("reported", if (row.reported) 1 else 0)
    }

    private fun heldOf(c: Cursor) = HeldRow(
        id = c.str("id")!!,
        at = c.long("at"),
        kind = SenderKind.valueOf(c.str("kind")!!),
        mms = c.long("mms") != 0L,
        senderClass = classOf(c.str("sender_class")),
        sender = c.str("sender").orEmpty(),
        body = c.str("body"),
        push = c.getColumnIndexOrThrow("push").let { if (c.isNull(it)) null else c.getBlob(it) },
        subId = c.intOrNull("sub_id"),
        sentAt = c.longOrNull("sent_at"),
        expiresAt = c.long("expires_at"),
        state = HeldState.entries.first { it.wire == c.str("state") },
        reported = c.long("reported") != 0L,
    )

    // ---- blocked, pending push, counters ----

    override fun insertBlocked(row: BlockedRow) {
        db.insertOrThrow("blocked", null, valuesOf(row))
    }

    override fun blockedRows(): List<BlockedRow> =
        db.query("blocked", null, null, null, null, null, "at").use { c ->
            generateSequence {
                if (!c.moveToNext()) null else BlockedRow(
                    c.str("id")!!, c.long("at"), c.str("kind")!!, classOf(c.str("sender_class")), c.str("sender").orEmpty(),
                    c.str("reason")!!, c.long("reported") != 0L,
                )
            }.toList()
        }

    override fun updateBlocked(row: BlockedRow) {
        db.update("blocked", valuesOf(row), "id = ?", arrayOf(row.id))
    }

    override fun deleteBlocked(id: String) {
        db.delete("blocked", "id = ?", arrayOf(id))
    }

    private fun valuesOf(row: BlockedRow) = ContentValues().apply {
        put("id", row.id)
        put("at", row.at)
        put("kind", row.kind)
        put("sender_class", row.senderClass.wire)
        put("sender", row.sender.take(20))
        put("reason", row.reason)
        put("reported", if (row.reported) 1 else 0)
    }

    override fun insertPendingPush(row: PendingPushRow) {
        db.insertOrThrow(
            "pending_push", null,
            ContentValues().apply {
                put("id", row.id)
                put("pdu", row.pdu)
                put("sub_id", row.subId)
                put("at", row.at)
            },
        )
    }

    override fun addCounter(key: String, by: Long) {
        db.execSQL("INSERT INTO counter (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = value + ?", arrayOf<Any>(key, by, by))
    }

    override fun counters(): Map<String, Long> =
        db.rawQuery("SELECT key, value FROM counter", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getLong(1) else null }.toMap() }

    override fun afterDeletes() {
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (e: Exception) {
            android.util.Log.w(LOG_TAG, "WAL checkpoint failed: ${e.javaClass.simpleName}")
        }
    }

    private fun classOf(wire: String?): SenderClass = SenderClass.entries.firstOrNull { it.wire == wire } ?: SenderClass.MALFORMED

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // Deleted means gone (QA R2-L3): freed pages are overwritten.
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            for (statement in SCHEMA) db.execSQL(statement)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first; a later one adds its own steps here, never drops the history.
        }
    }

    companion object {
        private const val LOG_TAG = "SmsStore"
        const val DB_NAME = "messages.db"
        private const val DB_VERSION = 1

        /** §6.6's tables. */
        private val SCHEMA = listOf(
            "CREATE TABLE engine_state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)",
            "INSERT INTO engine_state (key, value) VALUES ('seq', 0)",
            "CREATE TABLE counter (key TEXT PRIMARY KEY, value INTEGER NOT NULL)",
            "CREATE TABLE conversation (id TEXT PRIMARY KEY, conv_key TEXT NOT NULL UNIQUE, display_name TEXT, " +
                "sub_id INTEGER, created_at INTEGER NOT NULL, can_send INTEGER NOT NULL)",
            "CREATE TABLE participant (conversation_id TEXT NOT NULL, address TEXT NOT NULL, contact_id INTEGER, " +
                "PRIMARY KEY (conversation_id, address))",
            "CREATE TABLE message (id TEXT PRIMARY KEY, conversation_id TEXT, seq INTEGER NOT NULL, direction TEXT NOT NULL, " +
                "sender TEXT, sender_kind TEXT, unknown_sender INTEGER NOT NULL, at INTEGER NOT NULL, sent_at INTEGER, body TEXT, " +
                "delivery TEXT NOT NULL, failure TEXT, is_read INTEGER NOT NULL, client_id TEXT UNIQUE, origin TEXT NOT NULL, " +
                "verdict TEXT NOT NULL, sub_id INTEGER, parts_total INTEGER NOT NULL, parts_sent INTEGER NOT NULL, " +
                "sending_at INTEGER, mms_transaction_id TEXT, mms_content_location TEXT)",
            "CREATE INDEX message_conversation ON message (conversation_id, seq)",
            "CREATE INDEX message_delivery ON message (delivery)",
            "CREATE TABLE part (message_id TEXT NOT NULL, idx INTEGER NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, " +
                "sha256 TEXT, width INTEGER, height INTEGER, state TEXT NOT NULL, PRIMARY KEY (message_id, idx))",
            "CREATE TABLE held (id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, mms INTEGER NOT NULL, " +
                "sender_class TEXT NOT NULL, sender TEXT NOT NULL, body TEXT, push BLOB, sub_id INTEGER, sent_at INTEGER, " +
                "expires_at INTEGER NOT NULL, state TEXT NOT NULL, reported INTEGER NOT NULL)",
            "CREATE TABLE pending_push (id TEXT PRIMARY KEY, pdu BLOB NOT NULL, sub_id INTEGER, at INTEGER NOT NULL)",
            "CREATE TABLE event (seq INTEGER PRIMARY KEY, type TEXT NOT NULL, message_id TEXT, conversation_id TEXT, " +
                "origin TEXT NOT NULL, up_to_seq INTEGER, at INTEGER NOT NULL)",
            "CREATE TABLE blocked (id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT NOT NULL, sender_class TEXT NOT NULL, " +
                "sender TEXT NOT NULL, reason TEXT NOT NULL, reported INTEGER NOT NULL)",
        )
    }
}

private fun Cursor.str(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

private fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

private fun Cursor.intOrNull(column: String): Int? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
