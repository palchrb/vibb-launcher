package com.kidslauncher.mdm.appdisplay

import com.kidslauncher.mdm.server.dto.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Design 87 step 2 §3: the phone's logo cache - the key, what is drawn again, what the report carries, which files
 * go, the drawing's budget and the index's temp-file write (R2-L10). */
class AppLogosCacheTest {
    private val key = AppLogoKey(versionCode = 7, lastUpdateTime = 1_000, fingerprint = "example/build:16/1")
    private val h1 = "%064x".format(1)
    private val h2 = "%064x".format(2)

    @Test
    fun `each part of the key draws the app again`() {
        val index = AppLogoIndex(mapOf("org.example.drawing" to AppLogoEntry(key, h1)))
        assertEquals(emptyList<String>(), logoRefreshPlan(index, mapOf("org.example.drawing" to key), setOf(h1)).toDraw)
        for (changed in listOf(
            key.copy(versionCode = 8),
            key.copy(lastUpdateTime = 2_000),
            key.copy(fingerprint = "example/build:16/2"),
            key.copy(render = LOGO_RENDER_VERSION + 1),
        )) {
            val plan = logoRefreshPlan(index, mapOf("org.example.drawing" to changed), setOf(h1))
            assertEquals("$changed", listOf("org.example.drawing"), plan.toDraw)
        }
        // A new app, and one whose file is gone, are drawn; a failed draw under the same key is not tried again.
        val failed = AppLogoIndex(mapOf("org.example.notes" to AppLogoEntry(key)))
        assertEquals(emptyList<String>(), logoRefreshPlan(failed, mapOf("org.example.notes" to key), emptySet()).toDraw)
        assertEquals(listOf("org.example.drawing"), logoRefreshPlan(index, mapOf("org.example.drawing" to key), emptySet()).toDraw)
        assertEquals(listOf("org.example.new"), logoRefreshPlan(AppLogoIndex(), mapOf("org.example.new" to key), emptySet()).toDraw)
    }

    @Test
    fun `a removed package leaves the index and its file goes unless another names it`() {
        val index = AppLogoIndex(
            mapOf(
                "org.example.drawing" to AppLogoEntry(key, h1),
                "org.example.notes" to AppLogoEntry(key, h2),
                "org.example.twin" to AppLogoEntry(key, h2),
            ),
        )
        val plan = logoRefreshPlan(index, mapOf("org.example.notes" to key), setOf(h1, h2))
        assertEquals(setOf("org.example.notes"), plan.index.apps.keys)
        assertEquals(
            listOf("$h1.png", "index.json.tmp", "$h1.png.tmp"),
            unnamedLogoFiles(plan.index, listOf("$h1.png", "$h2.png", LOGO_INDEX_FILE, "index.json.tmp", "$h1.png.tmp")),
        )
    }

    @Test
    fun `the report carries a hash only when drawn under today's key with its file`() {
        val index = AppLogoIndex(
            mapOf(
                "org.example.drawing" to AppLogoEntry(key, h1),
                "org.example.notes" to AppLogoEntry(key, h2),
                "org.example.stale" to AppLogoEntry(key, h1),
                "org.example.failed" to AppLogoEntry(key),
            ),
        )
        val current = mapOf(
            "org.example.drawing" to key, "org.example.notes" to key,
            "org.example.stale" to key.copy(versionCode = 9), "org.example.failed" to key,
        )
        val logos = reportedLogos(index, current, setOf(h1))
        assertEquals(mapOf("org.example.drawing" to h1), logos)
        val apps = withLogos(
            listOf(InstalledApp("org.example.drawing", "Drawing", false), InstalledApp("org.example.notes", "Notes", true)),
            logos,
        )
        assertEquals(listOf(h1, null), apps.map { it.logo })
    }

    @Test
    fun `the index reads back, and a broken one is empty`() {
        val index = AppLogoIndex(mapOf("org.example.drawing" to AppLogoEntry(key, h1), "org.example.notes" to AppLogoEntry(key)))
        assertEquals(index, decodeLogoIndex(encodeLogoIndex(index)))
        assertEquals(AppLogoIndex(), decodeLogoIndex(null))
        assertEquals(AppLogoIndex(), decodeLogoIndex("{\"apps\":"))
        assertEquals(AppLogoIndex(), decodeLogoIndex("[]"))
    }

    @Test
    fun `the drawing stops when its budget is spent`() {
        var now = 0L
        val drawn = mutableListOf<Int>()
        val done = runWithin((1..10).toList(), LOGO_DRAW_BUDGET_MS, { now }) { drawn += it; now += 400 }
        // 0, 400, 800, 1200 start inside 1.5 s; the fifth would start at 1600.
        assertEquals(4, done)
        assertEquals(listOf(1, 2, 3, 4), drawn)
        assertEquals(3, runWithin(listOf(1, 2, 3), LOGO_DRAW_BUDGET_MS, { 0L }) {})
    }

    @Test
    fun `the index is written through a temp file and a failed write keeps the old`() {
        val dir = Files.createTempDirectory("app-logos").toFile()
        try {
            val target = File(dir, LOGO_INDEX_FILE)
            writeReplacing(target) { it.write("old".toByteArray()) }
            assertEquals("old", target.readText())
            val failed = runCatching {
                writeReplacing(target) {
                    it.write("half".toByteArray())
                    throw java.io.IOException("killed")
                }
            }
            assertTrue(failed.isFailure)
            assertEquals("old", target.readText())
            assertFalse(File(dir, "$LOGO_INDEX_FILE.tmp").exists())
            writeReplacing(target) { it.write("new".toByteArray()) }
            assertEquals("new", target.readText())
            assertEquals(listOf(LOGO_INDEX_FILE), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }
}
