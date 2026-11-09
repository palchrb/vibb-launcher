package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.MmscRule
import com.kidslauncher.mdm.calls.OwnNumberRule
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.sms.engine.Carrier
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.DownloadError
import com.kidslauncher.mdm.sms.engine.EngineEvent
import com.kidslauncher.mdm.sms.engine.HeldState
import com.kidslauncher.mdm.sms.engine.IdSource
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.sms.engine.MmsFlow
import com.kidslauncher.mdm.sms.engine.MmsRadio
import com.kidslauncher.mdm.sms.engine.MmsRow
import com.kidslauncher.mdm.sms.engine.MmsState
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.PartFiles
import com.kidslauncher.mdm.sms.engine.PartState
import com.kidslauncher.mdm.sms.engine.PendingPushRow
import com.kidslauncher.mdm.sms.engine.PushOutcome
import com.kidslauncher.mdm.sms.engine.Refusal
import com.kidslauncher.mdm.sms.engine.RetrievedMms
import com.kidslauncher.mdm.sms.engine.RetrievedPart
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SenderClass
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.engine.SmsJudge
import com.kidslauncher.mdm.sms.engine.SmsRadio
import com.kidslauncher.mdm.sms.mms.MmsAddress
import com.kidslauncher.mdm.sms.mms.MmsNotification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 77 step 3b: the MMS half of the engine over the in-memory store - the push-first state machine (§6.4), D10's
 * gate before any fetch, D16's held push fetched only after an allow and every check again, D12's group rule, D11's
 * pictures both ways with the per-contact switch, D14's waiting state and retry. The fake MMSC host and numbers are
 * made up (`.invalid`, `+479000xxxx`).
 */
class MmsEngineTest {
    private var now = 1_793_800_000_000L
    private val mamma = RuleContact(1, "Mamma", "+4790000001", inbound = true, outbound = true, smsIn = true, smsOut = true, mms = true)
    private val pappa = RuleContact(2, "Pappa", "+4790000002", inbound = true, outbound = true, smsIn = true, smsOut = true, mms = false)
    private val bestemor = RuleContact(5, "Bestemor", "+4790000005", smsIn = true, smsOut = true, mms = true)
    private val kid = "+4790000010"
    private val operator = "24299"
    private val host = "mmsc.carrier.invalid"
    private var table = listOf(MmscRule(operator, listOf(host)))
    private var own = listOf(OwnNumberRule(1, kid))
    private var rules: CallRules? = rules()
    private var carrierMax = 300 * 1024

    private fun rules() = CallRules(
        callsEnabled = true, contacts = listOf(mamma, pappa, bestemor), callsMode = ChannelMode.MANAGED, smsMode = ChannelMode.MANAGED,
        ownNumbers = own, mmscHosts = table,
    )

    private var callbackWindow = false

    private fun judge() = SmsJudge(
        state = rules?.copy(mmscHosts = table, ownNumbers = own)?.let { CallPolicyState.Managed(it) } ?: CallPolicyState.UnknownFailClosed,
        roleHeld = true,
        callbackWindowOpen = { callbackWindow },
        platformEmergency = { false },
        carrier = { Carrier(operator, carrierMax) },
    )

    private val store = FakeMessageStore()
    private val downloads = mutableListOf<Pair<String, String>>()
    private val sends = mutableListOf<Pair<String, ByteArray>>()
    private val answers = mutableListOf<ByteArray>()
    private val discarded = mutableListOf<String>()
    private var downloadThrows = false
    private val mmsRadio = object : MmsRadio {
        override fun download(messageId: String, location: String, subId: Int?, maxBytes: Int) {
            if (downloadThrows) throw IllegalStateException("no data")
            downloads += messageId to location
        }

        override fun send(messageId: String, pdu: ByteArray, subId: Int?) {
            sends += messageId to pdu
        }

        override fun answer(pdu: ByteArray, subId: Int?) {
            answers += pdu
        }

        override fun discardDownload(messageId: String) {
            discarded += messageId
        }
    }
    private val files = object : PartFiles {
        val stored = linkedMapOf<String, ByteArray>()
        val modified = mutableMapOf<String, Long>()
        override fun put(bytes: ByteArray): String {
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            stored[sha] = bytes
            modified[sha] = now
            return sha
        }
        override fun open(sha256: String) = stored[sha256]?.inputStream()
        override fun read(sha256: String) = stored[sha256]
        override fun size(sha256: String) = stored[sha256]?.size?.toLong()
        override fun delete(sha256: String) {
            stored.remove(sha256)
        }
        override fun all() = stored.keys.associateWith { modified[it] ?: 0L }
    }
    private val smsRadio = object : SmsRadio {
        override fun send(messageId: String, address: String, body: String, subId: Int?, deliveryReport: Boolean) = 1
    }
    private var counter = 0
    private val ids = object : IdSource {
        override fun ulid(now: Long) = "U%025d".format(++counter)
        override fun hex() = "%032x".format(++counter)
    }
    private val engine = SmsEngine(store, { now }, { judge() }, smsRadio, ids, mmsRadio, files)
    private val mms = engine.mms

