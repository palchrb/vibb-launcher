package com.kidslauncher.mdm.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Storytel session courier (design 58 §4): the server's answer as the callback carries it, and the request's body. */
class StorytelTokenCourierTest {
    private fun ok(jwt: String = "FAKE-JWT-1", life: String = "3540", session: String = "\"1:00000000000000a1\"") =
        """{"status": "ok", "jwt": "$jwt", "expires_in_s": $life, "session": $session}"""

    private fun module(path: String) = listOf(File(path), File("app/$path")).first { it.exists() }

    @Test
    fun `every row of the shared answers file reads as the server meant it`() {
        val answers = Json.parseToJsonElement(javaClass.classLoader!!.getResource("storytel_token_answers.json")!!.readText())
            .let { (it as JsonObject)["answers"] as JsonArray }
        val read = answers.associate { row ->
            row as JsonObject
            val name = row["name"]!!.jsonPrimitive.content
            name to storytelTokenAnswer(row["http"]!!.jsonPrimitive.content.toInt(), row["body"].toString())
        }
        assertEquals(
            mapOf(
                "ok" to StorytelTokenAnswer("ok", "FAKE-JWT-1", 3_540_000, "1:00000000000000a1"),
                "off" to StorytelTokenAnswer("off", null, 0, null),
                "refused" to StorytelTokenAnswer("refused", null, 2_400_000, null),
                "busy" to StorytelTokenAnswer("busy", null, 30_000, null),
                "unreachable" to StorytelTokenAnswer("unreachable", null, 0, null),
                "key" to StorytelTokenAnswer("key", null, 0, null),
                "database" to StorytelTokenAnswer("unreachable", null, 0, null),
            ),
            read,
        )
        // Every status a row carries is one the callback knows.
        for (answer in read.values) assertTrue(answer.status, answer.status in STORYTEL_TOKEN_STATUSES)
    }

    @Test
    fun `an ok that isn't whole is the launcher's failure, never a half JWT`() {
        for (body in listOf(
            ok(life = "0"), ok(life = "\"3540\""), ok(life = "86401"), ok(life = "-5"),
            ok(session = "\"1:00000000000000A1\""), ok(session = "\"1:a1\""), ok(session = "\"x:00000000000000a1\""), ok(session = "null"),
            ok(jwt = ""), ok(jwt = "two words"), ok(jwt = "x".repeat(8193)), """{"status": "ok"}""",
        )) {
            assertEquals(body, StorytelTokenAnswer.LAUNCHER, storytelTokenAnswer(200, body))
        }
        assertEquals("ok only with 200", StorytelTokenAnswer.LAUNCHER, storytelTokenAnswer(500, ok()))
        assertEquals("the longest JWT", "x".repeat(8192), storytelTokenAnswer(200, ok(jwt = "x".repeat(8192))).jwt)
        assertEquals("the longest life", 86_400_000L, storytelTokenAnswer(200, ok(life = "86400")).expiresInMs)
    }

    @Test
    fun `refused and busy carry their wait, bounded, with a default each`() {
        assertEquals(StorytelTokenAnswer("refused", null, 1_000, null), storytelTokenAnswer(409, """{"status": "refused", "retry_after_s": 1}"""))
        assertEquals(StorytelTokenAnswer("busy", null, 3_600_000, null), storytelTokenAnswer(429, """{"status": "busy", "retry_after_s": 3600}"""))
        for (wait in listOf("", ", \"retry_after_s\": 0", ", \"retry_after_s\": 3601", ", \"retry_after_s\": \"30\"")) {
            assertEquals(wait, STORYTEL_REFUSED_DEFAULT_MS, storytelTokenAnswer(409, """{"status": "refused"$wait}""").expiresInMs)
            assertEquals(wait, STORYTEL_BUSY_DEFAULT_MS, storytelTokenAnswer(429, """{"status": "busy"$wait}""").expiresInMs)
        }
        assertEquals(3_600_000L, STORYTEL_REFUSED_DEFAULT_MS)
        assertEquals(30_000L, STORYTEL_BUSY_DEFAULT_MS)
    }

