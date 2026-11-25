package com.kidslauncher.mdm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * Design 129's shared contract (`device_key.json`, in `contracts/core/`, whose `tests::device_key`
 * verifies the crypto rows and produces the route rows): the canonical form and the proofs built exactly (and their
 * signatures verifying over what this side builds), the enrolment's challenge, the signature clock's offset, every
 * `Vibb-Auth` answer's action, the policy's `device_key`, and the registration flow's table (`phone`, `phone_nudge`).
 */
class DeviceKeyContractTest {
    private val text = javaClass.classLoader!!.getResource("device_key.json")!!.readText()
    private val contract = Json.parseToJsonElement(text).jsonObject

    private fun rows(name: String) = contract[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String): String? = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.s(key: String): String = str(key)!!
    private fun unb64u(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private fun publicKey(raw: ByteArray): PublicKey {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun verifies(publicKey: String, message: String, signature: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey(unb64u(publicKey)))
        update(message.toByteArray(Charsets.UTF_8))
        try {
            verify(signature)
        } catch (e: java.security.SignatureException) {
            false
        }
    }

    private fun canonicalOf(row: JsonObject): String = canonicalRequest(
        row.s("key_id"), row["t"]!!.jsonPrimitive.long, row.s("nonce"), row.s("method"),
        signedTarget(row.s("encoded_path"), row.str("encoded_query"))!!, row.s("content_encoding"), row.s("body_sha256"),
    )

    @Test
    fun `the canonical form is built exactly`() {
        for (row in rows("canonical")) {
            val body = Base64.getDecoder().decode(row.s("body_b64"))
            assertEquals(row.s("name"), row.s("body_sha256"), hexOf(sha256(body)))
            assertEquals(row.s("name"), row.s("signed"), canonicalOf(row))
        }
        // A proxy's path prefix signs the same; a path outside the device API isn't signed.
        assertEquals("/api/devices/policy", signedTarget("/vibb/api/devices/policy", null))
        assertEquals(null, signedTarget("/healthz", null))
    }

    @Test
    fun `the signed rows verify over what this side builds`() {
        for (row in rows("signed")) {
            val header = row.s("signature")
            val parts = header.removePrefix("v1;").split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
            val signature = unb64u(parts.getValue("s"))
            assertEquals(row.s("name"), header, signatureHeader(parts.getValue("k"), parts.getValue("t").toLong(), parts.getValue("n"), signature))
            assertEquals(row.s("name"), row["valid"]!!.jsonPrimitive.boolean, verifies(row.s("public_key"), canonicalOf(row), signature))
        }
    }

    @Test
    fun `the proofs are built exactly`() {
        for (row in rows("proof")) {
            val kind = ProofKind.entries.single { it.wire == row.s("kind") }
            val message = proofMessage(unb64u(row.s("challenge")), kind)
            assertEquals(row.s("signed"), message)
            assertEquals(row.toString(), row["valid"]!!.jsonPrimitive.boolean, verifies(row.s("public_key"), message, unb64u(row.s("proof"))))
        }
    }

    @Test
    fun `the enrolment's challenge comes from the code`() {
        for (row in rows("enroll_challenge")) {
            assertEquals(row.s("normalised"), normalisedEnrolCode(row.s("code")))
            assertEquals(row.s("challenge"), b64u(enrolChallenge(row.s("code"))))
        }
    }

    @Test
    fun `the signature clock's offset and t`() {
        for (row in contract["clock"]!!.jsonObject["offset"]!!.jsonArray.map { it.jsonObject }) {
            val offset = signatureOffset(row["vibb_time"]!!.jsonPrimitive.long, row["sent_elapsed"]!!.jsonPrimitive.long, row["received_elapsed"]!!.jsonPrimitive.long)
            assertEquals(row["offset"]!!.jsonPrimitive.long, offset)
            assertEquals(row["t"]!!.jsonPrimitive.long, signatureTime(offset, row["now_elapsed"]!!.jsonPrimitive.long))
        }
    }

    @Test
    fun `every refusal maps to the phone's action`() {
        for (row in rows("answers")) assertEquals(row.s("reason"), row.s("phone"), refusalAction(row.s("reason")).wire)
        assertEquals(RefusalAction.SILENT, refusalAction("something new"))
        assertEquals(RefusalAction.SILENT, refusalAction(null))
        val headers = contract["headers"]!!.jsonObject
        assertEquals(listOf(SIGNATURE_HEADER, BODY_SHA256_HEADER, SIGNATURE_TIME_HEADER, AUTH_REFUSAL_HEADER), listOf("signature", "body_sha256", "time", "auth").map { headers.getValue(it).jsonPrimitive.content })
    }

    @Test
    fun `the policy's device_key decodes`() {
        val policy = contract["policy"]!!.jsonObject
        assertEquals(DeviceKeyPolicy("none"), ServerJson.decodeFromString(DeviceKeyPolicy.serializer(), policy["none"].toString()))
        assertEquals(DeviceKeyPolicy("observed", "<key id>", "<hex>"), ServerJson.decodeFromString(DeviceKeyPolicy.serializer(), policy["observed"].toString()))
        assertEquals(DeviceKeyPolicy("exempt"), ServerJson.decodeFromString(DeviceKeyPolicy.serializer(), policy["exempt"].toString()))
        assertEquals(DeviceKeyAnswer("exempt"), ServerJson.decodeFromString(DeviceKeyAnswer.serializer(), contract["register"]!!.jsonObject["get_key_exempt"].toString()))
        val register = contract["register"]!!.jsonObject
        assertEquals(DeviceKeyAnswer("observed", "<key id>", "<hex>"), ServerJson.decodeFromString(DeviceKeyAnswer.serializer(), register["get_key_observed"].toString()))
        assertEquals(KeyChallengeAnswer("<Base64url, 32 bytes>", 600), ServerJson.decodeFromString(KeyChallengeAnswer.serializer(), register["challenge"].toString()))
        // The registration body is exactly the contract's keys.
        val body = ServerJson.encodeToString(KeyRegisterBody.serializer(), KeyRegisterBody("<Base64url>", listOf("<Base64 DER>"), "<Base64url>"))
        assertEquals(Json.parseToJsonElement(register["body"].toString()), Json.parseToJsonElement(body))
        val stale = register["answers"]!!.jsonArray.map { it.jsonObject }.single { it.s("case") == "list_stale" }
        assertEquals(KeyRefusalAnswer("list_stale", 3600), ServerJson.decodeFromString(KeyRefusalAnswer.serializer(), stale["body"].toString()))
    }

    @Test
    fun `the enrolment's bodies and answers`() {
        val enroll = contract["enroll"]!!.jsonObject
        fun encode(request: com.kidslauncher.mdm.server.dto.EnrollRequest) =
            Json.parseToJsonElement(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.EnrollRequest.serializer(), request))
        val attempt = "<16 bytes, Base64url>"
        assertEquals(
            enroll["body_with_key"],
            encode(com.kidslauncher.mdm.server.dto.EnrollRequest("<code>", attempt, listOf("<Base64 DER>"), "<Base64url>")),
        )
        // Design 129b D2 (M3): every no-key body names why.
        for (row in enroll["body_no_key"]!!.jsonArray.map { it.jsonObject }) {
            val body = row["body"]!!.jsonObject
            val keyError = body.str("key_error") ?: continue
            assertEquals(body, encode(com.kidslauncher.mdm.server.dto.EnrollRequest("<code>", attempt, keyError = keyError)))
        }
        // An older launcher's body (the server's 426).
        assertEquals(enroll["body_code_only"], encode(com.kidslauncher.mdm.server.dto.EnrollRequest("<code>")))

        // Every E1 answer to the phone's action.
        val answers = enroll["answers"]!!.jsonObject
        fun kindOf(row: JsonObject): String {
            val status = row["status"]!!.jsonPrimitive.int
            val body = row["body"]!!.takeIf { it != JsonNull }?.jsonObject
            val keyState = body?.get("key")?.jsonObject?.str("state")
            if (status == 409) {
                val failure = enrollFailureOf(status, body.toString())
                assertTrue(row.toString(), failure is EnrollWaiting)
                assertEquals(body!!.s("reason"), (failure as EnrollWaiting).reason)
                assertEquals(15L, failure.retryAfterS)
            }
            if (status == 426) assertTrue(enrollFailureOf(status, body.toString()) is EnrollUpdateApp)
            return enrolAnswerKind(status, keyState, body?.str("state")).wire
        }
        for ((name, value) in answers) {
            val rows = if (value is kotlinx.serialization.json.JsonArray) value.map { it.jsonObject } else listOf(value.jsonObject)
            for (row in rows) assertEquals(name, row.s("phone"), kindOf(row))
        }
        assertEquals(
            "exempt",
            ServerJson.decodeFromString(
                com.kidslauncher.mdm.server.dto.EnrollResponse.serializer(),
                answers["exempt"]!!.jsonObject["body"].toString().replace("\"<id>\"", "7"),
            ).key!!.state,
        )
        // S1's answers (a server before design 129b) are still read until S2 (D13).
        val s1 = enroll["answers_s1"]!!.jsonObject
        fun key(name: String) = s1[name]!!.jsonObject["key"]?.let {
            ServerJson.decodeFromString(com.kidslauncher.mdm.server.dto.EnrollKeyAnswer.serializer(), it.toString())
        }
        assertEquals(null, key("code_only"))
        assertEquals("<key id>", key("observed")!!.keyId)
        assertEquals("observed", key("repeat")!!.state)
        assertEquals("app", key("refused")!!.reason)
        assertEquals(3600L, key("list_stale")!!.retryAfterS)
    }