    private var tid = 0

    private fun push(at: Long = now) = PendingPushRow(ids.hex(), byteArrayOf(1, 2, 3), 1, at)

    private fun notification(
        from: String? = "${mamma.number}/TYPE=PLMN",
        location: String = "http://$host/mms/${++tid}",
        size: Long = 50_000,
        expirySeconds: Long = now / 1000 + 7 * 24 * 3600,
    ) = MmsNotification("T$tid", location, from?.let(::MmsAddress), size, expirySeconds, null, null, 0x12)

    private fun retrieved(
        from: String = "${mamma.number}/TYPE=PLMN",
        to: List<String> = listOf(kid),
        cc: List<String> = emptyList(),
        texts: List<String> = listOf("Se her!"),
        parts: List<RetrievedPart> = listOf(picture()),
        status: Int = 0x80,
    ) = RetrievedMms(MmsAddress(from), to.map { MmsAddress("$it/TYPE=PLMN") }, cc.map { MmsAddress("$it/TYPE=PLMN") }, "T$tid", status, texts, parts)

    private fun picture(bytes: ByteArray = byteArrayOf(9, 9, 9, tid.toByte())) = RetrievedPart.Picture(files.put(bytes), 1600, 1200, bytes.size.toLong())

    /** A push from [from] judged and, when allowed, fetched to the point of the parse. */
    private fun arrive(n: MmsNotification = notification()): PushOutcome {
        val p = push()
        store.insertPendingPush(p)
        val outcome = mms.judgePush(p, n)
        mms.drainDownloads()
        return outcome
    }

    private fun only(): String = store.mms.keys.single()

    // ---- the push and D10 ----

    @Test
    fun `an allowed contact's MMS is shown at once, fetched, then filled in from the parse`() {
        val outcome = arrive() as PushOutcome.Shown
        assertTrue(outcome.fetch)
        val id = outcome.shown.message.id.value
        assertEquals(listOf(PartState.PENDING), outcome.shown.message.parts.map { it.state })
        assertEquals(listOf(id to "http://$host/mms/1"), downloads)
        assertEquals(MmsState.DOWNLOADING, store.mms(id)!!.state)
        assertTrue(store.pushes.isEmpty())
        assertTrue(mms.onDownloaded(id))
        assertEquals(listOf(id), mms.parseJobs().map { it.messageId })
        assertTrue(mms.parseJobs().single().pictures)
        val told = mms.onRetrieved(id, retrieved())
        assertNotNull(told)
        val message = engine.messages(ConversationId(told!!.conversation.id), null, 10).single()
        assertEquals("Se her!", message.body)
        assertEquals(listOf(PartState.AVAILABLE), message.parts.map { it.state })
        assertEquals(1600 to 1200, message.parts.single().width to message.parts.single().height)
        // The seam hands out only our own JPEG, by index and by hash.
        assertArrayEquals(byteArrayOf(9, 9, 9, 1), engine.openPart(MessageId(id), 0)!!.readBytes())
        assertNotNull(engine.openPartByHash(message.parts.single().sha256!!))
        assertNull(engine.openPart(MessageId(id), 1))
        assertNull(engine.openPartByHash("0".repeat(64)))
        // MessageUpdated carries the parts; the MMSC got its answer - an immediate fetch's m-notifyresp-ind "retrieved"
        // (code QA L2), with the notification's transaction id.
        val updated = engine.events(0).filterIsInstance<EngineEvent.MessageUpdated>().last()
        assertEquals(PartState.AVAILABLE, updated.parts.single().state)
        assertEquals(1, answers.size)
        assertEquals(0x83, answers.single()[1].toInt() and 0xff)
        assertTrue(String(answers.single(), Charsets.ISO_8859_1).contains("T1"))
        // A second result for the same message changes nothing.
        assertNull(mms.onRetrieved(id, retrieved()))
    }