    @Test
    fun `every other status passes through without a JWT`() {
        assertEquals(StorytelTokenAnswer("key", null, 0, null), storytelTokenAnswer(503, """{"status": "key", "jwt": "FAKE-JWT-9"}"""))
        assertEquals(StorytelTokenAnswer("paused_by_parent", null, 0, null), storytelTokenAnswer(403, """{"status": "paused_by_parent"}"""))
    }

    @Test
    fun `no status word - an older server's 404 is off, a 5xx unreachable, the rest the launcher's`() {
        assertEquals(StorytelTokenAnswer("off", null, 0, null), storytelTokenAnswer(404, ""))
        assertEquals(StorytelTokenAnswer("unreachable", null, 0, null), storytelTokenAnswer(502, "<html>Bad gateway</html>"))
        assertEquals(StorytelTokenAnswer.LAUNCHER, storytelTokenAnswer(401, null))
        assertEquals(StorytelTokenAnswer.LAUNCHER, storytelTokenAnswer(413, null))
        assertEquals(StorytelTokenAnswer.LAUNCHER, storytelTokenAnswer(200, """{"status": "OK!"}"""))
        assertEquals(StorytelTokenAnswer.LAUNCHER, storytelTokenAnswer(200, "[]"))
    }

    @Test
    fun `the answer never prints its JWT`() {
        val answer = storytelTokenAnswer(200, ok())
        assertFalse(answer.toString(), answer.toString().contains("FAKE-JWT"))
        assertTrue(answer.toString().contains("jwt=<redacted>"))
        assertFalse("nor the session", answer.toString().contains("00000000000000a1"))
        assertTrue(answer.toString().contains("session=<set>"))
    }

    @Test
    fun `the body names a stale session only when it is one`() {
        assertEquals("""{"stale":"1:00000000000000a1"}""", staleJson("1:00000000000000a1"))
        for (junk in listOf(null, "", "1:00000000000000A1", "1:a1", "1:00000000000000a1\"}", "x")) {
            assertEquals("$junk", "{}", staleJson(junk))
        }
        val body = staleBody("1:00000000000000a1")
        assertEquals("application/json", "${body.contentType()?.type}/${body.contentType()?.subtype}")
        val sent = Buffer().also { body.writeTo(it) }.readUtf8()
        assertEquals("""{"stale":"1:00000000000000a1"}""", sent)
        assertEquals("1:00000000000000a1", (Json.parseToJsonElement(sent) as JsonObject)["stale"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the bridge asks the server at every call - no cache, no log of the answer, launcher on any failure`() {
        val source = module("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt").readText()
        val method = source.substringAfter("private suspend fun answerStorytelToken(").substringBefore("\n    }\n")
        assertTrue(method.contains("withTimeout(STORYTEL_TOKEN_TIMEOUT_MS)"))
        assertTrue(method.contains("createMusicServer(url, token).storytelToken(stale)"))
        assertTrue(method.contains("StorytelTokenAnswer.LAUNCHER"))
        val logs = method.lines().filter { "Log." in it }
        assertEquals(2, logs.size)
        for (line in logs) assertFalse(line, Regex("\\$\\{?(answer|body|response|token|url|stale|jwt|session)").containsMatchIn(line))
        assertFalse("nothing stored", method.contains("MusicStore") || method.contains("prefs"))
        val binder = source.substringAfter("override fun getStorytelToken(").substringBefore("\n        }\n")
        assertTrue(binder.contains("scope.launch { answerStorytelToken(stale, callback) }"))
        // The courier file itself logs nothing.
        assertFalse(module("src/main/java/com/kidslauncher/mdm/music/StorytelTokenCourier.kt").readText().contains("Log."))
    }

    @Test
    fun `the launcher tells the server it carries the courier`() {
        assertEquals("storytel_jwt_v1", STORYTEL_JWT_CAPABILITY)
        assertTrue(STORYTEL_JWT_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
    }
}