    @Test
    fun `the attempt's number is the server's`() {
        for (row in contract["enroll"]!!.jsonObject["numbers"]!!.jsonArray.map { it.jsonObject }) {
            val attempt = unb64u(row.s("attempt"))
            assertEquals(row.s("attempt_hash"), hexOf(sha256(attempt)))
            assertEquals(row.s("number"), attemptNumber(attempt))
            assertEquals(row.s("shown"), shownNumber(attemptNumber(attempt)))
        }
    }

    @Test
    fun `the waiting enrolment's steps`() {
        for (row in rows("enrol_wait")) {
            val step = enrolWaitStep(
                row.s("answer"), row.str("retry_after_s")?.toLong(), row["since_first_ms"]!!.jsonPrimitive.long,
                row["has_key"]!!.jsonPrimitive.boolean,
            )
            assertEquals(row.s("name"), row.s("action"), step.action)
            assertEquals(row.s("name"), row.str("delay_ms")?.toLong(), step.delayMs)
            assertEquals(row.s("name"), row["make_key"]!!.jsonPrimitive.boolean, step.makeKey)
        }
        assertEquals(QrExtrasAfter.WAIT, qrExtrasAfter(Result.failure(EnrollWaiting("root", "123456", 15))))
        assertEquals(QrExtrasAfter.RETRY, qrExtrasAfter(Result.failure(EnrollUpdateApp())))
        assertEquals(null, enrolFailureLine(EnrollWaiting("root", "123456", 15)))
        assertEquals(com.kidslauncher.mdm.R.string.enrol_update_app, enrolFailureLine(EnrollUpdateApp()))
    }