    @Test
    fun `a stranger's push is held unfetched, and fetched only after the allow, every check again (D16)`() {
        val outcome = arrive(notification(from = "+4790000041/TYPE=PLMN")) as PushOutcome.Held
        assertTrue(outcome.row.mms)
        assertTrue(downloads.isEmpty())
        assertTrue(store.mms.isEmpty())
        // The parent creates the contact: released into the pushes with its first receipt...
        val first = now
        now += 60_000
        rules = rules()!!.copy(contacts = rules!!.contacts + RuleContact(9, "Ny", "+4790000041", smsIn = true))
        assertTrue(engine.releasePass(emptyList()).isEmpty())
        // Code QA L9: still held (its push gone back) until the second judgement - "released" only once it is shown.
        assertEquals(HeldState.HELD, store.heldRows().single().state)
        assertNull(store.heldRows().single().push)
        val released = store.pushes.single()
        assertEquals(first, released.at)
        // A second policy releases nothing twice.
        engine.releasePass(emptyList())
        assertEquals(1, store.pushes.size)
        // ... and judged again there: From, the host, the size - fetched only now.
        val shown = mms.judgePush(released, notification(from = "+4790000041/TYPE=PLMN")) as PushOutcome.Shown
        assertEquals(first, shown.shown.message.at)
        assertEquals(HeldState.RELEASED, store.heldRows().single().state)
        assertFalse(store.heldRows().single().reported)
        mms.drainDownloads()
        assertEquals(1, downloads.size)
        // A released (deferred) fetch is answered with an m-acknowledge-ind (code QA L2).
        val id = shown.shown.message.id.value
        mms.onDownloaded(id)
        mms.onRetrieved(id, retrieved(from = "+4790000041/TYPE=PLMN"))
        assertEquals(0x85, answers.single()[1].toInt() and 0xff)
    }

    @Test
    fun `a released push whose host is no longer the SIM's is blocked, not fetched`() {
        arrive(notification(from = "+4790000041/TYPE=PLMN"))
        rules = rules()!!.copy(contacts = rules!!.contacts + RuleContact(9, "Ny", "+4790000041", smsIn = true))
        engine.releasePass(emptyList())
        table = listOf(MmscRule(operator, listOf("other.carrier.invalid")))
        assertTrue(mms.judgePush(store.pushes.single(), notification(from = "+4790000041/TYPE=PLMN")) is PushOutcome.Blocked)
        assertTrue(downloads.isEmpty())
        // Code QA L9: the parent sees "dropped", never "delivered", for what the kid never got.
        assertEquals(HeldState.DROPPED, store.heldRows().first { it.mms }.state)
        assertEquals(SenderClass.BAD_LOCATION, store.blockedRows().last().senderClass)
    }

    @Test
    fun `D10 blocks before any fetch - a hidden sender, a non-PLMN one, another host`() {
        for ((n, cls) in listOf(
            notification(from = null) to SenderClass.EMPTY,
            notification(from = "x@example.invalid") to SenderClass.MALFORMED,
            notification(location = "http://attacker.invalid/x") to SenderClass.BAD_LOCATION,
        )) {
            assertTrue(arrive(n) is PushOutcome.Blocked)
            assertEquals(cls, store.blockedRows().last().senderClass)
            assertEquals("mms", store.blockedRows().last().kind)
        }
        assertTrue(downloads.isEmpty())
        assertTrue(store.mms.isEmpty())
        assertTrue(store.pushes.isEmpty())
        // Not a notification at all, or one whose id can't be answered.
        val p = push().also(store::insertPendingPush)
        assertTrue(mms.judgePush(p, null) is PushOutcome.Blocked)
        val q = push().also(store::insertPendingPush)
        assertTrue(mms.judgePush(q, notification().copy(transactionId = "a b")) is PushOutcome.Blocked)
    }

    @Test
    fun `an unknown SIM operator fails closed - shown as can't be fetched, never fetched`() {
        table = emptyList()
        val shown = arrive() as PushOutcome.Shown
        assertFalse(shown.fetch)
        assertTrue(downloads.isEmpty())
        assertEquals(listOf(PartState.FAILED), shown.shown.message.parts.map { it.state })
        assertEquals(MmsRow.MMSC_UNKNOWN, store.mms(shown.shown.message.id.value)!!.failure)
        // Not the kid's tap: nothing to try again.
        assertFalse(mms.retryDownload(shown.shown.message.id.value))
    }

    @Test
    fun `over the carrier's maximum, or past its expiry, it isn't fetched`() {
        val large = arrive(notification(size = 300 * 1024 + 1)) as PushOutcome.Shown
        assertEquals(PartState.TOO_LARGE, large.shown.message.parts.single().state)
        val gone = arrive(notification(expirySeconds = now / 1000 - 1)) as PushOutcome.Shown
        assertEquals(PartState.GONE, gone.shown.message.parts.single().state)
        assertTrue(downloads.isEmpty())
    }

    @Test
    fun `a repeated push adds nothing, and one the rules can't judge waits (D15, QA L5)`() {
        val n = notification()
        arrive(n)
        assertEquals(PushOutcome.Duplicate, arrive(n))
        assertEquals(1, store.mms.size)
        rules = null
        val p = push().also(store::insertPendingPush)
        assertEquals(PushOutcome.Waiting, mms.judgePush(p, notification()))
        assertEquals(listOf(p.id), store.pushes.map { it.id })
    }

    @Test
    fun `the isolated parse dying three times on a push blocks it as malformed`() {
        val p = push().also(store::insertPendingPush)
        assertNull(mms.pushCrashed(p))
        assertNull(mms.pushCrashed(store.pushes.single()))
        assertTrue(mms.pushCrashed(store.pushes.single()) is PushOutcome.Blocked)
        assertTrue(store.pushes.isEmpty())
        assertEquals(1L, store.counters()[SmsEngine.COUNT_PARSE_FAILURES])
    }

