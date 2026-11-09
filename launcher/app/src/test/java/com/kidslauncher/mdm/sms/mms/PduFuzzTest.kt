package com.kidslauncher.mdm.sms.mms

import com.kidslauncher.mdm.sms.vendor.pdu.PduParser
import com.kidslauncher.mdm.sms.vendor.pdu.SendReq
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Design 77 §8.1: a fixed seed and 5,000 mutations of each golden PDU (40,000 in all; QA L4), in the normal test run. Every input either
 * parses or fails typed - never [MmsParseFailure.INTERNAL] (an exception the hardening didn't foresee) - and none
 * allocates far beyond its own size or takes long.
 */
class PduFuzzTest {
    private val seeds: List<ByteArray> = listOf(
        PduVectors.notification(),
        PduVectors.retrieveConf(),
        PduVectors.sendConf(),
        PduVectors.retrieveHeaders(
            PduBytes().withLength(
                PduBytes().byte(PduVectors.CT_MULTIPART_RELATED, PduVectors.PARAM_START).text("<smil>")
                    .byte(PduVectors.PARAM_TYPE).text("application/smil"),
            ),
        ).uintvar(2)
            .part(
                PduBytes().text("application/smil")
                    .byte(PduVectors.PART_CONTENT_ID).byte(0x22).text("<smil>"),
                "<smil><body><par><img src=\"image000.jpg\"/></par></body></smil>".toByteArray(),
            )
            .part(
                PduBytes().byte(PduVectors.CT_MULTIPART_ALTERNATIVE),
                PduBytes().uintvar(1).part(PduVectors.textPart(), "alt".toByteArray()).build(),
            )
            .build(),
        // QA L4: the charset form of From, To, Cc and Subject, Content-Disposition with a filename and more
        // parameters, a Content-Type with a charset name.
        PduBytes()
            .byte(PduVectors.MESSAGE_TYPE, 0x84)
            .byte(PduVectors.TRANSACTION).text(PduVectors.TRANSACTION_ID)
            .byte(PduVectors.VERSION, 0x92)
            .byte(PduVectors.DATE).longInteger(PduVectors.DATE_SECONDS)
            .byte(PduVectors.FROM).withLength(PduBytes().byte(0x80).raw(charsetForm("${PduVectors.SENDER}/TYPE=PLMN").build()))
            .byte(PduVectors.TO).raw(charsetForm("${PduVectors.RECIPIENT}/TYPE=PLMN").build())
            .byte(PduVectors.CC).raw(charsetForm("+4700000002/TYPE=PLMN").build())
            .byte(PduVectors.CC).text("+4700000003/TYPE=PLMN")
            .byte(PduVectors.SUBJECT).raw(charsetForm("Bilde æøå").build())
            .byte(PduVectors.CONTENT_TYPE, PduVectors.CT_MULTIPART_MIXED)
            .uintvar(2)
            .part(
                PduBytes().byte(PduVectors.CT_IMAGE_JPEG)
                    .byte(PduVectors.PART_DISPOSITION).withLength(PduBytes().byte(0x81, 0x98).text("bilde.jpg").byte(0x99).text("x"))
                    .byte(PduVectors.PART_CONTENT_LOCATION).text("bilde.jpg"),
                PduVectors.jpeg,
            )
            .part(
                PduBytes().withLength(PduBytes().byte(PduVectors.CT_TEXT_PLAIN, PduVectors.PARAM_CHARSET).text("utf-8").byte(0x85).text("t.txt")),
                PduVectors.TEXT.toByteArray(Charsets.UTF_8),
            )
            .build(),
        // A retrieve-conf with an error status and no body.
        PduBytes()
            .byte(PduVectors.MESSAGE_TYPE, 0x84)
            .byte(PduVectors.TRANSACTION).text(PduVectors.TRANSACTION_ID)
            .byte(PduVectors.VERSION, 0x92)
            .byte(PduVectors.DATE).longInteger(PduVectors.DATE_SECONDS)
            .byte(0x99, 0xE1) // X-Mms-Retrieve-Status: permanent, message not found
            .byte(PduVectors.CONTENT_TYPE, PduVectors.CT_MULTIPART_MIXED)
            .build(),
        // A notification with a charset-form subject and an Element-Descriptor.
        PduBytes()
            .byte(0xB2).withLength(PduBytes().text("ref").byte(0x83, 0x83))
            .byte(PduVectors.SUBJECT).raw(charsetForm("Bilde").build())
            .raw(PduVectors.notification())
            .build(),
        (MmsCodec.composeSendReq(
            listOf(PduVectors.SENDER),
            listOf(MmsOutgoingPart.jpeg(PduVectors.jpeg), MmsOutgoingPart.text(PduVectors.TEXT)),
            transactionId = "T-fake-fuzz",
            nowSeconds = PduVectors.DATE_SECONDS,
        ) as MmsComposeResult.Ok).bytes,
    )

    private fun charsetForm(text: String) = PduBytes().withLength(PduBytes().byte(PduVectors.UTF_8).text(text))

    private val interesting = intArrayOf(0x00, 0x01, 0x1E, 0x1F, 0x20, 0x22, 0x7F, 0x80, 0x81, 0x8F, 0xC0, 0xFF)

    private fun mutate(random: Random, input: ByteArray): ByteArray {
        var bytes = input
        repeat(1 + random.nextInt(4)) {
            if (bytes.isEmpty()) bytes = byteArrayOf(random.nextInt(256).toByte())
            val at = random.nextInt(bytes.size)
            bytes = when (random.nextInt(8)) {
                0 -> bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
                1 -> bytes.copyOf().also { it[at] = interesting[random.nextInt(interesting.size)].toByte() }
                2 -> bytes.copyOf().also { it[at] = random.nextInt(256).toByte() }
                3 -> bytes.copyOfRange(0, at) + ByteArray(1 + random.nextInt(8)) { random.nextInt(256).toByte() } + bytes.copyOfRange(at, bytes.size)
                4 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + 1 + random.nextInt(16)), bytes.size)
                5 -> bytes.copyOf(at)
                // A run of continuation octets: the long uintvars and lengths.
                6 -> bytes.copyOfRange(0, at) + ByteArray(1 + random.nextInt(6)) { 0xFF.toByte() } + bytes.copyOfRange(at, bytes.size)
                else -> {
                    val other = seeds[random.nextInt(seeds.size)]
                    val from = random.nextInt(other.size)
                    bytes.copyOfRange(0, at) + other.copyOfRange(from, minOf(other.size, from + 1 + random.nextInt(64)))
                }
            }
        }
        return bytes
    }

    // HotSpot's per-thread allocation counter, by reflection: unit tests compile against android.jar, which has no
    // java.lang.management. -1 where the JVM has none (the time and INTERNAL checks still run).
    private val allocatedBytesOf: (() -> Long)? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
        val read = { method.invoke(bean) as Long }
        read.takeIf { it() >= 0 }
    }.getOrNull()

    private fun allocatedBytes(): Long = allocatedBytesOf?.invoke() ?: -1

    @Test(timeout = 120_000)
    fun `5,000 seeded mutations of each seed parse or fail typed, in bounded memory and time`() {
        // QA N1: each seed is valid as its own type.
        val kinds = seeds.map { seed ->
            when {
                MmsCodec.parseNotification(seed) is MmsParseResult.Ok -> "notification"
                MmsCodec.parseRetrieveConf(seed) is MmsParseResult.Ok -> "retrieve-conf"
                MmsCodec.parseSendConf(seed) is MmsParseResult.Ok -> "send-conf"
                PduParser(seed, true).parseOrThrow() is SendReq -> "send-req"
                else -> "invalid"
            }
        }
        assertEquals(
            listOf("notification", "retrieve-conf", "send-conf", "retrieve-conf", "retrieve-conf", "retrieve-conf", "notification", "send-req"),
            kinds,
        )
        val random = Random(77)
        val outcomes = HashMap<String, Int>()
        var slowest = 0L
        val started = System.nanoTime()
        repeat(PER_SEED * seeds.size) { i ->
            val input = mutate(random, seeds[i % seeds.size])
            val before = allocatedBytes()
            val t0 = System.nanoTime()
            val results = listOf(MmsCodec.parseRetrieveConf(input), MmsCodec.parseNotification(input), MmsCodec.parseSendConf(input))
            val took = System.nanoTime() - t0
            val allocated = if (before >= 0) allocatedBytes() - before else 0
            slowest = maxOf(slowest, took)
            for (result in results) {
                if (result == MmsParseResult.Failed(MmsParseFailure.INTERNAL)) {
                    fail("iteration $i: INTERNAL for ${input.joinToString("") { "%02x".format(it) }}")
                }
                val key = if (result is MmsParseResult.Failed) result.reason.name else "OK"
                outcomes[key] = (outcomes[key] ?: 0) + 1
            }
            // Three parses, each copying its input a few times at most: never an attacker-sized array.
            assertTrue("iteration $i allocated $allocated bytes for ${input.size}", allocated < 64L * input.size + 4_000_000)
            assertTrue("iteration $i took ${took / 1_000_000} ms", took < 2_000_000_000L)
        }
        assertTrue("the allocation check must run on the test JVM", allocatedBytesOf != null)
        val seconds = (System.nanoTime() - started) / 1e9
        println("PduFuzzTest: ${PER_SEED * seeds.size} inputs in %.2f s, slowest %.1f ms, outcomes %s".format(seconds, slowest / 1e6, outcomes.toSortedMap()))
        // The fuzzer reaches deep: some mutations still parse, and the typed failures it finds are varied.
        assertTrue((outcomes["OK"] ?: 0) > 0)
        assertTrue(outcomes.keys.size >= 6)
    }

    @Test
    fun `random bytes of every short length fail typed`() {
        val random = Random(9)
        repeat(5_000) {
            val input = ByteArray(random.nextInt(48)) { random.nextInt(256).toByte() }
            for (result in listOf(MmsCodec.parseRetrieveConf(input), MmsCodec.parseNotification(input), MmsCodec.parseSendConf(input))) {
                assertTrue(result != MmsParseResult.Failed(MmsParseFailure.INTERNAL))
            }
        }
        assertEquals(MmsParseResult.Failed(MmsParseFailure.REJECTED), MmsCodec.parseNotification(ByteArray(0)))
    }

    private companion object {
        const val PER_SEED = 5_000
    }
}
