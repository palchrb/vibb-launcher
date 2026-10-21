package me.vibb.music.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.vibb.music.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's Storytel session through the bridge (design 58 §5.4): the callback's answer as a [JwtResult]. */
class StorytelBridgeTokenTest {
    private val now = 1_000_000L

    @Test
    fun `each status maps to its result`() {
        assertEquals(JwtResult.Ok("FAKE-JWT-1", now + 3_540_000, "1:00000000000000a1"), jwtResult("ok", "FAKE-JWT-1", 3_540_000, "1:00000000000000a1", now))
        assertEquals(JwtResult.Off, jwtResult("off", null, 0, null, now))
        assertEquals(JwtResult.Refused(2_400_000), jwtResult("refused", null, 2_400_000, null, now))
        assertEquals(JwtResult.Refused(JWT_REFUSED_DEFAULT_MS), jwtResult("refused", null, 0, null, now))
        assertEquals(JwtResult.Busy(30_000), jwtResult("busy", null, 30_000, null, now))
        assertEquals(JwtResult.Busy(JWT_BUSY_DEFAULT_MS), jwtResult("busy", null, -1, null, now))
        for (word in listOf("unreachable", "key", "launcher", "paused_by_parent")) {
            assertEquals(word, JwtResult.Unreachable(word), jwtResult(word, null, 0, null, now))
        }
        assertEquals("no word", JwtResult.Unreachable("launcher"), jwtResult(null, null, 0, null, now))
        assertEquals(3_600_000L, JWT_REFUSED_DEFAULT_MS)
        assertEquals(30_000L, JWT_BUSY_DEFAULT_MS)
    }

    @Test
    fun `an ok that isn't whole is the launcher's failure`() {
        val half = JwtResult.Unreachable("launcher")
        assertEquals(half, jwtResult("ok", null, 3_540_000, "1:00000000000000a1", now))
        assertEquals(half, jwtResult("ok", "", 3_540_000, "1:00000000000000a1", now))
        assertEquals(half, jwtResult("ok", "FAKE-JWT-1", 0, "1:00000000000000a1", now))
        assertEquals(half, jwtResult("ok", "FAKE-JWT-1", 3_540_000, null, now))
        assertEquals(half, jwtResult("ok", "FAKE-JWT-1", 3_540_000, "1:00000000000000A1", now))
        assertEquals(half, jwtResult("ok", "FAKE-JWT-1", 3_540_000, "1:a1", now))
    }

    @Test
    fun `the shared answers file - each row as the launcher hands it on`() {
        val answers = (Json.parseToJsonElement(Fixtures.resource("storytel_token_answers.json")) as JsonObject)["answers"] as JsonArray
        val read = answers.associate { row ->
            row as JsonObject
            val body = row["body"] as JsonObject
            fun text(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val seconds = (body["expires_in_s"] ?: body["retry_after_s"]) as? JsonPrimitive
            (row["name"] as JsonPrimitive).content to
                jwtResult(text("status"), text("jwt"), (seconds?.longOrNull ?: 0) * 1000, text("session"), now)
        }
        assertEquals(
            mapOf(
                "ok" to JwtResult.Ok("FAKE-JWT-1", now + 3_540_000, "1:00000000000000a1"),
                "off" to JwtResult.Off,
                "refused" to JwtResult.Refused(2_400_000),
                "busy" to JwtResult.Busy(30_000),
                "unreachable" to JwtResult.Unreachable("unreachable"),
                "key" to JwtResult.Unreachable("key"),
                "database" to JwtResult.Unreachable("unreachable"),
            ),
            read,
        )
        // Every status the launcher answers is one this app reads.
        for (status in StorytelBridgeToken.STATUSES) {
            val result = jwtResult(status, "FAKE-JWT-1", 1, "1:0000000000000001", now)
            assertEquals(status, status in listOf("unreachable", "key", "launcher"), result is JwtResult.Unreachable)
        }
    }

    @Test
    fun `the result never prints its JWT`() {
        val ok = jwtResult("ok", "FAKE-JWT-1", 3_540_000, "1:00000000000000a1", now)
        assertFalse(ok.toString(), ok.toString().contains("FAKE-JWT"))
        assertTrue(ok.toString().contains("jwt=<redacted>"))
        assertFalse("nor the session", ok.toString().contains("00000000000000a1"))
    }
}