    // ---- D14: waiting for data, the retry ----

    @Test
    fun `no data waits, five tries offer the kid's tap, data coming back starts again`() {
        downloadThrows = true
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        assertEquals(MmsState.WAITING_DATA, store.mms(id)!!.state)
        assertEquals(1, mms.waiting())
        assertEquals(listOf(PartState.WAITING_DATA), engine.messages(ConversationId(store.message(id)!!.conversationId!!), null, 1).single().parts.map { it.state })
        repeat(4) {
            mms.retryWaiting(dataOn = false)
            mms.drainDownloads()
        }
        val failed = store.mms(id)!!
        assertEquals(MmsState.FAILED, failed.state)
        assertEquals(MmsRow.RETRY, failed.failure)
        assertEquals(MmsFlow.FAILURE_RETRY, store.message(id)!!.failure)
        // Each sync leaves it; the tap starts again.
        mms.retryWaiting(dataOn = false)
        assertEquals(MmsState.FAILED, store.mms(id)!!.state)
        downloadThrows = false
        assertTrue(mms.retryDownload(id))
        mms.drainDownloads()
        assertEquals(MmsState.DOWNLOADING, store.mms(id)!!.state)
        assertEquals(1, store.mms(id)!!.tries)
        assertNull(store.message(id)!!.failure)
    }

    @Test
    fun `the platform's errors - gone is final, no data waits, silence waits too`() {
        val gone = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloadFailed(gone, DownloadError.GONE)
        assertEquals(MmsState.GONE, store.mms(gone)!!.state)
        val waits = (arrive() as PushOutcome.Shown).shown.message.id.value
        assertNotNull("the first wait is told once", mms.onDownloadFailed(waits, DownloadError.NO_DATA))
        assertEquals(MmsState.WAITING_DATA, store.mms(waits)!!.state)
        val silent = (arrive() as PushOutcome.Shown).shown.message.id.value
        now += MmsFlow.DOWNLOAD_SILENT_MS
        mms.retryWaiting(dataOn = false)
        assertEquals(MmsState.NOTIFIED, store.mms(silent)!!.state)
        assertEquals(DownloadError.GONE, MmsFlow.downloadErrorOf(4, 404))
        assertEquals(DownloadError.NO_DATA, MmsFlow.downloadErrorOf(11, 0))
        assertEquals(DownloadError.OTHER, MmsFlow.downloadErrorOf(4, 500))
    }

    // ---- after the fetch: From, D12, the parts ----

