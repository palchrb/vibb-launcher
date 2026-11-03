package com.kidslauncher.mdm.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 75 §5.4: the lock's weather reads no location and asks no host but our own server's API client - no
 * `android.location`, no URL literal under `weather/` or in the lock's panel; the pull goes through `createMdmApi`
 * only; its logs carry counts and classes, never the answer's place.
 */
class WeatherGuardTest {
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun code(text: String) =
        text.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")

    private val sources: List<File> =
        file("java/com/kidslauncher/mdm/weather").listFiles()!!.filter { it.extension == "kt" } +
            file("java/com/kidslauncher/mdm/lock/WeatherLockPanel.kt")

    @Test
    fun `no location, no other host`() {
        assertTrue(sources.size >= 6)
        for (source in sources) {
            val text = code(source.readText())
            assertFalse("${source.name} reads a location", "android.location" in text || "LocationManager" in text || "FusedLocation" in text)
            assertFalse("${source.name} names a host", Regex("""https?://""").containsMatchIn(text))
            for (forbidden in listOf("OkHttpClient", "HttpURLConnection", "URL(", "Retrofit.Builder", "startActivity", "PendingIntent")) {
                assertFalse("${source.name} uses $forbidden", forbidden in text)
            }
        }
        val pull = code(file("java/com/kidslauncher/mdm/weather/WeatherPull.kt").readText())
        assertEquals(1, Regex("""createMdmApi\(""").findAll(pull).count())
        assertTrue(pull.contains("api.weather()") && pull.contains("it.weatherNow()"))
    }

    @Test
    fun `the logs name no place, temperature or time`() {
        for (source in sources) {
            val text = code(source.readText())
            for (call in Regex("""Log\.[iwed]\([^\n]*""").findAll(text).map { it.value }) {
                for (word in listOf("place}", "temp", "atMs", ".hours}", "symbol}", "answer}")) {
                    assertFalse("${source.name}: $call", word in call)
                }
            }
        }
        // The DTOs' toString leaves the place, temperatures and times out.
        val dto = file("java/com/kidslauncher/mdm/server/dto/WeatherAnswer.kt").readText()
        assertEquals(6, Regex("""override fun toString\(\)""").findAll(dto).count())
    }
}
