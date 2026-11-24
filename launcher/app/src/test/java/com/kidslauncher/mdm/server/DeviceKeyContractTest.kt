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
 * Design 129's shared contract (`device_key.json`, byte-identical with `server/testdata/`, whose `tests::device_key`
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
        val withKey = com.kidslauncher.mdm.server.dto.EnrollRequest("<code>", listOf("<Base64 DER>"), "<Base64url>")
        assertEquals(enroll["body_with_key"], Json.parseToJsonElement(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.EnrollRequest.serializer(), withKey)))
        val codeOnly = com.kidslauncher.mdm.server.dto.EnrollRequest("<code>")
        assertEquals(enroll["body_code_only"], Json.parseToJsonElement(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.EnrollRequest.serializer(), codeOnly)))
        // The answers' `key` (their `device_id` is a placeholder here; the real one is a number).
        val answers = enroll["answers"]!!.jsonObject
        fun key(name: String) = answers[name]!!.jsonObject["key"]?.let {
            ServerJson.decodeFromString(com.kidslauncher.mdm.server.dto.EnrollKeyAnswer.serializer(), it.toString())
        }
        assertEquals(null, key("code_only"))
        assertEquals("<key id>", key("observed")!!.keyId)
        assertEquals("observed", key("repeat")!!.state)
        assertEquals("app", key("refused")!!.reason)
        assertEquals(3600L, key("list_stale")!!.retryAfterS)
        val real = ServerJson.decodeFromString(
            com.kidslauncher.mdm.server.dto.EnrollResponse.serializer(),
            answers["observed"].toString().replace("\"<id>\"", "7"),
        )
        assertEquals("<key id>", real.key!!.keyId)
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
            )
            val expected = when (row.s("action")) {
                "adopt" -> KeyAction.Adopt(row.s("key"), row["drop_token"]!!.jsonPrimitive.boolean)
                "keep" -> KeyAction.Keep
                "register" -> KeyAction.Register
                "report_conflict" -> KeyAction.ReportConflict
                "wait" -> KeyAction.Wait
                "re_enrol" -> KeyAction.ReEnrol
                else -> error(row.s("action"))
            }
            assertEquals(row.s("name"), expected, keyStep(input))
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
                keyNudge(policy, row.str("adopted_key_id"), row["has_token"]!!.jsonPrimitive.boolean, row["lost"]!!.jsonPrimitive.boolean, row["backoff"]!!.jsonPrimitive.boolean),
            )
        }
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

    @Test
    fun `the contract is identical to the servers copy`() {
        val serverCopy = File("../../server/testdata/device_key.json")
        assertTrue(serverCopy.absolutePath, serverCopy.isFile)
        assertEquals(serverCopy.readText(), text)
    }
}