    @Test
    fun `a fetched message from someone else than the push said is dropped`() {
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        assertNull(mms.onRetrieved(id, retrieved(from = "${pappa.number}/TYPE=PLMN")))
        assertNull(store.message(id))
        assertTrue(engine.events(0).last() is EngineEvent.MessageDeleted)
        assertEquals(SenderClass.MALFORMED, store.blockedRows().last().senderClass)
        // Its picture file went with it, and nothing answered the MMSC for it (code QA L1).
        assertTrue(files.stored.isEmpty())
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `a group with a stranger is blocked, a group of contacts gets its own conversation (D12)`() {
        val blocked = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(blocked)
        assertNull(mms.onRetrieved(blocked, retrieved(to = listOf(kid, "+4790000099"))))
        assertNull(store.message(blocked))
        assertEquals(SenderClass.GROUP, store.blockedRows().last().senderClass)

        val group = (arrive() as PushOutcome.Shown).shown.message.id.value
        val oneToOne = store.message(group)!!.conversationId
        mms.onDownloaded(group)
        val told = mms.onRetrieved(group, retrieved(to = listOf(kid, bestemor.number)))!!
        assertEquals(listOf(mamma.number, bestemor.number), told.conversation.participants.map { it.address })
        assertTrue(told.conversation.id != oneToOne)
        assertEquals(told.conversation.id, store.message(group)!!.conversationId)
        // Gone from the sender's conversation, added to the group's.
        val events = engine.events(0)
        assertTrue(events.any { it is EngineEvent.MessageDeleted && it.id.value == group })
        assertEquals(group, (events.last() as EngineEvent.MessageAdded).message.id.value)
        // The group may be answered: both may be texted.
        assertTrue(engine.conversation(ConversationId(told.conversation.id))!!.canSend)
    }

    @Test
    fun `with no own number a message to two addresses is blocked as a group`() {
        own = emptyList()
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        assertNull(mms.onRetrieved(id, retrieved(to = listOf(kid, bestemor.number))))
        assertEquals(SenderClass.GROUP, store.blockedRows().last().senderClass)
        val alone = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(alone)
        assertNotNull(mms.onRetrieved(alone, retrieved(to = listOf(kid))))
    }

    @Test
    fun `pictures off with the contact - fetched, text shown, no picture (D11)`() {
        val id = (arrive(notification(from = "${pappa.number}/TYPE=PLMN")) as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        assertFalse(mms.parseJobs().single().pictures)
        mms.onRetrieved(id, retrieved(from = "${pappa.number}/TYPE=PLMN", parts = listOf(RetrievedPart.NotShown, RetrievedPart.Other("video/mp4; x=1"))))
        val message = engine.messages(ConversationId(store.message(id)!!.conversationId!!), null, 1).single()
        assertEquals("Se her!", message.body)
        assertEquals(listOf(PartState.NOT_SHOWN, PartState.FAILED), message.parts.map { it.state })
        assertEquals("video/mp4", message.parts[1].mime)
        assertNull(engine.openPart(MessageId(id), 0))
    }

    @Test
    fun `a parse that fails, or kills the isolated process three times, fails the message`() {
        val bad = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(bad)
        assertTrue(mms.beginParse(bad).first)
        assertNotNull(mms.onParseFailed(bad))
        assertEquals(MmsState.FAILED, store.mms(bad)!!.state)
        // Code QA N7: "Meldingen kunne ikke vises", not "couldn't be fetched".
        assertEquals(MmsFlow.FAILURE_UNREADABLE, store.message(bad)!!.failure)
        val crash = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(crash)
        repeat(2) {
            assertTrue(mms.beginParse(crash).first)
            assertNull(mms.onParseCrashed(crash))
        }
        assertEquals(MmsState.DOWNLOADED, store.mms(crash)!!.state)
        assertTrue(mms.beginParse(crash).first)
        assertNotNull(mms.onParseCrashed(crash))
        assertEquals(MmsState.FAILED, store.mms(crash)!!.state)
        assertEquals(2L, store.counters()[SmsEngine.COUNT_PARSE_FAILURES])
    }

    @Test
    fun `the parse's attempt counts before it runs - a main-process death three times ends the loop (code QA M1)`() {
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        // Three starts that died while handling the answer: nothing came back, the count went up anyway.
        repeat(3) { assertTrue(mms.beginParse(id).first) }
        val (go, givenUp) = mms.beginParse(id)
        assertFalse(go)
        assertNotNull(givenUp)
        assertEquals(MmsState.FAILED, store.mms(id)!!.state)
        assertFalse(mms.awaitsParse(id))
        // A service that couldn't be bound gives its attempt back.
        val other = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(other)
        assertTrue(mms.beginParse(other).first)
        mms.parseUnavailable(other)
        assertEquals(0, store.mms(other)!!.crashes)
        assertTrue(mms.awaitsParse(other))
    }

    @Test
    fun `retrieve statuses - transient waits, not found is gone`() {
        val transient = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(transient)
        mms.onRetrieved(transient, retrieved(status = 0xC1, parts = emptyList()))
        assertEquals(MmsState.WAITING_DATA, store.mms(transient)!!.state)
        val gone = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(gone)
        mms.onRetrieved(gone, retrieved(status = 0xE2, parts = emptyList()))
        assertEquals(MmsState.GONE, store.mms(gone)!!.state)
    }

    // ---- sending (D11, D12) ----

    @Test
    fun `a picture goes as an MMS only to a contact with pictures on`() {
        val ref = engine.putPicture(byteArrayOf(1, 2, 3, 4), 1280, 960)
        val sent = engine.send(SendCommand("c1", null, mamma.number, "Hei", listOf(ref), Origin.KID_SCREEN)) as SendResult.Accepted
        assertEquals(listOf(PartState.AVAILABLE), sent.message.parts.map { it.state })
        assertEquals(1280, sent.message.parts.single().width)
        engine.drainOutbox()
        val (id, pdu) = sends.single()
        assertEquals(sent.message.id.value, id)
        // An m-send-req (X-Mms-Message-Type 0x80 = send-req).
        assertEquals(0x8C, pdu[0].toInt() and 0xff)
        assertEquals(0x80, pdu[1].toInt() and 0xff)
        assertEquals(Delivery.SENDING, store.message(id)!!.delivery)
        engine.onMmsSent(id, ok = true, code = null)
        assertEquals(Delivery.SENT, store.message(id)!!.delivery)
        // The same command again is the same message; another picture under the id is refused.
        assertEquals(sent.message.id, (engine.send(SendCommand("c1", null, mamma.number, "Hei", listOf(ref), Origin.KID_SCREEN)) as SendResult.Accepted).message.id)
        val other = engine.putPicture(byteArrayOf(5), 10, 10)
        assertEquals(SendResult.Refused(Refusal.DUPLICATE_CLIENT_ID), engine.send(SendCommand("c1", null, mamma.number, "Hei", listOf(other), Origin.KID_SCREEN)))
    }

    @Test
    fun `pictures are refused with pictures off, a picture the engine doesn't hold, or over the maximum`() {
        val ref = engine.putPicture(byteArrayOf(1, 2, 3), 10, 10)
        assertEquals(SendResult.Refused(Refusal.NOT_ALLOWED), engine.send(SendCommand("p", null, pappa.number, "", listOf(ref), Origin.KID_SCREEN)))
        assertEquals(SendResult.Refused(Refusal.NOT_ALLOWED), engine.send(SendCommand("e", null, "112", "", listOf(ref), Origin.KID_SCREEN)))
        val unknown = ref.copy(sha256 = "0".repeat(64))
        assertEquals(SendResult.Refused(Refusal.NOT_ALLOWED), engine.send(SendCommand("u", null, mamma.number, "", listOf(unknown), Origin.KID_SCREEN)))
        carrierMax = 1024
        val big = engine.putPicture(ByteArray(2000), 10, 10)
        assertEquals(SendResult.Refused(Refusal.TOO_LARGE), engine.send(SendCommand("b", null, mamma.number, "", listOf(big), Origin.KID_SCREEN)))
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `the contact's pictures switched off before the outbox ran - it fails, nothing goes`() {
        val ref = engine.putPicture(byteArrayOf(1, 2, 3), 10, 10)
        val sent = engine.send(SendCommand("c", null, mamma.number, "", listOf(ref), Origin.KID_SCREEN)) as SendResult.Accepted
        rules = rules()!!.copy(contacts = listOf(mamma.copy(mms = false), pappa, bestemor))
        engine.drainOutbox()
        assertTrue(sends.isEmpty())
        assertEquals(Delivery.FAILED, store.message(sent.message.id.value)!!.delivery)
    }

    @Test
    fun `a group's reply goes to every stored participant as one MMS (D12)`() {
        val group = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(group)
        val conversation = mms.onRetrieved(group, retrieved(to = listOf(kid, bestemor.number)))!!.conversation
        val reply = engine.send(SendCommand("g1", ConversationId(conversation.id), null, "Ja!", origin = Origin.KID_SCREEN)) as SendResult.Accepted
        assertNull(reply.message.sender)
        engine.drainOutbox()
        assertEquals(reply.message.id.value, sends.single().first)
        // Pappa may text but not get pictures: no picture to a group with him.
        val withPappa = engine.groupConversationForTest(listOf(mamma.number, pappa.number))
        val ref = engine.putPicture(byteArrayOf(7), 10, 10)
        assertEquals(SendResult.Refused(Refusal.NOT_ALLOWED), engine.send(SendCommand("g2", ConversationId(withPappa), null, "", listOf(ref), Origin.KID_SCREEN)))
    }

    @Test
    fun `a deleted picture message takes its file, an unsent pick goes after a day`() {
        val ref = engine.putPicture(byteArrayOf(1, 2, 3), 10, 10)
        val sent = engine.send(SendCommand("d", null, mamma.number, "", listOf(ref), Origin.KID_SCREEN)) as SendResult.Accepted
        val staged = engine.putPicture(byteArrayOf(4, 5), 10, 10)
        engine.delete(sent.message.id, Origin.KID_SCREEN)
        assertFalse(ref.sha256 in files.stored)
        assertTrue("an unsent pick stays its day", staged.sha256 in files.stored)
        now += MmsFlow.STAGED_KEEP_MS + 1
        engine.prune()
        assertTrue(files.stored.isEmpty())
    }

    private fun SmsEngine.groupConversationForTest(addresses: List<String>): String = store.transaction { groupConversationFor(addresses, null).id }

    // ---- the code QA's fix round ----

    @Test
    fun `a contact switched off before the fetch - nothing is fetched, the waiting message goes (code QA M2)`() {
        downloadThrows = true
        val waiting = (arrive() as PushOutcome.Shown).shown.message.id.value
        assertEquals(MmsState.WAITING_DATA, store.mms(waiting)!!.state)
        downloadThrows = false
        // The parent turns Mamma's "Kan sende melding til" off; data comes back.
        rules = rules()!!.copy(contacts = listOf(mamma.copy(smsIn = false), pappa, bestemor))
        downloads.clear()
        mms.retryWaiting(dataOn = true)
        mms.drainDownloads()
        assertTrue(downloads.isEmpty())
        assertNull(store.message(waiting))
        assertEquals(SenderClass.NUMBER, store.blockedRows().last().senderClass)
    }

    @Test
    fun `the kid's tap and a fetched file are judged again too (code QA M2)`() {
        downloadThrows = true
        val tapped = (arrive() as PushOutcome.Shown).shown.message.id.value
        repeat(4) {
            mms.retryWaiting(dataOn = false)
            mms.drainDownloads()
        }
        assertEquals(MmsRow.RETRY, store.mms(tapped)!!.failure)
        downloadThrows = false
        rules = rules()!!.copy(contacts = listOf(pappa, bestemor))
        downloads.clear()
        assertTrue(mms.retryDownload(tapped))
        mms.drainDownloads()
        assertTrue(downloads.isEmpty())
        assertNull(store.message(tapped))
        // Fetched, then removed before the parse: no parse job, nothing shown, nothing answered.
        rules = rules()
        val fetched = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(fetched)
        rules = rules()!!.copy(contacts = listOf(pappa, bestemor))
        assertTrue(mms.parseJobs().isEmpty())
        assertNull(store.message(fetched))
        assertTrue(answers.isEmpty())
        // ... and after the parse ran: dropped at its answer.
        rules = rules()
        val parsed = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(parsed)
        assertEquals(1, mms.parseJobs().size)
        rules = rules()!!.copy(contacts = listOf(pappa, bestemor))
        assertNull(mms.onRetrieved(parsed, retrieved()))
        assertNull(store.message(parsed))
        assertTrue(answers.isEmpty())
        // Rules that can't be read keep a fetched message waiting for its parse.
        rules = rules()
        val unknown = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(unknown)
        rules = null
        assertTrue(mms.parseJobs().isEmpty())
        assertTrue(mms.awaitsParse(unknown))
    }

    @Test
    fun `a stranger the callback window let in isn't fetched once the window closed, nor in a group (D12, code QA M2)`() {
        callbackWindow = true
        val stranger = "+4790000066"
        val shown = (arrive(notification(from = "$stranger/TYPE=PLMN")) as PushOutcome.Shown).shown.message
        assertTrue(shown.unknownSender)
        // A group from the callback window alone is blocked as a group.
        mms.onDownloaded(shown.id.value)
        assertNull(mms.onRetrieved(shown.id.value, retrieved(from = "$stranger/TYPE=PLMN", to = listOf(kid, bestemor.number))))
        assertEquals(SenderClass.GROUP, store.blockedRows().last().senderClass)
        // One waiting while the window closes is never fetched.
        downloadThrows = true
        val later = (arrive(notification(from = "$stranger/TYPE=PLMN")) as PushOutcome.Shown).shown.message.id.value
        downloadThrows = false
        callbackWindow = false
        downloads.clear()
        mms.retryWaiting(dataOn = true)
        mms.drainDownloads()
        assertTrue(downloads.isEmpty())
        assertNull(store.message(later))
    }

    @Test
    fun `a held MMS from an emergency number is never released (D16, R2-H1)`() {
        arrive(notification(from = "113/TYPE=PLMN"))
        assertEquals(1, store.heldRows().count { it.mms })
        rules = rules()!!.copy(contacts = rules!!.contacts + RuleContact(9, "Ambulanse", "113", inbound = true, outbound = true, smsIn = true))
        engine.releasePass(emptyList())
        assertTrue(store.pushes.isEmpty())
        assertEquals(HeldState.HELD, store.heldRows().single { it.mms }.state)
        assertNotNull(store.heldRows().single { it.mms }.push)
    }

    @Test
    fun `the parent's delete after the allow wins - nothing is fetched (D16)`() {
        val held = (arrive(notification(from = "+4790000041/TYPE=PLMN")) as PushOutcome.Held).row
        rules = rules()!!.copy(contacts = rules!!.contacts + RuleContact(9, "Ny", "+4790000041", smsIn = true))
        engine.releasePass(emptyList())
        // The push waits (rules unknown) while the parent's "Slett nå" arrives.
        store.updateHeld(store.held(held.id)!!.copy(state = HeldState.DROPPED))
        assertEquals(PushOutcome.Blocked(null), mms.judgePush(store.pushes.single(), notification(from = "+4790000041/TYPE=PLMN")))
        assertTrue(store.pushes.isEmpty())
        assertTrue(store.mms.isEmpty())
    }

    @Test
    fun `a download past the cap is too large for good, never fetched again (code QA L5)`() {
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        assertNotNull(mms.onDownloadTooLarge(id))
        assertEquals(MmsState.TOO_LARGE, store.mms(id)!!.state)
        downloads.clear()
        mms.retryWaiting(dataOn = true)
        mms.drainDownloads()
        assertTrue(downloads.isEmpty())
    }

    @Test
    fun `an MMS that failed for an unknown operator is fetched once the table knows the SIM (code QA L10)`() {
        table = emptyList()
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        assertEquals(MmsRow.MMSC_UNKNOWN, store.mms(id)!!.failure)
        assertFalse(mms.retryWaiting(dataOn = false))
        table = listOf(MmscRule(operator, listOf(host)))
        assertTrue(mms.retryWaiting(dataOn = false))
        mms.drainDownloads()
        assertEquals(id, downloads.single().first)
    }

    @Test
    fun `a picture that waited is told again quietly when it arrives, a group move names the conversation it left (code QA L8)`() {
        downloadThrows = true
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        val told = store.mms(id)!!
        assertTrue(told.notified)
        downloadThrows = false
        mms.retryWaiting(dataOn = true)
        mms.drainDownloads()
        mms.onDownloaded(id)
        val oneToOne = store.message(id)!!.conversationId
        val arrived = mms.onRetrieved(id, retrieved(to = listOf(kid, bestemor.number)))!!
        assertTrue(arrived.again)
        assertEquals(oneToOne, arrived.movedFrom)
        // A fresh one is told loudly, once, and has moved nowhere.
        val fresh = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(fresh)
        val first = mms.onRetrieved(fresh, retrieved())!!
        assertFalse(first.again)
        assertNull(first.movedFrom)
    }

    @Test
    fun `repeated pushes are known until their own expiry, blocked ones too (code QA L2)`() {
        val hidden = notification(from = null)
        assertTrue(arrive(hidden) is PushOutcome.Blocked)
        assertEquals(PushOutcome.Duplicate, arrive(hidden))
        assertEquals(1, store.blockedRows().size)
        val held = notification(from = "+4790000041/TYPE=PLMN")
        assertTrue(arrive(held) is PushOutcome.Held)
        // Two days later the MMSC repeats it (its expiry is a week): no second held row, no second push to the parent.
        now += 2 * 24 * 60 * 60 * 1000L
        store.pruneReceived(now - SmsEngine.RECEIVED_KEEP_MS)
        assertEquals(PushOutcome.Duplicate, arrive(held))
        assertEquals(1, store.heldRows().size)
        // Past its expiry (a week) the key is gone.
        now += 6 * 24 * 60 * 60 * 1000L
        store.pruneReceived(now - SmsEngine.RECEIVED_KEEP_MS)
        assertFalse(arrive(held) is PushOutcome.Duplicate)
    }

    @Test
    fun `a pick the kid hasn't sent survives a delete of a message with the same picture (code QA N8)`() {
        val sent = engine.send(SendCommand("s", null, mamma.number, "", listOf(engine.putPicture(byteArrayOf(1, 2), 10, 10)), Origin.KID_SCREEN)) as SendResult.Accepted
        val again = engine.putPicture(byteArrayOf(1, 2), 10, 10)
        engine.delete(sent.message.id, Origin.KID_SCREEN)
        assertTrue(again.sha256 in files.stored)
        assertTrue(engine.send(SendCommand("t", null, mamma.number, "", listOf(again), Origin.KID_SCREEN)) is SendResult.Accepted)
    }

    @Test
    fun `deleting a conversation deletes its pictures' files (D18)`() {
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        val shown = mms.onRetrieved(id, retrieved())!!
        assertEquals(1, files.stored.size)
        engine.deleteConversation(ConversationId(shown.conversation.id), Origin.KID_SCREEN)
        assertTrue(files.stored.isEmpty())
    }

    // ---- the fix round's verification ----

    @Test
    fun `an unknown-operator MMS from a sender no longer allowed is dropped, not fetched, once the table knows the SIM (N-V3)`() {
        table = emptyList()
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        assertEquals(MmsRow.MMSC_UNKNOWN, store.mms(id)!!.failure)
        rules = rules()!!.copy(contacts = listOf(pappa, bestemor))
        table = listOf(MmscRule(operator, listOf(host)))
        assertTrue(mms.retryWaiting(dataOn = false))
        mms.drainDownloads()
        assertTrue(downloads.isEmpty())
        assertNull(store.message(id))
    }

    @Test
    fun `a fetched message dropped before its parse takes its downloaded file with it (N-V3)`() {
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        rules = rules()!!.copy(contacts = listOf(pappa, bestemor))
        assertTrue(mms.parseJobs().isEmpty())
        assertEquals(listOf(id), discarded)
        assertNull(store.message(id))
    }

    @Test
    fun `a parse answered while the rules can't be read doesn't use up an attempt (N-V3)`() {
        val id = (arrive() as PushOutcome.Shown).shown.message.id.value
        mms.onDownloaded(id)
        assertTrue(mms.beginParse(id).first)
        assertEquals(1, store.mms(id)!!.crashes)
        rules = null
        assertNull(mms.onRetrieved(id, retrieved()))
        assertEquals(0, store.mms(id)!!.crashes)
        assertTrue(mms.awaitsParse(id))
    }

    @Test
    fun `a released MMS whose push never settles expires after a week and frees its slot (N-V2)`() {
        val held = (arrive(notification(from = "+4790000041/TYPE=PLMN")) as PushOutcome.Held).row
        rules = rules()!!.copy(contacts = rules!!.contacts + RuleContact(9, "Ny", "+4790000041", smsIn = true))
        engine.releasePass(emptyList())
        assertEquals(1, store.pushes.size)
        // Rules unknown for good: the push waits, the row stays held past its day.
        rules = null
        now += 2 * 24 * 60 * 60 * 1000L
        engine.expire()
        assertEquals(HeldState.HELD, store.held(held.id)!!.state)
        now = held.at + SmsEngine.RELEASE_IN_TRANSIT_MS
        engine.expire()
        assertEquals(HeldState.EXPIRED, store.held(held.id)!!.state)
        assertTrue(store.pushes.isEmpty())
    }
}