    @Test
    fun `the book keeps the attempts across a reload`() {
        val book = KeyBookState(enrolAttempts = mapOf("challenge" to "attempt"), exemptTriedMs = 42L)
        val again = ServerJson.decodeFromString(KeyBookState.serializer(), ServerJson.encodeToString(KeyBookState.serializer(), book))
        assertEquals(book, again)
        // A book written before design 129b reads without them.
        assertEquals(emptyMap<String, String>(), ServerJson.decodeFromString(KeyBookState.serializer(), "{}").enrolAttempts)
    }

    @Test
    fun `one attempt per challenge, gone at a new enrolment`() {
        val once = withEnrolAttempt(KeyBookState(), "c1", "a1")
        assertEquals(mapOf("c1" to "a1"), once.enrolAttempts)
        // The kept one wins: every try with the code sends the same attempt.
        assertEquals(once, withEnrolAttempt(once, "c1", "a2"))
        val two = withEnrolAttempt(once, "c2", "b1")
        assertEquals(mapOf("c1" to "a1", "c2" to "b1"), two.enrolAttempts)
        val fresh = newEnrolmentBook(two.copy(exemptTriedMs = 5L, lost = true, lastRefusal = "root"))
        assertEquals(emptyMap<String, String>(), fresh.enrolAttempts)
        assertEquals(null, fresh.exemptTriedMs)
        assertEquals(false, fresh.lost)
        assertEquals(null, fresh.lastRefusal)
    }

    @Test
    fun `every no-key path names why`() {
        assertEquals("cap", noKeyReason(bookCorrupt = false, failure = null))
        assertEquals("keystore", noKeyReason(bookCorrupt = true, failure = null))
        assertEquals("keys_unavailable", noKeyReason(false, KeyGenFailure(true, null)))
        assertEquals("keystore", noKeyReason(false, KeyGenFailure(false, null)))
        assertEquals("keystore", noKeyReason(false, IllegalStateException("anything else")))
        // The words are the contract's.
        val words = contract["enroll"]!!.jsonObject["body_no_key"]!!.jsonArray.mapNotNull { it.jsonObject["body"]!!.jsonObject.str("key_error") }
        assertTrue(words.containsAll(listOf("cap", "keystore", "keys_unavailable")))
        assertEquals("089 851", shownNumber("089851"))
    }

    private fun step1(row: JsonObject): KeyStepOne {
        val step = row["step1"]!!.jsonObject
        val answer = step["answer"]!!.takeIf { it != JsonNull }?.let { ServerJson.decodeFromString(DeviceKeyAnswer.serializer(), it.toString()) }
        return KeyStepOne(step["status"]!!.jsonPrimitive.int, step.str("vibb_auth"), answer)
    }

    @Test
    fun `the registration flow's table`() {
        for (row in rows("phone")) {
            val input = KeyStepInput(
                hasToken = row["has_token"]!!.jsonPrimitive.boolean,
                local = row["local"]!!.jsonArray.map { it.jsonPrimitive.content },
                adopted = row.str("adopted"),
                step1 = step1(row),
                inFlight = row["in_flight"]!!.jsonPrimitive.boolean,
                conflictReported = row.str("conflict_reported"),
                backoff = row["backoff"]!!.jsonPrimitive.boolean,
                exemptSinceLastTryMs = row.str("exempt_since_last_try_ms")?.toLong(),
            )
            val expected = when (row.s("action")) {
                "adopt" -> KeyAction.Adopt(row.s("key"), row["drop_token"]!!.jsonPrimitive.boolean)
                "keep" -> KeyAction.Keep
                "register" -> KeyAction.Register
                "report_conflict" -> KeyAction.ReportConflict
                "wait" -> KeyAction.Wait
                "suspect_lost" -> KeyAction.SuspectLost
                else -> error(row.s("action"))
            }
            assertEquals(row.s("name"), expected, keyStep(input))
            // Design 129b X: after a `register`, the challenge's answer decides whether a key is made.
            val challenge = row["challenge"]!!.takeIf { it != JsonNull }?.jsonObject ?: continue
            val body = challenge["body"]!!.takeIf { it != JsonNull }?.toString()
            val refusal = body?.let { runCatching { ServerJson.decodeFromString(KeyRefusalAnswer.serializer(), it) }.getOrNull() }
            val step = keyChallengeStep(challenge["status"]!!.jsonPrimitive.int, refusal?.takeIf { it.reason.isNotBlank() })
            val want = row["challenge_action"]!!.jsonObject
            assertEquals(row.s("name"), ChallengeAction(want.s("action"), want.str("wait_ms")?.toLong(), want["make_key"]!!.jsonPrimitive.boolean), step)
        }
        // The last call's fate never decides: step 1 does.
        assertTrue(rows("phone").any { it.str("last_call") == "timeout" && it.s("action") == "adopt" })
        assertTrue(rows("phone").any { it.str("last_call") == "timeout" && it.s("action") == "register" })
    }

    @Test
    fun `the policy only nudges`() {
        for (row in rows("phone_nudge")) {
            val policy = row["policy"]!!.takeIf { it != JsonNull }?.let { ServerJson.decodeFromString(DeviceKeyPolicy.serializer(), it.toString()) }
            assertEquals(
                row.s("name"),
                row["ask"]!!.jsonPrimitive.boolean,
                keyNudge(
                    policy, row.str("adopted_key_id"), row["has_token"]!!.jsonPrimitive.boolean, row["lost"]!!.jsonPrimitive.boolean,
                    row["backoff"]!!.jsonPrimitive.boolean, row.str("since_last_ask_ms")?.toLong(),
                ),
            )
        }
    }

    @Test
    fun `lost needs three spaced strikes over an hour`() {
        fun suspicion(o: JsonObject?) = o?.let {
            LostSuspicion(it["first_elapsed_ms"]!!.jsonPrimitive.long, it["first_boot"]!!.jsonPrimitive.int, it["strikes"]!!.jsonPrimitive.int, it["last_elapsed_ms"]!!.jsonPrimitive.long)
        }
        for (row in rows("lost_verdict")) {
            val before = row["before"]!!.takeIf { it != JsonNull }?.jsonObject
            val (after, lost) = lostVerdict(suspicion(before), row["now_elapsed"]!!.jsonPrimitive.long, row["boot"]!!.jsonPrimitive.int)
            assertEquals(row.s("name"), suspicion(row["after"]!!.jsonObject), after)
            assertEquals(row.s("name"), row["lost"]!!.jsonPrimitive.boolean, lost)
        }
    }

    @Test
    fun `a sign failure is unusable only when permanent or long`() {
        assertTrue(signUnusable(true, 1, 0, 0))
        assertEquals(false, signUnusable(false, SIGN_FAILURES, 0, SIGN_FAILURE_SPAN_MS - 1))
        assertEquals(false, signUnusable(false, SIGN_FAILURES - 1, 0, SIGN_FAILURE_SPAN_MS))
        assertTrue(signUnusable(false, SIGN_FAILURES, 0, SIGN_FAILURE_SPAN_MS))
    }

    @Test
    fun `rkpd is exempted only as a system app with a uid of its own`() {
        assertTrue(rkpdExemptable(10123, system = true, packagesForUid = 1))
        assertEquals(false, rkpdExemptable(1000, system = true, packagesForUid = 1))
        assertEquals(false, rkpdExemptable(10123, system = false, packagesForUid = 1))
        assertEquals(false, rkpdExemptable(10123, system = true, packagesForUid = 2))
        assertEquals(listOf("com.android.rkpdapp", "com.google.android.rkpdapp"), RKPD_PACKAGES)
        assertEquals(emptySet<String>(), lockdownAllowList(false, setOf("com.google.android.rkpdapp")))
        assertEquals(setOf("com.google.android.rkpdapp"), lockdownAllowList(true, setOf("com.google.android.rkpdapp")))
    }

    @Test
    fun `the book against the Keystore`() {
        val book = KeyBookState(
            next = 3,
            keys = listOf(KeyAlias("device_key_1", 10, 20), KeyAlias("device_key_1", 10, 20), KeyAlias("device_key_2", 10, 20)),
            adopted = AdoptedKey("device_key_2", "k2"),
        )
        // The adopted alias gone: marked, not silently forgotten (M2); duplicates go; a newer stray is fresh, an older
        // one long expired; next moves past every alias (L8).
        val after = reconcileBook(book, setOf("device_key_1", "device_key_0", "device_key_5"), 1000, 600)
        assertEquals(null, after.adopted)
        assertTrue(after.keyGone)
        assertEquals(listOf("device_key_1", "device_key_0", "device_key_5"), after.keys.map { it.alias })
        assertEquals(KeyAlias("device_key_0", 0, 0), after.keys[1])
        assertEquals(KeyAlias("device_key_5", 1000, 1600), after.keys[2])
        assertEquals(6, after.next)
    }

    @Test
    fun `the cap deletes only expired keys the server never named`() {
        val now = 1_000_000L
        assertEquals(emptyList<String>(), keyCapPlan(listOf(KeyAlias("a", 1, 0), KeyAlias("b", 2, 0)), now))
        val three = listOf(KeyAlias("a", 1, now - 1), KeyAlias("b", 2, now - 1), KeyAlias("c", 3, now + 1))
        assertEquals(listOf("a"), keyCapPlan(three, now))
        assertEquals(listOf("b"), keyCapPlan(three.map { if (it.alias == "a") it.copy(named = true) else it }, now))
        // Every key's challenge still live, or named: none may go - no new key.
        assertEquals(null, keyCapPlan(three.map { it.copy(challengeUntilMs = now + 1) }, now))
    }
}
